package dev.oreslang.compiler;

import dev.oreslang.ast.Ast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative admission for quantum-targeted callables.
 *
 * <p>The current compiler deliberately has no QPU instruction/circuit IR yet.
 * Until that surface exists, a quantum callable may only contain scalar,
 * side-effect-free control/arithmetic and calls to other statically resolved
 * quantum callables. This prevents a future backend from inheriting ambient
 * CPU/host semantics by accident.</p>
 */
public final class QuantumSafetyChecker {
    private static final Set<String> SCALAR_TYPES = Set.of(
            "bool", "Bool",
            "i8", "i16", "i32", "i64",
            "u8", "u16", "u32", "u64",
            "int", "uint",
            "f32", "f64", "float",
            "complex64", "complex128", "complex",
            "void");

    private record FunctionRef(String module, Ast.FunctionDecl function) { }
    private record ClassRef(String module, Ast.ClassDecl klass) { }

    private final Map<String, FunctionRef> qualifiedFunctions = new LinkedHashMap<>();
    private final Map<String, List<FunctionRef>> simpleFunctions = new LinkedHashMap<>();
    private final Map<String, ClassRef> qualifiedClasses = new LinkedHashMap<>();
    private final Map<String, List<ClassRef>> simpleClasses = new LinkedHashMap<>();

    private QuantumSafetyChecker(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function) {
                    FunctionRef ref = new FunctionRef(module.name(), function);
                    qualifiedFunctions.put(module.name() + "." + function.name(), ref);
                    simpleFunctions.computeIfAbsent(function.name(), ignored -> new ArrayList<>()).add(ref);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    ClassRef ref = new ClassRef(module.name(), klass);
                    qualifiedClasses.put(module.name() + "." + klass.name(), ref);
                    simpleClasses.computeIfAbsent(klass.name(), ignored -> new ArrayList<>()).add(ref);
                }
            }
        }
    }

    public static Ast.Program check(Ast.Program program) {
        QuantumSafetyChecker checker = new QuantumSafetyChecker(program);
        checker.checkProgram(program);
        return program;
    }

    private void checkProgram(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl function
                        && Ast.hasQuantumPlacement(function.annotations())) {
                    checkFunction(module.name(), function);
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        if (Ast.hasQuantumPlacement(method.annotations())) {
                            checkStaticFunction(module.name(), klass, method);
                        }
                    }
                }
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl function) {
        String label = "quantum fnc " + function.name();
        if (!function.genericParameters().isEmpty()) {
            fail(label, "generic parameters are not part of the current QPU ABI");
        }
        checkSignature(label, function.parameters(), function.returnType());
        Set<String> locals = new HashSet<>();
        for (Ast.Param parameter : function.parameters()) locals.add(parameter.name());
        checkStatements(module, label, function.body(), locals);
    }

    private void checkStaticFunction(
            String module,
            Ast.ClassDecl owner,
            Ast.MethodDecl method) {
        String label = "quantum static fnc " + owner.name() + "." + method.name();
        if (!method.isStatic()) fail(label, "quantum class callables must be static");
        if (!method.genericParameters().isEmpty()) {
            fail(label, "generic parameters are not part of the current QPU ABI");
        }
        checkSignature(label, method.parameters(), method.returnType());
        Set<String> locals = new HashSet<>();
        for (Ast.Param parameter : method.parameters()) locals.add(parameter.name());
        checkStatements(module, label, method.body(), locals);
    }

    private void checkSignature(
            String label,
            List<Ast.Param> parameters,
            Ast.TypeRef returnType) {
        for (Ast.Param parameter : parameters) {
            if (parameter.structural() || parameter.mutable()) {
                fail(label, "QPU parameters cannot be structural or mutable: " + parameter.name());
            }
            requireScalarType(label, parameter.type(), "parameter " + parameter.name());
        }
        requireScalarType(label, returnType, "return type");
    }

    private void requireScalarType(String label, Ast.TypeRef type, String position) {
        if (type == null
                || type.inferArguments()
                || !type.arguments().isEmpty()
                || type.isBorrow()
                || !SCALAR_TYPES.contains(type.name())) {
            fail(label,
                    position + " must use the current scalar QPU ABI; found "
                            + (type == null ? "<inferred>" : type.name()));
        }
    }

    private void checkStatements(
            String module,
            String label,
            List<Ast.Stmt> statements,
            Set<String> locals) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                if (binding.declaredType() != null) {
                    requireScalarType(label, binding.declaredType(), "local " + binding.name());
                }
                checkExpr(module, label, binding.initializer(), locals);
                locals.add(binding.name());
            } else if (statement instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) checkExpr(module, label, returned.value(), locals);
            } else if (statement instanceof Ast.ExprStmt expression) {
                checkExpr(module, label, expression.expression(), locals);
            } else if (statement instanceof Ast.BlockStmt block) {
                checkStatements(module, label, block.body(), new HashSet<>(locals));
            } else if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    checkExpr(module, label, branch.condition(), locals);
                    checkStatements(module, label, branch.body(), new HashSet<>(locals));
                }
                checkStatements(module, label, conditional.elseBody(), new HashSet<>(locals));
            } else if (statement instanceof Ast.ForStmt loop) {
                Set<String> loopLocals = new HashSet<>(locals);
                if (loop.initializer() != null) {
                    checkStatements(module, label, List.of(loop.initializer()), loopLocals);
                }
                if (loop.condition() != null) checkExpr(module, label, loop.condition(), loopLocals);
                if (loop.update() != null) checkExpr(module, label, loop.update(), loopLocals);
                checkStatements(module, label, loop.body(), new HashSet<>(loopLocals));
            } else if (statement instanceof Ast.LoopStmt loop) {
                checkStatements(module, label, loop.body(), new HashSet<>(locals));
            } else if (statement instanceof Ast.BreakStmt
                    || statement instanceof Ast.ContinueStmt) {
                // Scalar control flow only.
            } else {
                fail(label,
                        "unsupported QPU statement " + statement.getClass().getSimpleName()
                                + "; host/runtime/async/container effects are not admitted");
            }
        }
    }

    private void checkExpr(
            String module,
            String label,
            Ast.Expr expression,
            Set<String> locals) {
        if (expression == null) return;

        if (expression instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value instanceof String || value instanceof Ast.Imaginary) {
                fail(label, "string/imaginary literals are not part of the current QPU scalar ABI");
            }
            return;
        }

        if (expression instanceof Ast.NameExpr name) {
            if (!locals.contains(name.name())) {
                fail(label, "host/global capture is forbidden: " + name.name());
            }
            return;
        }

        if (expression instanceof Ast.BinaryExpr binary) {
            checkExpr(module, label, binary.left(), locals);
            checkExpr(module, label, binary.right(), locals);
            return;
        }

        if (expression instanceof Ast.UnaryExpr unary) {
            checkExpr(module, label, unary.operand(), locals);
            return;
        }

        if (expression instanceof Ast.ConditionalExpr conditional) {
            checkExpr(module, label, conditional.condition(), locals);
            checkExpr(module, label, conditional.whenTrue(), locals);
            checkExpr(module, label, conditional.whenFalse(), locals);
            return;
        }

        if (expression instanceof Ast.AssignExpr assignment) {
            if (!(assignment.target() instanceof Ast.NameExpr target)
                    || !locals.contains(target.name())) {
                fail(label, "QPU assignment may target only a local scalar binding");
            }
            checkExpr(module, label, assignment.value(), locals);
            return;
        }

        if (expression instanceof Ast.CallExpr call) {
            checkQuantumCall(module, label, call, locals);
            return;
        }

        fail(label,
                "unsupported QPU expression " + expression.getClass().getSimpleName()
                        + "; object, async, channel, runtime, collection, lambda, "
                        + "cast/pattern, and dynamic-member effects are not admitted");
    }

    private void checkQuantumCall(
            String module,
            String label,
            Ast.CallExpr call,
            Set<String> locals) {
        if (call.typeArgumentsPresent()) {
            fail(label, "generic QPU calls are not part of the current QPU ABI");
        }
        for (Ast.Expr argument : call.arguments()) checkExpr(module, label, argument, locals);

        if (call.callee() instanceof Ast.NameExpr name) {
            FunctionRef target = resolveFunction(module, name.name(), call.arguments().size());
            if (target == null) {
                fail(label, "QPU call must resolve statically to a quantum fnc: " + name.name());
            }
            if (!Ast.hasQuantumPlacement(target.function().annotations())) {
                fail(label, "quantum code cannot call CPU fnc " + target.function().name());
            }
            return;
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            String ownerPath = expressionPath(member.receiver());
            ClassRef owner = resolveClass(module, ownerPath);
            if (owner == null) {
                // A qualified module function has the shape module.fn(...).
                String qualifiedFunction = ownerPath == null
                        ? null
                        : ownerPath + "." + member.member();
                FunctionRef function = qualifiedFunction == null
                        ? null
                        : qualifiedFunctions.get(qualifiedFunction);
                if (function != null
                        && function.function().parameters().size() == call.arguments().size()) {
                    if (!Ast.hasQuantumPlacement(function.function().annotations())) {
                        fail(label, "quantum code cannot call CPU fnc " + qualifiedFunction);
                    }
                    return;
                }
                fail(label, "dynamic/member QPU calls are forbidden: "
                        + (ownerPath == null ? member.member() : ownerPath + "." + member.member()));
            }

            Ast.MethodDecl found = null;
            for (Ast.MethodDecl method : owner.klass().methods()) {
                if (!method.isStatic()
                        || !method.name().equals(member.member())
                        || method.arity() != call.arguments().size()) {
                    continue;
                }
                if (found != null) {
                    fail(label, "ambiguous static QPU call " + owner.klass().name() + "." + member.member());
                }
                found = method;
            }
            if (found == null) {
                fail(label, "QPU call must resolve statically to a static fnc: "
                        + owner.klass().name() + "." + member.member());
            }
            if (!Ast.hasQuantumPlacement(found.annotations())) {
                fail(label, "quantum code cannot call CPU static fnc "
                        + owner.klass().name() + "." + found.name());
            }
            return;
        }

        fail(label, "QPU calls cannot use dynamic callable values");
    }

    private FunctionRef resolveFunction(String module, String name, int arity) {
        FunctionRef local = qualifiedFunctions.get(module + "." + name);
        if (local != null && local.function().parameters().size() == arity) return local;

        FunctionRef found = null;
        for (FunctionRef candidate : simpleFunctions.getOrDefault(name, List.of())) {
            if (candidate.function().parameters().size() != arity) continue;
            if (found != null) return null;
            found = candidate;
        }
        return found;
    }

    private ClassRef resolveClass(String module, String path) {
        if (path == null) return null;
        ClassRef exact = qualifiedClasses.get(path);
        if (exact != null) return exact;
        ClassRef local = qualifiedClasses.get(module + "." + path);
        if (local != null) return local;
        List<ClassRef> candidates = simpleClasses.getOrDefault(path, List.of());
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    private static String expressionPath(Ast.Expr expression) {
        if (expression instanceof Ast.NameExpr name) return name.name();
        if (expression instanceof Ast.MemberExpr member) {
            String prefix = expressionPath(member.receiver());
            return prefix == null ? null : prefix + "." + member.member();
        }
        return null;
    }

    private static void fail(String label, String message) {
        throw new IllegalArgumentException(label + ": " + message);
    }
}
