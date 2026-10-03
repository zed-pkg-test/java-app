package dev.oreslang.types;

import dev.oreslang.ast.Ast;

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
 * - class/list/object/function values are move-only by default;
 * - ordinary parameters read-borrow for the duration of a call;
 * - mut Type name parameters are exclusive temporary mutable borrows;
 * - contextual take Type name parameters transfer ownership;
 * - borrow(x) creates a stored immutable view; take(x) explicitly moves;
 * - no pointer-style '&' or '*' ownership syntax is exposed;
 * - escaping closures own non-Copy captures and mutable captures;
 * - closures may not capture a borrow (use take when ownership must escape);
 * - moving an outer value from a repeating loop is rejected conservatively.
 */
public final class OwnershipChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> importedFunctions = new HashSet<>();
    private final Set<String> importedClasses = new HashSet<>();
    private final Set<String> importedNamespaces = new HashSet<>();

    private OwnershipChecker(Ast.Program program) {
        indexImports(program);
        index(program);
    }

    private void indexImports(Ast.Program program) {
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.wildcard()) {
                if (imported.namespace() != null) importedNamespaces.add(imported.namespace());
                continue;
            }
            switch (imported.kind()) {
                case FUNCTION -> importedFunctions.addAll(imported.names());
                case CLASS -> importedClasses.addAll(imported.names());
                case MODULE, ALL -> importedNamespaces.addAll(imported.names());
            }
        }
    }

    public static Ast.Program check(Ast.Program program) {
        OwnershipChecker checker = new OwnershipChecker(program);
        checker.validate(program);
        return program;
    }

    private void index(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            modules.put(module.name(), module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) index(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface);
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
            checkPersistentFieldInitializers(module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module, fn);
                else if (decl instanceof Ast.InitDecl init) checkInit(module, init);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module, klass);
            }
        }
    }

    private void checkPersistentFieldInitializers(Ast.ModuleDecl module) {
        // Module fields and class default fields are storage boundaries too.
        // They must not bypass move/borrow provenance before a callable begins.
        Scope moduleScope = new Scope(null);
        seedModuleState(module, moduleScope);

        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FieldDecl field && field.initializer() != null) {
                ValueInfo value = checkExpr(field.initializer(), moduleScope, true);
                rejectBorrowStorage(value, "persistent module field '" + field.name() + "'");
            } else if (decl instanceof Ast.ClassDecl klass) {
                for (Ast.FieldDecl field : klass.fields()) {
                    if (field.initializer() == null) continue;
                    ValueInfo value = checkExpr(field.initializer(), moduleScope, true);
                    rejectBorrowStorage(
                            value,
                            "default field '" + klass.name() + "." + field.name() + "'");
                }
            }
        }

        moduleScope.close();
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
            scope.define(field.name(), new VarState(
                    type,
                    field.bindingKind() == Ast.BindingKind.LET,
                    kindOfType(type),
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
        for (Ast.MethodDecl method : klass.methods()) {
            Scope moduleScope = new Scope(null);
            seedModuleState(module, moduleScope);
            Scope scope = new Scope(moduleScope);
            if (!method.isStatic()) {
                Ast.TypeRef explicit = method.explicitReceiverType();
                ValueKind receiverKind;
                if (explicit == null) receiverKind = ValueKind.IMM_BORROW;
                else if (explicit.isBorrow()) {
                    receiverKind = explicit.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
                } else {
                    receiverKind = ValueKind.MOVE_ONLY; // take self
                }
                Ast.TypeRef selfType = Ast.TypeRef.simple(klass.name());
                scope.define(
                        "self",
                        new VarState(selfType, false, receiverKind, Origin.PARAM));
            }
            for (Ast.Param param : method.parameters()) scope.define(param.name(), stateForParam(param));
            checkBlock(method.body(), scope, method.returnType());
            scope.close();
            moduleScope.close();
        }
    }

    private VarState stateForParam(Ast.Param param) {
        ValueKind kind = switch (param.mode()) {
            case BORROW -> isCopyType(param.type()) ? ValueKind.COPY : ValueKind.IMM_BORROW;
            case MUT -> ValueKind.MUT_BORROW;
            case TAKE -> kindOfType(param.type());
        };
        if (param.structural()) kind = ValueKind.IMM_BORROW;
        // Copy parameters are copied at the boundary. Non-Copy BORROW
        // parameters are immutable aliases; MUT is an exclusive alias.
        return new VarState(param.type(), false, kind, Origin.PARAM);
    }

    private void checkBlock(List<Ast.Stmt> body, Scope parent, Ast.TypeRef returnType) {
        Scope scope = new Scope(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, scope, returnType);
        scope.close();
    }

    private void checkStatement(Ast.Stmt stmt, Scope scope, Ast.TypeRef returnType) {
        if (stmt instanceof Ast.BindingStmt binding) {
            checkBinding(binding, scope);
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            ValueInfo source = checkExpr(destructure.initializer(), scope, true);
            List<Ast.TypeRef> elementTypes = destructuredElementTypes(
                    source.type,
                    destructure.bindings().size());

            for (int i = 0; i < destructure.bindings().size(); i++) {
                Ast.DestructureBinding binding = destructure.bindings().get(i);
                Ast.TypeRef elementType = elementTypes.get(i);

                ValueKind elementKind;
                VarState elementBorrowSource = null;

                if (isCopyType(elementType)) {
                    elementKind = ValueKind.COPY;
                } else if (source.kind == ValueKind.IMM_BORROW
                        || source.kind == ValueKind.MUT_BORROW) {
                    // Destructuring never upgrades a borrowed aggregate into
                    // owned elements. Non-Copy projections remain read borrows.
                    elementKind = ValueKind.IMM_BORROW;
                    elementBorrowSource = source.borrowSource;
                } else if (source.kind == ValueKind.COPY) {
                    elementKind = ValueKind.COPY;
                } else {
                    // The aggregate itself was consumed, so its non-Copy
                    // elements may transfer into the new bindings.
                    elementKind = ValueKind.MOVE_ONLY;
                }

                VarState state = new VarState(
                        elementType,
                        binding.kind() == Ast.BindingKind.LET && elementKind == ValueKind.MOVE_ONLY,
                        elementKind,
                        Origin.LOCAL);
                state.borrowSource = elementBorrowSource;
                if (elementBorrowSource != null && elementKind == ValueKind.IMM_BORROW) {
                    beginPersistentBorrow(elementBorrowSource, false);
                }
                scope.define(binding.name(), state);
            }
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() != null) {
                ValueInfo returned = checkExpr(ret.value(), scope, true);
                if (returned.kind == ValueKind.IMM_BORROW || returned.kind == ValueKind.MUT_BORROW) {
                    throw error(
                            "cannot return a borrowed value without explicit return provenance; "
                                    + "return ownership with take or wait for the 'T from owner' return contract");
                }
            }
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) {
            checkExpr(expression.expression(), scope, false);
            return;
        }
        if (stmt instanceof Ast.DeferStmt defer) {
            // defer executes at lexical block exit, not at registration time.
            // Validate it now, then retain every non-Copy capture as a scoped
            // borrow so later statements cannot move/mutate data the deferred
            // expression will still access.
            checkExpr(defer.expression(), scope, false);
            reserveDeferredCaptures(defer.expression(), scope);
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
        if (stmt instanceof Ast.MatchStmt matched) {
            checkOptionMatch(matched, scope, returnType);
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
            boolean consumingIterable = isIntrinsicCall(loop.iterable(), "take");
            ValueInfo iterable = checkExpr(loop.iterable(), scope, consumingIterable);
            Ast.TypeRef elementType = iterableElementType(loop.iterable(), scope);
            if (elementType == null) elementType = Ast.TypeRef.inferred();

            ValueKind elementKind = isCopyType(elementType)
                    ? ValueKind.COPY
                    : consumingIterable ? ValueKind.MOVE_ONLY : ValueKind.IMM_BORROW;

            VarState iterableOwner = null;
            if (!consumingIterable) {
                if (loop.iterable() instanceof Ast.NameExpr name) {
                    iterableOwner = scope.lookup(name.name());
                }
                if (iterableOwner == null) iterableOwner = iterable.borrowSource;
                if (iterableOwner == null) iterableOwner = projectionOwner(loop.iterable(), scope);
            }

            Map<VarState,Boolean> before = movedSnapshot(scope);
            Scope loopScope = new Scope(scope);
            VarState elementState = new VarState(
                    elementType,
                    loop.bindingKind() == Ast.BindingKind.LET
                            && elementKind != ValueKind.IMM_BORROW
                            && elementKind != ValueKind.MUT_BORROW,
                    elementKind,
                    Origin.LOCAL);
            if (elementKind == ValueKind.IMM_BORROW && iterableOwner != null) {
                beginPersistentBorrow(iterableOwner, false);
                elementState.borrowSource = iterableOwner;
            }
            loopScope.define(loop.bindingName(), elementState);
            checkBlock(loop.body(), loopScope, returnType);
            loopScope.close();
            rejectLoopMoves(before, scope);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Scope loopScope = new Scope(scope);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopScope, returnType);

            // Initializer executes once. Condition, body, and update form the
            // repeating region, so snapshot *before* the condition and include
            // initializer-local bindings as well as outer bindings. Any move
            // from that persistent state could be repeated on a later
            // iteration and is rejected conservatively.
            Map<VarState,Boolean> beforeRepeatedRegion = movedSnapshot(loopScope);
            if (loop.condition() != null) checkExpr(loop.condition(), loopScope, false);
            checkBlock(loop.body(), loopScope, returnType);
            if (loop.update() != null) checkExpr(loop.update(), loopScope, false);
            rejectLoopMoves(beforeRepeatedRegion, loopScope);
            loopScope.close();
        }
    }

    private List<Ast.TypeRef> destructuredElementTypes(Ast.TypeRef sourceType, int arity) {
        Ast.TypeRef type = sourceType;
        while (type != null && type.isBorrow()) type = type.borrowedTarget();

        if (type != null && type.name().equals("Tuple") && type.arguments().size() == arity) {
            return type.arguments();
        }

        if (type != null
                && (type.name().equals("Array") || type.name().equals("List"))
                && type.arguments().size() == 1) {
            List<Ast.TypeRef> result = new ArrayList<>(arity);
            for (int i = 0; i < arity; i++) result.add(type.arguments().getFirst());
            return result;
        }

        List<Ast.TypeRef> unknown = new ArrayList<>(arity);
        for (int i = 0; i < arity; i++) unknown.add(Ast.TypeRef.inferred());
        return unknown;
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

        if ((value.kind == ValueKind.IMM_BORROW || value.kind == ValueKind.MUT_BORROW)
                && value.borrowSource != null) {
            beginPersistentBorrow(value.borrowSource, value.kind == ValueKind.MUT_BORROW);
        }

        if (recursiveLambda) {
            placeholder.type = binding.declaredType() == null ? value.type : binding.declaredType();
            placeholder.kind = value.kind;
            placeholder.borrowSource = value.borrowSource;
            return;
        }

        VarState state = new VarState(
                binding.declaredType() == null ? value.type : binding.declaredType(),
                binding.kind() == Ast.BindingKind.LET,
                value.kind,
                Origin.LOCAL);
        state.borrowSource = value.borrowSource;
        scope.define(binding.name(), state);
    }

    private ValueInfo checkExpr(Ast.Expr expr, Scope scope, boolean consuming) {
        if (expr instanceof Ast.LiteralExpr literal) {
            return new ValueInfo(inferLiteralType(literal.value()), ValueKind.COPY, null);
        }
        if (expr instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            if (state == null) {
                if (importedFunctions.contains(name.name())) {
                    throw error("imported function '" + name.name()
                            + "' cannot be extracted until the cross-unit linker supplies its ownership contract");
                }
                return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.COPY, null); // local function/module/global
            }
            state.debugName = name.name();
            requireUsable(state, name.name(), false);
            if (consuming && state.kind == ValueKind.MOVE_ONLY) move(state, name.name());
            if (consuming && state.kind == ValueKind.MUT_BORROW) move(state, name.name());
            return new ValueInfo(state.type, state.kind, state.borrowSource);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return checkExpr(unary.operand(), scope, false);
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            checkAssignmentTarget(assignment.target(), scope);
            ValueInfo assigned = checkExpr(assignment.value(), scope, true);
            rejectBorrowStorage(assigned, "assignment");
            return assigned;
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

            boolean leftBorrow = left.kind == ValueKind.IMM_BORROW || left.kind == ValueKind.MUT_BORROW;
            boolean rightBorrow = right.kind == ValueKind.IMM_BORROW || right.kind == ValueKind.MUT_BORROW;
            if (leftBorrow || rightBorrow) {
                if (leftBorrow && rightBorrow
                        && left.kind == right.kind
                        && left.borrowSource != null
                        && left.borrowSource == right.borrowSource) {
                    return new ValueInfo(left.type, left.kind, left.borrowSource);
                }
                throw error("conditional expression has ambiguous borrowed ownership provenance; "
                        + "bind/copy ownership before the conditional");
            }

            return new ValueInfo(left.type, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.CallExpr call) {
            return checkCall(call, scope);
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace
                    && scope.lookup(namespace.name()) == null
                    && modules.containsKey(namespace.name())) {
                Ast.FieldDecl moduleField = findModuleField(modules.get(namespace.name()), member.member());
                if (moduleField != null) {
                    Ast.TypeRef fieldType = moduleField.type() == null
                            ? inferFieldType(moduleField.initializer())
                            : moduleField.type();
                    if (isCopyType(fieldType) || isSingletonProxyField(modules.get(namespace.name()), moduleField)) {
                        // A public class-valued singleton field is not the raw
                        // process-owned object. TypeChecker/runtime expose an
                        // immutable actor-backed proxy capability.
                        return new ValueInfo(fieldType, ValueKind.COPY, null);
                    }
                    throw error("cannot directly extract non-Copy module state '"
                            + namespace.name() + "." + member.member()
                            + "'; use a borrowing accessor or explicit ownership-transfer API");
                }
            }

            ValueInfo receiver = checkExpr(member.receiver(), scope, false);
            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field != null) {
                    Ast.TypeRef fieldType = field.type() == null ? Ast.TypeRef.inferred() : field.type();
                    if (isCopyType(fieldType)) {
                        return new ValueInfo(fieldType, ValueKind.COPY, null);
                    }
                    VarState owner = receiver.borrowSource != null
                            ? receiver.borrowSource
                            : projectionOwner(member.receiver(), scope);
                    if (owner == null && consuming && receiver.kind == ValueKind.MOVE_ONLY) {
                        // Moving a field out of an otherwise unreachable temporary is safe.
                        return new ValueInfo(fieldType, ValueKind.MOVE_ONLY, null);
                    }
                    return new ValueInfo(Ast.TypeRef.borrowed(fieldType, false), ValueKind.IMM_BORROW, owner);
                }
                if (hasMutableReceiverMethod(klass, member.member(), new LinkedHashSet<>())) {
                    throw error("mutable-receiver method value '" + member.member()
                            + "' cannot be extracted; call it directly through a mutable owner"
                            + " until persistent exclusive bound-method lifetimes are modeled");
                }
            } else {
                Ast.InterfaceDecl iface = interfaceOfReceiver(member.receiver(), scope);
                Ast.InterfaceFieldDecl field = iface == null
                        ? null
                        : findInterfaceField(iface, member.member(), new LinkedHashSet<>());
                if (field != null) {
                    Ast.TypeRef fieldType = field.type();
                    if (isCopyType(fieldType)) return new ValueInfo(fieldType, ValueKind.COPY, null);
                    VarState owner = receiver.borrowSource != null
                            ? receiver.borrowSource
                            : projectionOwner(member.receiver(), scope);
                    if (owner == null && consuming && receiver.kind == ValueKind.MOVE_ONLY) {
                        return new ValueInfo(fieldType, ValueKind.MOVE_ONLY, null);
                    }
                    return new ValueInfo(Ast.TypeRef.borrowed(fieldType, false), ValueKind.IMM_BORROW, owner);
                }
            }

            Ast.TypeRef structuralField = objectFieldType(receiver.type, member.member());
            if (structuralField != null) {
                if (isCopyType(structuralField)) {
                    return new ValueInfo(structuralField, ValueKind.COPY, null);
                }
                VarState owner = receiver.borrowSource != null
                        ? receiver.borrowSource
                        : projectionOwner(member.receiver(), scope);
                if (owner == null && consuming && receiver.kind == ValueKind.MOVE_ONLY) {
                    return new ValueInfo(structuralField, ValueKind.MOVE_ONLY, null);
                }
                return new ValueInfo(
                        Ast.TypeRef.borrowed(structuralField, false),
                        ValueKind.IMM_BORROW,
                        owner);
            }

            // Any unresolved projection rooted in a live owned binding is
            // conservatively a read borrow. This keeps imported/dynamic shapes
            // from manufacturing a second owner.
            VarState rootOwner = projectionOwner(member.receiver(), scope);
            if (rootOwner != null && rootOwner.kind != ValueKind.COPY) {
                return new ValueInfo(
                        Ast.TypeRef.borrowed(Ast.TypeRef.inferred(), false),
                        ValueKind.IMM_BORROW,
                        rootOwner);
            }
            return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            ValueInfo receiver = checkExpr(indexed.receiver(), scope, false);
            checkExpr(indexed.index(), scope, false);
            Ast.TypeRef elementType = indexedElementType(indexed, scope);
            if (elementType != null && isCopyType(elementType)) {
                return new ValueInfo(elementType, ValueKind.COPY, null);
            }
            Ast.TypeRef effectiveType = elementType == null ? Ast.TypeRef.inferred() : elementType;
            VarState owner = receiver.borrowSource != null
                    ? receiver.borrowSource
                    : projectionOwner(indexed.receiver(), scope);
            if (owner == null && consuming && receiver.kind == ValueKind.MOVE_ONLY) {
                return new ValueInfo(effectiveType, ValueKind.MOVE_ONLY, null);
            }
            return new ValueInfo(Ast.TypeRef.borrowed(effectiveType, false), ValueKind.IMM_BORROW, owner);
        }
        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) {
                ValueInfo value = checkExpr(arg, scope, true);
                rejectBorrowStorage(value, "constructor field");
            }
            return new ValueInfo(created.type(), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.AwaitExpr awaited) return checkExpr(awaited.expression(), scope, consuming);
        if (expr instanceof Ast.ListExpr list) {
            Ast.TypeRef elementType = null;
            boolean uniform = true;
            for (Ast.Expr item : list.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                rejectBorrowStorage(info, "list/array element");
                if (elementType == null) elementType = info.type;
                else uniform &= elementType.equals(info.type);
            }
            Ast.TypeRef listType = elementType != null && uniform
                    ? new Ast.TypeRef("Array", List.of(elementType), false)
                    : Ast.TypeRef.simple("Array");
            return new ValueInfo(listType, ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            boolean copy = true;
            List<Ast.TypeRef> elementTypes = new ArrayList<>();
            for (Ast.Expr item : tuple.elements()) {
                ValueInfo info = checkExpr(item, scope, true);
                rejectBorrowStorage(info, "tuple element");
                copy &= info.kind == ValueKind.COPY;
                elementTypes.add(info.type);
            }
            return new ValueInfo(
                    new Ast.TypeRef("Tuple", elementTypes, false),
                    copy ? ValueKind.COPY : ValueKind.MOVE_ONLY,
                    null);
        }
        if (expr instanceof Ast.ObjectExpr object) {
            List<Ast.TypeRef> fields = new ArrayList<>();
            for (Ast.ObjectField field : object.fields()) {
                ValueInfo value = checkExpr(field.value(), scope, true);
                rejectBorrowStorage(value, "object field '" + field.name() + "'");
                fields.add(new Ast.TypeRef(
                        "$objfield$" + field.name(),
                        List.of(value.type),
                        false));
            }
            return new ValueInfo(new Ast.TypeRef("$obj$", fields, false), ValueKind.MOVE_ONLY, null);
        }
        if (expr instanceof Ast.LambdaExpr lambda) return checkLambda(lambda, scope, null);
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private ValueInfo checkCall(Ast.CallExpr call, Scope scope) {
        if (call.callee() instanceof Ast.NameExpr imported
                && scope.lookup(imported.name()) == null
                && importedFunctions.contains(imported.name())) {
            throw error("imported function '" + imported.name()
                    + "' cannot be called until the cross-unit linker supplies borrow/mut/take and return-ownership metadata");
        }

        if (call.callee() instanceof Ast.NameExpr intrinsic) {
            if (intrinsic.name().equals("Some")) {
                requireIntrinsicArity(call, "Some", 1);
                ValueInfo payload = checkExpr(call.arguments().getFirst(), scope, true);
                if (payload.kind == ValueKind.IMM_BORROW || payload.kind == ValueKind.MUT_BORROW) {
                    throw error("Some(...) cannot store a borrow until container borrow provenance is modeled; "
                            + "store an owned value or match the borrow directly");
                }
                Ast.TypeRef optionType = new Ast.TypeRef("Option", List.of(payload.type), false);
                return new ValueInfo(
                        optionType,
                        payload.kind == ValueKind.COPY ? ValueKind.COPY : ValueKind.MOVE_ONLY,
                        null);
            }
            if (intrinsic.name().equals("borrow")) {
                VarState owner = intrinsicOwner(call, scope, "borrow");
                validateBorrow(owner, false);
                return new ValueInfo(Ast.TypeRef.borrowed(owner.type, false), ValueKind.IMM_BORROW, owner);
            }
            if (intrinsic.name().equals("take")) {
                requireIntrinsicArity(call, "take", 1);
                ValueInfo taken = checkExpr(call.arguments().getFirst(), scope, true);
                if (taken.kind == ValueKind.IMM_BORROW || taken.kind == ValueKind.MUT_BORROW) {
                    throw error("take(...) cannot take ownership from a borrowed value");
                }
                return taken;
            }
            if (intrinsic.name().equals("copy")) {
                requireIntrinsicArity(call, "copy", 1);
                ValueInfo source = checkExpr(call.arguments().getFirst(), scope, false);
                if (source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW) {
                    throw error("copy(...) requires an owned value, not a borrow");
                }
                if (source.kind != ValueKind.COPY) {
                    throw error("copy(...) requires a type proven Copy; classes are not implicitly copyable and must opt into the class-copy contract");
                }
                return new ValueInfo(source.type, ValueKind.COPY, null);
            }
            if (intrinsic.name().equals("share")) {
                throw error("share(...) is reserved for explicit shared capabilities; ordinary ownership cannot be converted to shared mutable state implicitly");
            }

            Ast.FunctionDecl fn = findFunction(intrinsic.name());
            if (fn != null) {
                checkArguments(call.arguments(), fn.parameters(), scope, "function " + fn.name());
                return new ValueInfo(fn.returnType(), kindOfType(fn.returnType()), null);
            }
        }

        if (call.callee() instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace
                    && scope.lookup(namespace.name()) == null
                    && importedNamespaces.contains(namespace.name())) {
                throw error("imported namespace call '" + namespace.name() + "." + member.member()
                        + "(...)' requires linked ownership metadata");
            }

            Ast.TypeRef receiverTypeForImport = ownershipTypeOfExpr(member.receiver(), scope);
            if (receiverTypeForImport != null) {
                Ast.TypeRef importBase = receiverTypeForImport;
                while (importBase.isBorrow()) importBase = importBase.borrowedTarget();
                if (importedClasses.contains(importBase.name())) {
                    throw error("method call on imported class '" + importBase.name()
                            + "' requires linked receiver/parameter ownership metadata");
                }
            }

            if (member.receiver() instanceof Ast.NameExpr namespace && scope.lookup(namespace.name()) == null) {
                if (importedClasses.contains(namespace.name())) {
                    throw error("static call on imported class '" + namespace.name()
                            + "' requires linked ownership metadata");
                }
                Ast.FunctionDecl qualified = findFunction(namespace.name() + "." + member.member());
                if (qualified != null) {
                    checkArguments(call.arguments(), qualified.parameters(), scope,
                            "function " + namespace.name() + "." + qualified.name());
                    return new ValueInfo(qualified.returnType(), kindOfType(qualified.returnType()), null);
                }

                Ast.ClassDecl staticClass = findClass(namespace.name());
                Ast.MethodDecl staticMethod = staticClass == null
                        ? null
                        : findStaticMethod(staticClass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                if (staticMethod != null) {
                    checkArguments(call.arguments(), staticMethod.parameters(), scope,
                            "static function " + staticClass.name() + "." + staticMethod.name());
                    return new ValueInfo(staticMethod.returnType(), kindOfType(staticMethod.returnType()), null);
                }
            }

            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            Ast.MethodDecl method = klass == null ? null : findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (method != null) {
                if (requiresTakeReceiver(method)) {
                    ValueInfo receiver = checkExpr(member.receiver(), scope, true);
                    if (receiver.kind == ValueKind.IMM_BORROW || receiver.kind == ValueKind.MUT_BORROW) {
                        throw error("method '" + method.name() + "' takes self ownership but receiver is borrowed");
                    }
                    checkArguments(call.arguments(), method.parameters(), scope, "method " + method.name());
                    return new ValueInfo(method.returnType(), kindOfType(method.returnType()), null);
                }

                boolean mutableReceiver = requiresMutableReceiver(method);
                ValueInfo receiver = checkExpr(member.receiver(), scope, false);
                VarState receiverOwner = receiver.borrowSource != null
                        ? receiver.borrowSource
                        : projectionOwner(member.receiver(), scope);

                if (mutableReceiver) {
                    if (receiverOwner != null) {
                        ensureMutableReceiver(member.receiver(), scope, "method '" + method.name() + "' receiver");
                    } else if (receiver.kind != ValueKind.MOVE_ONLY) {
                        throw error("mutable method '" + method.name() + "' requires a unique owned temporary or mutable owner");
                    }
                }

                TemporaryBorrow receiverBorrow = null;
                if (receiverOwner != null && receiverOwner.kind != ValueKind.COPY) {
                    beginTemporaryBorrow(
                            receiverOwner,
                            mutableReceiver,
                            "method '" + method.name() + "' receiver",
                            0,
                            receiverOwner.debugName);
                    receiverBorrow = new TemporaryBorrow(receiverOwner, mutableReceiver);
                }

                try {
                    checkArguments(call.arguments(), method.parameters(), scope, "method " + method.name());
                } finally {
                    if (receiverBorrow != null) endTemporaryBorrow(receiverBorrow);
                }
                return new ValueInfo(method.returnType(), kindOfType(method.returnType()), null);
            }

            Ast.InterfaceDecl iface = interfaceOfReceiver(member.receiver(), scope);
            Ast.InterfaceFunctionDecl interfaceMethod = iface == null
                    ? null
                    : findInterfaceMethod(iface, member.member(), call.arguments().size(), new LinkedHashSet<>());
            if (interfaceMethod != null) {
                ValueInfo receiver = checkExpr(member.receiver(), scope, false);
                VarState receiverOwner = receiver.borrowSource != null
                        ? receiver.borrowSource
                        : projectionOwner(member.receiver(), scope);
                TemporaryBorrow receiverBorrow = null;
                if (receiverOwner != null && receiverOwner.kind != ValueKind.COPY) {
                    beginTemporaryBorrow(
                            receiverOwner,
                            false,
                            "interface method '" + interfaceMethod.name() + "' receiver",
                            0,
                            receiverOwner.debugName);
                    receiverBorrow = new TemporaryBorrow(receiverOwner, false);
                }
                try {
                    checkArguments(
                            call.arguments(),
                            interfaceMethod.parameters(),
                            scope,
                            "interface method " + interfaceMethod.name());
                } finally {
                    if (receiverBorrow != null) endTemporaryBorrow(receiverBorrow);
                }
                return new ValueInfo(
                        interfaceMethod.returnType(),
                        kindOfType(interfaceMethod.returnType()),
                        null);
            }

            checkExpr(member.receiver(), scope, false);
        } else {
            checkExpr(call.callee(), scope, false);
        }

        // Ownership-sensitive functions cannot currently be extracted as Fnc,
        // so an indirect function value can only have ordinary read-borrow
        // parameters. Never silently move non-Copy arguments here.
        List<Ast.Param> borrowed = new ArrayList<>(call.arguments().size());
        for (int i = 0; i < call.arguments().size(); i++) {
            borrowed.add(new Ast.Param(Ast.TypeRef.inferred(), "$arg" + i));
        }
        checkArguments(call.arguments(), borrowed, scope, "first-class function");
        return new ValueInfo(Ast.TypeRef.inferred(), ValueKind.MOVE_ONLY, null);
    }

    private boolean requiresMutableReceiver(Ast.MethodDecl method) {
        Ast.TypeRef receiver = method.explicitReceiverType();
        return receiver != null && receiver.isBorrow() && receiver.mutableBorrow();
    }

    private boolean requiresTakeReceiver(Ast.MethodDecl method) {
        Ast.TypeRef receiver = method.explicitReceiverType();
        return receiver != null && !receiver.isBorrow();
    }

    private void checkArguments(List<Ast.Expr> arguments, List<Ast.Param> params, Scope scope, String callable) {
        if (arguments.size() != params.size()) return; // arity is TypeChecker's responsibility
        List<TemporaryBorrow> temporaryBorrows = new ArrayList<>();
        try {
            for (int i = 0; i < arguments.size(); i++) {
                Ast.Expr arg = arguments.get(i);
                Ast.Param param = params.get(i);
                Ast.ParamMode mode = param.structural() ? Ast.ParamMode.BORROW : param.mode();

                if (mode == Ast.ParamMode.TAKE) {
                    ValueInfo taken = checkExpr(arg, scope, true);
                    if (isBorrowed(taken)) {
                        throw error(callable + " argument " + (i + 1)
                                + " requires ownership but the supplied value is borrowed");
                    }
                    continue;
                }

                boolean mutable = mode == Ast.ParamMode.MUT;
                if (mutable) {
                    if (!(arg instanceof Ast.NameExpr name)) {
                        throw error(callable + " argument " + (i + 1)
                                + " for a mut parameter must be a named mutable owner");
                    }
                    VarState state = scope.lookup(name.name());
                    if (state == null) {
                        throw error(callable + " argument " + (i + 1)
                                + " for a mut parameter must be a named mutable owner");
                    }
                    requireUsable(state, name.name(), true);
                    beginTemporaryBorrow(state, true, callable, i + 1, name.name());
                    temporaryBorrows.add(new TemporaryBorrow(state, true));
                    continue;
                }

                ValueInfo read = checkExpr(arg, scope, false);
                VarState owner = null;
                String ownerName = "<projection>";

                if (arg instanceof Ast.NameExpr name) {
                    VarState state = scope.lookup(name.name());
                    if (state == null) continue; // global/builtin Copy-like value
                    requireUsable(state, name.name(), false);
                    if (state.kind == ValueKind.COPY) continue;
                    owner = state;
                    ownerName = name.name();
                } else if (read.borrowSource != null) {
                    owner = read.borrowSource;
                    ownerName = owner.debugName;
                } else {
                    VarState projected = projectionOwner(arg, scope);
                    if (projected != null && projected.kind != ValueKind.COPY) {
                        owner = projected;
                        ownerName = projected.debugName;
                    }
                }

                if (owner != null) {
                    beginTemporaryBorrow(owner, false, callable, i + 1, ownerName);
                    temporaryBorrows.add(new TemporaryBorrow(owner, false));
                }
            }
        } finally {
            for (int i = temporaryBorrows.size() - 1; i >= 0; i--) {
                endTemporaryBorrow(temporaryBorrows.get(i));
            }
        }
    }

    private void beginTemporaryBorrow(
            VarState state,
            boolean mutable,
            String callable,
            int position,
            String name) {
        if (state.kind == ValueKind.IMM_BORROW) {
            if (mutable) {
                throw error(callable + " argument " + position
                        + " requires exclusive mutable access but '" + name + "' is a read borrow");
            }
            if (state.mutableBorrowed) {
                throw error("cannot read-borrow '" + name + "' while an exclusive reborrow is active");
            }
            incrementImmutableBorrow(state);
            return;
        }

        if (state.kind == ValueKind.MUT_BORROW) {
            if (mutable) {
                if (state.mutableBorrowed || state.immutableBorrows > 0) {
                    throw error("cannot exclusively reborrow '" + name + "' while another reborrow is active");
                }
                state.mutableBorrowed = true;
            } else {
                if (state.mutableBorrowed) {
                    throw error("cannot read-borrow '" + name + "' while an exclusive reborrow is active");
                }
                incrementImmutableBorrow(state);
            }
            return;
        }

        beginPersistentBorrow(state, mutable);
    }

    private void endTemporaryBorrow(TemporaryBorrow temporary) {
        VarState owner = temporary.owner();
        if (temporary.mutable()) {
            if (!owner.mutableBorrowed) {
                throw new IllegalStateException("Oreslang ownership checker invariant: releasing inactive mutable borrow of '"
                        + owner.debugName + "'");
            }
            owner.mutableBorrowed = false;
        } else {
            if (owner.immutableBorrows <= 0) {
                throw new IllegalStateException("Oreslang ownership checker invariant: immutable borrow underflow for '"
                        + owner.debugName + "'");
            }
            owner.immutableBorrows--;
        }
    }

    private void checkOptionMatch(Ast.MatchStmt matched, Scope scope, Ast.TypeRef returnType) {
        boolean explicitTake = isIntrinsicCall(matched.value(), "take");
        boolean explicitCopy = isIntrinsicCall(matched.value(), "copy");
        boolean explicitBorrow = isIntrinsicCall(matched.value(), "borrow");

        ValueInfo source = checkExpr(matched.value(), scope, explicitTake);
        Ast.TypeRef optionType = source.type.isBorrow() ? source.type.borrowedTarget() : source.type;
        Ast.TypeRef payloadType = optionType.name().equals("Option") && optionType.arguments().size() == 1
                ? optionType.arguments().getFirst()
                : Ast.TypeRef.inferred();

        VarState reservedOwner = null;
        if (!explicitTake && !explicitCopy) {
            if (explicitBorrow) {
                reservedOwner = intrinsicOwner((Ast.CallExpr) matched.value(), scope, "borrow");
            } else if (matched.value() instanceof Ast.NameExpr name) {
                VarState candidate = requireState(scope, name.name());
                if (candidate.kind != ValueKind.COPY) reservedOwner = candidate;
            }
            if (reservedOwner != null) beginPersistentBorrow(reservedOwner, false);
        }

        Map<VarState, StateSnapshot> base = stateSnapshot(scope);
        List<Map<VarState, StateSnapshot>> exits = new ArrayList<>();
        boolean seenSome = false;
        boolean seenNone = false;
        try {
            for (Ast.MatchArm arm : matched.arms()) {
                restoreState(base);
                Scope armScope = new Scope(scope);
                if (arm.pattern() instanceof Ast.SomePattern some) {
                    if (seenSome) throw error("duplicate Some arm in Option match");
                    seenSome = true;
                    ValueKind payloadKind = isCopyType(payloadType)
                            ? ValueKind.COPY
                            : explicitTake ? ValueKind.MOVE_ONLY : ValueKind.IMM_BORROW;
                    armScope.define(
                            some.bindingName(),
                            new VarState(payloadType, false, payloadKind, Origin.LOCAL));
                } else if (arm.pattern() instanceof Ast.NonePattern) {
                    if (seenNone) throw error("duplicate None arm in Option match");
                    seenNone = true;
                } else {
                    throw error("unsupported Option match pattern " + arm.pattern());
                }
                checkBlock(arm.body(), armScope, returnType);
                exits.add(stateSnapshot(scope));
                armScope.close();
            }
            if (!seenSome || !seenNone || matched.arms().size() != 2) {
                throw error("Option match must be exhaustive with exactly one Some(...) arm and one None arm");
            }
            mergeBranchState(base, exits);
        } finally {
            if (reservedOwner != null) endTemporaryBorrow(new TemporaryBorrow(reservedOwner, false));
        }
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
        if (receiver instanceof Ast.NameExpr name) {
            VarState state = requireState(scope, name.name());
            requireUsable(state, name.name(), true);
            boolean mutableBorrow = state.kind == ValueKind.MUT_BORROW;
            if (!state.mutable && !mutableBorrow) {
                throw error("cannot mutate " + what + " through immutable binding/read borrow '"
                        + name.name() + "'; use a let owner or a mut parameter");
            }
            if (state.kind == ValueKind.IMM_BORROW) {
                throw error("cannot mutate " + what + " through read borrow '" + name.name() + "'");
            }
            if (state.immutableBorrows > 0 || state.mutableBorrowed) {
                throw error("cannot mutate '" + name.name() + "' while borrowed");
            }
            return;
        }
        if (receiver instanceof Ast.MemberExpr member) {
            ensureMutableReceiver(member.receiver(), scope, what);
            return;
        }
        if (receiver instanceof Ast.IndexExpr indexed) {
            ensureMutableReceiver(indexed.receiver(), scope, what);
            return;
        }
        if (receiver instanceof Ast.NewExpr) return;
        throw error("mutation target must be rooted in a let owner, mut parameter, or unique temporary");
    }

    private boolean isIntrinsicCall(Ast.Expr expression, String name) {
        return expression instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.NameExpr callee
                && callee.name().equals(name);
    }

    private void requireIntrinsicArity(Ast.CallExpr call, String name, int expected) {
        if (call.arguments().size() != expected) {
            throw error(name + "(...) expects exactly " + expected + " argument(s)");
        }
    }

    private VarState intrinsicOwner(Ast.CallExpr call, Scope scope, String name) {
        requireIntrinsicArity(call, name, 1);
        Ast.Expr argument = call.arguments().getFirst();
        if (!(argument instanceof Ast.NameExpr owner)) {
            throw error(name + "(...) currently requires a named owner");
        }
        return borrowOwner(owner, scope);
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

    private void beginPersistentBorrow(VarState owner, boolean mutable) {
        validateBorrow(owner, mutable);
        if (mutable) owner.mutableBorrowed = true;
        else incrementImmutableBorrow(owner);
    }

    private void incrementImmutableBorrow(VarState owner) {
        if (owner.immutableBorrows == Integer.MAX_VALUE) {
            throw error("immutable borrow count overflow for '" + owner.debugName + "'");
        }
        owner.immutableBorrows++;
    }

    private boolean isBorrowed(ValueInfo value) {
        return value.kind == ValueKind.IMM_BORROW || value.kind == ValueKind.MUT_BORROW;
    }

    private void rejectBorrowStorage(ValueInfo value, String where) {
        if (isBorrowed(value)) {
            throw error("cannot store a borrowed value in " + where
                    + " until aggregate/slot borrow provenance is represented explicitly");
        }
    }

    private void reserveDeferredCaptures(Ast.Expr expression, Scope scope) {
        CaptureSet captures = new CaptureSet();
        scanExpr(expression, Set.of(), scope, null, captures, false);
        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            if (source.kind == ValueKind.COPY) continue;
            requireUsable(source, capture.name, capture.write);
            beginTemporaryBorrow(
                    source,
                    capture.write,
                    "defer",
                    0,
                    capture.name);
            scope.holdBorrow(new TemporaryBorrow(source, capture.write));
        }
    }

    private Ast.TypeRef iterableElementType(Ast.Expr iterable, Scope scope) {
        Ast.TypeRef type = ownershipTypeOfExpr(iterable, scope);
        while (type != null && type.isBorrow()) type = type.borrowedTarget();
        if (type == null) return null;
        if ((type.name().equals("Array") || type.name().equals("List"))
                && type.arguments().size() == 1) {
            return type.arguments().getFirst();
        }
        if (type.name().equals("Tuple") && !type.arguments().isEmpty()) {
            Ast.TypeRef first = type.arguments().getFirst();
            boolean uniform = type.arguments().stream().allMatch(first::equals);
            return uniform ? first : null;
        }
        return null;
    }

    private ValueInfo checkLambda(Ast.LambdaExpr lambda, Scope outer, String recursiveBinding) {
        CaptureSet captures = collectCaptures(lambda, outer, recursiveBinding);
        Scope closure = new Scope(null);

        for (Capture capture : captures.values.values()) {
            VarState source = capture.source;
            source.debugName = capture.name;
            requireUsable(source, capture.name, capture.write);

            if (source.kind == ValueKind.IMM_BORROW || source.kind == ValueKind.MUT_BORROW || source.type.isBorrow()) {
                throw error("closure cannot capture borrowed value '" + capture.name + "'; capture its owner by value or pass the borrow as a lambda parameter");
            }

            if (capture.write) {
                if (!source.mutable) throw error("closure cannot mutate immutable capture '" + capture.name + "'");
                if (source.immutableBorrows > 0 || source.mutableBorrowed) throw error("closure cannot capture '" + capture.name + "' mutably while borrowed");
                move(source, capture.name);
                closure.define(capture.name, new VarState(source.type, true, source.kind, Origin.CAPTURE));
            } else if (source.kind == ValueKind.MOVE_ONLY) {
                move(source, capture.name);
                closure.define(capture.name, new VarState(source.type, false, ValueKind.MOVE_ONLY, Origin.CAPTURE));
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
            } else if (stmt instanceof Ast.MatchStmt s) {
                scanExpr(s.value(), blockLocals, outer, recursiveBinding, captures, false);
                for (Ast.MatchArm arm : s.arms()) {
                    Set<String> armLocals = new HashSet<>(blockLocals);
                    if (arm.pattern() instanceof Ast.SomePattern some) armLocals.add(some.bindingName());
                    scanStatements(arm.body(), armLocals, outer, recursiveBinding, captures);
                }
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
        else if (expr instanceof Ast.AwaitExpr e) scanExpr(e.expression(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) scanExpr(item, locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField field : e.fields()) scanExpr(field.value(), locals, outer, recursiveBinding, captures, false);
        else if (expr instanceof Ast.LambdaExpr lambda) {
            // A nested closure's free variables are also transitive captures of
            // this closure. Otherwise an outer escaping closure could leave a
            // grandchild closure holding a reference to a dead/moved owner.
            Set<String> nestedLocals = new HashSet<>(locals);
            for (Ast.Param param : lambda.parameters()) nestedLocals.add(param.name());
            if (lambda.expressionBody() != null) {
                scanExpr(lambda.expressionBody(), nestedLocals, outer, recursiveBinding, captures, false);
            }
            if (lambda.blockBody() != null) {
                scanStatements(lambda.blockBody(), nestedLocals, outer, recursiveBinding, captures);
            }
        }
    }

    private Ast.ClassDecl classOfReceiver(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = ownershipTypeOfExpr(receiver, scope);
        if (type == null) return null;
        while (type.isBorrow()) type = type.borrowedTarget();
        return findClass(type.name());
    }

    private VarState projectionOwner(Ast.Expr expression, Scope scope) {
        if (expression instanceof Ast.NameExpr name) return scope.lookup(name.name());
        if (expression instanceof Ast.MemberExpr member) return projectionOwner(member.receiver(), scope);
        if (expression instanceof Ast.IndexExpr indexed) return projectionOwner(indexed.receiver(), scope);
        return null;
    }

    private Ast.TypeRef indexedElementType(Ast.IndexExpr indexed, Scope scope) {
        Ast.TypeRef collection = ownershipTypeOfExpr(indexed.receiver(), scope);
        if (collection == null) return null;
        while (collection.isBorrow()) collection = collection.borrowedTarget();

        if ((collection.name().equals("Array") || collection.name().equals("List"))
                && collection.arguments().size() == 1) {
            return collection.arguments().getFirst();
        }

        if (collection.name().equals("Tuple") && !collection.arguments().isEmpty()) {
            if (indexed.index() instanceof Ast.LiteralExpr literal && literal.value() instanceof Long value) {
                int position = Math.toIntExact(value);
                if (position >= 0 && position < collection.arguments().size()) {
                    return collection.arguments().get(position);
                }
            }
            Ast.TypeRef first = collection.arguments().getFirst();
            boolean uniform = collection.arguments().stream().allMatch(first::equals);
            if (uniform) return first;
            boolean allCopy = collection.arguments().stream().allMatch(this::isCopyType);
            if (allCopy) return first; // exact type is TypeChecker's job; ownership only needs Copy-ness.
        }
        return null;
    }

    private Ast.TypeRef ownershipTypeOfExpr(Ast.Expr expression, Scope scope) {
        if (expression instanceof Ast.ObjectExpr object) {
            List<Ast.TypeRef> fields = new ArrayList<>();
            for (Ast.ObjectField field : object.fields()) {
                Ast.TypeRef valueType = ownershipTypeOfExpr(field.value(), scope);
                fields.add(new Ast.TypeRef(
                        "$objfield$" + field.name(),
                        List.of(valueType == null ? Ast.TypeRef.inferred() : valueType),
                        false));
            }
            return new Ast.TypeRef("$obj$", fields, false);
        }
        if (expression instanceof Ast.NameExpr name) {
            VarState state = scope.lookup(name.name());
            return state == null ? null : state.type;
        }
        if (expression instanceof Ast.NewExpr created) return created.type();
        if (expression instanceof Ast.AwaitExpr awaited) return ownershipTypeOfExpr(awaited.expression(), scope);
        if (expression instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace
                    && scope.lookup(namespace.name()) == null
                    && modules.containsKey(namespace.name())) {
                Ast.FieldDecl moduleField = findModuleField(modules.get(namespace.name()), member.member());
                if (moduleField != null) {
                    return moduleField.type() == null
                            ? inferFieldType(moduleField.initializer())
                            : moduleField.type();
                }
            }

            Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
            if (klass != null) {
                Ast.FieldDecl field = findField(klass, member.member(), new LinkedHashSet<>());
                if (field != null) return field.type();

                Ast.MethodDecl method = findMethod(klass, member.member(), 0, new LinkedHashSet<>());
                if (method != null) return normalizeSelfReturn(method.returnType(), klass);
            }

            Ast.InterfaceDecl iface = interfaceOfReceiver(member.receiver(), scope);
            if (iface != null) {
                Ast.InterfaceFieldDecl field = findInterfaceField(iface, member.member(), new LinkedHashSet<>());
                if (field != null) return field.type();
                Ast.InterfaceFunctionDecl method = findInterfaceMethod(iface, member.member(), 0, new LinkedHashSet<>());
                if (method != null) return method.returnType();
            }

            Ast.TypeRef receiverType = ownershipTypeOfExpr(member.receiver(), scope);
            return objectFieldType(receiverType, member.member());
        }
        if (expression instanceof Ast.IndexExpr indexed) return indexedElementType(indexed, scope);
        if (expression instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name) {
                if ((name.name().equals("borrow") || name.name().equals("take") || name.name().equals("copy"))
                        && call.arguments().size() == 1) {
                    Ast.TypeRef target = ownershipTypeOfExpr(call.arguments().getFirst(), scope);
                    if (target == null) return null;
                    return name.name().equals("borrow") ? Ast.TypeRef.borrowed(target, false) : target;
                }
                Ast.FunctionDecl fn = findFunction(name.name());
                return fn == null ? null : fn.returnType();
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                if (member.receiver() instanceof Ast.NameExpr namespace && scope.lookup(namespace.name()) == null) {
                    Ast.FunctionDecl qualified = findFunction(namespace.name() + "." + member.member());
                    if (qualified != null) return qualified.returnType();
                    Ast.ClassDecl staticClass = findClass(namespace.name());
                    Ast.MethodDecl staticMethod = staticClass == null
                            ? null
                            : findStaticMethod(staticClass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (staticMethod != null) return normalizeSelfReturn(staticMethod.returnType(), staticClass);
                }
                Ast.ClassDecl klass = classOfReceiver(member.receiver(), scope);
                Ast.MethodDecl method = klass == null
                        ? null
                        : findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                if (method != null) return normalizeSelfReturn(method.returnType(), klass);

                Ast.InterfaceDecl iface = interfaceOfReceiver(member.receiver(), scope);
                Ast.InterfaceFunctionDecl interfaceMethod = iface == null
                        ? null
                        : findInterfaceMethod(iface, member.member(), call.arguments().size(), new LinkedHashSet<>());
                if (interfaceMethod != null) return interfaceMethod.returnType();
            }
        }
        if (expression instanceof Ast.ConditionalExpr conditional) {
            Ast.TypeRef left = ownershipTypeOfExpr(conditional.whenTrue(), scope);
            Ast.TypeRef right = ownershipTypeOfExpr(conditional.whenFalse(), scope);
            return left != null && left.equals(right) ? left : null;
        }
        return null;
    }

    private Ast.TypeRef normalizeSelfReturn(Ast.TypeRef returnType, Ast.ClassDecl klass) {
        return returnType != null && returnType.name().equals("self")
                ? Ast.TypeRef.simple(klass.name())
                : returnType;
    }

    private Ast.TypeRef objectFieldType(Ast.TypeRef objectType, String fieldName) {
        if (objectType == null) return null;
        while (objectType.isBorrow()) objectType = objectType.borrowedTarget();
        if (!objectType.name().equals("$obj$")) return null;
        String expected = "$objfield$" + fieldName;
        for (Ast.TypeRef field : objectType.arguments()) {
            if (field.name().equals(expected) && field.arguments().size() == 1) {
                return field.arguments().getFirst();
            }
        }
        return null;
    }

    private boolean isSingletonProxyField(Ast.ModuleDecl module, Ast.FieldDecl field) {
        if (module == null || !module.singleton()) return false;
        if (field.visibility() != Ast.Visibility.PUBLIC) return false;
        if (field.bindingKind() == Ast.BindingKind.LET) return false;
        if (field.type() == null || field.type().isBorrow() || !field.type().arguments().isEmpty()) return false;
        return findClass(field.type().name()) != null;
    }

    private Ast.FieldDecl findModuleField(Ast.ModuleDecl module, String name) {
        for (Ast.Decl declaration : module.declarations()) {
            if (declaration instanceof Ast.FieldDecl field && field.name().equals(name)) return field;
        }
        return null;
    }

    private Ast.InterfaceDecl interfaceOfReceiver(Ast.Expr receiver, Scope scope) {
        Ast.TypeRef type = ownershipTypeOfExpr(receiver, scope);
        if (type == null) return null;
        while (type.isBorrow()) type = type.borrowedTarget();
        return findInterface(type.name());
    }

    private Ast.InterfaceFunctionDecl findInterfaceMethod(
            Ast.InterfaceDecl iface,
            String name,
            int arity,
            Set<Ast.InterfaceDecl> seen) {
        if (!seen.add(iface)) return null;
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn
                    && fn.name().equals(name)
                    && fn.parameters().size() == arity) {
                seen.remove(iface);
                return fn;
            }
        }
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) continue;
            Ast.InterfaceFunctionDecl found = findInterfaceMethod(parent, name, arity, seen);
            if (found != null) {
                seen.remove(iface);
                return found;
            }
        }
        seen.remove(iface);
        return null;
    }

    private Ast.InterfaceFieldDecl findInterfaceField(
            Ast.InterfaceDecl iface,
            String name,
            Set<Ast.InterfaceDecl> seen) {
        if (!seen.add(iface)) return null;
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFieldDecl field && field.name().equals(name)) {
                seen.remove(iface);
                return field;
            }
        }
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) continue;
            Ast.InterfaceFieldDecl found = findInterfaceField(parent, name, seen);
            if (found != null) {
                seen.remove(iface);
                return found;
            }
        }
        seen.remove(iface);
        return null;
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
            Ast.ClassDecl p = findClass(parent.name());
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

    private Ast.MethodDecl findStaticMethod(
            Ast.ClassDecl klass,
            String name,
            int arity,
            Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parent : klass.parents()) {
            Ast.ClassDecl p = findClass(parent.name());
            if (p == null) continue;
            Ast.MethodDecl found = findStaticMethod(p, name, arity, seen);
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
        if (ambiguousClasses.contains(name)) return null;
        return classes.get(name);
    }

    private Ast.InterfaceDecl findInterface(String name) {
        if (ambiguousInterfaces.contains(name)) return null;
        return interfaces.get(name);
    }

    private void requireUsable(VarState state, String name, boolean write) {
        if (state.moved) throw error("use of moved value '" + name + "'");
        if (write) {
            if (state.mutableBorrowed) throw error("cannot mutate '" + name + "' while mutably borrowed/reborrowed");
            if (state.immutableBorrows > 0) throw error("cannot mutate '" + name + "' while immutably borrowed");
        } else if (state.mutableBorrowed) {
            throw error("cannot read '" + name + "' while it is mutably borrowed/reborrowed");
        }
    }

    private void move(VarState state, String name) {
        requireUsable(state, name, false);
        if (state.origin == Origin.MODULE) {
            throw error("cannot move persistent module-owned state '" + name
                    + "'; borrow it or expose an explicit ownership-transfer service");
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

    private ValueKind kindOfType(Ast.TypeRef type) {
        if (type == null) return ValueKind.MOVE_ONLY;
        if (type.isBorrow()) return type.mutableBorrow() ? ValueKind.MUT_BORROW : ValueKind.IMM_BORROW;
        return isCopyType(type) ? ValueKind.COPY : ValueKind.MOVE_ONLY;
    }

    private boolean isCopyType(Ast.TypeRef type) {
        if (type == null || type.isBorrow()) return false;
        if (type.isStringLiteral()) return true;
        if (type.name().equals("Option") && type.arguments().size() == 1) {
            Ast.TypeRef inner = type.arguments().getFirst();
            return inner.name().equals("null") || isCopyType(inner);
        }
        return switch (type.name()) {
            case "i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint",
                    "f32","f64","float","decimal","complex64","complex128","complex",
                    "bool","Bool","string","String","void" -> true;
            default -> false;
        };
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

    private enum ValueKind { COPY, MOVE_ONLY, IMM_BORROW, MUT_BORROW }
    private enum Origin { PARAM, LOCAL, CAPTURE, MODULE }

    private static final class ValueInfo {
        private final Ast.TypeRef type;
        private final ValueKind kind;
        private final VarState borrowSource;
        private ValueInfo(Ast.TypeRef type, ValueKind kind, VarState borrowSource) {
            this.type = type;
            this.kind = kind;
            this.borrowSource = borrowSource;
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
        private final List<TemporaryBorrow> heldBorrows = new ArrayList<>();
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

        private void holdBorrow(TemporaryBorrow borrow) {
            heldBorrows.add(borrow);
        }

        private void close() {
            if (closed) return;
            closed = true;

            // Deferred-expression captures are live until block exit, so release
            // those reborrows before retiring local borrow bindings themselves.
            for (int i = heldBorrows.size() - 1; i >= 0; i--) {
                TemporaryBorrow held = heldBorrows.get(i);
                VarState owner = held.owner();
                if (held.mutable()) {
                    if (!owner.mutableBorrowed) {
                        throw new IllegalStateException("Oreslang ownership checker invariant: releasing inactive scoped mutable borrow of '"
                                + owner.debugName + "'");
                    }
                    owner.mutableBorrowed = false;
                } else {
                    if (owner.immutableBorrows <= 0) {
                        throw new IllegalStateException("Oreslang ownership checker invariant: scoped immutable borrow underflow for '"
                                + owner.debugName + "'");
                    }
                    owner.immutableBorrows--;
                }
            }

            for (VarState state : locals.values()) {
                if (state.borrowSource != null) {
                    if (state.kind == ValueKind.MUT_BORROW) {
                        if (!state.borrowSource.mutableBorrowed) {
                            throw new IllegalStateException("Oreslang ownership checker invariant: releasing inactive mutable borrow source for '"
                                    + state.debugName + "'");
                        }
                        state.borrowSource.mutableBorrowed = false;
                    } else if (state.kind == ValueKind.IMM_BORROW) {
                        if (state.borrowSource.immutableBorrows <= 0) {
                            throw new IllegalStateException("Oreslang ownership checker invariant: immutable borrow source underflow for '"
                                    + state.debugName + "'");
                        }
                        state.borrowSource.immutableBorrows--;
                    }
                }
            }
        }
    }

    private record TemporaryBorrow(VarState owner, boolean mutable) { }

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
