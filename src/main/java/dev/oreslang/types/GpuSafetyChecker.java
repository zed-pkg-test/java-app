package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative GPU execution-domain verifier.
 *
 * A gpu callable is an effect boundary: it may compose other gpu callables and
 * pure/local computation, but it cannot reach host/actor facilities or dynamic
 * call targets that the compiler cannot prove are GPU-safe.
 */
final class GpuSafetyChecker {
    private static final Set<String> HOST_ROOTS = Set.of(
            "stdio", "process", "network", "fs", "env", "ffi", "polyglot",
            "thread", "actor", "actors");

    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<Ast.FunctionDecl, String> owners = new java.util.IdentityHashMap<>();

    private GpuSafetyChecker(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FunctionDecl fn)) continue;
                owners.put(fn, module.name());
                functions.put(module.name() + "." + fn.name(), fn);
                Ast.FunctionDecl previous = functions.putIfAbsent(fn.name(), fn);
                if (previous != null && previous != fn) {
                    ambiguousFunctions.add(fn.name());
                    functions.remove(fn.name());
                }
            }
        }
    }

    static void check(Ast.Program program) {
        GpuSafetyChecker checker = new GpuSafetyChecker(program);
        for (Map.Entry<Ast.FunctionDecl, String> entry : checker.owners.entrySet()) {
            if (entry.getKey().gpu()) checker.checkStatements(entry.getKey().body(), entry.getValue(), entry.getKey());
        }
    }

    private void checkStatements(List<Ast.Stmt> statements, String module, Ast.FunctionDecl owner) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt s) checkExpr(s.initializer(), module, owner);
            else if (stmt instanceof Ast.DestructureStmt s) checkExpr(s.initializer(), module, owner);
            else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) checkExpr(s.value(), module, owner);
            else if (stmt instanceof Ast.ExprStmt s) checkExpr(s.expression(), module, owner);
            else if (stmt instanceof Ast.DeferStmt) fail(module, owner, "defer is not GPU-safe");
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch branch : s.branches()) {
                    checkExpr(branch.condition(), module, owner);
                    checkStatements(branch.body(), module, owner);
                }
                checkStatements(s.elseBody(), module, owner);
            } else if (stmt instanceof Ast.TryStmt) {
                fail(module, owner, "try/catch/finally is not GPU-safe");
            } else if (stmt instanceof Ast.ForOfStmt s) {
                checkExpr(s.iterable(), module, owner);
                checkStatements(s.body(), module, owner);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() != null) checkStatements(List.of(s.initializer()), module, owner);
                if (s.condition() != null) checkExpr(s.condition(), module, owner);
                if (s.update() != null) checkExpr(s.update(), module, owner);
                checkStatements(s.body(), module, owner);
            } else {
                throw new IllegalStateException("unhandled GPU statement node: " + stmt.getClass().getName());
            }
        }
    }

    private void checkExpr(Ast.Expr expr, String module, Ast.FunctionDecl owner) {
        if (expr instanceof Ast.LiteralExpr) return;

        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals("print") || HOST_ROOTS.contains(name.name())) {
                fail(module, owner, "host/actor API '" + name.name() + "' is not available in the GPU execution domain");
            }
            return;
        }

        if (expr instanceof Ast.CallExpr call) {
            Ast.FunctionDecl callee = resolveStaticCall(call.callee(), module);
            if (callee != null) {
                if (!callee.gpu()) {
                    String calleeModule = owners.getOrDefault(callee, module);
                    fail(module, owner, "cannot call CPU callable '" + calleeModule + "." + callee.name()
                            + "'; mark the callee gpu or move the call outside the GPU domain");
                }
            } else if (!isGpuIntrinsic(call.callee())) {
                String path = memberPath(call.callee());
                fail(module, owner, "dynamic or unverified call target '" + (path == null ? "<expression>" : path)
                        + "' is not GPU-safe");
            }
            checkExpr(call.callee(), module, owner);
            for (Ast.Expr arg : call.arguments()) checkExpr(arg, module, owner);
            return;
        }

        if (expr instanceof Ast.MemberExpr member) {
            String path = memberPath(member);
            if (path != null) {
                String root = path.contains(".") ? path.substring(0, path.indexOf('.')) : path;
                if (HOST_ROOTS.contains(root)) {
                    fail(module, owner, "host/actor API '" + path + "' is not available in the GPU execution domain");
                }
            }
            checkExpr(member.receiver(), module, owner);
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            if (assignment.target() instanceof Ast.MemberExpr) {
                fail(module, owner, "object/actor member mutation is not GPU-safe");
            }
            checkExpr(assignment.target(), module, owner);
            checkExpr(assignment.value(), module, owner);
            return;
        }

        if (expr instanceof Ast.NewExpr) {
            fail(module, owner, "class allocation with 'new' is not GPU-safe; allocate/transfer data before dispatch");
        }
        if (expr instanceof Ast.AwaitExpr) {
            fail(module, owner, "await is not available inside a GPU callable");
        }
        if (expr instanceof Ast.LambdaExpr) {
            fail(module, owner, "closures/lambdas are not GPU-safe until closure lowering is implemented");
        }

        if (expr instanceof Ast.BinaryExpr e) {
            checkExpr(e.left(), module, owner);
            checkExpr(e.right(), module, owner);
        } else if (expr instanceof Ast.UnaryExpr e) {
            checkExpr(e.operand(), module, owner);
        } else if (expr instanceof Ast.ConditionalExpr e) {
            checkExpr(e.condition(), module, owner);
            checkExpr(e.whenTrue(), module, owner);
            checkExpr(e.whenFalse(), module, owner);
        } else if (expr instanceof Ast.IndexExpr e) {
            checkExpr(e.receiver(), module, owner);
            checkExpr(e.index(), module, owner);
        } else if (expr instanceof Ast.ListExpr e) {
            for (Ast.Expr item : e.elements()) checkExpr(item, module, owner);
        } else if (expr instanceof Ast.TupleExpr e) {
            for (Ast.Expr item : e.elements()) checkExpr(item, module, owner);
        } else if (expr instanceof Ast.ObjectExpr e) {
            for (Ast.ObjectField field : e.fields()) checkExpr(field.value(), module, owner);
        } else {
            throw new IllegalStateException("unhandled GPU expression node: " + expr.getClass().getName());
        }
    }

    private Ast.FunctionDecl resolveStaticCall(Ast.Expr callee, String module) {
        if (callee instanceof Ast.NameExpr name) {
            Ast.FunctionDecl local = functions.get(module + "." + name.name());
            if (local != null) return local;
            return ambiguousFunctions.contains(name.name()) ? null : functions.get(name.name());
        }
        if (callee instanceof Ast.MemberExpr member && member.receiver() instanceof Ast.NameExpr namespace) {
            return functions.get(namespace.name() + "." + member.member());
        }
        return null;
    }

    private boolean isGpuIntrinsic(Ast.Expr callee) {
        return callee instanceof Ast.NameExpr name && name.name().equals("Some");
    }

    private static String memberPath(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr name) return name.name();
        if (expr instanceof Ast.MemberExpr member) {
            String parent = memberPath(member.receiver());
            return parent == null ? null : parent + "." + member.member();
        }
        return null;
    }

    private static void fail(String module, Ast.FunctionDecl owner, String detail) {
        throw new IllegalArgumentException("gpu " + owner.kind().name().toLowerCase() + " '"
                + module + "." + owner.name() + "' " + detail);
    }
}
