package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Conservative write-effect analysis for {@code pure} fnc/routine declarations.
 *
 * <p>Oreslang purity is a no-external-write contract. Reads of explicit inputs
 * and statically resolved outside state are allowed. Writes are limited to
 * storage owned by the current callable invocation. Nested function expressions
 * are checked as independent callable boundaries, so capture cannot launder
 * mutation authority. Calls with unknown effects fail closed.</p>
 */
public final class PureEffectChecker {
    private enum SlotOrigin { LOCAL, PARAM, EXTERNAL }
    private enum ValueOrigin { OWNED, EXTERNAL_ALIAS }

    private record Binding(
            SlotOrigin slot,
            ValueOrigin value,
            boolean provenPureCallable) { }

    private record Lookup(Binding binding, boolean crossedCallableBoundary) { }

    private static final class Scope {
        private final Scope parent;
        private final boolean callableBoundaryFromParent;
        private final Map<String, Binding> bindings = new HashMap<>();

        private Scope(Scope parent) {
            this(parent, false);
        }

        private Scope(Scope parent, boolean callableBoundaryFromParent) {
            this.parent = parent;
            this.callableBoundaryFromParent = callableBoundaryFromParent;
        }

        private void define(String name, Binding binding) {
            if (bindings.putIfAbsent(name, binding) != null) {
                throw error("duplicate binding '" + name + "' while checking pure effects");
            }
        }

        private Lookup lookup(String name) {
            Binding local = bindings.get(name);
            if (local != null) return new Lookup(local, false);
            if (parent == null) return null;
            Lookup inherited = parent.lookup(name);
            if (inherited == null) return null;
            return new Lookup(
                    inherited.binding(),
                    callableBoundaryFromParent || inherited.crossedCallableBoundary());
        }
    }

    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Map<String, Set<String>> moduleBindings = new HashMap<>();

    private PureEffectChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        PureEffectChecker checker = new PureEffectChecker(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            Set<String> bindings = new HashSet<>();
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    functions.put(module.name() + "." + fn.name(), fn);
                    Ast.FunctionDecl previous = functions.putIfAbsent(fn.name(), fn);
                    if (previous != null && previous != fn) {
                        functions.remove(fn.name());
                        ambiguousFunctions.add(fn.name());
                    }
                } else if (declaration instanceof Ast.FieldDecl field) {
                    bindings.add(field.name());
                }
            }
            moduleBindings.put(module.name(), Set.copyOf(bindings));
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl declaration : module.declarations()) {
                if (declaration instanceof Ast.FunctionDecl fn) {
                    if (fn.pure()) checkFunction(module.name(), fn);
                    else scanExplicitPureLambdas(fn.body(), module.name(), fn.name());
                } else if (declaration instanceof Ast.ClassDecl klass) {
                    for (Ast.MethodDecl method : klass.methods()) {
                        scanExplicitPureLambdas(
                                method.body(),
                                module.name(),
                                klass.name() + "." + method.name());
                    }
                }
            }
        }
    }

    private void checkStandalonePureLambda(
            Ast.LambdaExpr lambda,
            String module,
            String owner) {
        Scope scope = new Scope(null);
        for (Ast.Param param : lambda.parameters()) {
            if (param.mutable()) {
                throw error("pure function expression in '" + owner
                        + "' cannot declare mutable parameter '" + param.name() + "'");
            }
            scope.define(param.name(), new Binding(
                    SlotOrigin.PARAM,
                    ValueOrigin.EXTERNAL_ALIAS,
                    false));
        }
        checkStatements(lambda.blockBody(), scope, module, owner + "::<pure fnc>");
    }

    private void scanExplicitPureLambdas(
            List<Ast.Stmt> statements,
            String module,
            String owner) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                scanExplicitPureLambdas(binding.initializer(), module, owner);
            } else if (statement instanceof Ast.DestructureStmt destructure) {
                scanExplicitPureLambdas(destructure.initializer(), module, owner);
            } else if (statement instanceof Ast.ReturnStmt returned) {
                scanExplicitPureLambdas(returned.value(), module, owner);
            } else if (statement instanceof Ast.ExprStmt expression) {
                scanExplicitPureLambdas(expression.expression(), module, owner);
            } else if (statement instanceof Ast.DeferStmt defer) {
                scanExplicitPureLambdas(defer.expression(), module, owner);
            } else if (statement instanceof Ast.BlockStmt block) {
                scanExplicitPureLambdas(block.body(), module, owner);
            } else if (statement instanceof Ast.LoopStmt loop) {
                scanExplicitPureLambdas(loop.body(), module, owner);
            } else if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    scanExplicitPureLambdas(branch.condition(), module, owner);
                    scanExplicitPureLambdas(branch.body(), module, owner);
                }
                scanExplicitPureLambdas(conditional.elseBody(), module, owner);
            } else if (statement instanceof Ast.MatchStmt matched) {
                scanExplicitPureLambdas(matched.subject(), module, owner);
                for (Ast.MatchArm arm : matched.arms()) {
                    scanExplicitPureLambdas(arm.guard(), module, owner);
                    scanExplicitPureLambdas(arm.body(), module, owner);
                }
            } else if (statement instanceof Ast.SwitchStmt switched) {
                scanExplicitPureLambdas(switched.subject(), module, owner);
                for (Ast.SwitchCase arm : switched.cases()) {
                    for (Ast.Expr constant : arm.constants()) {
                        scanExplicitPureLambdas(constant, module, owner);
                    }
                    scanExplicitPureLambdas(arm.body(), module, owner);
                }
                scanExplicitPureLambdas(switched.defaultBody(), module, owner);
            } else if (statement instanceof Ast.TryStmt attempted) {
                scanExplicitPureLambdas(attempted.body(), module, owner);
                scanExplicitPureLambdas(attempted.catchBody(), module, owner);
                scanExplicitPureLambdas(attempted.finallyBody(), module, owner);
            } else if (statement instanceof Ast.ForOfDestructureStmt loop) {
                scanExplicitPureLambdas(loop.iterable(), module, owner);
                scanExplicitPureLambdas(loop.body(), module, owner);
            } else if (statement instanceof Ast.ForOfStmt loop) {
                scanExplicitPureLambdas(loop.iterable(), module, owner);
                scanExplicitPureLambdas(loop.body(), module, owner);
            } else if (statement instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) {
                    scanExplicitPureLambdas(List.of(loop.initializer()), module, owner);
                }
                scanExplicitPureLambdas(loop.condition(), module, owner);
                scanExplicitPureLambdas(loop.update(), module, owner);
                scanExplicitPureLambdas(loop.body(), module, owner);
            }
        }
    }

    private void scanExplicitPureLambdas(
            Ast.Expr expression,
            String module,
            String owner) {
        if (expression == null) return;
        if (expression instanceof Ast.LambdaExpr lambda) {
            if (lambda.pure()) {
                checkStandalonePureLambda(lambda, module, owner);
                return;
            }
            if (lambda.expressionBody() != null) {
                scanExplicitPureLambdas(lambda.expressionBody(), module, owner);
            }
            if (lambda.blockBody() != null) {
                scanExplicitPureLambdas(lambda.blockBody(), module, owner);
            }
            return;
        }
        if (expression instanceof Ast.BinaryExpr binary) {
            scanExplicitPureLambdas(binary.left(), module, owner);
            scanExplicitPureLambdas(binary.right(), module, owner);
        } else if (expression instanceof Ast.UnaryExpr unary) {
            scanExplicitPureLambdas(unary.operand(), module, owner);
        } else if (expression instanceof Ast.AssignExpr assign) {
            scanExplicitPureLambdas(assign.target(), module, owner);
            scanExplicitPureLambdas(assign.value(), module, owner);
        } else if (expression instanceof Ast.ConditionalExpr conditional) {
            scanExplicitPureLambdas(conditional.condition(), module, owner);
            scanExplicitPureLambdas(conditional.whenTrue(), module, owner);
            scanExplicitPureLambdas(conditional.whenFalse(), module, owner);
        } else if (expression instanceof Ast.TypeTestExpr tested) {
            scanExplicitPureLambdas(tested.value(), module, owner);
        } else if (expression instanceof Ast.PatternTestExpr tested) {
            scanExplicitPureLambdas(tested.value(), module, owner);
        } else if (expression instanceof Ast.CastExpr cast) {
            scanExplicitPureLambdas(cast.value(), module, owner);
        } else if (expression instanceof Ast.CallExpr call) {
            scanExplicitPureLambdas(call.callee(), module, owner);
            for (Ast.Expr arg : call.arguments()) {
                scanExplicitPureLambdas(arg, module, owner);
            }
        } else if (expression instanceof Ast.MemberExpr member) {
            scanExplicitPureLambdas(member.receiver(), module, owner);
        } else if (expression instanceof Ast.IndexExpr indexed) {
            scanExplicitPureLambdas(indexed.receiver(), module, owner);
            scanExplicitPureLambdas(indexed.index(), module, owner);
        } else if (expression instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) {
                scanExplicitPureLambdas(arg, module, owner);
            }
        } else if (expression instanceof Ast.AwaitExpr awaited) {
            scanExplicitPureLambdas(awaited.expression(), module, owner);
        } else if (expression instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                scanExplicitPureLambdas(item, module, owner);
            }
        } else if (expression instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                scanExplicitPureLambdas(item, module, owner);
            }
        } else if (expression instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                scanExplicitPureLambdas(field.dynamicName(), module, owner);
                scanExplicitPureLambdas(field.value(), module, owner);
            }
        }
    }

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Scope external = new Scope(null);
        for (String name : moduleBindings.getOrDefault(module, Set.of())) {
            external.define(name, new Binding(
                    SlotOrigin.EXTERNAL,
                    ValueOrigin.EXTERNAL_ALIAS,
                    false));
        }

        Scope scope = new Scope(external, true);
        for (Ast.Param param : fn.parameters()) {
            if (param.mutable()) {
                throw error("pure callable '" + fn.name()
                        + "' cannot declare mutable parameter '" + param.name() + "'");
            }
            scope.define(param.name(), new Binding(
                    SlotOrigin.PARAM,
                    ValueOrigin.EXTERNAL_ALIAS,
                    false));
        }
        checkStatements(fn.body(), scope, module, fn.name());
    }

    private void checkStatements(
            List<Ast.Stmt> statements,
            Scope scope,
            String module,
            String callable) {
        for (Ast.Stmt statement : statements) {
            if (statement instanceof Ast.BindingStmt binding) {
                checkExpr(binding.initializer(), scope, module, callable);
                scope.define(binding.name(), new Binding(
                        SlotOrigin.LOCAL,
                        aliasesExternal(binding.initializer(), scope)
                                ? ValueOrigin.EXTERNAL_ALIAS
                                : ValueOrigin.OWNED,
                        provenPureCallable(binding.initializer(), scope)));
                continue;
            }
            if (statement instanceof Ast.DestructureStmt destructure) {
                checkExpr(destructure.initializer(), scope, module, callable);
                ValueOrigin value = aliasesExternal(destructure.initializer(), scope)
                        ? ValueOrigin.EXTERNAL_ALIAS
                        : ValueOrigin.OWNED;
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    if (!binding.isDiscard()) {
                        scope.define(binding.name(), new Binding(
                                SlotOrigin.LOCAL, value, false));
                    }
                }
                continue;
            }
            if (statement instanceof Ast.ReturnStmt returned) {
                if (returned.value() != null) {
                    checkExpr(returned.value(), scope, module, callable);
                }
                continue;
            }
            if (statement instanceof Ast.ExprStmt expression) {
                checkExpr(expression.expression(), scope, module, callable);
                continue;
            }
            if (statement instanceof Ast.DeferStmt defer) {
                checkExpr(defer.expression(), scope, module, callable);
                continue;
            }
            if (statement instanceof Ast.BlockStmt block) {
                checkStatements(block.body(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.LoopStmt loop) {
                checkStatements(loop.body(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    checkExpr(branch.condition(), scope, module, callable);
                    checkStatements(branch.body(), new Scope(scope), module, callable);
                }
                checkStatements(conditional.elseBody(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.MatchStmt matched) {
                checkExpr(matched.subject(), scope, module, callable);
                ValueOrigin subjectOrigin = aliasesExternal(matched.subject(), scope)
                        ? ValueOrigin.EXTERNAL_ALIAS
                        : ValueOrigin.OWNED;
                for (Ast.MatchArm arm : matched.arms()) {
                    Scope armScope = new Scope(scope);
                    definePatternBindings(arm.pattern(), armScope, subjectOrigin);
                    if (arm.guard() != null) checkExpr(arm.guard(), armScope, module, callable);
                    checkStatements(arm.body(), armScope, module, callable);
                }
                continue;
            }
            if (statement instanceof Ast.SwitchStmt switched) {
                checkExpr(switched.subject(), scope, module, callable);
                for (Ast.SwitchCase arm : switched.cases()) {
                    for (Ast.Expr constant : arm.constants()) {
                        checkExpr(constant, scope, module, callable);
                    }
                    checkStatements(arm.body(), new Scope(scope), module, callable);
                }
                checkStatements(switched.defaultBody(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.TryStmt attempted) {
                checkStatements(attempted.body(), new Scope(scope), module, callable);
                Scope caught = new Scope(scope);
                caught.define(attempted.errorName(), new Binding(
                        SlotOrigin.LOCAL,
                        ValueOrigin.OWNED,
                        false));
                checkStatements(attempted.catchBody(), caught, module, callable);
                checkStatements(attempted.finallyBody(), new Scope(scope), module, callable);
                continue;
            }
            if (statement instanceof Ast.ForOfDestructureStmt loop) {
                checkExpr(loop.iterable(), scope, module, callable);
                Scope body = new Scope(scope);
                ValueOrigin origin = aliasesExternal(loop.iterable(), scope)
                        ? ValueOrigin.EXTERNAL_ALIAS
                        : ValueOrigin.OWNED;
                for (Ast.DestructureBinding binding : loop.bindings()) {
                    if (!binding.isDiscard()) {
                        body.define(binding.name(), new Binding(
                                SlotOrigin.LOCAL, origin, false));
                    }
                }
                checkStatements(loop.body(), body, module, callable);
                continue;
            }
            if (statement instanceof Ast.ForOfStmt loop) {
                checkExpr(loop.iterable(), scope, module, callable);
                Scope body = new Scope(scope);
                body.define(loop.bindingName(), new Binding(
                        SlotOrigin.LOCAL,
                        aliasesExternal(loop.iterable(), scope)
                                ? ValueOrigin.EXTERNAL_ALIAS
                                : ValueOrigin.OWNED,
                        false));
                checkStatements(loop.body(), body, module, callable);
                continue;
            }
            if (statement instanceof Ast.ForStmt loop) {
                Scope body = new Scope(scope);
                if (loop.initializer() != null) {
                    checkStatements(List.of(loop.initializer()), body, module, callable);
                }
                if (loop.condition() != null) {
                    checkExpr(loop.condition(), body, module, callable);
                }
                if (loop.update() != null) {
                    checkExpr(loop.update(), body, module, callable);
                }
                checkStatements(loop.body(), body, module, callable);
                continue;
            }
            if (statement instanceof Ast.BreakStmt || statement instanceof Ast.ContinueStmt) {
                continue;
            }
            throw error("pure callable '" + callable
                    + "' contains a statement whose write effects are not classified: "
                    + statement.getClass().getSimpleName());
        }
    }

    private void definePatternBindings(
            Ast.Pattern pattern,
            Scope scope,
            ValueOrigin valueOrigin) {
        if (pattern instanceof Ast.BindingPattern binding) {
            scope.define(binding.name(), new Binding(
                    SlotOrigin.LOCAL, valueOrigin, false));
        } else if (pattern instanceof Ast.TypePattern typed && typed.binding() != null) {
            scope.define(typed.binding(), new Binding(
                    SlotOrigin.LOCAL, valueOrigin, false));
        } else if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern nested : constructor.arguments()) {
                definePatternBindings(nested, scope, valueOrigin);
            }
        }
    }

    private void checkExpr(
            Ast.Expr expr,
            Scope scope,
            String module,
            String callable) {
        if (expr == null || expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) {
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            assertWritableTarget(assignment.target(), scope, callable);
            checkExpr(assignment.value(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), scope, module, callable);
            checkExpr(binary.right(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            checkExpr(unary.operand(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExpr(conditional.condition(), scope, module, callable);
            checkExpr(conditional.whenTrue(), scope, module, callable);
            checkExpr(conditional.whenFalse(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.TypeTestExpr tested) {
            checkExpr(tested.value(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.PatternTestExpr tested) {
            checkExpr(tested.value(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.CastExpr cast) {
            checkExpr(cast.value(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            for (Ast.Expr argument : call.arguments()) {
                checkExpr(argument, scope, module, callable);
            }
            if (call.callee() instanceof Ast.LambdaExpr lambda) {
                checkExpr(lambda, scope, module, callable);
                return;
            }
            assertPureCall(call.callee(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.MemberExpr member) {
            checkExpr(member.receiver(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            checkExpr(indexed.receiver(), scope, module, callable);
            checkExpr(indexed.index(), scope, module, callable);
            return;
        }
        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr argument : created.arguments()) {
                checkExpr(argument, scope, module, callable);
            }
            return;
        }
        if (expr instanceof Ast.AwaitExpr) {
            throw error("pure callable '" + callable
                    + "' cannot await until asynchronous effects are typed");
        }
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                checkExpr(item, scope, module, callable);
            }
            return;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                checkExpr(item, scope, module, callable);
            }
            return;
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (field.dynamicName() != null) {
                    checkExpr(field.dynamicName(), scope, module, callable);
                }
                checkExpr(field.value(), scope, module, callable);
            }
            return;
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Scope nested = new Scope(scope, true);
            for (Ast.Param param : lambda.parameters()) {
                if (param.mutable()) {
                    throw error("function expression created by pure callable '" + callable
                            + "' cannot expose mutable parameter '" + param.name() + "'");
                }
                nested.define(param.name(), new Binding(
                        SlotOrigin.PARAM,
                        ValueOrigin.EXTERNAL_ALIAS,
                        false));
            }
            if (lambda.expressionBody() != null) {
                checkExpr(lambda.expressionBody(), nested, module, callable);
            }
            if (lambda.blockBody() != null) {
                checkStatements(lambda.blockBody(), nested, module, callable);
            }
            return;
        }

        throw error("pure callable '" + callable
                + "' contains an expression whose write effects are not classified: "
                + expr.getClass().getSimpleName());
    }

    private void assertPureCall(
            Ast.Expr callee,
            Scope scope,
            String module,
            String callable) {
        if (callee instanceof Ast.NameExpr name) {
            Lookup local = scope.lookup(name.name());
            if (local != null && local.binding().provenPureCallable()) return;

            if (name.name().equals("Some")
                    || name.name().equals("Ok")
                    || name.name().equals("Err")) {
                return;
            }

            Ast.FunctionDecl target = ambiguousFunctions.contains(name.name())
                    ? null
                    : functions.get(name.name());
            if (target != null && target.pure()) return;

            throw error("pure callable '" + callable
                    + "' cannot call unproven-effect callable '" + name.name() + "'");
        }

        if (callee instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            Ast.FunctionDecl target = functions.get(
                    namespace.name() + "." + member.member());
            if (target != null && target.pure()) return;
        }

        throw error("pure callable '" + callable
                + "' may call only statically proven pure fnc/routine values; "
                + "method, imported, runtime-object, and unknown indirect calls fail closed");
    }

    private boolean provenPureCallable(Ast.Expr expr, Scope scope) {
        if (expr instanceof Ast.LambdaExpr) return true;
        if (expr instanceof Ast.NameExpr name) {
            Lookup found = scope.lookup(name.name());
            if (found != null && found.binding().provenPureCallable()) return true;
            Ast.FunctionDecl target = ambiguousFunctions.contains(name.name())
                    ? null
                    : functions.get(name.name());
            return target != null && target.pure();
        }
        if (expr instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            Ast.FunctionDecl target = functions.get(
                    namespace.name() + "." + member.member());
            return target != null && target.pure();
        }
        return false;
    }

    private void assertWritableTarget(
            Ast.Expr target,
            Scope scope,
            String callable) {
        Ast.NameExpr root = rootName(target);
        if (root == null) {
            throw error("pure callable '" + callable
                    + "' cannot mutate an unrooted/unknown target");
        }

        Lookup found = scope.lookup(root.name());
        if (found == null) {
            throw error("pure callable '" + callable
                    + "' cannot write external binding '" + root.name() + "'");
        }

        Binding binding = found.binding();
        if (found.crossedCallableBoundary()) {
            throw error("pure callable '" + callable
                    + "' cannot mutate captured binding '" + root.name() + "'");
        }

        if (target instanceof Ast.NameExpr) {
            if (binding.slot() == SlotOrigin.PARAM) {
                throw error("pure callable '" + callable
                        + "' cannot modify input parameter '" + root.name() + "'");
            }
            if (binding.slot() == SlotOrigin.EXTERNAL) {
                throw error("pure callable '" + callable
                        + "' cannot write outside binding '" + root.name() + "'");
            }
            return;
        }

        if (binding.slot() != SlotOrigin.LOCAL
                || binding.value() == ValueOrigin.EXTERNAL_ALIAS) {
            throw error("pure callable '" + callable
                    + "' cannot mutate state reachable from parameter/outside binding '"
                    + root.name() + "'");
        }
    }

    private Ast.NameExpr rootName(Ast.Expr expr) {
        if (expr instanceof Ast.NameExpr name) return name;
        if (expr instanceof Ast.MemberExpr member) return rootName(member.receiver());
        if (expr instanceof Ast.IndexExpr indexed) return rootName(indexed.receiver());
        return null;
    }

    private boolean aliasesExternal(Ast.Expr expr, Scope scope) {
        if (expr == null) return false;
        if (expr instanceof Ast.NameExpr name) {
            Lookup found = scope.lookup(name.name());
            if (found == null) return true;
            Binding binding = found.binding();
            return found.crossedCallableBoundary()
                    || binding.slot() != SlotOrigin.LOCAL
                    || binding.value() == ValueOrigin.EXTERNAL_ALIAS;
        }
        if (expr instanceof Ast.MemberExpr member) {
            return aliasesExternal(member.receiver(), scope);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return aliasesExternal(indexed.receiver(), scope);
        }
        if (expr instanceof Ast.CastExpr cast) {
            return aliasesExternal(cast.value(), scope);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return aliasesExternal(conditional.whenTrue(), scope)
                    || aliasesExternal(conditional.whenFalse(), scope);
        }
        if (expr instanceof Ast.ListExpr list) {
            return list.elements().stream().anyMatch(item -> aliasesExternal(item, scope));
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().anyMatch(item -> aliasesExternal(item, scope));
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (aliasesExternal(field.value(), scope)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.CallExpr) {
            // Without an ownership/freshness result summary, a pure call may
            // still return an alias of one of its inputs. Fail closed.
            return true;
        }
        if (expr instanceof Ast.LambdaExpr) return false;
        return false;
    }

    private static IllegalArgumentException error(String message) {
        return new IllegalArgumentException(
                "Oreslang pure effect error: " + message);
    }
}
