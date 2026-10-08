package dev.oreslang.ast;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source actor ABI admission before type checking.
 *
 * The VM-owned dispatcher is not a guest method. A run() declaration defines a
 * private future request/reply protocol, not a public method or constructor.
 * This pass is intentionally syntax/AST-only; typing and transport checks
 * remain separate mandatory gates.
 */
public final class ActorContractValidator {
    private static final Set<String> RUNTIME_HANDLERS =
            Set.of("run", "receive", "on_start");

    private ActorContractValidator() { }

    public static void validate(Ast.Program program) {
        // Only unique, local source declarations can be resolved here. Import
        // resolution and generic substitutions remain mandatory type/link gates.
        Map<String, Ast.ClassDecl> uniqueClasses = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass) {
                    if (uniqueClasses.putIfAbsent(klass.name(), klass) != null) {
                        ambiguous.add(klass.name());
                    }
                }
            }
        }
        for (String name : ambiguous) uniqueClasses.remove(name);
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.ClassDecl klass
                        && klass.actorKind() != Ast.ActorKind.NONE) {
                    validateActor(klass);
                    validateKnownInheritedModes(klass, uniqueClasses, new HashSet<>());
                }
            }
        }
    }

    private static int validateKnownInheritedModes(
            Ast.ClassDecl actor, Map<String, Ast.ClassDecl> known, Set<Ast.ClassDecl> visiting) {
        if (!visiting.add(actor)) {
            // Cycle details are diagnosed by the type checker.
            return 0;
        }
        try {
            int modes = 0;
            for (Ast.MethodDecl method : actor.methods()) {
                if (method.name().equals("run")) modes |= 1;
                if (method.name().equals("receive")) modes |= 2;
            }
            for (Ast.TypeRef parentRef : actor.parents()) {
                Ast.ClassDecl parent = known.get(parentRef.name());
                if (parent != null && parent.actorKind() != Ast.ActorKind.NONE) {
                    modes |= validateKnownInheritedModes(parent, known, visiting);
                }
            }
            if (modes == 3) {
                throw new IllegalArgumentException(
                        "actor '" + actor.name()
                                + "' cannot mix inherited run and receive handlers");
            }
            return modes;
        } finally {
            visiting.remove(actor);
        }
    }

    private static void validateActor(Ast.ClassDecl actor) {
        if (actor.constructor() != null) {
            throw new IllegalArgumentException(
                    "actor '" + actor.name() + "' cannot declare a constructor");
        }
        int runs = 0;
        int receives = 0;
        Set<String> signatures = new HashSet<>();
        for (Ast.FieldDecl field : actor.fields()) {
            checkExpr(field.initializer());
        }
        for (Ast.MethodDecl method : actor.methods()) {
            // A second handler of either name cannot be hidden by arity-based
            // overload resolution. The actor's mailbox ABI is a single slot.
            if (method.name().equals("run")) {
                runs++;
                if (method.visibility() != Ast.Visibility.PRIVATE
                        || method.isStatic() || method.isAbstract()
                        || method.async() || method.structural()
                        || method.explicitReceiverType() != null
                        || !method.genericParameters().isEmpty()
                        || method.parameters().size() != 1
                        || method.returnType().name().equals("void")) {
                    throw new IllegalArgumentException(
                            "actor '" + actor.name()
                                    + "': run must be private, synchronous, concrete, non-generic, "
                                    + "take exactly one message, and return a non-void result");
                }
            }
            if (method.name().equals("receive")) receives++;
            if (method.name().equals("on_start")
                    && method.visibility() == Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException(
                        "actor '" + actor.name() + "': on_start is private");
            }
            String signature = method.name() + "/" + method.arity()
                    + "/" + method.isStatic();
            if (!signatures.add(signature)) {
                throw new IllegalArgumentException(
                        "actor '" + actor.name() + "': duplicate method " + signature);
            }
            checkStmts(method.body());
        }
        if (runs > 1 || receives > 1) {
            throw new IllegalArgumentException(
                    "actor '" + actor.name() + "' cannot overload run or receive");
        }
        if (runs > 0 && receives > 0) {
            throw new IllegalArgumentException(
                    "actor '" + actor.name()
                            + "' cannot mix request handler run with event handler receive");
        }
    }

    private static void checkStmts(List<Ast.Stmt> statements) {
        for (Ast.Stmt statement : statements) checkStmt(statement);
    }

    private static void checkStmt(Ast.Stmt stmt) {
        if (stmt == null) return;
        if (stmt instanceof Ast.BindingStmt v) checkExpr(v.initializer());
        else if (stmt instanceof Ast.DestructureStmt v) checkExpr(v.initializer());
        else if (stmt instanceof Ast.ReturnStmt v) checkExpr(v.value());
        else if (stmt instanceof Ast.YieldStmt v) checkExpr(v.value());
        else if (stmt instanceof Ast.ExprStmt v) checkExpr(v.expression());
        else if (stmt instanceof Ast.DeferStmt v) checkExpr(v.expression());
        else if (stmt instanceof Ast.BlockStmt v) checkStmts(v.body());
        else if (stmt instanceof Ast.IfStmt v) {
            for (Ast.IfBranch branch : v.branches()) {
                checkExpr(branch.condition());
                checkStmts(branch.body());
            }
            checkStmts(v.elseBody());
        } else if (stmt instanceof Ast.MatchStmt v) {
            checkExpr(v.subject());
            for (Ast.MatchArm arm : v.arms()) {
                checkExpr(arm.guard());
                checkStmts(arm.body());
            }
        } else if (stmt instanceof Ast.SwitchStmt v) {
            checkExpr(v.subject());
            for (Ast.SwitchCase arm : v.cases()) {
                for (Ast.Expr c : arm.constants()) checkExpr(c);
                checkStmts(arm.body());
            }
            checkStmts(v.defaultBody());
        } else if (stmt instanceof Ast.TryStmt v) {
            checkStmts(v.body());
            checkStmts(v.catchBody());
            checkStmts(v.finallyBody());
        } else if (stmt instanceof Ast.ForOfStmt v) {
            checkExpr(v.iterable());
            checkStmts(v.body());
        } else if (stmt instanceof Ast.ForOfDestructureStmt v) {
            checkExpr(v.iterable());
            checkStmts(v.body());
        } else if (stmt instanceof Ast.ForStmt v) {
            checkStmt(v.initializer());
            checkExpr(v.condition());
            checkExpr(v.update());
            checkStmts(v.body());
        } else if (stmt instanceof Ast.LoopStmt v) checkStmts(v.body());
        else if (stmt instanceof Ast.SelectStmt v) {
            for (Ast.SelectArm arm : v.arms()) {
                checkExpr(arm.channel());
                checkExpr(arm.value());
                checkStmts(arm.body());
            }
        }
    }

    private static void checkExpr(Ast.Expr expr) {
        if (expr == null) return;
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr name
                    && name.name().equals("self")
                    && RUNTIME_HANDLERS.contains(member.member())) {
                throw new IllegalArgumentException(
                        "actor runtime handler self." + member.member()
                                + " cannot be called directly or captured as a value");
            }
            checkExpr(member.receiver());
        } else if (expr instanceof Ast.CallExpr v) {
            checkExpr(v.callee());
            for (Ast.Expr arg : v.arguments()) checkExpr(arg);
        } else if (expr instanceof Ast.BinaryExpr v) {
            checkExpr(v.left());
            checkExpr(v.right());
        } else if (expr instanceof Ast.UnaryExpr v) checkExpr(v.operand());
        else if (expr instanceof Ast.AssignExpr v) {
            checkExpr(v.target());
            checkExpr(v.value());
        } else if (expr instanceof Ast.ConditionalExpr v) {
            checkExpr(v.condition());
            checkExpr(v.whenTrue());
            checkExpr(v.whenFalse());
        } else if (expr instanceof Ast.TypeTestExpr v) checkExpr(v.value());
        else if (expr instanceof Ast.PatternTestExpr v) checkExpr(v.value());
        else if (expr instanceof Ast.CastExpr v) checkExpr(v.value());
        else if (expr instanceof Ast.SpreadExpr v) checkExpr(v.expression());
        else if (expr instanceof Ast.RuntimeCallExpr v) {
            for (Ast.Expr arg : v.arguments()) checkExpr(arg);
        } else if (expr instanceof Ast.IndexExpr v) {
            checkExpr(v.receiver());
            checkExpr(v.index());
        } else if (expr instanceof Ast.NewExpr v) {
            for (Ast.Expr arg : v.arguments()) checkExpr(arg);
        } else if (expr instanceof Ast.AwaitExpr v) checkExpr(v.expression());
        else if (expr instanceof Ast.ChannelOpExpr v) {
            checkExpr(v.channel());
            checkExpr(v.value());
            checkStmts(v.callbackBody());
        } else if (expr instanceof Ast.DynamicSelectExpr v) checkExpr(v.cases());
        else if (expr instanceof Ast.ListExpr v) {
            for (Ast.Expr item : v.elements()) checkExpr(item);
        } else if (expr instanceof Ast.TupleExpr v) {
            for (Ast.Expr item : v.elements()) checkExpr(item);
        } else if (expr instanceof Ast.ObjectExpr v) {
            for (Ast.ObjectField field : v.fields()) checkExpr(field.value());
        } else if (expr instanceof Ast.LambdaExpr v) {
            checkExpr(v.expressionBody());
            checkStmts(v.blockBody());
        }
    }
}
