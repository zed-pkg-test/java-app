package dev.oreslang.types;

import dev.oreslang.ast.Ast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ownership / borrow / closure-capture analysis.
 *
 * This pass is intentionally independent of the interpreter. Borrows erase at
 * runtime; compile-time ownership remains authoritative for every backend.
 *
 * Current model:
 * - primitive immutable values are Copy;
 * - class/list/object/function values and structs are move-only by default;
 * - by-value call/binding/return moves move-only values;
 * - &T permits shared immutable borrows;
 * - &mut T is exclusive and requires a mutable owner;
 * - Bar mut b makes an owned parameter mutable inside the callee;
 * - escaping closures own non-Copy captures and mutable captures;
 * - closures may not capture a borrow (pass it as a lambda parameter instead);
 * - moving an outer value from a repeating loop is rejected conservatively.
 */
public final class OwnershipChecker {
    private Ast.ModuleDecl activeModule;
    private Ast.ClassDecl activeClass;
    private Ast.MethodDecl activeMethod;
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final ArrayDeque<Map<String, Ast.ClassDecl>> localClassScopes = new ArrayDeque<>();
    private final ArrayDeque<Map<String, Ast.TypeAliasDecl>> localAliasScopes = new ArrayDeque<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedNames = new HashSet<>();
    private final Set<Ast.FieldDecl> detachedSharedModuleFields =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private OwnershipChecker(Ast.Program program) {
        index(program);
    }

    public static Ast.Program check(Ast.Program program) {
        OwnershipChecker checker = new OwnershipChecker(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.wildcard()) {
                if (imported.namespace() != null) importedNames.add(imported.namespace());
            } else {
                importedNames.addAll(imported.names());
            }
        }
        for (Ast.ModuleDecl module : program.modules()) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                else if (decl instanceof Ast.TypeAliasDecl alias) index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
            }
        }
    }

    private static <T> void index(Map<String,T> map, Set<String> ambiguous, String module, String name, T value) {
        map.put(module + "." + name, value);
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            map.remove(name);
            ambiguous.add(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            Ast.ModuleDecl previous = activeModule;
            activeModule = module;
            try {
                checkModuleFieldInitializers(module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) checkFunction(module, fn);
                    else if (decl instanceof Ast.InitDecl init) checkInit(module, init);
                    else if (decl instanceof Ast.ClassDecl klass) checkClass(module, klass);
                }
            } finally {
                activeModule = previous;
            }
        }
    }

    private void checkModuleFieldInitializers(Ast.ModuleDecl module) {
        Scope scope = new Scope(null);
        try {
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FieldDecl field) || field.initializer() == null) continue;

                // Module/file initialization is declaration-ordered. Do not pre-seed
                // later slots: doing so can hide an aliasing move from an earlier
                // module-owned slot and contradict runtime initialization order.
                ValueInfo stored = checkExpr(field.initializer(), scope, true);
                String where = "module field '" + module.name() + "." + field.name() + "'";
                rejectStoredBorrow(stored, where);
                requireDetachedStoredBorrow(field.type(), stored, where);
                if (field.type() != null) {
                    requireBindingOwnershipCompatibility(field.type(), stored, where);
                }

                Ast.TypeRef type = field.type() == null
                        ? (stored.type == null ? inferFieldType(field.initializer()) : stored.type)
                        : field.type();
                ValueKind kind = stored.kind == ValueKind.SHARED
                        ? ValueKind.SHARED
                        : kindOfType(type);
                VarState state = new VarState(
                        type,
                        field.bindingKind() == Ast.BindingKind.LET,
                        kind,
                        Origin.MODULE);
                scope.define(field.name(), state);

                if (stored.kind == ValueKind.SHARED) {
                    detachedSharedModuleFields.add(field);
                }
            }
        } finally {
            scope.close();
        }
    }

    private void checkInit(Ast.ModuleDecl module, Ast.InitDecl init) {
        Scope moduleScope = new Scope(null);
        seedModuleState(module, moduleScope);
        Scope scope = new Scope(moduleScope);
        checkBlock(init.body(), scope, Ast.TypeRef.simple("void"));
        scope.close();
        moduleScope.close();
    }

    private void checkFunction(Ast.ModuleDecl module, Ast.FunctionDecl fn) {
        Scope moduleScope = new Scope(null);
        seedModuleState(module, moduleScope);
        Scope scope = new Scope(moduleScope);
        for (Ast.Param param : fn.parameters()) {
            scope.define(param.name(), stateForParam(param));
        }
        checkBlock(fn.body(), scope, fn.returnType());
        scope.close();
        moduleScope.close();
    }

    private void seedModuleState(Ast.ModuleDecl module, Scope scope) {
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FieldDecl field)) continue;
            Ast.TypeRef type = field.type() == null ? inferFieldType(field.initializer()) : field.type();
            ValueKind kind = detachedSharedModuleFields.contains(field)
                    ? ValueKind.SHARED
                    : kindOfType(type);
            scope.define(field.name(), new VarState(
                    type,
                    field.bindingKind() == Ast.BindingKind.LET,
                    kind,
                    Origin.MODULE));
        }
    }

    private Ast.TypeRef inferFieldType(Ast.Expr initializer) {
        if (initializer instanceof Ast.LiteralExpr literal) return inferLiteralType(literal.value());
        if (initializer instanceof Ast.ListExpr) return Ast.TypeRef.simple("Array");
        if (initializer instanceof Ast.ObjectExpr) return Ast.TypeRef.simple("obj");
        if (initializer instanceof Ast.NewExpr created) return created.type();
        if (initializer instanceof Ast.LambdaExpr) return Ast.TypeRef.simple("Fnc");
        return Ast.TypeRef.inferred();
    }

    private void checkClass(Ast.ModuleDecl module, Ast.ClassDecl klass) {
        Scope fieldModuleScope = new Scope(null);
        seedModuleState(module, fieldModuleScope);
        try {
            for (Ast.FieldDecl field : klass.fields()) {
                if (field.initializer() == null) continue;
                ValueInfo stored = checkExpr(field.initializer(), fieldModuleScope, true);
                String where = (klass.isStruct() ? "struct" : "class")
                        + " field initializer '" + klass.name() + "." + field.name() + "'";
                rejectStoredBorrow(stored, where);
                requireDetachedStoredBorrow(field.type(), stored, where);
            }
        } finally {
            fieldModuleScope.close();
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Ast.ClassDecl previousClass = activeClass;
            Ast.MethodDecl previousMethod = activeMethod;
            activeClass = klass;
            activeMethod = method;

            Scope moduleScope = new Scope(null);
            seedModuleState(module, moduleScope);
            Scope scope = new Scope(moduleScope);
            try {
                if (!method.isStatic()) {
                    Ast.TypeRef explicit = method.explicitReceiverType();
                    boolean mutableReceiver = explicit != null
                            && explicit.isBorrow()
                            && explicit.mutableBorrow();
                    Ast.TypeRef selfType = Ast.TypeRef.simple(klass.name());
                    scope.define(
                            "self",
                            new VarState(
                                    selfType,
                                    false,
                                    mutableReceiver ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW,
                                    Origin.PARAM));
                }
                for (Ast.Param param : method.parameters()) scope.define(param.name(), stateForParam(param));
                checkBlock(method.body(), scope, method.returnType());
            } finally {
                scope.close();
                moduleScope.close();
                activeClass = previousClass;
                activeMethod = previousMethod;
            }
        }
    }

    private VarState stateForParam(Ast.Param param) {
        ValueKind kind = param.structural() && !param.type().isBorrow() ? ValueKind.IMM_BORROW : kindOfType(param.type());
        boolean mutableOwner = param.mutable();
        if (param.type().isBorrow() && param.type().mutableBorrow()) mutableOwner = false;
        return new VarState(param.type(), mutableOwner, kind, Origin.PARAM);
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, Ast.TypeRef returnType) {
        pushLocalTypeScopes(body);
        Scope scope = new Scope(parent);
        try {
            for (Ast.Stmt stmt : body) checkStatement(stmt, scope, returnType);
        } finally {
            scope.close();
            localAliasScopes.pop();
            localClassScopes.pop();
        }
    }

    private void pushLocalTypeScopes(List<Ast.Stmt> body) {
        LinkedHashMap<String, Ast.ClassDecl> localClasses = new LinkedHashMap<>();
        LinkedHashMap<String, Ast.TypeAliasDecl> localAliases = new LinkedHashMap<>();
        for (Ast.Stmt stmt : body) {
            if (!(stmt instanceof Ast.TypeDeclStmt typeDecl)) continue;
            if (typeDecl.declaration() instanceof Ast.ClassDecl klass) {
                localClasses.put(klass.name(), klass);
            } else if (typeDecl.declaration() instanceof Ast.TypeAliasDecl alias) {
                localAliases.put(alias.name(), alias);
            }
        }
        localClassScopes.push(localClasses);
        localAliasScopes.push(localAliases);
    }

    private void checkStatement(Ast.Stmt stmt, Scope scope, Ast.TypeRef returnType) {
        if (stmt instanceof Ast.TypeDeclStmt localType) {
            if (localType.declaration() instanceof Ast.ClassDecl klass) {
                if (activeModule == null) throw error("local aggregate has no enclosing module ownership scope");
                checkClass(activeModule, klass);
            }
            return;
        }
        if (stmt instanceof Ast.BindingStmt binding) {
            checkBinding(binding, scope);
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            ValueInfo source = checkExpr(destructure.initializer(), scope, true);
            if (source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW
                    || source.kind == ValueKind.SHARED) {
                throw error("cannot destructure a borrowed/shared container into owned bindings; "
                        + "copy(...) the container first or access its elements as read-only views");
            }
            for (Ast.DestructureBinding binding : destructure.bindings()) {
                scope.define(binding.name(), new VarState(
                        Ast.TypeRef.inferred(),
                        binding.kind() == Ast.BindingKind.LET,
                        source.kind == ValueKind.COPY ? ValueKind.COPY : ValueKind.MOVE_ONLY,
                        Origin.LOCAL));
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() != null) {
                ValueInfo returned = checkExpr(ret.value(), scope, true);
                requireBindingOwnershipCompatibility(returnType, returned, "return value");
                if ((returned.kind == ValueKind.IMM_BORROW || returned.kind == ValueKind.MUT_BORROW)
                        && returned.borrowSource != null
                        && !borrowMayEscape(returned.borrowSource)) {
                    throw error("cannot return a borrow of owned value '" + returned.borrowSource.debugName
                            + "'; borrowed value would outlive its owner");
                }
            }
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            checkExpr(expression.expression(), scope, false);
            return;
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            checkExpr(defer.expression(), scope, false);
            return;
        }
        if (stmt instanceof Ast.IfStmt conditional) {
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);
            List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();

            for (Ast.IfBranch branch : conditional.branches()) {
                restoreState(base);
                checkExpr(branch.condition(), scope, false);
                checkBlock(branch.body(), scope, returnType);
                exits.add(stateSnapshot(scope));
            }

            restoreState(base);
            if (!conditional.elseBody().isEmpty()) {
                checkBlock(conditional.elseBody(), scope, returnType);
                exits.add(stateSnapshot(scope));
            } else {
                exits.add(base); // condition may be false with no else
            }

            mergeBranchState(base, exits);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            checkBlock(attempted.body(), scope, returnType);
            Scope caught = new Scope(scope);
            caught.define(attempted.errorName(), new VarState(Ast.TypeRef.inferred(), false, ValueKind.MOVE_ONLY, Origin.LOCAL));
            checkBlock(attempted.catchBody(), caught, returnType);
            caught.close();
            checkBlock(attempted.finallyBody(), scope, returnType);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            checkExpr(loop.iterable(), scope, false);
            Map<VarState,Boolean> before = movedSnapshot(scope);
            Scope loopScope = new Scope(scope);
            loopScope.define(loop.bindingName(), new VarState(Ast.TypeRef.inferred(), loop.bindingKind() == Ast.BindingKind.LET, ValueKind.MOVE_ONLY, Origin.LOCAL));
            checkBlock(loop.body(), loopScope, returnType);
            loopScope.close();
            rejectLoopMoves(before, scope);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Scope loopScope = new Scope(scope);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopScope, returnType);
            if (loop.condition() != null) checkExpr(loop.condition(), loopScope, false);
            Map<VarState,Boolean> before = movedSnapshot(scope);
            checkBlock(loop.body(), loopScope, returnType);
            if (loop.update() != null) checkExpr(loop.update(), loopScope, false);
            rejectLoopMoves(before, scope);
            loopScope.close();
        }
    }

    private void checkBinding(Ast.BindingStmt binding, Scope scope) {
        boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
        VarState placeholder = null;
        if (recursiveLambda) {
            placeholder = new VarState(
                    binding.declaredType() == null ? Ast.TypeRef.inferred() : binding.declaredType(),
                    binding.kind() == Ast.BindingKind.LET,
                    ValueKind.MOVE_ONLY,
                    Origin.LOCAL);
            scope.define(binding.name(), placeholder);
        }

        ValueInfo value;
        if (binding.initializer() instanceof Ast.LambdaExpr lambda) {
            value = checkLambda(lambda, scope, binding.name());
        } else {
            value = checkExpr(binding.initializer(), scope, true);
        }

        if (recursiveLambda) {
            placeholder.type = binding.declaredType() == null ? value.type : binding.declaredType();
            placeholder.kind = value.kind;
            placeholder.borrowSource = value.borrowSource;
            return;
        }

        if (binding.declaredType() != null) {
            requireBindingOwnershipCompatibility(
                    binding.declaredType(), value, "binding '" + binding.name() + "'");
        }

        VarState state = new VarState(
                binding.declaredType() == null ? value.type : binding.declaredType(),
                binding.kind() == Ast.BindingKind.LET,
                value.kind,
                Origin.LOCAL);
        state.borrowSource = value.borrowSource;
        if (value.borrowSource != null) {
            if (value.kind == ValueKind.IMM_BORROW) {
                value.borrowSource.immutableBorrows++;
                state.borrowRegistered = true;
            } else if (value.kind == ValueKind.MUT_BORROW) {
                if (!value.borrowRegistered) {
                    beginPersistentBorrow(value.borrowSource, true);
                }
                state.borrowRegistered = true;
            }
        }
        scope.define(binding.name(), state);
    }

    private ValueInfo checkExpr(Ast.Expr expr, Scope scope, boolean consuming) {
        if (expr instanceof Ast.LiteralExpr literal) {
            return new ValueInfo(inferLiteralType(literal.value()), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state == null) return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null); // function/module/global
            state.debugName = name.name();
            requireUsable(state, name.name(), false);
            if (consuming && state.kind == ValueKind.MOVE_ONLY) move(state, name.name());
            if (consuming && state.kind == ValueKind.MUT_BORROW) {
                VarState source = state.borrowSource;
                boolean registered = state.borrowRegistered;
                move(state, name.name());
                state.borrowSource = null;
                state.borrowRegistered = false;
                return new ValueInfo(state.type, ValueKind.MUT_BORROW, source, registered);
            }
            return new ValueInfo(state.type, state.kind, state.borrowSource, state.borrowRegistered);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                boolean mutable = unary.operator().equals("&mut");
                VarState owner = borrowOwner(unary.operand(), scope);
                if (!mutable && owner.kind == ValueKind.SHARED) {
                    Ast.TypeRef target = owner.type != null && owner.type.isBorrow()
                            ? owner.type.borrowedTarget()
                            : owner.type;
                    return new ValueInfo(Ast.TypeRef.borrowed(target, false), ValueKind.SHARED, null);
                }
                validateBorrow(owner, mutable);
                return new ValueInfo(Ast.TypeRef.borrowed(owner.type, mutable), mutable ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW, owner);
            }
            return checkExpr(unary.operand(), scope, false);
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            checkAssignmentTarget(assignment.target(), scope);
            if (assignment.target() instanceof Ast.NameExpr name) {
                VarState target = requireState(scope, name.name());
                if (target.kind == ValueKind.IMM_BORROW || target.kind == ValueKind.MUT_BORROW
                        || target.kind == ValueKind.SHARED
                        || (target.type != null && target.type.isBorrow())) {
                    throw error("reassigning borrow/share binding '" + name.name()
                            + "' is not supported until lifetime-state reassignment is modeled explicitly; "
                            + "introduce a new lexical binding instead");
                }
            }
            ValueInfo value = checkExpr(assignment.value(), scope, true);
            if (assignment.target() instanceof Ast.MemberExpr || assignment.target() instanceof Ast.IndexExpr) {
                rejectStoredBorrow(value, "assignment into owning aggregate/container storage");
                Ast.TypeRef targetType = receiverType(assignment.target(), scope);
                requireDetachedStoredBorrow(targetType, value,
                        "assignment into owning aggregate/container storage");
            }
            return value;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            checkExpr(binary.left(), scope, false);
            checkExpr(binary.right(), scope, false);
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            checkExpr(conditional.condition(), scope, false);
            Map<VarState, StateSnapshot> base = stateSnapshot(scope);

            ValueInfo left = checkExpr(conditional.whenTrue(), scope, consuming);
            Map<VarState, StateSnapshot> leftExit = stateSnapshot(scope);

            restoreState(base);
            ValueInfo right = checkExpr(conditional.whenFalse(), scope, consuming);
            Map<VarState, StateSnapshot> rightExit = stateSnapshot(scope);

            mergeBranchState(base, List.of(leftExit, rightExit));
            if (left.kind == ValueKind.COPY && right.kind == ValueKind.COPY) return left;
            if (left.kind == ValueKind.SHARED && right.kind == ValueKind.SHARED) {
                return new ValueInfo(left.type, ValueKind.SHARED, null);
            }
            boolean leftBorrow = left.kind == ValueKind.IMM_BORROW || left.kind == ValueKind.MUT_BORROW;
            boolean rightBorrow = right.kind == ValueKind.IMM_BORROW || right.kind == ValueKind.MUT_BORROW;
            if (leftBorrow || rightBorrow) {
                if (left.kind == right.kind && left.borrowSource == right.borrowSource) {
                    return new ValueInfo(left.type, left.kind, left.borrowSource,
                            left.borrowRegistered && right.borrowRegistered);
                }
                throw error("conditional expression cannot merge borrows with different owners/lifetimes; use share(...) for a detached snapshot");
            }
            return new ValueInfo(left.type, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.CallExpr call) {
            if (isOwnershipIntrinsicCall(call, scope)) {
                Ast.NameExpr intrinsic = (Ast.NameExpr) call.callee();
                if (call.arguments().size() != 1) throw error(intrinsic.name() + " expects exactly one value");
                Ast.Expr argument = call.arguments().getFirst();
                return switch (intrinsic.name()) {
                    case "copy" -> {
                        ValueInfo source = checkExpr(argument, scope, false);
                        Ast.TypeRef target = source.type != null && source.type.isBorrow()
                                ? source.type.borrowedTarget()
                                : source.type;
                        ValueKind resultKind = kindOfType(target);
                        if (resultKind == ValueKind.IMM_BORROW || resultKind == ValueKind.MUT_BORROW
                                || resultKind == ValueKind.SHARED) resultKind = ValueKind.MOVE_ONLY;
                        if (target != null && target.name().equals("$infer$") && source.kind == ValueKind.COPY) {
                            resultKind = ValueKind.COPY;
                        }
                        yield new ValueInfo(target, resultKind, null);
                    }
                    case "take" -> {
                        ValueInfo source = checkExpr(argument, scope, true);
                        if (source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW
                                || source.kind == ValueKind.SHARED
                                || (source.type != null && source.type.isBorrow())) {
                            throw error("take requires an owned value, not a borrow");
                        }
                        yield source;
                    }
                    case "borrow" -> {
                        VarState owner = borrowOwner(argument, scope);
                        if (owner.kind == ValueKind.SHARED) {
                            Ast.TypeRef target = owner.type != null && owner.type.isBorrow()
                                    ? owner.type.borrowedTarget()
                                    : owner.type;
                            yield new ValueInfo(Ast.TypeRef.borrowed(target, false), ValueKind.SHARED, null);
                        }
                        validateBorrow(owner, false);
                        yield new ValueInfo(Ast.TypeRef.borrowed(owner.type, false), ValueKind.IMM_BORROW, owner);
                    }
                    case "share" -> {
                        ValueInfo source = checkExpr(argument, scope, false);
                        Ast.TypeRef target = source.type != null && source.type.isBorrow()
                                ? source.type.borrowedTarget()
                                : source.type;
                        yield new ValueInfo(Ast.TypeRef.borrowed(target, false), ValueKind.SHARED, null);
                    }
                    default -> throw new IllegalStateException("unknown ownership intrinsic " + intrinsic.name());
                };
            }
            return checkCall(call, scope);
        }
        if (expr instanceof Ast.MemberExpr member) {
            ValueInfo receiverValue = checkExpr(member.receiver(), scope, false);
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field != null) {
                    Ast.TypeRef projected = receiverType(member, scope);
                    return projectionValue(projected, member.receiver(), receiverValue, scope);
                }
                if (hasInstanceMethod(klass, member.member(), new LinkedHashSet<>())) {
                    throw error("bound instance method value '" + member.member()
                            + "' cannot be extracted yet because the receiver lifetime/ownership would become hidden; "
                            + "call the method directly or wrap the owner explicitly in a closure");
                }
            }
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            ValueInfo receiverValue = checkExpr(indexed.receiver(), scope, false);
            checkExpr(indexed.index(), scope, false);
            Ast.TypeRef projected = receiverType(indexed, scope);
            return projectionValue(projected, indexed.receiver(), receiverValue, scope);
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.TypeRef concreteCreated = resolveOwnershipAlias(created.type(), new LinkedHashSet<>());
            Ast.ClassDecl klass = findClass(concreteCreated.name());
            List<Ast.FieldDecl> constructorFields = klass == null
                    ? List.of()
                    : effectiveFields(klass, new LinkedHashSet<>()).stream()
                            .filter(field -> !field.composed())
                            .toList();
            for (int i = 0; i < created.arguments().size(); i++) {
                ValueInfo stored = checkExpr(created.arguments().get(i), scope, true);
                String where = "class constructor argument " + (i + 1);
                rejectStoredBorrow(stored, where);
                if (i < constructorFields.size()) {
                    Ast.TypeRef expected = substituteClassGenerics(
                            constructorFields.get(i).type(), klass, concreteCreated);
                    requireDetachedStoredBorrow(expected, stored, where);
                }
            }
            return new ValueInfo(created.type(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.StructInitExpr created) {
            Ast.TypeRef concreteStruct = created.type() == null
                    ? null
                    : resolveOwnershipAlias(created.type(), new LinkedHashSet<>());
            Ast.ClassDecl struct = concreteStruct == null ? null : findClass(concreteStruct.name());
            for (Ast.ObjectField supplied : created.fields()) {
                ValueInfo stored = checkExpr(supplied.value(), scope, true);
                String where = "struct field '" + supplied.name() + "'";
                rejectStoredBorrow(stored, where);
                if (struct != null) {
                    Ast.FieldDecl field = findField(struct, supplied.name(), new LinkedHashSet<>());
                    if (field != null) {
                        Ast.TypeRef expected = substituteClassGenerics(field.type(), struct, concreteStruct);
                        requireDetachedStoredBorrow(expected, stored, where);
                    }
                }
            }
            return new ValueInfo(created.type() == null ? Ast.TypeRef.inferred() : created.type(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.AwaitExpr awaited) return checkExpr(awaited.expression(), scope, consuming);
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                ValueInfo stored = checkExpr(item, scope, true);
                rejectStoredBorrow(stored, "array/list element");
            }
            return new ValueInfo(Ast.TypeRef.simple("Array"), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            boolean copy = true;
            for (Ast.Expr item : tuple.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                rejectStoredBorrow(info, "tuple element");
                copy &= info.kind == ValueKind.COPY;
            }
            return new ValueInfo(Ast.TypeRef.inferred(), copy ? ValueKind.COPY : ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                ValueInfo stored = checkExpr(field.value(), scope, true);
                rejectStoredBorrow(stored, "object field '" + field.name() + "'");
            }
            return new ValueInfo(Ast.TypeRef.simple("obj"), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.LambdaExpr lambda) return checkLambda(lambda, scope, null);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo checkCall(Ast.CallExpr call, Scope scope) {
        if (call.callee() instanceof Ast.NameExpr some
                && some.name().equals("Some")
                && call.arguments().size() == 1) {
            ValueInfo stored = checkExpr(call.arguments().getFirst(), scope, true);
            rejectStoredBorrow(stored, "Option value");
            return new ValueInfo(Ast.TypeRef.simple("Option"),
                    stored.kind == ValueKind.COPY ? ValueKind.COPY : ValueKind.MOVE_ONLY,
                    null);
        }

        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                List<ValueInfo> argumentValues =
                        checkArguments(call.arguments(), fn.parameters(), scope, "function " + fn.name());
                return callResultValue(fn.returnType(), argumentValues, fn.parameters(),
                        null, null, scope, "function " + fn.name());
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            ValueInfo receiverValue = checkExpr(member.receiver(), scope, false);
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            Ast.MethodDecl method = klass == null ? null : findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (method != null) {
                if (requiresMutableReceiver(method)) {
                    ensureMutableReceiver(member.receiver(), scope, "method '" + method.name() + "' receiver");
                }
                List<ValueInfo> argumentValues =
                        checkArguments(call.arguments(), method.parameters(), scope, "method " + method.name());
                return callResultValue(method.returnType(), argumentValues, method.parameters(),
                        member.receiver(), receiverValue, scope, "method " + method.name());
            }
        }

        checkExpr(call.callee(), scope, false);
        for (Ast.Expr arg : call.arguments()) checkExpr(arg, scope, true);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo callResultValue(
            Ast.TypeRef declaredReturn,
            List<ValueInfo> arguments,
            List<Ast.Param> parameters,
            Ast.Expr receiver,
            ValueInfo receiverValue,
            Scope scope,
            String callable) {
        Ast.TypeRef resolvedReturn = resolveOwnershipAlias(declaredReturn, new LinkedHashSet<>());
        ValueKind resultKind = kindOfType(resolvedReturn);
        if (!resolvedReturn.isBorrow()) {
            return new ValueInfo(declaredReturn, resultKind, null);
        }

        VarState source = null;
        if (receiver != null) {
            if (receiverValue != null && receiverValue.kind == ValueKind.SHARED) {
                source = null;
            } else if (receiverValue != null && receiverValue.borrowSource != null) {
                source = effectiveBorrowOwner(receiverValue.borrowSource);
            } else {
                VarState receiverRoot = rootedOwner(receiver, scope);
                if (receiverRoot == null) {
                    throw error(callable + " returns a borrow but is invoked on a temporary/unrooted receiver; "
                            + "bind the receiver first so its lifetime is explicit");
                }
                source = effectiveBorrowOwner(receiverRoot);
            }
        }

        for (int i = 0; i < Math.min(arguments.size(), parameters.size()); i++) {
            Ast.TypeRef parameterType = resolveOwnershipAlias(parameters.get(i).type(), new LinkedHashSet<>());
            if (!parameterType.isBorrow()) continue;
            ValueInfo argument = arguments.get(i);
            if (argument.kind == ValueKind.SHARED || argument.borrowSource == null) continue;
            VarState candidate = effectiveBorrowOwner(argument.borrowSource);
            if (candidate == null) continue;
            if (source != null && source != candidate) {
                throw error(callable + " returns a borrow whose lifetime could depend on multiple local owners; "
                        + "explicit lifetime relationships are not modeled yet");
            }
            source = candidate;
        }

        return new ValueInfo(declaredReturn, resultKind, source);
    }

    private VarState effectiveBorrowOwner(VarState state) {
        if (state == null || state.kind == ValueKind.SHARED) return null;
        if ((state.kind == ValueKind.IMM_BORROW || state.kind == ValueKind.MUT_BORROW)
                && state.borrowSource != null) {
            return state.borrowSource;
        }
        if ((state.kind == ValueKind.IMM_BORROW || state.kind == ValueKind.MUT_BORROW)
                && state.origin == Origin.PARAM) {
            // External borrow parameter: the current function does not own it.
            return null;
        }
        return state;
    }

    private boolean requiresMutableReceiver(Ast.MethodDecl method) {
        Ast.TypeRef receiver = method.explicitReceiverType();
        return receiver != null && receiver.isBorrow() && receiver.mutableBorrow();
    }

    private List<ValueInfo> checkArguments(
            List<Ast.Expr> arguments,
            List<Ast.Param> params,
            Scope scope,
            String callable) {
        ArrayList<ValueInfo> checked = new ArrayList<>(arguments.size());
        if (arguments.size() != params.size()) return checked; // arity is TypeChecker's responsibility

        for (int i = 0; i < arguments.size(); i++) {
            Ast.Expr arg = arguments.get(i);
            Ast.Param param = params.get(i);

            if (param.structural() && !param.type().isBorrow()) {
                checked.add(checkExpr(arg, scope, false));
                continue;
            }

            if (param.type().isBorrow()) {
                boolean mutable = param.type().mutableBorrow();
                ValueInfo supplied = checkExpr(arg, scope, false);
                if (mutable) {
                    if (supplied.kind != ValueKind.MUT_BORROW) {
                        throw error(callable + " argument " + (i + 1) + " requires an exclusive mutable borrow");
                    }
                } else if (supplied.kind != ValueKind.IMM_BORROW
                        && supplied.kind != ValueKind.MUT_BORROW
                        && supplied.kind != ValueKind.SHARED) {
                    throw error(callable + " argument " + (i + 1)
                            + " requires a borrowed/read-only value; use borrow(...) or share(...)");
                }
                checked.add(supplied);
                continue;
            }

            ValueInfo supplied = checkExpr(arg, scope, true);
            if (supplied.kind == ValueKind.IMM_BORROW || supplied.kind == ValueKind.MUT_BORROW
                    || supplied.kind == ValueKind.SHARED) {
                throw error(callable + " argument " + (i + 1)
                        + " requires an owned value; use copy(...) to create ownership "
                        + "instead of implicitly moving from borrowed/shared storage");
            }
            checked.add(supplied);
        }
        return List.copyOf(checked);
    }

    private void checkAssignmentTarget(Ast.Expr target, Scope scope) {
        if (target instanceof Ast.NameExpr name) {
            VarState state = requireState(scope, name.name());
            requireUsable(state, name.name(), true);
            if (!state.mutable) throw error("cannot assign immutable binding '" + name.name() + "'; use let or a mut parameter");
            if (state.immutableBorrows > 0 || state.mutableBorrowed) throw error("cannot assign '" + name.name() + "' while it is borrowed");
            return;
        }
        if (target instanceof Ast.MemberExpr member) {
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field == null) throw error("unknown field '" + member.member() + "' on " + klass.name());
                if (field.bindingKind() != Ast.BindingKind.LET) {
                    throw error("field '" + klass.name() + "." + member.member() + "' is immutable; declare the field with let to permit mutation");
                }
            }
            ensureMutableReceiver(member.receiver(), scope, "field '" + member.member() + "'");
            return;
        }
        if (target instanceof Ast.IndexExpr indexed) {
            ensureMutableReceiver(indexed.receiver(), scope, "indexed value");
            checkExpr(indexed.index(), scope, false);
            return;
        }
        throw error("unsupported assignment target");
    }

    private void ensureMutableReceiver(Ast.Expr receiver, Scope scope, String what) {
        if (receiver instanceof Ast.UnaryExpr unary && unary.operator().equals("&mut")) {
            VarState owner = borrowOwner(unary.operand(), scope);
            validateBorrow(owner, true);
            return;
        }
        if (receiver instanceof Ast.CallExpr call && isOwnershipIntrinsicCall(call, scope)
                && call.callee() instanceof Ast.NameExpr intrinsic
                && (intrinsic.name().equals("copy") || intrinsic.name().equals("take"))) {
            return;
        }

        VarState state = rootedOwner(receiver, scope);
        if (state == null) {
            // A fresh temporary has no competing owner alias. Named/projection
            // receivers must always resolve through rootedOwner.
            if (receiver instanceof Ast.NewExpr || receiver instanceof Ast.StructInitExpr) return;
            throw error("mutation target must be rooted in a mutable local/parameter or &mut borrow");
        }

        requireUsable(state, state.debugName, true);
        boolean mutableBorrow = state.kind == ValueKind.MUT_BORROW
                || (state.type != null && state.type.isBorrow() && state.type.mutableBorrow());
        if (!state.mutable && !mutableBorrow) {
            throw error("cannot mutate " + what + " through immutable parameter/binding '" + state.debugName
                    + "'; declare the owned parameter/binding as mutable or pass &mut");
        }
        if (state.kind == ValueKind.IMM_BORROW
                || (state.type != null && state.type.isBorrow() && !state.type.mutableBorrow())) {
            throw error("cannot mutate " + what + " through immutable borrow '" + state.debugName + "'");
        }
        if (state.kind != ValueKind.MUT_BORROW && (state.immutableBorrows > 0 || state.mutableBorrowed)) {
            throw error("cannot mutate '" + state.debugName + "' while borrowed");
        }
    }

    private VarState rootedOwner(Ast.Expr expression, Scope scope) {
        if (expression instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state != null) state.debugName = name.name();
            return state;
        }
        if (expression instanceof Ast.MemberExpr member) return rootedOwner(member.receiver(), scope);
        if (expression instanceof Ast.IndexExpr indexed) return rootedOwner(indexed.receiver(), scope);
        if (expression instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
            return rootedOwner(unary.operand(), scope);
        }
        return null;
    }

    private VarState borrowOwner(Ast.Expr operand, Scope scope) {
        if (!(operand instanceof Ast.NameExpr name)) {
            throw error("borrows currently require a named owner; borrow the binding before projecting fields/indexes");
        }
        VarState owner = requireState(scope, name.name());
        owner.debugName = name.name();
        requireUsable(owner, name.name(), false);
        return owner;
    }

    private void validateBorrow(VarState owner, boolean mutable) {
        if (owner.moved) throw error("cannot borrow moved value '" + owner.debugName + "'");
        if (mutable) {
            if (!owner.mutable) throw error("cannot mutably borrow immutable owner '" + owner.debugName + "'");
            if (owner.mutableBorrowed || owner.immutableBorrows > 0) throw error("cannot mutably borrow '" + owner.debugName + "' while another borrow is active");
        } else if (owner.mutableBorrowed) {
            throw error("cannot immutably borrow '" + owner.debugName + "' while a mutable borrow is active");
        }
    }

    private void requireBindingOwnershipCompatibility(
            Ast.TypeRef expected,
            ValueInfo value,
            String where) {
        if (expected == null) return;
        Ast.TypeRef resolved = resolveOwnershipAlias(expected, new LinkedHashSet<>());
        if (resolved.isBorrow()) {
            if (resolved.mutableBorrow()) {
                if (value.kind != ValueKind.MUT_BORROW) {
                    throw error(where + " expects an exclusive mutable borrow");
                }
            } else if (value.kind != ValueKind.IMM_BORROW
                    && value.kind != ValueKind.MUT_BORROW
                    && value.kind != ValueKind.SHARED) {
                throw error(where + " expects a borrowed/read-only value");
            }
            return;
        }

        if (value.kind == ValueKind.IMM_BORROW || value.kind == ValueKind.MUT_BORROW
                || value.kind == ValueKind.SHARED) {
            if (insideClassCopyContract()) {
                throw error("Copy contract for class '" + activeClass.name()
                        + "' must return a fresh owned value; returning self or borrowed/shared source storage is forbidden");
            }
            throw error(where + " expects ownership but received a borrowed/shared view; use copy(...) for a new owner");
        }
    }

    private void requireDetachedStoredBorrow(
            Ast.TypeRef expected,
            ValueInfo value,
            String where) {
        if (expected == null) return;
        Ast.TypeRef resolved = resolveOwnershipAlias(expected, new LinkedHashSet<>());
        if (!resolved.isBorrow()) return;
        if (resolved.mutableBorrow()) {
            throw error(where + " cannot store an exclusive mutable borrow in owning storage; "
                    + "store the owner itself or redesign the lifetime boundary");
        }
        if (value.kind != ValueKind.SHARED) {
            throw error(where + " has borrow-typed owning storage and therefore requires share(...), "
                    + "not an ordinary borrow or unverifiable value");
        }
    }

    private void rejectStoredBorrow(ValueInfo value, String where) {
        if (value.kind == ValueKind.IMM_BORROW || value.kind == ValueKind.MUT_BORROW) {
            if (insideClassCopyContract()) {
                throw error("Copy contract violated by retaining mutable storage from the source in " + where
                        + "; explicitly copy(...) nested mutable state");
            }
            throw error(where + " cannot store an ordinary borrow without explicit lifetime support; "
                    + "use copy(...) for new ownership or share(...) for a detached read-only snapshot");
        }
    }

    private boolean insideClassCopyContract() {
        return activeClass != null
                && activeMethod != null
                && !activeClass.isStruct()
                && !activeMethod.isStatic()
                && activeMethod.name().equals("copy")
                && activeMethod.parameters().isEmpty();
    }

    private boolean borrowMayEscape(VarState source) {
        if (source.origin == Origin.MODULE) return true;
        if (source.origin == Origin.PARAM) {
            return source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW
                    || (source.type != null && source.type.isBorrow());
        }
        return false;
    }

    private void beginPersistentBorrow(VarState owner, boolean mutable) {
        validateBorrow(owner, mutable);
        if (mutable) owner.mutableBorrowed = true;
        else owner.immutableBorrows++;
    }

    private ValueInfo checkLambda(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        CaptureSet captures = collectCaptures(lambda, outer, recursiveBinding);
        Scope closure = new Scope(null);

        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            requireUsable(source, capture.name, capture.write);

            if (source.kind != ValueKind.SHARED
                    && (source.kind == ValueKind.IMM_BORROW
                    || source.kind == ValueKind.MUT_BORROW
                    || (source.type != null && source.type.isBorrow()))) {
                throw error("closure cannot capture borrowed value '" + capture.name
                        + "'; capture its owner by value, use share(...) for a detached read-only snapshot, "
                        + "or pass the borrow as a lambda parameter");
            }

            if (capture.write) {
                if (!source.mutable) throw error("closure cannot mutate immutable capture '" + capture.name + "'");
                if (source.immutableBorrows > 0 || source.mutableBorrowed) throw error("closure cannot capture '" + capture.name + "' mutably while borrowed");
                move(source, capture.name);
                closure.define(capture.name, new VarState(source.type, true, source.kind, Origin.CAPTURE));
            } else if (source.kind == ValueKind.MOVE_ONLY) {
                move(source, capture.name);
                closure.define(capture.name, new VarState(source.type, false, ValueKind.MOVE_ONLY, Origin.CAPTURE));
            } else if (source.kind == ValueKind.SHARED) {
                closure.define(capture.name, new VarState(source.type, false, ValueKind.SHARED, Origin.CAPTURE));
            } else {
                closure.define(capture.name, new VarState(source.type, false, ValueKind.COPY, Origin.CAPTURE));
            }
        }

        for (Ast.Param param : lambda.parameters()) closure.define(param.name(), stateForParam(param));
        for (Ast.Stmt stmt : lambda.blockBody()) checkStatement(stmt, closure, Ast.TypeRef.inferred());
        closure.close();
        return new ValueInfo(Ast.TypeRef.simple("Fnc"), ValueKind.MOVE_ONLY, null);
    }

    private CaptureSet collectCaptures(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        CaptureSet captures = new CaptureSet();
        Set<String> locals = new HashSet<>();
        for (Ast.Param param : lambda.parameters()) locals.add(param.name());
        scanStatements(lambda.blockBody(), locals, outer, recursiveBinding, captures);
        return captures;
    }

    private void scanStatements(List<Ast.Stmt> statements, Set<String> locals, Scope outer, String recursiveBinding, CaptureSet captures) {
        Set<String> blockLocals = new HashSet<>(locals);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                scanExpr(binding.initializer(), blockLocals, outer, recursiveBinding, captures, false);
                blockLocals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                scanExpr(destructure.initializer(), blockLocals, outer, recursiveBinding, captures, false);
                for (Ast.DestructureBinding binding : destructure.bindings()) blockLocals.add(binding.name());
            } else if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) {
                scanExpr(ret.value(), blockLocals, outer, recursiveBinding, captures, false);
            } else if (stmt instanceof Ast.ExprStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
            else if (stmt instanceof Ast.DeferStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
            else if (stmt instanceof Ast.IfStmt s) {
                for (Ast.IfBranch b : s.branches()) {
                    scanExpr(b.condition(), blockLocals, outer, recursiveBinding, captures, false);
                    scanStatements(b.body(), blockLocals, outer, recursiveBinding, captures);
                }
                scanStatements(s.elseBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.TryStmt s) {
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
                Set<String> caught = new HashSet<>(blockLocals);
                caught.add(s.errorName());
                scanStatements(s.catchBody(), caught, outer, recursiveBinding, captures);
                scanStatements(s.finallyBody(), blockLocals, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.ForOfStmt s) {
                scanExpr(s.iterable(), blockLocals, outer, recursiveBinding, captures, false);
                Set<String> loop = new HashSet<>(blockLocals);
                loop.add(s.bindingName());
                scanStatements(s.body(), loop, outer, recursiveBinding, captures);
            } else if (stmt instanceof Ast.ForStmt s) {
                if (s.initializer() instanceof Ast.ExprStmt e) scanExpr(e.expression(), blockLocals, outer, recursiveBinding, captures, false);
                if (s.condition() != null) scanExpr(s.condition(), blockLocals, outer, recursiveBinding, captures, false);
                if (s.update() != null) scanExpr(s.update(), blockLocals, outer, recursiveBinding, captures, false);
                scanStatements(s.body(), blockLocals, outer, recursiveBinding, captures);
            }
        }
    }

    private void scanExpr(Ast.Expr expr, Set<String> locals, Scope outer, String recursiveBinding, CaptureSet captures, boolean write) {
        if (expr instanceof Ast.NameExpr name) {
            if (name.name().equals(recursiveBinding)) return;
            if (!locals.contains(name.name())) {
                VarState state = outer.lookup(name.name());
                if (state != null) captures.add(name.name(), state, write);
            }
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            scanExpr(assignment.target(), locals, outer, recursiveBinding, captures, true);
            scanExpr(assignment.value(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.BinaryExpr e) {
            scanExpr(e.left(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.right(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.UnaryExpr e) scanExpr(e.operand(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ConditionalExpr e) {
            scanExpr(e.condition(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.whenTrue(), locals, outer, recursiveBinding, captures, false);
            scanExpr(e.whenFalse(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.CallExpr e) {
            scanExpr(e.callee(), locals, outer, recursiveBinding, captures, false);
            for (Ast.Expr arg : e.arguments()) scanExpr(arg, locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.MemberExpr e) scanExpr(e.receiver(), locals, outer, recursiveBinding, captures, write);
        else if (expr instanceof Ast.IndexExpr e) {
            scanExpr(e.receiver(), locals, outer, recursiveBinding, captures, write);
            scanExpr(e.index(), locals, outer, recursiveBinding, captures, false);
        } else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) scanExpr(arg, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.StructInitExpr e) for (Ast.ObjectField field : e.fields()) scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.AwaitExpr e) scanExpr(e.expression(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField field : e.fields()) scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.LambdaExpr) {
            // Nested lambda performs its own capture analysis when checked.
        }
    }

    private Ast.ClassDecl classOfReceiver(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = receiverType(receiver, scope);
        if (type == null) return null;
        type = resolveOwnershipAlias(type, new LinkedHashSet<>());
        if (type.isBorrow()) type = resolveOwnershipAlias(type.borrowedTarget(), new LinkedHashSet<>());
        return findClass(type.name());
    }

    private Ast.TypeRef receiverType(Ast.Expr receiver, Scope scope) {
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? null : state.type;
        }
        if (receiver instanceof Ast.NewExpr created) return created.type();
        if (receiver instanceof Ast.StructInitExpr created) return created.type();
        if (receiver instanceof Ast.UnaryExpr unary
                && (unary.operator().equals("&") || unary.operator().equals("&mut"))) {
            return receiverType(unary.operand(), scope);
        }
        if (receiver instanceof Ast.IndexExpr indexed) {
            Ast.TypeRef base = receiverType(indexed.receiver(), scope);
            if (base == null) return null;
            base = resolveOwnershipAlias(base, new LinkedHashSet<>());
            if (base.isBorrow()) base = resolveOwnershipAlias(base.borrowedTarget(), new LinkedHashSet<>());
            if ((base.name().equals("Array") || base.name().equals("List")) && base.arguments().size() == 1) {
                return base.arguments().getFirst();
            }
            return null;
        }
        if (receiver instanceof Ast.MemberExpr member) {
            Ast.TypeRef base = receiverType(member.receiver(), scope);
            if (base == null) return null;
            base = resolveOwnershipAlias(base, new LinkedHashSet<>());
            if (base.isBorrow()) base = resolveOwnershipAlias(base.borrowedTarget(), new LinkedHashSet<>());
            Ast.ClassDecl klass = findClass(base.name());
            if (klass == null) return null;
            Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
            if (field == null) return null;
            return substituteClassGenerics(field.type(), klass, base);
        }
        return null;
    }

    private Ast.TypeRef substituteClassGenerics(Ast.TypeRef type, Ast.ClassDecl klass, Ast.TypeRef receiverType) {
        if (klass.genericParameters().isEmpty() || receiverType.arguments().size() != klass.genericParameters().size()) return type;
        LinkedHashMap<String, Ast.TypeRef> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            substitutions.put(klass.genericParameters().get(i), receiverType.arguments().get(i));
        }
        return substituteOwnershipAliasType(type, substitutions);
    }

    private ValueInfo projectionValue(
            Ast.TypeRef projected,
            Ast.Expr receiver,
            ValueInfo receiverValue,
            Scope scope) {
        if (projected == null) return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        Ast.TypeRef resolved = resolveOwnershipAlias(projected, new LinkedHashSet<>());
        if (resolved.isBorrow()) {
            // Borrow-typed owning storage is admitted only through detached share.
            return new ValueInfo(resolved, ValueKind.SHARED, null);
        }
        if (isCopyType(resolved)) return new ValueInfo(resolved, ValueKind.COPY, null);
        if (receiverValue != null && receiverValue.kind == ValueKind.SHARED) {
            return new ValueInfo(Ast.TypeRef.borrowed(resolved, false), ValueKind.SHARED, null);
        }

        VarState root = rootedOwner(receiver, scope);
        if (root == null) {
            // Projection from a fresh temporary transfers from a value that dies
            // with the expression; no persistent container alias remains.
            return new ValueInfo(resolved, ValueKind.MOVE_ONLY, null);
        }
        VarState source = effectiveBorrowOwner(root);
        return new ValueInfo(Ast.TypeRef.borrowed(resolved, false), ValueKind.IMM_BORROW,
                source == null ? root : source);
    }

    private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<String, Ast.FieldDecl> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.TypeRef resolvedParent = resolveOwnershipAlias(parentRef, new LinkedHashSet<>());
            if (resolvedParent.name().equals("Object") || resolvedParent.name().equals("List")) continue;
            Ast.ClassDecl parent = findClass(resolvedParent.name());
            if (parent == null) continue;
            for (Ast.FieldDecl field : effectiveFields(parent, seen)) fields.putIfAbsent(field.name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), field);
        seen.remove(klass);
        return List.copyOf(fields.values());
    }

    private Ast.FieldDecl findField(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return field;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.TypeRef resolvedParent = resolveOwnershipAlias(parent, new LinkedHashSet<>());
            Ast.ClassDecl p = findClass(resolvedParent.name());
            if (p == null) continue;
            Ast.FieldDecl found = findField(p, name, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private boolean hasInstanceMethod(
            Ast.ClassDecl klass,
            String name,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name)) {
                seen.remove(klass);
                return true;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.TypeRef resolved = resolveOwnershipAlias(parent, new LinkedHashSet<>());
            Ast.ClassDecl parentClass = findClass(resolved.name());
            if (parentClass != null && hasInstanceMethod(parentClass, name, seen)) {
                seen.remove(klass);
                return true;
            }
        }
        seen.remove(klass);
        return false;
    }

    private boolean hasMutableReceiverMethod(
            Ast.ClassDecl klass,
            String name,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic()
                    && method.name().equals(name)
                    && requiresMutableReceiver(method)) {
                seen.remove(klass);
                return true;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl parentClass = findClass(parent.name());
            if (parentClass != null
                    && hasMutableReceiverMethod(parentClass, name, seen)) {
                seen.remove(klass);
                return true;
            }
        }
        seen.remove(klass);
        return false;
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            Ast.MethodDecl found = findMethod(p, name, arity, seen);
            if (found != null) {
                seen.remove(klass);
                return found;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) return null;
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (!name.contains(".")) {
            for (Map<String, Ast.ClassDecl> scope : localClassScopes) {
                Ast.ClassDecl local = scope.get(name);
                if (local != null) return local;
            }
        }
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (!name.contains(".")) {
            for (Map<String, Ast.TypeAliasDecl> scope : localAliasScopes) {
                Ast.TypeAliasDecl local = scope.get(name);
                if (local != null) return local;
            }
        }
        if (ambiguousTypeAliases.contains(name)) return null;
        return typeAliases.get(name);
    }

    private Ast.TypeRef resolveOwnershipAlias(Ast.TypeRef type, Set<Ast.TypeAliasDecl> seen) {
        if (type == null) return null;
        if (type.isBorrow()) {
            return Ast.TypeRef.borrowed(
                    resolveOwnershipAlias(type.borrowedTarget(), seen),
                    type.mutableBorrow());
        }

        Ast.TypeAliasDecl alias = findTypeAlias(type.name());
        if (alias == null) return type;
        if (!seen.add(alias)) throw error("type alias cycle involving '" + alias.name() + "'");
        if (type.arguments().size() != alias.genericParameters().size()) {
            throw error("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                    + " type argument(s), got " + type.arguments().size());
        }

        LinkedHashMap<String, Ast.TypeRef> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < alias.genericParameters().size(); i++) {
            substitutions.put(alias.genericParameters().get(i), type.arguments().get(i));
        }
        Ast.TypeRef target = substituteOwnershipAliasType(alias.target(), substitutions);
        return resolveOwnershipAlias(target, seen);
    }

    private Ast.TypeRef substituteOwnershipAliasType(
            Ast.TypeRef type,
            Map<String, Ast.TypeRef> substitutions) {
        if (type.isBorrow()) {
            return Ast.TypeRef.borrowed(
                    substituteOwnershipAliasType(type.borrowedTarget(), substitutions),
                    type.mutableBorrow());
        }
        Ast.TypeRef replacement = substitutions.get(type.name());
        if (replacement != null && type.arguments().isEmpty() && !type.inferArguments()) return replacement;
        if (type.arguments().isEmpty()) return type;
        return new Ast.TypeRef(
                type.name(),
                type.arguments().stream()
                        .map(argument -> substituteOwnershipAliasType(argument, substitutions))
                        .toList(),
                type.inferArguments());
    }

    private void requireUsable(VarState state, String name, boolean write) {
        if (state.moved) throw error("use of moved value '" + name + "'");
        if (write) {
            if (state.mutableBorrowed && state.kind != ValueKind.MUT_BORROW) throw error("cannot mutate '" + name + "' while mutably borrowed");
            if (state.immutableBorrows > 0) throw error("cannot mutate '" + name + "' while immutably borrowed");
        } else if (state.mutableBorrowed && state.kind != ValueKind.MUT_BORROW) {
            throw error("cannot read '" + name + "' while it is mutably borrowed");
        }
    }

    private void move(VarState state, String name) {
        requireUsable(state, name, false);
        if (state.origin == Origin.MODULE) {
            throw error("cannot move module-owned value '" + name
                    + "' by ordinary value flow because the runtime module slot would retain an alias; "
                    + "use copy(...) or share(...). Explicit module-slot extraction will require a dedicated runtime take operation");
        }
        if (state.immutableBorrows > 0 || state.mutableBorrowed) throw error("cannot move '" + name + "' while it is borrowed");
        state.moved = true;
    }

    private VarState requireState(Scope scope, String name) {
        VarState state = scope.lookup(name);
        if (state == null) throw error("unknown owned binding '" + name + "'");
        state.debugName = name;
        return state;
    }

    private Map<VarState, StateSnapshot> stateSnapshot(Scope scope) {
        Map<VarState, StateSnapshot> result = new IdentityHashMap<>();
        for (VarState state : scope.visibleStates()) {
            result.put(state, new StateSnapshot(state.moved, state.immutableBorrows, state.mutableBorrowed));
        }
        return result;
    }

    private void restoreState(Map<VarState, StateSnapshot> snapshot) {
        for (Map.Entry<VarState, StateSnapshot> entry : snapshot.entrySet()) {
            VarState state = entry.getKey();
            StateSnapshot saved = entry.getValue();
            state.moved = saved.moved();
            state.immutableBorrows = saved.immutableBorrows();
            state.mutableBorrowed = saved.mutableBorrowed();
        }
    }

    private void mergeBranchState(Map<VarState, StateSnapshot> base, List<Map<VarState, StateSnapshot>> exits) {
        restoreState(base);
        for (VarState state : base.keySet()) {
            boolean movedOnAnyPath = base.get(state).moved();
            for (Map<VarState, StateSnapshot> exit : exits) {
                StateSnapshot saved = exit.get(state);
                if (saved != null) movedOnAnyPath |= saved.moved();
            }
            state.moved = movedOnAnyPath;
        }
    }

    private Map<VarState,Boolean> movedSnapshot(Scope scope) {
        Map<VarState,Boolean> result = new IdentityHashMap<>();
        for (VarState state : scope.visibleStates()) result.put(state, state.moved);
        return result;
    }

    private void rejectLoopMoves(Map<VarState,Boolean> before, Scope after) {
        for (Map.Entry<VarState,Boolean> entry : before.entrySet()) {
            if (!entry.getValue() && entry.getKey().moved && entry.getKey().kind != ValueKind.COPY) {
                throw error("cannot move outer value '" + entry.getKey().debugName + "' from a repeating loop; borrow it or move it before entering the loop");
            }
        }
    }

    private static boolean isOwnershipIntrinsicName(String name) {
        return name.equals("borrow") || name.equals("copy") || name.equals("take") || name.equals("share");
    }

    private boolean isOwnershipIntrinsicCall(Ast.Expr expression, String expected, Scope scope) {
        return expression instanceof Ast.CallExpr call
                && call.arguments().size() == 1
                && call.callee() instanceof Ast.NameExpr callee
                && callee.name().equals(expected)
                && isOwnershipIntrinsicCall(call, scope);
    }

    private boolean isOwnershipIntrinsicCall(Ast.CallExpr call, Scope scope) {
        if (!(call.callee() instanceof Ast.NameExpr callee) || !isOwnershipIntrinsicName(callee.name())) return false;
        if (scope.lookup(callee.name()) != null) return false;
        if (importedNames.contains(callee.name())) return false;
        if (ambiguousFunctions.contains(callee.name()) || functions.containsKey(callee.name())) return false;
        return true;
    }

    private ValueKind kindOfType(Ast.TypeRef type) {
        if (type == null) return ValueKind.MOVE_ONLY;
        Ast.TypeRef resolved = resolveOwnershipAlias(type, new LinkedHashSet<>());
        if (resolved.isBorrow()) return resolved.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
        return isCopyType(resolved) ? ValueKind.COPY : ValueKind.MOVE_ONLY;
    }

    private boolean isCopyType(Ast.TypeRef type) {
        return isCopyType(type, new LinkedHashSet<>());
    }

    private boolean isCopyType(Ast.TypeRef type, Set<Ast.ClassDecl> visiting) {
        if (type == null || type.isBorrow() || type.inferArguments()) return false;
        Ast.TypeRef resolved = resolveOwnershipAlias(type, new LinkedHashSet<>());
        if (resolved == null || resolved.isBorrow() || resolved.inferArguments()) return false;

        switch (resolved.name()) {
            case "i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint",
                    "f32","f64","float","decimal","complex64","complex128","complex",
                    "bool","Bool","string","String","void" -> {
                return true;
            }
            case "Option" -> {
                return resolved.arguments().size() == 1
                        && isCopyType(resolved.arguments().getFirst(), visiting);
            }
            default -> { }
        }

        Ast.ClassDecl aggregate = findClass(resolved.name());
        if (aggregate == null || !aggregate.isStruct()) return false;
        if (aggregate.genericParameters().size() != resolved.arguments().size()) return false;
        if (!visiting.add(aggregate)) return false;

        LinkedHashMap<String, Ast.TypeRef> substitutions = new LinkedHashMap<>();
        for (int i = 0; i < aggregate.genericParameters().size(); i++) {
            substitutions.put(aggregate.genericParameters().get(i), resolved.arguments().get(i));
        }

        try {
            for (Ast.FieldDecl field : aggregate.fields()) {
                Ast.TypeRef fieldType = substituteOwnershipAliasType(field.type(), substitutions);
                if (!isCopyType(fieldType, visiting)) return false;
            }
            return true;
        } finally {
            visiting.remove(aggregate);
        }
    }

    private Ast.TypeRef inferLiteralType(Object value) {
        if (value instanceof Boolean) return Ast.TypeRef.simple("bool");
        if (value instanceof Long) return Ast.TypeRef.simple("int");
        if (value instanceof Double) return Ast.TypeRef.simple("float");
        if (value instanceof String) return Ast.TypeRef.simple("String");
        if (value instanceof Ast.Imaginary) return Ast.TypeRef.simple("complex");
        return Ast.TypeRef.inferred();
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException("Oreslang ownership error: " + message);
    }

    private enum ValueKind { COPY, MOVE_ONLY, IMM_BORROW, MUT_BORROW, SHARED }
    private enum Origin { PARAM, LOCAL, CAPTURE, MODULE }

    private static final class ValueInfo {
        private final Ast.TypeRef type;
        private final ValueKind kind;
        private final VarState borrowSource;
        private final boolean borrowRegistered;
        private ValueInfo(Ast.TypeRef type, ValueKind kind, VarState borrowSource) {
            this(type, kind, borrowSource, false);
        }
        private ValueInfo(Ast.TypeRef type, ValueKind kind, VarState borrowSource, boolean borrowRegistered) {
            this.type = type;
            this.kind = kind;
            this.borrowSource = borrowSource;
            this.borrowRegistered = borrowRegistered;
        }
    }

    private static final class VarState {
        private Ast.TypeRef type;
        private final boolean mutable;
        private ValueKind kind;
        private final Origin origin;
        private boolean moved;
        private int immutableBorrows;
        private boolean mutableBorrowed;
        private VarState borrowSource;
        private boolean borrowRegistered;
        private String debugName = "<value>";

        private VarState(Ast.TypeRef type, boolean mutable, ValueKind kind, Origin origin) {
            this.type = type;
            this.mutable = mutable;
            this.kind = kind;
            this.origin = origin;
        }
    }

    private static final class Scope {
        private final Scope parent;
        private final Map<String,VarState> locals = new LinkedHashMap<>();
        private boolean closed;

        private Scope(Scope parent) { this.parent = parent; }

        private void define(String name, VarState state) {
            if (locals.putIfAbsent(name, state) != null) throw new IllegalArgumentException("Oreslang ownership error: duplicate binding '" + name + "'");
            state.debugName = name;
        }

        private VarState lookup(String name) {
            VarState local = locals.get(name);
            return local != null ? local : parent == null ? null : parent.lookup(name);
        }

        private List<VarState> visibleStates() {
            ArrayList<VarState> result = new ArrayList<>();
            if (parent != null) result.addAll(parent.visibleStates());
            result.addAll(locals.values());
            return result;
        }

        private void close() {
            if (closed) return;
            closed = true;
            for (VarState state : locals.values()) {
                if (state.borrowRegistered && state.borrowSource != null) {
                    if (state.kind == ValueKind.MUT_BORROW) state.borrowSource.mutableBorrowed = false;
                    else if (state.kind == ValueKind.IMM_BORROW) state.borrowSource.immutableBorrows--;
                }
            }
        }
    }

    private record StateSnapshot(boolean moved, int immutableBorrows, boolean mutableBorrowed) { }

    private record Capture(String name, VarState source, boolean write) { }

    private static final class CaptureSet {
        private final Map<String,Capture> values = new LinkedHashMap<>();
        private void add(String name, VarState state, boolean write) {
            Capture existing = values.get(name);
            values.put(name, existing == null ? new Capture(name, state, write)
                    : new Capture(name, state, existing.write() || write));
        }
    }
}
