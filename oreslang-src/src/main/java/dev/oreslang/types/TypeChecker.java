package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
import dev.oreslang.types.Types.SingletonProxy;
import dev.oreslang.types.Types.StringLiteral;
import dev.oreslang.types.Types.Tuple;
import dev.oreslang.types.Types.Type;
import dev.oreslang.types.Types.Unknown;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Static semantic pass run before Oreslang code is lowered/executed. */
public final class TypeChecker {
    private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
    private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
    private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
    private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
    private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();

    private final IdentityHashMap<Ast.FunctionDecl, String> functionOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, String> classOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.InterfaceDecl, String> interfaceOwners = new IdentityHashMap<>();
    private final IdentityHashMap<Ast.ClassDecl, Record> classShapeCache = new IdentityHashMap<>();

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    private final Set<String> importedNames = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Set<Ast.ClassDecl> processEffectCheckedClasses =
            java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    public static Ast.Program check(Ast.Program program) {
        TypeChecker checker = new TypeChecker();
        checker.validateImports(program);
        checker.collect(program);
        checker.validateRoutineRecursion();
        checker.validate(program);
        OwnershipChecker.check(program);
        return program;
    }

    private void validateImports(Ast.Program program) {
        Set<String> exposed = new HashSet<>();
        for (Ast.ImportDecl imported : program.imports()) {
            if (imported.path() == null || imported.path().isBlank()) throw new IllegalArgumentException("import path cannot be empty");
            if (imported.wildcard()) {
                if (imported.namespace() == null || imported.namespace().isBlank()) throw new IllegalArgumentException("wildcard imports require a namespace alias");
                if (!exposed.add(imported.namespace())) throw new IllegalArgumentException("duplicate imported name '" + imported.namespace() + "'");
                importedNames.add(imported.namespace());
                importedValues.add(imported.namespace());
            } else {
                if (imported.names().isEmpty()) throw new IllegalArgumentException("named import must select at least one name");
                for (String name : imported.names()) {
                    if (!exposed.add(name)) throw new IllegalArgumentException("duplicate imported name '" + name + "'");
                    importedNames.add(name);
                    if (imported.kind() != Ast.ImportKind.CLASS) importedValues.add(name);
                }
            }
        }
    }

    private void collect(Ast.Program program) {
        for (Ast.ModuleDecl module : program.modules()) {
            if (modules.putIfAbsent(module.name(), module) != null) throw new IllegalArgumentException("duplicate module '" + module.name() + "'");
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) {
                    putQualified(functions, ambiguousFunctions, module.name(), fn.name(), fn, fn.kind() == Ast.CallableKind.ROUTINE ? "routine" : "function");
                    functionOwners.put(fn, module.name());
                } else if (decl instanceof Ast.ClassDecl klass) {
                    putQualified(classes, ambiguousClasses, module.name(), klass.name(), klass, "class");
                    classOwners.put(klass, module.name());
                } else if (decl instanceof Ast.InterfaceDecl iface) {
                    putQualified(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface, "interface");
                    interfaceOwners.put(iface, module.name());
                } else if (decl instanceof Ast.TypeAliasDecl alias) {
                    putQualified(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias, "type alias");
                }
            }
        }
    }

    private static <T> void putQualified(Map<String, T> map, Set<String> ambiguous, String module, String name, T value, String kind) {
        String qualified = module + "." + name;
        if (map.putIfAbsent(qualified, value) != null) {
            throw new IllegalArgumentException("duplicate " + kind + " '" + qualified + "'; fnc/routine declarations cannot overload");
        }
        T previous = map.putIfAbsent(name, value);
        if (previous != null && previous != value) {
            ambiguous.add(name);
            map.remove(name);
        }
    }

    private void validate(Ast.Program program) {
        for (Ast.ClassDecl klass : classOwners.keySet()) classShape(klass, new LinkedHashSet<>());
        for (Ast.InterfaceDecl iface : interfaceOwners.keySet()) interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());

        for (Ast.ModuleDecl module : program.modules()) {
            checkModuleAdherence(module);
            long initCount = module.declarations().stream().filter(Ast.InitDecl.class::isInstance).count();
            if (initCount > 1) {
                String scope = module.name().equals(Parser.ROOT_MODULE) ? "source file" : "module '" + module.name() + "'";
                throw new IllegalArgumentException(scope + " may declare at most one init routine");
            }
            if (module.singleton()) validateSingletonModule(module);
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module, fn);
                else if (decl instanceof Ast.InitDecl init) checkInit(module, init);
                else if (decl instanceof Ast.ClassDecl klass) checkClass(module.name(), klass);
                else if (decl instanceof Ast.InterfaceDecl iface) checkInterface(iface);
                else if (decl instanceof Ast.TypeAliasDecl alias) resolve(alias.target(), Set.copyOf(alias.genericParameters()), null);
                else if (decl instanceof Ast.FieldDecl field) checkModuleBinding(field);
            }
        }
    }

    private void checkModuleAdherence(Ast.ModuleDecl module) {
        for (Ast.Annotation annotation : module.annotations()) {
            if (!annotation.name().equals("AdheresTo")) continue;
            if (module.singleton()) {
                throw new IllegalArgumentException("singleton module '" + module.name()
                        + "' cannot use ordinary @AdheresTo interfaces because its external surface is asynchronous;"
                        + " define a service/singleton interface kind before advertising synchronous conformance");
            }
            if (annotation.arguments().isEmpty()) throw new IllegalArgumentException("@AdheresTo requires at least one interface");
            Record actual = moduleShape(module);
            for (Ast.TypeRef ref : annotation.arguments()) {
                Ast.InterfaceDecl iface = findInterface(ref.name());
                if (iface == null) throw new IllegalArgumentException("unknown module interface '" + ref.name() + "'");
                Record expected = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
                if (!assignable(actual, expected)) {
                    throw new IllegalArgumentException("module '" + module.name() + "' does not adhere to interface '" + ref.name() + "': expected " + expected + " but got " + actual);
                }
            }
        }
    }

    private Record moduleShape(Ast.ModuleDecl module) {
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                Type signature = functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                mergeMember(members, fn.name(), signature, "module " + module.name());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()), signature, "module " + module.name());
            } else if (decl instanceof Ast.FieldDecl field && field.visibility() == Ast.Visibility.PUBLIC) {
                Type type = field.type() == null ? typeOf(field.initializer(), new Env(null), Set.of(), null) : resolve(field.type(), Set.of(), null);
                mergeMember(members, field.name(), type, "module " + module.name());
            }
        }
        return new Record(members);
    }

    private void checkInterface(Ast.InterfaceDecl iface) {
        Set<String> generics = uniqueGenerics(iface.genericParameters(), "interface " + iface.name());
        Set<String> memberKeys = new HashSet<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            if (findInterface(parentRef.name()) == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
        }

        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                String key = methodKey(fn.name(), fn.parameters().size());
                if (!memberKeys.add(key)) throw new IllegalArgumentException("duplicate interface method '" + iface.name() + "." + fn.name() + "' with arity " + fn.parameters().size());
                Set<String> all = new HashSet<>(generics);
                for (String generic : fn.genericParameters()) {
                    if (!all.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in interface " + iface.name() + "." + fn.name());
                }
                functionType(fn.parameters(), fn.returnType(), all, null);
            } else {
                Ast.InterfaceFieldDecl field = (Ast.InterfaceFieldDecl) member;
                if (!memberKeys.add(field.name())) throw new IllegalArgumentException("duplicate interface member '" + iface.name() + "." + field.name() + "'");
                resolve(field.type(), generics, null);
            }
        }
    }

    private void checkInit(Ast.ModuleDecl module, Ast.InitDecl init) {
        Env env = module.singleton() ? singletonModuleEnv(module) : moduleBindingEnv(module);
        if (module.singleton()) validatePureSingletonInit(module, init);
        validateSingletonTransportStatements(init.body(), module.name());
        checkBlock(init.body(), env, Set.of(), Primitive.VOID, null);
    }

    /**
     * Process init cannot inherit ambient authority from whichever actor or
     * tenant first touches the singleton. Keep it deterministic and confined
     * to already-declared singleton state until a supervisor-owned init
     * capability model exists.
     */
    private void validatePureSingletonInit(Ast.ModuleDecl module, Ast.InitDecl init) {
        Set<String> names = new LinkedHashSet<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FieldDecl field) names.add(field.name());
        }
        validatePureSingletonInitStatements(module, init.body(), names);
    }

    private void validatePureSingletonInitStatements(
            Ast.ModuleDecl module,
            List<Ast.Stmt> statements,
            Set<String> visibleNames) {
        Set<String> names = new LinkedHashSet<>(visibleNames);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (!isPureSingletonInitializer(binding.initializer(), names)) {
                    throw impureSingletonInit(module, "local binding '" + binding.name() + "'");
                }
                names.add(binding.name());
                continue;
            }
            if (stmt instanceof Ast.ExprStmt expression
                    && expression.expression() instanceof Ast.AssignExpr assignment
                    && assignment.target() instanceof Ast.NameExpr target
                    && names.contains(target.name())
                    && isPureSingletonInitializer(assignment.value(), names)) {
                continue;
            }
            if (stmt instanceof Ast.ReturnStmt ret && ret.value() == null) continue;
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (!isPureSingletonInitializer(branch.condition(), names)) {
                        throw impureSingletonInit(module, "if condition");
                    }
                    validatePureSingletonInitStatements(module, branch.body(), names);
                }
                validatePureSingletonInitStatements(module, conditional.elseBody(), names);
                continue;
            }
            throw impureSingletonInit(module, stmt.getClass().getSimpleName());
        }
    }

    private IllegalArgumentException impureSingletonInit(Ast.ModuleDecl module, String construct) {
        return new IllegalArgumentException("singleton init routine '" + module.name()
                + "' must be deterministic and context-free; " + construct
                + " would make process state depend on the first caller");
    }

    private Env moduleBindingEnv(Ast.ModuleDecl module) {
        Env env = new Env(null, module.name());
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FieldDecl field)) continue;
            Type declared;
            if (field.initializer() == null) {
                if (field.type() == null) {
                    throw new IllegalArgumentException("uninitialized module member '" + module.name() + "." + field.name() + "' requires an explicit type");
                }
                declared = resolve(field.type(), Set.of(), null);
            } else {
                Type actual = typeOf(field.initializer(), env, Set.of(), null);
                declared = field.type() == null ? actual : resolve(field.type(), Set.of(), null);
                requireAssignable(actual, declared, "initializer for " + module.name() + "." + field.name());
            }
            env.define(field.name(), declared, field.bindingKind());
        }
        return env;
    }

    private void checkFunction(Ast.ModuleDecl module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        Env env = module.singleton() ? singletonModuleEnv(module) : moduleBindingEnv(module);
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolveParam(param, generics, null), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        Type returns = resolve(fn.returnType(), generics, null);
        validateSingletonTransportStatements(fn.body(), module.name());
        checkBlock(fn.body(), env, generics, returns, null);
        if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException("non-void " + fn.kind().name().toLowerCase() + " '" + module.name() + "." + fn.name() + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }

    private Env singletonModuleEnv(Ast.ModuleDecl module) {
        Env env = new Env(null, module.name());
        for (Ast.Decl decl : module.declarations()) {
            if (!(decl instanceof Ast.FieldDecl field)) continue;
            if (field.initializer() == null) {
                throw new IllegalArgumentException("singleton module binding '" + module.name() + "." + field.name() + "' requires an initializer");
            }
            Type actual = typeOf(field.initializer(), env, Set.of(), null);
            Type declared = field.type() == null ? actual : resolve(field.type(), Set.of(), null);
            requireAssignable(actual, declared, "initializer for " + module.name() + "." + field.name());
            env.define(field.name(), declared, field.bindingKind());
        }
        return env;
    }

    private void validateSingletonModule(Ast.ModuleDecl module) {
        Set<String> initializedFields = new LinkedHashSet<>();
        for (Ast.Decl decl : module.declarations()) {
            if (decl instanceof Ast.FieldDecl field) {
                if (field.type() == null) {
                    throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                            + "' requires an explicit type so process-lifetime state has a stable hot-reload schema");
                }
                if (containsTypeAlias(field.type())) {
                    throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                            + "' cannot use type aliases in process-lifetime state; spell the stable storage type explicitly");
                }

                Ast.ClassDecl proxyClass = singletonProxyClass(field);
                boolean exportedProxy = field.visibility() == Ast.Visibility.PUBLIC && proxyClass != null;
                if (field.visibility() == Ast.Visibility.PUBLIC && !exportedProxy) {
                    throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                            + "' cannot be public unless it is an immutable class-instance proxy");
                }

                if (exportedProxy) {
                    if (field.bindingKind() == Ast.BindingKind.LET) {
                        throw new IllegalArgumentException("exported singleton object '" + module.name() + "." + field.name()
                                + "' must use val or const so the process-wide capability cannot be rebound");
                    }
                    validateSingletonProxyClass(module, field, proxyClass);
                    if (!isPureSingletonProxyInitializer(field.initializer(), proxyClass, initializedFields)) {
                        throw new IllegalArgumentException("exported singleton object '" + module.name() + "." + field.name()
                                + "' must be initialized with a context-free 'new " + proxyClass.name() + "(...)'");
                    }
                } else {
                    Type stateType = resolve(field.type(), Set.of(), null);
                    if (!isProcessStableStateType(stateType)) {
                        throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                                + "' uses process-unstable state type " + field.type()
                                + "; use scalars, Option<T>, or Array/List<T> of stable values");
                    }
                    if (field.initializer() == null
                            || !isPureSingletonInitializer(field.initializer(), initializedFields)) {
                        throw new IllegalArgumentException("singleton module field '" + module.name() + "." + field.name()
                                + "' requires a context-free initializer; singleton initialization cannot call functions,"
                                + " access capabilities, await work, construct arbitrary classes, or mutate state");
                    }
                }
                initializedFields.add(field.name());
            }

            if (decl instanceof Ast.FunctionDecl fn && fn.visibility() == Ast.Visibility.PUBLIC) {
                if (fn.async()) {
                    throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                            + "' cannot be declared async; the actor transport already supplies Future<T>");
                }
                if (!fn.genericParameters().isEmpty()) {
                    throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                            + "' cannot be generic until Oreslang has explicit Send constraints");
                }

                Set<String> generics = Set.copyOf(fn.genericParameters());
                for (Ast.Param param : fn.parameters()) {
                    if (param.mutable()) {
                        throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                                + "' cannot accept mut parameters because transported values are frozen snapshots");
                    }
                    if (param.structural()) {
                        throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                                + "' cannot use structural parameters until structural Send is explicit");
                    }
                    Type parameter = resolveParam(param, generics, null);
                    if (!isActorSendableType(parameter, false)) {
                        throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                                + "' parameter '" + param.name() + "' is not statically Sendable: " + param.type());
                    }
                }
                Type result = resolve(fn.returnType(), generics, null);
                if (!isActorSendableType(result, true)) {
                    throw new IllegalArgumentException("singleton callable '" + module.name() + "." + fn.name()
                            + "' result is not statically Sendable: " + fn.returnType());
                }
            }
        }
        validateProcessOwnedModuleEffects(module);
    }

    private Ast.ClassDecl singletonProxyClass(Ast.FieldDecl field) {
        if (field.type() == null || !field.type().arguments().isEmpty()) return null;
        return findClass(field.type().name());
    }

    private boolean isPureSingletonProxyInitializer(
            Ast.Expr initializer,
            Ast.ClassDecl klass,
            Set<String> initializedFields) {
        if (!(initializer instanceof Ast.NewExpr created)) return false;
        Ast.ClassDecl createdClass = findClass(created.type().name());
        if (createdClass != klass) return false;
        if (created.arguments().stream().anyMatch(arg -> !isPureSingletonInitializer(arg, initializedFields))) {
            return false;
        }
        for (Ast.FieldDecl classField : effectiveFields(klass, new LinkedHashSet<>())) {
            if (classField.type() == null) return false;
            Type fieldType = resolve(classField.type(), Set.copyOf(klass.genericParameters()), nominalClassType(klass));
            if (!isProcessStableStateType(fieldType)) return false;
            if (classField.initializer() != null
                    && !isPureSingletonInitializer(classField.initializer(), Set.of())) return false;
        }
        return true;
    }

    private void validateSingletonProxyClass(
            Ast.ModuleDecl owner,
            Ast.FieldDecl exportedField,
            Ast.ClassDecl klass) {
        if (!klass.parents().isEmpty()) {
            throw new IllegalArgumentException("exported singleton object '" + owner.name() + "."
                    + exportedField.name()
                    + "' cannot use class inheritance until inherited proxy methods are flattened and Send-checked");
        }
        if (!klass.genericParameters().isEmpty()) {
            throw new IllegalArgumentException("exported singleton object '" + owner.name() + "." + exportedField.name()
                    + "' cannot use a generic class until proxy Send constraints are explicit");
        }

        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() || method.visibility() != Ast.Visibility.PUBLIC) continue;
            if (method.async()) {
                throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                        + "' cannot be async; mailbox transport already returns Future<T>");
            }
            if (!method.genericParameters().isEmpty()) {
                throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                        + "' cannot be generic until proxy Send constraints are explicit");
            }
            for (Ast.Param param : method.parameters()) {
                if (param.mutable() || param.structural()) {
                    throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                            + "' cannot use mut or structural transported parameters");
                }
                Type parameter = resolveParam(param, Set.of(), nominalClassType(klass));
                if (!isActorSendableType(parameter, false)) {
                    throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                            + "' parameter '" + param.name() + "' is not statically Sendable");
                }
            }
            Type result = resolve(method.returnType(), Set.of(), nominalClassType(klass));
            if (!isActorSendableType(result, true)) {
                throw new IllegalArgumentException("singleton proxy method '" + klass.name() + "." + method.name()
                        + "' result is not statically Sendable");
            }

        }

        String classOwnerName = classOwners.get(klass);
        Ast.ModuleDecl classOwner = classOwnerName == null ? null : modules.get(classOwnerName);
        if (classOwner != null && classOwner != owner) {
            Set<String> actorBindings = new LinkedHashSet<>();
            for (Ast.Decl decl : classOwner.declarations()) {
                if (decl instanceof Ast.FieldDecl field) actorBindings.add(field.name());
            }
            if (!actorBindings.isEmpty()) {
                for (Ast.FieldDecl classField : klass.fields()) {
                    if (classField.initializer() != null
                            && referencesActorModuleBinding(classField.initializer(), actorBindings, Set.of())) {
                        throw new IllegalArgumentException("exported singleton class '" + klass.name()
                                + "' cannot capture actor-local module state from '" + classOwner.name() + "'");
                    }
                }
                for (Ast.MethodDecl method : klass.methods()) {
                    Set<String> shadowed = new LinkedHashSet<>();
                    shadowed.add("self");
                    for (Ast.Param param : method.parameters()) shadowed.add(param.name());
                    if (referencesActorModuleBinding(method.body(), actorBindings, shadowed)) {
                        throw new IllegalArgumentException("exported singleton class '" + klass.name()
                                + "' cannot capture actor-local module state from '" + classOwner.name() + "'");
                    }
                }
            }
        }
        validateProcessOwnedClassEffects(owner, klass);
    }

    private void validateProcessOwnedModuleEffects(Ast.ModuleDecl owner) {
        for (Ast.Decl decl : owner.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn) {
                Set<String> locals = new LinkedHashSet<>();
                for (Ast.Param param : fn.parameters()) locals.add(param.name());
                validateProcessOwnedStatements(owner, null, fn.body(), locals,
                        "singleton callable '" + owner.name() + "." + fn.name() + "'");
            } else if (decl instanceof Ast.ClassDecl klass) {
                validateProcessOwnedClassEffects(owner, klass);
            }
        }
    }

    private void validateProcessOwnedClassEffects(Ast.ModuleDecl owner, Ast.ClassDecl klass) {
        if (!processEffectCheckedClasses.add(klass)) return;

        for (Ast.FieldDecl field : klass.fields()) {
            if (field.initializer() != null) {
                validateProcessOwnedExpr(owner, klass, field.initializer(), Set.of(),
                        "process-owned class '" + klass.name() + "' field initializer");
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> locals = new LinkedHashSet<>();
            if (!method.isStatic()) locals.add("self");
            for (Ast.Param param : method.parameters()) locals.add(param.name());
            validateProcessOwnedStatements(owner, klass, method.body(), locals,
                    "process-owned method '" + klass.name() + "." + method.name() + "'");
        }
    }

    private void validateProcessOwnedStatements(
            Ast.ModuleDecl processOwner,
            Ast.ClassDecl processClass,
            List<Ast.Stmt> statements,
            Set<String> inheritedLocals,
            String where) {
        Set<String> locals = new LinkedHashSet<>(inheritedLocals);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                validateProcessOwnedExpr(processOwner, processClass, binding.initializer(), locals, where);
                locals.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                validateProcessOwnedExpr(processOwner, processClass, destructure.initializer(), locals, where);
                for (Ast.DestructureBinding binding : destructure.bindings()) locals.add(binding.name());
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null) validateProcessOwnedExpr(processOwner, processClass, ret.value(), locals, where);
            } else if (stmt instanceof Ast.ExprStmt expression) {
                validateProcessOwnedExpr(processOwner, processClass, expression.expression(), locals, where);
            } else if (stmt instanceof Ast.DeferStmt defer) {
                validateProcessOwnedExpr(processOwner, processClass, defer.expression(), locals, where);
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateProcessOwnedExpr(processOwner, processClass, branch.condition(), locals, where);
                    validateProcessOwnedStatements(processOwner, processClass, branch.body(),
                            new LinkedHashSet<>(locals), where);
                }
                validateProcessOwnedStatements(processOwner, processClass, conditional.elseBody(),
                        new LinkedHashSet<>(locals), where);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                validateProcessOwnedStatements(processOwner, processClass, attempted.body(),
                        new LinkedHashSet<>(locals), where);
                Set<String> caught = new LinkedHashSet<>(locals);
                caught.add(attempted.errorName());
                validateProcessOwnedStatements(processOwner, processClass, attempted.catchBody(), caught, where);
                validateProcessOwnedStatements(processOwner, processClass, attempted.finallyBody(),
                        new LinkedHashSet<>(locals), where);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                validateProcessOwnedExpr(processOwner, processClass, loop.iterable(), locals, where);
                Set<String> loopLocals = new LinkedHashSet<>(locals);
                loopLocals.add(loop.bindingName());
                validateProcessOwnedStatements(processOwner, processClass, loop.body(), loopLocals, where);
            } else if (stmt instanceof Ast.ForStmt loop) {
                Set<String> loopLocals = new LinkedHashSet<>(locals);
                if (loop.initializer() != null) {
                    validateProcessOwnedStatements(processOwner, processClass,
                            List.of(loop.initializer()), loopLocals, where);
                    if (loop.initializer() instanceof Ast.BindingStmt binding) loopLocals.add(binding.name());
                }
                if (loop.condition() != null) {
                    validateProcessOwnedExpr(processOwner, processClass, loop.condition(), loopLocals, where);
                }
                if (loop.update() != null) {
                    validateProcessOwnedExpr(processOwner, processClass, loop.update(), loopLocals, where);
                }
                validateProcessOwnedStatements(processOwner, processClass, loop.body(), loopLocals, where);
            }
        }
    }

    private void validateProcessOwnedExpr(
            Ast.ModuleDecl processOwner,
            Ast.ClassDecl processClass,
            Ast.Expr expr,
            Set<String> locals,
            String where) {
        if (expr instanceof Ast.LiteralExpr) return;

        if (expr instanceof Ast.NameExpr name) {
            if (locals.contains(name.name()) || name.name().equals("self")
                    || name.name().equals("Some") || name.name().equals("None")) return;
            if (name.name().equals("stdio") || name.name().equals("process") || name.name().equals("print")) {
                throw processEffectError(where, "ambient caller capability '" + name.name() + "' (ambient capability)");
            }
            if (importedNames.contains(name.name())) {
                throw processEffectError(where, "imported dependency '" + name.name() + "'");
            }
            Ast.ModuleDecl referencedModule = modules.get(name.name());
            if (referencedModule != null && !referencedModule.singleton()) {
                throw processEffectError(where, "actor/context-local module '" + referencedModule.name() + "' (caller/context-local module)");
            }
            return;
        }

        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name && !locals.contains(name.name())) {
                Ast.FunctionDecl target = findFunction(name.name());
                if (target != null) {
                    String targetOwnerName = functionOwners.get(target);
                    Ast.ModuleDecl targetOwner = targetOwnerName == null ? null : modules.get(targetOwnerName);
                    if (targetOwner == null || !targetOwner.singleton()) {
                        throw processEffectError(where, "actor/context-local function '" + name.name() + "' (ordinary helper function)");
                    }
                }
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr receiver
                    && !locals.contains(receiver.name())) {
                Ast.ModuleDecl targetModule = modules.get(receiver.name());
                if (targetModule != null && !targetModule.singleton()) {
                    throw processEffectError(where, "actor/context-local module '" + targetModule.name() + "' (caller/context-local module)");
                }
                Ast.ClassDecl targetClass = findClass(receiver.name());
                if (targetClass != null && targetClass != processClass) {
                    String ownerName = classOwners.get(targetClass);
                    Ast.ModuleDecl classOwner = ownerName == null ? null : modules.get(ownerName);
                    if (classOwner == null || !classOwner.singleton()
                            || !classOwner.name().equals(processOwner.name())) {
                        throw processEffectError(where, "ordinary static class '" + targetClass.name() + "'");
                    }
                }
            }
            validateProcessOwnedExpr(processOwner, processClass, call.callee(), locals, where);
            for (Ast.Expr arg : call.arguments()) {
                validateProcessOwnedExpr(processOwner, processClass, arg, locals, where);
            }
            return;
        }

        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl target = findClass(created.type().name());
            if (target == null) {
                throw processEffectError(where, "unresolved or imported class construction '" + created.type().name() + "'");
            }
            if (target != processClass) {
                String ownerName = classOwners.get(target);
                Ast.ModuleDecl classOwner = ownerName == null ? null : modules.get(ownerName);
                if (classOwner == null || !classOwner.singleton()
                        || !classOwner.name().equals(processOwner.name())) {
                    throw processEffectError(where, "ordinary class construction '" + target.name() + "'");
                }
            }
            for (Ast.Expr arg : created.arguments()) {
                validateProcessOwnedExpr(processOwner, processClass, arg, locals, where);
            }
            return;
        }

        if (expr instanceof Ast.AssignExpr assignment) {
            validateProcessOwnedExpr(processOwner, processClass, assignment.target(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, assignment.value(), locals, where);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            validateProcessOwnedExpr(processOwner, processClass, binary.left(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, binary.right(), locals, where);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            validateProcessOwnedExpr(processOwner, processClass, unary.operand(), locals, where);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            validateProcessOwnedExpr(processOwner, processClass, conditional.condition(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, conditional.whenTrue(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, conditional.whenFalse(), locals, where);
        } else if (expr instanceof Ast.MemberExpr member) {
            validateProcessOwnedExpr(processOwner, processClass, member.receiver(), locals, where);
        } else if (expr instanceof Ast.IndexExpr indexed) {
            validateProcessOwnedExpr(processOwner, processClass, indexed.receiver(), locals, where);
            validateProcessOwnedExpr(processOwner, processClass, indexed.index(), locals, where);
        } else if (expr instanceof Ast.AwaitExpr awaited) {
            validateProcessOwnedExpr(processOwner, processClass, awaited.expression(), locals, where);
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                validateProcessOwnedExpr(processOwner, processClass, item, locals, where);
            }
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                validateProcessOwnedExpr(processOwner, processClass, item, locals, where);
            }
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                validateProcessOwnedExpr(processOwner, processClass, field.value(), locals, where);
            }
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            Set<String> lambdaLocals = new LinkedHashSet<>(locals);
            for (Ast.Param param : lambda.parameters()) lambdaLocals.add(param.name());
            if (lambda.expressionBody() != null) {
                validateProcessOwnedExpr(processOwner, processClass, lambda.expressionBody(), lambdaLocals, where);
            }
            if (lambda.blockBody() != null) {
                validateProcessOwnedStatements(processOwner, processClass, lambda.blockBody(), lambdaLocals, where);
            }
        }
    }

    private IllegalArgumentException processEffectError(String where, String dependency) {
        return new IllegalArgumentException(where
                + " cannot depend on " + dependency
                + "; process-singleton code may use only its own state/helpers and explicit singleton-service calls"
                + " until Oreslang has a process-safe effect declaration");
    }

    private boolean referencesActorModuleBinding(
            List<Ast.Stmt> statements,
            Set<String> actorBindings,
            Set<String> inheritedShadowed) {
        Set<String> shadowed = new LinkedHashSet<>(inheritedShadowed);
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (referencesActorModuleBinding(binding.initializer(), actorBindings, shadowed)) return true;
                shadowed.add(binding.name());
            } else if (stmt instanceof Ast.DestructureStmt destructure) {
                if (referencesActorModuleBinding(destructure.initializer(), actorBindings, shadowed)) return true;
                for (Ast.DestructureBinding binding : destructure.bindings()) shadowed.add(binding.name());
            } else if (stmt instanceof Ast.ReturnStmt ret) {
                if (ret.value() != null && referencesActorModuleBinding(ret.value(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.ExprStmt expression) {
                if (referencesActorModuleBinding(expression.expression(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.DeferStmt defer) {
                if (referencesActorModuleBinding(defer.expression(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    if (referencesActorModuleBinding(branch.condition(), actorBindings, shadowed)
                            || referencesActorModuleBinding(branch.body(), actorBindings, shadowed)) return true;
                }
                if (referencesActorModuleBinding(conditional.elseBody(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.TryStmt attempted) {
                if (referencesActorModuleBinding(attempted.body(), actorBindings, shadowed)) return true;
                Set<String> caught = new LinkedHashSet<>(shadowed);
                caught.add(attempted.errorName());
                if (referencesActorModuleBinding(attempted.catchBody(), actorBindings, caught)
                        || referencesActorModuleBinding(attempted.finallyBody(), actorBindings, shadowed)) return true;
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                if (referencesActorModuleBinding(loop.iterable(), actorBindings, shadowed)) return true;
                Set<String> loopShadowed = new LinkedHashSet<>(shadowed);
                loopShadowed.add(loop.bindingName());
                if (referencesActorModuleBinding(loop.body(), actorBindings, loopShadowed)) return true;
            } else if (stmt instanceof Ast.ForStmt loop) {
                Set<String> loopShadowed = new LinkedHashSet<>(shadowed);
                if (loop.initializer() != null
                        && referencesActorModuleBinding(List.of(loop.initializer()), actorBindings, loopShadowed)) return true;
                if (loop.condition() != null
                        && referencesActorModuleBinding(loop.condition(), actorBindings, loopShadowed)) return true;
                if (loop.update() != null
                        && referencesActorModuleBinding(loop.update(), actorBindings, loopShadowed)) return true;
                if (referencesActorModuleBinding(loop.body(), actorBindings, loopShadowed)) return true;
            }
        }
        return false;
    }

    private boolean referencesActorModuleBinding(
            Ast.Expr expr,
            Set<String> actorBindings,
            Set<String> shadowed) {
        if (expr instanceof Ast.NameExpr name) {
            return actorBindings.contains(name.name()) && !shadowed.contains(name.name());
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            return referencesActorModuleBinding(assignment.target(), actorBindings, shadowed)
                    || referencesActorModuleBinding(assignment.value(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return referencesActorModuleBinding(binary.left(), actorBindings, shadowed)
                    || referencesActorModuleBinding(binary.right(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return referencesActorModuleBinding(unary.operand(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return referencesActorModuleBinding(conditional.condition(), actorBindings, shadowed)
                    || referencesActorModuleBinding(conditional.whenTrue(), actorBindings, shadowed)
                    || referencesActorModuleBinding(conditional.whenFalse(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.CallExpr call) {
            if (referencesActorModuleBinding(call.callee(), actorBindings, shadowed)) return true;
            for (Ast.Expr arg : call.arguments()) {
                if (referencesActorModuleBinding(arg, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.MemberExpr member) {
            return referencesActorModuleBinding(member.receiver(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return referencesActorModuleBinding(indexed.receiver(), actorBindings, shadowed)
                    || referencesActorModuleBinding(indexed.index(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr arg : created.arguments()) {
                if (referencesActorModuleBinding(arg, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            return referencesActorModuleBinding(awaited.expression(), actorBindings, shadowed);
        }
        if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) {
                if (referencesActorModuleBinding(item, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) {
                if (referencesActorModuleBinding(item, actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) {
                if (referencesActorModuleBinding(field.value(), actorBindings, shadowed)) return true;
            }
            return false;
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            Set<String> lambdaShadowed = new LinkedHashSet<>(shadowed);
            for (Ast.Param param : lambda.parameters()) lambdaShadowed.add(param.name());
            if (lambda.expressionBody() != null
                    && referencesActorModuleBinding(lambda.expressionBody(), actorBindings, lambdaShadowed)) return true;
            return lambda.blockBody() != null
                    && referencesActorModuleBinding(lambda.blockBody(), actorBindings, lambdaShadowed);
        }
        return false;
    }

    private boolean containsTypeAlias(Ast.TypeRef type) {
        if (type == null) return false;
        if (findTypeAlias(type.name()) != null) return true;
        for (Ast.TypeRef argument : type.arguments()) {
            if (containsTypeAlias(argument)) return true;
        }
        return false;
    }

    private boolean isProcessStableStateType(Type type) {
        if (type instanceof StringLiteral) return true;
        if (type instanceof Primitive primitive) return primitive != Primitive.VOID;
        if (type instanceof ListType list) return isProcessStableStateType(list.element());
        if (type instanceof Tuple tuple) return tuple.elements().stream().allMatch(this::isProcessStableStateType);
        if (type instanceof Named named && named.name().equals("Option") && named.arguments().size() == 1) {
            return isProcessStableStateType(named.arguments().getFirst());
        }
        return false;
    }

    private boolean isActorSendableType(Type type, boolean allowVoid) {
        if (type instanceof StringLiteral) return true;
        if (type instanceof Primitive primitive) {
            return primitive != Primitive.VOID || allowVoid;
        }
        if (type instanceof ListType list) return isActorSendableType(list.element(), false);
        if (type instanceof Tuple tuple) return tuple.elements().stream().allMatch(item -> isActorSendableType(item, false));
        if (type instanceof Record record) return record.members().values().stream()
                .allMatch(item -> isActorSendableType(item, false));
        if (type instanceof Named named && named.name().equals("Option") && named.arguments().size() == 1) {
            return isActorSendableType(named.arguments().getFirst(), false);
        }
        return false;
    }

    private boolean isPureSingletonInitializer(Ast.Expr expr, Set<String> initializedFields) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.NameExpr name) {
            return name.name().equals("None") || initializedFields.contains(name.name());
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            return !unary.operator().equals("&")
                    && !unary.operator().equals("&mut")
                    && isPureSingletonInitializer(unary.operand(), initializedFields);
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            return isPureSingletonInitializer(binary.left(), initializedFields)
                    && isPureSingletonInitializer(binary.right(), initializedFields);
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            return isPureSingletonInitializer(conditional.condition(), initializedFields)
                    && isPureSingletonInitializer(conditional.whenTrue(), initializedFields)
                    && isPureSingletonInitializer(conditional.whenFalse(), initializedFields);
        }
        if (expr instanceof Ast.ListExpr list) {
            return list.elements().stream().allMatch(item -> isPureSingletonInitializer(item, initializedFields));
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return tuple.elements().stream().allMatch(item -> isPureSingletonInitializer(item, initializedFields));
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            return isPureSingletonInitializer(indexed.receiver(), initializedFields)
                    && isPureSingletonInitializer(indexed.index(), initializedFields);
        }
        if (expr instanceof Ast.CallExpr call
                && call.callee() instanceof Ast.NameExpr name
                && name.name().equals("Some")
                && call.arguments().size() == 1) {
            return isPureSingletonInitializer(call.arguments().getFirst(), initializedFields);
        }
        return false;
    }

    private Function singletonTransportType(Ast.FunctionDecl fn) {
        Set<String> generics = Set.copyOf(fn.genericParameters());
        Function logical = (Function) functionType(fn.parameters(), fn.returnType(), generics, null);
        return new Function(logical.parameters(), new Named("Future", List.of(logical.result())));
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        boolean declaresConstructors = klass.methods().stream().anyMatch(this::isConstructor);
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            Ast.ClassDecl resolvedParent = resolveClassParent(parent, klass);
            if (resolvedParent != null
                    && (declaresConstructors || hasConstructorInHierarchy(resolvedParent, new LinkedHashSet<>()))) {
                throw new IllegalArgumentException("class '" + klass.name()
                        + "' cannot combine explicit constructor semantics with inheritance until super(...) is supported");
            }
        }

        Set<String> localMethodSignatures = new HashSet<>();
        for (Ast.MethodDecl method : klass.methods()) {
            String memberKind = method.isStatic() ? "static:" : "instance:";
            String signature = memberKind + methodKey(method.name(), method.arity());
            if (!localMethodSignatures.add(signature)) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException(label + " '" + klass.name() + "." + method.name() + "' already has arity " + method.arity()
                        + "; class callables may overload only by arity within their own static/instance namespace");
            }
        }

        Ast.ModuleDecl lexicalOwner = modules.get(module);
        Env classModuleEnv = lexicalOwner == null
                ? new Env(null, module)
                : lexicalOwner.singleton() ? singletonModuleEnv(lexicalOwner) : moduleBindingEnv(lexicalOwner);

        Set<String> localFieldNames = new HashSet<>();
        for (Ast.FieldDecl field : klass.fields()) {
            if (!localFieldNames.add(field.name())) {
                throw new IllegalArgumentException("duplicate data member '" + klass.name() + "." + field.name() + "'");
            }
            if (field.isStatic() && referencesClassGeneric(field.type(), classGenerics)) {
                throw new IllegalArgumentException("static field '" + klass.name() + "." + field.name()
                        + "' cannot reference class type parameters because static storage is shared across all instances");
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() == null) {
                throw new IllegalArgumentException("const field '" + klass.name() + "." + field.name()
                        + "' requires a declaration initializer");
            }
            Type fieldSelf = field.isStatic() ? null : self;
            Type fieldType = resolve(field.type(), classGenerics, fieldSelf);
            if (field.initializer() != null) {
                Type actual = typeOf(field.initializer(), new Env(classModuleEnv), classGenerics, fieldSelf);
                requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
            } else if (field.isStatic() && field.bindingKind() != Ast.BindingKind.LET) {
                throw new IllegalArgumentException("uninitialized static field '" + klass.name() + "." + field.name() + "' must be mutable");
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !constant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' needs a compile-time constant initializer");
            }
            if (field.isStatic() && lexicalOwner != null && lexicalOwner.singleton()) {
                Type stateType = resolve(field.type(), classGenerics, null);
                if (!isProcessStableStateType(stateType)) {
                    throw new IllegalArgumentException("static field '" + klass.name() + "." + field.name()
                            + "' inside singleton module '" + lexicalOwner.name() + "' must use process-stable state");
                }
                if (field.initializer() == null || !isPureSingletonInitializer(field.initializer(), Set.of())) {
                    throw new IllegalArgumentException("static field '" + klass.name() + "." + field.name()
                            + "' inside singleton module '" + lexicalOwner.name()
                            + "' requires a context-free initializer");
                }
            }
        }

        for (Ast.MethodDecl method : klass.methods()) {
            Set<String> generics = new HashSet<>(classGenerics);
            for (String generic : method.genericParameters()) {
                if (!generics.add(generic)) throw new IllegalArgumentException("duplicate/shadowed generic '" + generic + "' in " + klass.name() + "." + method.name());
            }

            if (method.explicitReceiverType() != null) {
                Ast.TypeRef receiverRef = method.explicitReceiverType();
                boolean namesEnclosingClass = receiverRef.name().equals(klass.name()) || receiverRef.name().equals(qualifiedClassName(klass)) || receiverRef.name().equals("self");
                if (!namesEnclosingClass) {
                    Type receiver = resolve(receiverRef, generics, self);
                    requireAssignable(self, receiver, "explicit self receiver in " + klass.name() + "." + method.name());
                }
            }

            Type callableSelf = method.isStatic() ? null : self;
            Env env = new Env(classModuleEnv, classModuleEnv.moduleName, isConstructor(method) ? klass : null);
            if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, callableSelf), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            Type returns = resolve(method.returnType(), generics, callableSelf);
            validateSingletonTransportStatements(method.body(), module);
            checkBlock(method.body(), env, generics, returns, callableSelf);
            if (!method.isAbstract() && returns != Primitive.VOID && !definitelyReturns(method.body())) {
                String label = method.isStatic() ? "static function" : "method";
                throw new IllegalArgumentException("non-void " + label + " '" + module + "." + klass.name() + "." + method.name() + "' must explicitly return on every path");
            }
        }

        Set<String> implemented = new HashSet<>();
        for (Ast.TypeRef interfaceRef : klass.interfaces()) {
            if (!implemented.add(interfaceRef.name())) throw new IllegalArgumentException("duplicate implemented interface '" + interfaceRef.name() + "' on " + klass.name());
            Ast.InterfaceDecl iface = findInterface(interfaceRef.name());
            if (iface == null) throw new IllegalArgumentException("unknown interface '" + interfaceRef.name() + "' implemented by " + klass.name());
            Record expected = interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
            Record actual = publicClassShape(klass, new LinkedHashSet<>());
            if (!assignable(actual, expected)) {
                throw new IllegalArgumentException("class '" + klass.name() + "' does not implement interface '" + interfaceRef.name() + "': expected " + expected + " but got " + actual);
            }
        }
    }

    private void checkModuleBinding(Ast.FieldDecl field) {
        if (field.initializer() == null) {
            if (field.type() == null) throw new IllegalArgumentException("uninitialized module member '" + field.name() + "' requires an explicit type");
            if (field.bindingKind() != Ast.BindingKind.LET) {
                throw new IllegalArgumentException("uninitialized module member '" + field.name() + "' must be mutable");
            }
            resolve(field.type(), Set.of(), null);
            return;
        }
        Type actual = typeOf(field.initializer(), new Env(null), Set.of(), null);
        if (field.type() != null) requireAssignable(actual, resolve(field.type(), Set.of(), null), "initializer for " + field.name());
        if (field.bindingKind() == Ast.BindingKind.CONST && !constant(field.initializer())) {
            throw new IllegalArgumentException("const '" + field.name() + "' needs a compile-time constant initializer");
        }
    }

    private void checkBlock(List<Ast.Stmt> body, Env parent, Set<String> generics, Type expectedReturn, Type self) {
        Env env = new Env(parent);
        for (Ast.Stmt stmt : body) checkStatement(stmt, env, generics, expectedReturn, self);
    }

    private void checkStatement(Ast.Stmt stmt, Env env, Set<String> generics, Type expectedReturn, Type self) {
        if (stmt instanceof Ast.BindingStmt binding) {
            Type declaredAhead = binding.declaredType() == null ? null : resolve(binding.declaredType(), generics, self);
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) {
                env.define(binding.name(), declaredAhead, binding.kind());
            }
            if (binding.initializer() instanceof Ast.LambdaExpr lambda && declaredAhead instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = typeOf(binding.initializer(), env, generics, self);
            if (actual instanceof SingletonProxy proxy) {
                throw new IllegalArgumentException("singleton object proxy '" + proxy.moduleName() + "."
                        + proxy.fieldName() + "' cannot be rebound locally; invoke and await its methods directly");
            }
            Type declared = declaredAhead == null ? actual : declaredAhead;
            requireAssignable(actual, declared, "initializer for " + binding.name());
            if (binding.kind() == Ast.BindingKind.CONST && !constant(binding.initializer())) {
                throw new IllegalArgumentException("const '" + binding.name() + "' needs a compile-time constant initializer");
            }
            if (recursiveLambda && declaredAhead instanceof Function) env.replace(binding.name(), declared, binding.kind());
            else env.define(binding.name(), declared, binding.kind());
            return;
        }
        if (stmt instanceof Ast.DestructureStmt destructure) {
            Type source = typeOf(destructure.initializer(), env, generics, self);
            if (source instanceof Tuple tuple) {
                if (tuple.elements().size() != destructure.bindings().size()) throw new IllegalArgumentException("destructure arity mismatch");
                for (int i = 0; i < destructure.bindings().size(); i++) {
                    Ast.DestructureBinding binding = destructure.bindings().get(i);
                    env.define(binding.name(), tuple.elements().get(i), binding.kind());
                }
            } else if (source instanceof ListType list) {
                for (Ast.DestructureBinding binding : destructure.bindings()) env.define(binding.name(), list.element(), binding.kind());
            } else throw new IllegalArgumentException("destructuring requires a tuple or array/list value");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            if (ret.value() instanceof Ast.LambdaExpr lambda && expectedReturn instanceof Function expectedFunction) {
                validateLambdaAgainstExpected(lambda, expectedFunction, env, generics, self);
            }
            Type actual = ret.value() == null ? Primitive.VOID : typeOf(ret.value(), env, generics, self);
            requireAssignable(actual, expectedReturn, "return value");
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) { typeOf(expression.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.DeferStmt defer) { typeOf(defer.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                requireAssignable(typeOf(branch.condition(), env, generics, self), Primitive.BOOL, "if condition");
                checkBlock(branch.body(), env, generics, expectedReturn, self);
            }
            checkBlock(conditional.elseBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            checkBlock(attempted.body(), env, generics, expectedReturn, self);
            Env caught = new Env(env);
            caught.define(attempted.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            checkBlock(attempted.catchBody(), caught, generics, expectedReturn, self);
            checkBlock(attempted.finallyBody(), env, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Type element = iterableElementType(iterable);
            Env loopEnv = new Env(env);
            loopEnv.define(loop.bindingName(), element, loop.bindingKind());
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Env loopEnv = new Env(env);
            if (loop.initializer() != null) checkStatement(loop.initializer(), loopEnv, generics, expectedReturn, self);
            if (loop.condition() != null) requireAssignable(typeOf(loop.condition(), loopEnv, generics, self), Primitive.BOOL, "for condition");
            if (loop.update() != null) typeOf(loop.update(), loopEnv, generics, self);
            checkBlock(loop.body(), loopEnv, generics, expectedReturn, self);
        }
    }

    private Type typeOf(Ast.Expr expr, Env env, Set<String> generics, Type self) {
        if (expr instanceof Ast.LiteralExpr literal) {
            Object value = literal.value();
            if (value == null) throw new IllegalArgumentException("standalone null values are forbidden; use Option<T>");
            if (value instanceof Long) return Primitive.INT;
            if (value instanceof Double) return Primitive.FLOAT;
            if (value instanceof Boolean) return Primitive.BOOL;
            if (value instanceof String s) return new StringLiteral(s);
            if (value instanceof Ast.Imaginary) return Primitive.COMPLEX;
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.NameExpr name) {
            Env.Binding local = env.lookup(name.name());
            if (local != null) return local.type();
            if (name.name().equals("stdio") || name.name().equals("process")) return new Named(name.name(), List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Unknown.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) {
                String ownerName = classOwners.get(classNamespace);
                Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
                if (owner != null && owner.singleton() && !owner.name().equals(env.moduleName)) {
                    throw new IllegalArgumentException("class '" + classNamespace.name()
                            + "' is actor-private inside singleton module '" + owner.name() + "'");
                }
                return new ClassNamespace(qualifiedClassName(classNamespace));
            }
            if (importedValues.contains(name.name())) return Unknown.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) {
                String ownerName = functionOwners.get(fn);
                Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
                if (owner != null && owner.singleton() && !owner.name().equals(env.moduleName)) {
                    if (fn.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException("private singleton callable '" + owner.name() + "."
                                + fn.name() + "' is actor-private");
                    }
                    return singletonTransportType(fn);
                }
                return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
            }
            throw new IllegalArgumentException("unknown name '" + name.name() + "'");
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            Type targetType;
            String where;
            if (assignment.target() instanceof Ast.NameExpr name) {
                Env.Binding binding = env.lookup(name.name());
                if (binding == null) throw new IllegalArgumentException("cannot assign unknown name '" + name.name() + "'");
                if (binding.kind() != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + binding.kind().name().toLowerCase() + " binding '" + name.name() + "'");
                targetType = binding.type();
                where = name.name();
            } else if (assignment.target() instanceof Ast.MemberExpr member) {
                targetType = memberType(member, env, generics, self);
                where = member.member();
            } else if (assignment.target() instanceof Ast.IndexExpr indexed) {
                Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
                Type index = typeOf(indexed.index(), env, generics, self);
                requireAssignable(index, Primitive.INT, "array/list index");
                if (receiver instanceof ListType list) targetType = list.element();
                else if (receiver instanceof Tuple tuple) targetType = tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
                else throw new IllegalArgumentException("indexed assignment requires an array/list or tuple");
                where = "index";
            } else throw new IllegalArgumentException("unsupported assignment target");
            Type value = typeOf(assignment.value(), env, generics, self);
            requireAssignable(value, targetType, "assignment to " + where);
            return targetType;
        }
        if (expr instanceof Ast.ConditionalExpr conditional) {
            requireAssignable(typeOf(conditional.condition(), env, generics, self), Primitive.BOOL, "ternary condition");
            Type left = typeOf(conditional.whenTrue(), env, generics, self);
            Type right = typeOf(conditional.whenFalse(), env, generics, self);
            return commonType(left, right);
        }
        if (expr instanceof Ast.UnaryExpr unary) {
            Type operand = typeOf(unary.operand(), env, generics, self);
            if (unary.operator().equals("&")) return new Borrow(operand, false);
            if (unary.operator().equals("&mut")) return new Borrow(operand, true);
            if (unary.operator().equals("!")) {
                requireAssignable(operand, Primitive.BOOL, "! operand");
                return Primitive.BOOL;
            }
            if (!Types.isNumeric(operand)) throw new IllegalArgumentException("unary " + unary.operator() + " needs a numeric operand");
            return operand;
        }
        if (expr instanceof Ast.BinaryExpr binary) {
            Type left = typeOf(binary.left(), env, generics, self);
            Type right = typeOf(binary.right(), env, generics, self);
            return switch (binary.operator()) {
                case ",", "|" -> {
                    requireAssignable(left, Primitive.BOOL, "boolean operand");
                    requireAssignable(right, Primitive.BOOL, "boolean operand");
                    yield Primitive.BOOL;
                }
                case "==", "!=" -> Primitive.BOOL;
                case "<", "<=", ">", ">=" -> {
                    if (!(Types.isNumeric(left) && Types.isNumeric(right)) && !(isStringLike(left) && isStringLike(right))) {
                        throw new IllegalArgumentException("comparison operands must both be numeric or both strings");
                    }
                    yield Primitive.BOOL;
                }
                case "+" -> isStringLike(left) && isStringLike(right) ? Primitive.STRING : numericJoin(left, right, "+");
                case "-", "*", "/", "%" -> numericJoin(left, right, binary.operator());
                default -> Unknown.INSTANCE;
            };
        }
        if (expr instanceof Ast.CallExpr call) {
            if (call.callee() instanceof Ast.NameExpr name && name.name().equals("Some")) {
                if (call.arguments().size() != 1) throw new IllegalArgumentException("Some expects exactly one value");
                return new Named("Option", List.of(typeOf(call.arguments().getFirst(), env, generics, self)));
            }
            if (call.callee() instanceof Ast.MemberExpr member) {
                Type receiver = deref(typeOf(member.receiver(), env, generics, self));
                if (receiver instanceof ClassNamespace classNamespace) {
                    Ast.ClassDecl klass = findClass(classNamespace.className());
                    if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                    Ast.MethodDecl fn = findStaticFunction(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (fn == null) throw new IllegalArgumentException("no static function '" + member.member() + "' with arity " + call.arguments().size() + " on " + klass.name());
                    Set<String> fnGenerics = new HashSet<>(klass.genericParameters());
                    fnGenerics.addAll(fn.genericParameters());
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Type expected = resolveParam(fn.parameters().get(i), fnGenerics, null);
                        validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                        requireAssignable(typeOf(call.arguments().get(i), env, generics, self), expected, "argument " + (i + 1));
                    }
                    return resolve(fn.returnType(), fnGenerics, null);
                }
                if (receiver instanceof SingletonProxy proxy) {
                    Ast.ClassDecl klass = findClass(proxy.target().name());
                    if (klass == null) throw new IllegalArgumentException("unknown singleton proxy class '" + proxy.target().name() + "'");
                    Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                    if (method == null || method.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException("no public singleton proxy method '" + member.member()
                                + "' with arity " + call.arguments().size() + " on " + proxy.target().name());
                    }
                    Set<String> methodGenerics = new HashSet<>(klass.genericParameters());
                    methodGenerics.addAll(method.genericParameters());
                    for (int i = 0; i < call.arguments().size(); i++) {
                        Type expected = resolveParam(method.parameters().get(i), methodGenerics, proxy.target());
                        validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                        requireAssignable(typeOf(call.arguments().get(i), env, generics, self), expected,
                                "singleton proxy argument " + (i + 1));
                    }
                    Type result = resolve(method.returnType(), methodGenerics, proxy.target());
                    return new Named("Future", List.of(result));
                }
                if (receiver instanceof Named named) {
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                        if (method == null) throw new IllegalArgumentException("no method '" + member.member() + "' with arity " + call.arguments().size() + " on " + named.name());
                        Set<String> methodGenerics = new HashSet<>(klass.genericParameters());
                        methodGenerics.addAll(method.genericParameters());
                        for (int i = 0; i < call.arguments().size(); i++) {
                            Type expected = resolveParam(method.parameters().get(i), methodGenerics, named);
                            validateLambdaArgument(call.arguments().get(i), expected, env, generics, self);
                            requireAssignable(typeOf(call.arguments().get(i), env, generics, self), expected, "argument " + (i + 1));
                        }
                        return resolve(method.returnType(), methodGenerics, named);
                    }
                }
            }
            Type callee = typeOf(call.callee(), env, generics, self);
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                validateLambdaArgument(call.arguments().get(i), fn.parameters().get(i), env, generics, self);
                requireAssignable(typeOf(call.arguments().get(i), env, generics, self), fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace && modules.containsKey(namespace.name())) {
                Ast.ModuleDecl module = modules.get(namespace.name());
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) {
                    if (module.singleton() && !module.name().equals(env.moduleName)) {
                        throw new IllegalArgumentException("classes inside singleton module '" + module.name()
                                + "' are actor-private and cannot be accessed through its external proxy");
                    }
                    return new ClassNamespace(qualifiedClassName(memberClass));
                }
                if (module.singleton()) {
                    for (Ast.Decl decl : module.declarations()) {
                        if (decl instanceof Ast.FunctionDecl fn
                                && fn.visibility() == Ast.Visibility.PUBLIC
                                && fn.name().equals(member.member())) {
                            return module.name().equals(env.moduleName)
                                    ? functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null)
                                    : singletonTransportType(fn);
                        }
                        if (decl instanceof Ast.FieldDecl field
                                && field.visibility() == Ast.Visibility.PUBLIC
                                && field.name().equals(member.member())) {
                            Type declared = resolve(field.type(), Set.of(), null);
                            if (!(declared instanceof Named named) || findClass(named.name()) == null) {
                                throw new IllegalArgumentException("singleton module field '" + module.name() + "."
                                        + field.name() + "' is not an exported class-instance proxy");
                            }
                            return module.name().equals(env.moduleName)
                                    ? named
                                    : new SingletonProxy(module.name(), field.name(), named);
                        }
                    }
                }
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            if (receiver instanceof Named named && named.name().equals("stdio.stdout") && member.member().equals("write")) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) return Unknown.INSTANCE;
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Unknown.INSTANCE;

            if (receiver instanceof SingletonProxy proxy) {
                Ast.ClassDecl klass = findClass(proxy.target().name());
                if (klass == null) throw new IllegalArgumentException("unknown singleton proxy class '" + proxy.target().name() + "'");
                if (findFieldType(klass, member.member(), new LinkedHashSet<>()) != null) {
                    throw new IllegalArgumentException("singleton object fields are actor-private; invoke a public method on "
                            + proxy.moduleName() + "." + proxy.fieldName());
                }
                if (!findMethodsByName(klass, member.member(), new LinkedHashSet<>()).isEmpty()) {
                    throw new IllegalArgumentException("singleton proxy method values cannot be extracted; call and await "
                            + proxy.moduleName() + "." + proxy.fieldName() + "." + member.member() + "(...) directly");
                }
                throw new IllegalArgumentException("unknown singleton proxy member '" + member.member() + "'");
            }

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
                Ast.FieldDecl staticField = findStaticField(klass, member.member(), new LinkedHashSet<>());
                if (staticField != null) {
                    return resolve(staticField.type(), Set.copyOf(klass.genericParameters()), null);
                }
                List<Ast.MethodDecl> functions = findStaticFunctionsByName(klass, member.member(), new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return functionType(fn.parameters(), fn.returnType(), Set.copyOf(fn.genericParameters()), null);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function '" + member.member() + "' must be called so arity can select the overload");
                throw new IllegalArgumentException("unknown static member '" + member.member() + "' on " + klass.name());
            }

            if (receiver instanceof Record record) {
                Type result = record.members().get(member.member());
                if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
                return result;
            }
            if (receiver instanceof Named named) {
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                    if (field != null) return field;
                    List<Ast.MethodDecl> methods = findMethodsByName(klass, member.member(), new LinkedHashSet<>());
                    if (methods.size() == 1) {
                        Ast.MethodDecl method = methods.getFirst();
                        return functionType(method.parameters(), method.returnType(), Set.copyOf(method.genericParameters()), named);
                    }
                    if (methods.size() > 1) throw new IllegalArgumentException("overloaded method '" + member.member() + "' must be called so arity can select the overload");
                }
            }
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.IndexExpr indexed) {
            Type receiver = deref(typeOf(indexed.receiver(), env, generics, self));
            Type index = typeOf(indexed.index(), env, generics, self);
            requireAssignable(index, Primitive.INT, "array/list index");
            if (receiver instanceof ListType list) return list.element();
            if (receiver instanceof Tuple tuple) return tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
            throw new IllegalArgumentException("indexing requires an array/list or tuple");
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = findClass(created.type().name());
            if (klass == null) return new Named(created.type().name(), created.type().arguments().stream().map(a -> resolve(a, generics, self)).toList());
            String ownerName = classOwners.get(klass);
            Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
            if (owner != null && owner.singleton() && !owner.name().equals(env.moduleName)) {
                throw new IllegalArgumentException("class '" + klass.name()
                        + "' is actor-private inside singleton module '" + owner.name() + "'");
            }
            Type nominal = nominalClassType(klass);
            boolean hasExplicitConstructors = klass.methods().stream().anyMatch(this::isConstructor);
            if (hasExplicitConstructors) {
                Ast.MethodDecl constructor = findConstructor(klass, created.arguments().size());
                if (constructor == null) {
                    throw new IllegalArgumentException("no constructor for " + klass.name() + " with arity " + created.arguments().size());
                }
                Set<String> constructorGenerics = Set.copyOf(klass.genericParameters());
                for (int i = 0; i < created.arguments().size(); i++) {
                    Type expected = resolveParam(constructor.parameters().get(i), constructorGenerics, nominal);
                    requireAssignable(typeOf(created.arguments().get(i), env, generics, self), expected,
                            "constructor argument " + (i + 1));
                }
                return nominal;
            }

            // Compatibility path for pre-constructor Oreslang: positional args
            // still initialize instance fields when no explicit constructor exists.
            List<Ast.FieldDecl> fields = effectiveFields(klass, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDecl field = fields.get(i);
                if (i < created.arguments().size()) {
                    requireAssignable(typeOf(created.arguments().get(i), env, generics, self),
                            resolve(field.type(), Set.copyOf(klass.genericParameters()), nominal), "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            Type element = typeOf(list.elements().getFirst(), env, generics, self);
            for (int i = 1; i < list.elements().size(); i++) element = commonType(element, typeOf(list.elements().get(i), env, generics, self));
            return new ListType(element);
        }
        if (expr instanceof Ast.TupleExpr tuple) {
            return new Tuple(tuple.elements().stream().map(item -> typeOf(item, env, generics, self)).toList());
        }
        if (expr instanceof Ast.ObjectExpr object) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Ast.ObjectField field : object.fields()) {
                if (members.putIfAbsent(field.name(), typeOf(field.value(), env, generics, self)) != null) {
                    throw new IllegalArgumentException("duplicate obj field '" + field.name() + "'");
                }
            }
            return new Record(members);
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            // Constructor initialization authority is lexical/dynamic to the
            // constructor body. A closure may capture self as a value, but it
            // never inherits permission to initialize val fields later.
            Env lambdaEnv = new Env(env, env.moduleName, null);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolveParam(param, generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            if (lambda.expressionBody() != null) {
                throw new IllegalArgumentException("expression-body lambdas are not supported; lambdas require braces and explicit return");
            }
            checkBlock(lambda.blockBody(), lambdaEnv, generics, Unknown.INSTANCE, self);
            return new Function(parameters, Unknown.INSTANCE);
        }
        return Unknown.INSTANCE;
    }

    private void validateLambdaArgument(Ast.Expr argument, Type expected, Env env, Set<String> generics, Type self) {
        if (argument instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
        }
    }

    private void validateLambdaAgainstExpected(Ast.LambdaExpr lambda, Function expected, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("lambdas always require a block body and explicit return for non-void results");
        }
        if (lambda.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("lambda arity " + lambda.parameters().size() + " does not match expected function arity " + expected.parameters().size());
        }
        Env lambdaEnv = new Env(parent, parent.moduleName, null);
        for (int i = 0; i < lambda.parameters().size(); i++) {
            Ast.Param param = lambda.parameters().get(i);
            Type expectedParam = expected.parameters().get(i);
            Type declared = param.type().name().equals("$infer$") ? expectedParam : resolveParam(param, generics, self);
            requireAssignable(expectedParam, declared, "lambda parameter " + param.name());
            requireAssignable(declared, expectedParam, "lambda parameter " + param.name());
            lambdaEnv.define(param.name(), declared, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        }
        checkBlock(lambda.blockBody(), lambdaEnv, generics, expected.result(), self);
        if (expected.result() != Primitive.VOID && !definitelyReturns(lambda.blockBody())) {
            throw new IllegalArgumentException("non-void lambda must explicitly return on every path");
        }
    }

    private void validateSingletonTransportStatements(List<Ast.Stmt> statements, String currentModule) {
        for (Ast.Stmt stmt : statements) {
            if (stmt instanceof Ast.BindingStmt binding) validateSingletonTransportExpr(binding.initializer(), currentModule);
            else if (stmt instanceof Ast.DestructureStmt destructure) validateSingletonTransportExpr(destructure.initializer(), currentModule);
            else if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) validateSingletonTransportExpr(ret.value(), currentModule);
            else if (stmt instanceof Ast.ExprStmt expression) validateSingletonTransportExpr(expression.expression(), currentModule);
            else if (stmt instanceof Ast.DeferStmt defer) validateSingletonTransportExpr(defer.expression(), currentModule);
            else if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) {
                    validateSingletonTransportExpr(branch.condition(), currentModule);
                    validateSingletonTransportStatements(branch.body(), currentModule);
                }
                validateSingletonTransportStatements(conditional.elseBody(), currentModule);
            } else if (stmt instanceof Ast.TryStmt attempted) {
                validateSingletonTransportStatements(attempted.body(), currentModule);
                validateSingletonTransportStatements(attempted.catchBody(), currentModule);
                validateSingletonTransportStatements(attempted.finallyBody(), currentModule);
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                validateSingletonTransportExpr(loop.iterable(), currentModule);
                validateSingletonTransportStatements(loop.body(), currentModule);
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (loop.initializer() != null) validateSingletonTransportStatements(List.of(loop.initializer()), currentModule);
                if (loop.condition() != null) validateSingletonTransportExpr(loop.condition(), currentModule);
                if (loop.update() != null) validateSingletonTransportExpr(loop.update(), currentModule);
                validateSingletonTransportStatements(loop.body(), currentModule);
            }
        }
    }

    private void validateSingletonTransportExpr(Ast.Expr expr, String currentModule) {
        if (expr instanceof Ast.AwaitExpr awaited) {
            if (awaited.expression() instanceof Ast.CallExpr call && isExternalSingletonCall(call, currentModule)) {
                validateSingletonTransportCallChildren(call, currentModule);
                return;
            }
            validateSingletonTransportExpr(awaited.expression(), currentModule);
            return;
        }
        if (expr instanceof Ast.CallExpr call) {
            if (isExternalSingletonCall(call, currentModule)) {
                throw new IllegalArgumentException("cross-singleton calls must be immediately awaited so they cannot outlive"
                        + " the caller context or leave an untracked mailbox wait");
            }
            validateSingletonTransportCallChildren(call, currentModule);
            return;
        }
        if (expr instanceof Ast.AssignExpr assignment) {
            validateSingletonTransportExpr(assignment.target(), currentModule);
            validateSingletonTransportExpr(assignment.value(), currentModule);
        } else if (expr instanceof Ast.BinaryExpr binary) {
            validateSingletonTransportExpr(binary.left(), currentModule);
            validateSingletonTransportExpr(binary.right(), currentModule);
        } else if (expr instanceof Ast.UnaryExpr unary) {
            validateSingletonTransportExpr(unary.operand(), currentModule);
        } else if (expr instanceof Ast.ConditionalExpr conditional) {
            validateSingletonTransportExpr(conditional.condition(), currentModule);
            validateSingletonTransportExpr(conditional.whenTrue(), currentModule);
            validateSingletonTransportExpr(conditional.whenFalse(), currentModule);
        } else if (expr instanceof Ast.MemberExpr member) {
            if (isExternalSingletonFunctionMember(member, currentModule)) {
                throw new IllegalArgumentException("singleton service function values cannot be extracted; call and await "
                        + ((Ast.NameExpr) member.receiver()).name() + "." + member.member() + "(...) directly");
            }
            validateSingletonTransportExpr(member.receiver(), currentModule);
        } else if (expr instanceof Ast.IndexExpr indexed) {
            validateSingletonTransportExpr(indexed.receiver(), currentModule);
            validateSingletonTransportExpr(indexed.index(), currentModule);
        } else if (expr instanceof Ast.NewExpr created) {
            for (Ast.Expr argument : created.arguments()) validateSingletonTransportExpr(argument, currentModule);
        } else if (expr instanceof Ast.ListExpr list) {
            for (Ast.Expr item : list.elements()) validateSingletonTransportExpr(item, currentModule);
        } else if (expr instanceof Ast.TupleExpr tuple) {
            for (Ast.Expr item : tuple.elements()) validateSingletonTransportExpr(item, currentModule);
        } else if (expr instanceof Ast.ObjectExpr object) {
            for (Ast.ObjectField field : object.fields()) validateSingletonTransportExpr(field.value(), currentModule);
        } else if (expr instanceof Ast.LambdaExpr lambda) {
            if (lambda.expressionBody() != null) validateSingletonTransportExpr(lambda.expressionBody(), currentModule);
            if (lambda.blockBody() != null) validateSingletonTransportStatements(lambda.blockBody(), currentModule);
        }
    }

    private void validateSingletonTransportCallChildren(Ast.CallExpr call, String currentModule) {
        // Immediate awaited singleton calls are transport operations, not
        // extraction of a first-class service function.
        if (!isExternalSingletonCall(call, currentModule)) {
            validateSingletonTransportExpr(call.callee(), currentModule);
        }
        for (Ast.Expr argument : call.arguments()) validateSingletonTransportExpr(argument, currentModule);
    }

    private boolean isExternalSingletonFunctionMember(Ast.MemberExpr member, String currentModule) {
        if (!(member.receiver() instanceof Ast.NameExpr namespace)) return false;
        Ast.ModuleDecl owner = modules.get(namespace.name());
        if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
        for (Ast.Decl decl : owner.declarations()) {
            if (decl instanceof Ast.FunctionDecl fn
                    && fn.visibility() == Ast.Visibility.PUBLIC
                    && fn.name().equals(member.member())) return true;
        }
        return false;
    }

    private boolean isExternalSingletonCall(Ast.CallExpr call, String currentModule) {
        if (call.callee() instanceof Ast.NameExpr name) {
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn == null) return false;
            String ownerName = functionOwners.get(fn);
            Ast.ModuleDecl owner = ownerName == null ? null : modules.get(ownerName);
            return owner != null && owner.singleton() && !owner.name().equals(currentModule);
        }
        if (call.callee() instanceof Ast.MemberExpr member
                && member.receiver() instanceof Ast.NameExpr namespace) {
            Ast.ModuleDecl owner = modules.get(namespace.name());
            if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
            for (Ast.Decl decl : owner.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.name().equals(member.member())) return true;
            }
        }
        if (call.callee() instanceof Ast.MemberExpr method
                && method.receiver() instanceof Ast.MemberExpr exported
                && exported.receiver() instanceof Ast.NameExpr namespace) {
            Ast.ModuleDecl owner = modules.get(namespace.name());
            if (owner == null || !owner.singleton() || owner.name().equals(currentModule)) return false;
            for (Ast.Decl decl : owner.declarations()) {
                if (decl instanceof Ast.FieldDecl field
                        && field.visibility() == Ast.Visibility.PUBLIC
                        && field.name().equals(exported.member())
                        && singletonProxyClass(field) != null) {
                    return true;
                }
            }
        }
        return false;
    }

    private Type deref(Type type) {
        return type instanceof Borrow borrow ? borrow.target() : type;
    }

    private Type memberType(Ast.MemberExpr member, Env env, Set<String> generics, Type self) {
        Type receiver = deref(typeOf(member.receiver(), env, generics, self));
        if (receiver instanceof Record record) {
            Type result = record.members().get(member.member());
            if (result == null) throw new IllegalArgumentException("unknown structural member '" + member.member() + "'");
            return result;
        }
        if (receiver instanceof ClassNamespace classNamespace) {
            Ast.ClassDecl klass = findClass(classNamespace.className());
            Ast.FieldDecl field = klass == null ? null : findStaticField(klass, member.member(), new LinkedHashSet<>());
            if (field == null) throw new IllegalArgumentException("unknown static field '" + member.member() + "'");
            if (field.bindingKind() != Ast.BindingKind.LET) {
                throw new IllegalArgumentException("cannot reassign " + field.bindingKind().name().toLowerCase()
                        + " static field '" + klass.name() + "." + field.name() + "'");
            }
            return resolve(field.type(), Set.copyOf(klass.genericParameters()), null);
        }
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Ast.FieldDecl field = findInstanceField(klass, member.member(), new LinkedHashSet<>());
                if (field != null) {
                    if (field.bindingKind() != Ast.BindingKind.LET
                            && !canInitializeValField(member, klass, field, env)) {
                        throw new IllegalArgumentException("cannot reassign " + field.bindingKind().name().toLowerCase()
                                + " field '" + klass.name() + "." + field.name()
                                + "'; field '" + klass.name() + "." + field.name() + "' is immutable");
                    }
                    return resolve(field.type(), Set.copyOf(klass.genericParameters()), named);
                }
            }
        }
        throw new IllegalArgumentException("assignment target '" + member.member() + "' is not a mutable data field");
    }

    private Type iterableElementType(Type iterable) {
        if (iterable instanceof ListType list) return list.element();
        if (iterable instanceof Tuple tuple) {
            Type result = Unknown.INSTANCE;
            for (Type element : tuple.elements()) result = result == Unknown.INSTANCE ? element : commonType(result, element);
            return result;
        }
        if (iterable instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Ast.MethodDecl iterator = findMethod(klass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator != null) {
                    Type result = resolve(iterator.returnType(), Set.copyOf(iterator.genericParameters()), named);
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    throw new IllegalArgumentException("[Symbol.iterator]() must return Array<T>, List<T>, or a tuple in v0");
                }
            }
        }
        throw new IllegalArgumentException("for-of requires an array/list, tuple, or a class with [Symbol.iterator]()");
    }

    private Type commonType(Type a, Type b) {
        if (assignable(b, a) && assignable(a, b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private boolean isStringLike(Type type) {
        return type == Primitive.STRING || type instanceof StringLiteral;
    }

    private Record classShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        Record cached = classShapeCache.get(klass);
        if (cached != null) return cached;
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                for (Map.Entry<String, Type> inherited : classShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "multiple inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (!field.isStatic()) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() || isConstructor(method)) continue;
            mergeMember(members, methodKey(method.name(), method.arity()),
                    functionType(method.parameters(), method.returnType(), generics, self), "class " + klass.name());
        }
        stack.remove(klass);
        Record result = new Record(members);
        classShapeCache.put(klass, result);
        return result;
    }

    private Record publicClassShape(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) {
                for (Map.Entry<String, Type> inherited : publicClassShape(parent, stack).members().entrySet()) {
                    mergeMember(members, inherited.getKey(), inherited.getValue(), "public inheritance of " + klass.name());
                }
            }
        }
        Set<String> generics = Set.copyOf(klass.genericParameters());
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (!field.isStatic() && field.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
            }
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && !isConstructor(method) && method.visibility() == Ast.Visibility.PUBLIC) {
                mergeMember(members, methodKey(method.name(), method.arity()),
                        functionType(method.parameters(), method.returnType(), generics, self), "class " + klass.name());
            }
        }
        stack.remove(klass);
        return new Record(members);
    }

    private Record interfaceShape(Ast.InterfaceDecl iface, Set<String> generics, Set<Ast.InterfaceDecl> stack) {
        if (!stack.add(iface)) throw new IllegalArgumentException("interface inheritance cycle involving '" + iface.name() + "'");
        Map<String, Type> members = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : iface.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent == null) throw new IllegalArgumentException("unknown parent interface '" + parentRef.name() + "' for " + iface.name());
            for (Map.Entry<String, Type> inherited : interfaceShape(parent, Set.copyOf(parent.genericParameters()), stack).members().entrySet()) {
                mergeMember(members, inherited.getKey(), inherited.getValue(), "interface inheritance of " + iface.name());
            }
        }
        for (Ast.InterfaceMember member : iface.members()) {
            if (member instanceof Ast.InterfaceFunctionDecl fn) {
                Set<String> all = new HashSet<>(generics);
                all.addAll(fn.genericParameters());
                mergeMember(members, methodKey(fn.name(), fn.parameters().size()),
                        functionType(fn.parameters(), fn.returnType(), all, null), "interface " + iface.name());
            } else if (member instanceof Ast.InterfaceFieldDecl field) {
                mergeMember(members, field.name(), resolve(field.type(), generics, null), "interface " + iface.name());
            }
        }
        stack.remove(iface);
        return new Record(members);
    }

    private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> stack) {
        if (!stack.add(klass)) throw new IllegalArgumentException("inheritance cycle involving class '" + klass.name() + "'");
        LinkedHashMap<String, Ast.FieldDecl> fields = new LinkedHashMap<>();
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null) for (Ast.FieldDecl field : effectiveFields(parent, stack)) fields.putIfAbsent(field.name(), field);
        }
        for (Ast.FieldDecl field : klass.fields()) if (!field.isStatic()) fields.put(field.name(), field);
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private Type findFieldType(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        Ast.FieldDecl field = findInstanceField(klass, name, seen);
        if (field == null) return null;
        return resolve(field.type(), Set.copyOf(klass.genericParameters()), nominalClassType(klass));
    }

    private Ast.FieldDecl findInstanceField(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.FieldDecl field : klass.fields()) {
            if (!field.isStatic() && field.name().equals(name)) {
                seen.remove(klass);
                return field;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.FieldDecl result = findInstanceField(parent, name, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.FieldDecl findStaticField(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        // Static data belongs to the class namespace that declares it. Unlike
        // instance fields/methods it is deliberately not inherited through the
        // instance layout/MRO.
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.isStatic() && field.name().equals(name)) return field;
        }
        return null;
    }

    private boolean isConstructor(Ast.MethodDecl method) {
        return !method.isStatic() && method.name().equals("constructor");
    }

    private Ast.MethodDecl findConstructor(Ast.ClassDecl klass, int arity) {
        for (Ast.MethodDecl method : klass.methods()) {
            if (isConstructor(method) && method.arity() == arity) return method;
        }
        return null;
    }

    private boolean hasConstructorInHierarchy(Ast.ClassDecl klass, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        if (klass.methods().stream().anyMatch(this::isConstructor)) return true;
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null && hasConstructorInHierarchy(parent, seen)) return true;
        }
        return false;
    }

    private boolean canInitializeValField(
            Ast.MemberExpr member,
            Ast.ClassDecl klass,
            Ast.FieldDecl field,
            Env env) {
        return field.bindingKind() == Ast.BindingKind.VAL
                && field.initializer() == null
                && member.receiver() instanceof Ast.NameExpr name
                && name.name().equals("self")
                && env.constructorClass == klass;
    }

    private boolean referencesClassGeneric(Ast.TypeRef type, Set<String> classGenerics) {
        if (type == null) return false;
        if (type.isBorrow()) return referencesClassGeneric(type.borrowedTarget(), classGenerics);
        if (classGenerics.contains(type.name())) return true;
        for (Ast.TypeRef argument : type.arguments()) {
            if (referencesClassGeneric(argument, classGenerics)) return true;
        }
        return false;
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && !isConstructor(method) && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findMethod(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findMethodsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> methods = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (!method.isStatic() && !isConstructor(method) && method.name().equals(name)) methods.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl method : findMethodsByName(parent, name, seen)) methods.putIfAbsent(method.arity(), method);
        }
        seen.remove(klass);
        return List.copyOf(methods.values());
    }

    private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic() && method.name().equals(name) && method.arity() == arity) {
                seen.remove(klass);
                return method;
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Ast.MethodDecl candidate = findStaticFunction(parent, name, arity, seen);
            if (candidate != null) {
                seen.remove(klass);
                return candidate;
            }
        }
        seen.remove(klass);
        return null;
    }

    private List<Ast.MethodDecl> findStaticFunctionsByName(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return List.of();
        LinkedHashMap<Integer, Ast.MethodDecl> functions = new LinkedHashMap<>();
        for (Ast.MethodDecl method : klass.methods()) if (method.isStatic() && method.name().equals(name)) functions.put(method.arity(), method);
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) functions.putIfAbsent(fn.arity(), fn);
        }
        seen.remove(klass);
        return List.copyOf(functions.values());
    }

    private Ast.ClassDecl resolveClassParent(Ast.TypeRef parentRef, Ast.ClassDecl child) {
        if (parentRef.name().equals("Object") || parentRef.name().equals("List")) return null;
        if (parentRef.name().equals("obj") || parentRef.name().equals("arr")) throw new IllegalArgumentException("inline obj/arr values cannot be subclassed; extend Object or List instead");
        Ast.ClassDecl parent = findClass(parentRef.name());
        if (parent == null) throw new IllegalArgumentException("unknown parent class '" + parentRef.name() + "' for " + child.name());
        if (parent == child) throw new IllegalArgumentException("class '" + child.name() + "' cannot extend itself");

        String parentOwnerName = classOwners.get(parent);
        Ast.ModuleDecl parentOwner = parentOwnerName == null ? null : modules.get(parentOwnerName);
        String childOwnerName = classOwners.get(child);
        if (parentOwner != null && parentOwner.singleton()
                && !java.util.Objects.equals(parentOwnerName, childOwnerName)) {
            throw new IllegalArgumentException("class '" + parent.name()
                    + "' is actor-private inside singleton module '" + parentOwner.name()
                    + "' and cannot be inherited outside that module");
        }
        return parent;
    }

    private void mergeMember(Map<String, Type> members, String name, Type type, String owner) {
        Type existing = members.get(name);
        if (existing != null && (!assignable(existing, type) || !assignable(type, existing))) {
            throw new IllegalArgumentException("conflicting member '" + name + "' in " + owner + ": " + existing + " vs " + type);
        }
        members.put(name, type);
    }

    private Function functionType(List<Ast.Param> params, Ast.TypeRef returns, Set<String> generics, Type self) {
        return new Function(params.stream().map(p -> resolveParam(p, generics, self)).toList(), resolve(returns, generics, self));
    }

    private Type resolveParam(Ast.Param param, Set<String> generics, Type self) {
        if (!param.structural()) return resolve(param.type(), generics, self);
        Ast.InterfaceDecl iface = findInterface(param.type().name());
        if (iface != null) return interfaceShape(iface, Set.copyOf(iface.genericParameters()), new LinkedHashSet<>());
        Ast.ClassDecl klass = findClass(param.type().name());
        if (klass != null) return publicClassShape(klass, new LinkedHashSet<>());
        throw new IllegalArgumentException("@Structural requires a known class or interface type, got '" + param.type().name() + "'");
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self) {
        return resolve(ref, generics, self, false);
    }

    private Type resolve(Ast.TypeRef ref, Set<String> generics, Type self, boolean allowNullMarker) {
        if (ref == null) return Unknown.INSTANCE;
        if (ref.isBorrow()) return new Borrow(resolve(ref.borrowedTarget(), generics, self, allowNullMarker), ref.mutableBorrow());
        if (ref.isStringLiteral()) return new StringLiteral(ref.stringLiteralValue());
        if (ref.name().equals("$infer$")) return Unknown.INSTANCE;
        if (ref.name().equals("self")) return self == null ? Unknown.INSTANCE : self;
        if (ref.name().equals("null")) {
            if (!allowNullMarker) throw new IllegalArgumentException("null is not a standalone type; it is only legal as Option<null>");
            return Primitive.NULL;
        }
        if (generics.contains(ref.name())) return new Generic(ref.name());

        Ast.TypeAliasDecl alias = findTypeAlias(ref.name());
        if (alias != null) {
            if (alias.genericParameters().size() != ref.arguments().size()) {
                throw new IllegalArgumentException("type alias '" + alias.name() + "' expects " + alias.genericParameters().size()
                        + " type argument(s), got " + ref.arguments().size());
            }
            if (!resolvingAliases.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
            try {
                Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                for (int i = 0; i < alias.genericParameters().size(); i++) {
                    substitutions.put(alias.genericParameters().get(i), ref.arguments().get(i));
                }
                return resolve(substituteAliasType(alias.target(), substitutions), generics, self, allowNullMarker);
            } finally {
                resolvingAliases.remove(alias);
            }
        }

        return switch (ref.name()) {
            case "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64", "int", "uint", "bigint" -> Primitive.INT;
            case "f32", "f64", "float" -> Primitive.FLOAT;
            case "decimal" -> Primitive.DECIMAL;
            case "complex64", "complex128", "complex" -> Primitive.COMPLEX;
            case "bool", "Bool" -> Primitive.BOOL;
            case "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "Array", "List" -> {
                if (!ref.inferArguments() && ref.arguments().size() != 1) throw new IllegalArgumentException(ref.name() + " requires exactly one type argument");
                yield new ListType(ref.arguments().isEmpty() ? Unknown.INSTANCE : resolve(ref.arguments().getFirst(), generics, self));
            }
            case "Option" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) throw new IllegalArgumentException("Option requires exactly one explicit type argument");
                Type element = resolve(ref.arguments().getFirst(), generics, self, true);
                if (element == Primitive.VOID) throw new IllegalArgumentException("Option<void> is invalid; use void for no return value");
                yield new Named("Option", List.of(element));
            }
            case "Fnc" -> {
                List<Type> args = ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList();
                if (args.isEmpty()) throw new IllegalArgumentException("Fnc requires at least a result type");
                yield new Function(args.subList(0, args.size() - 1), args.getLast());
            }
            default -> new Named(ref.name(), ref.arguments().stream().map(arg -> resolve(arg, generics, self)).toList());
        };
    }

    private Ast.TypeRef substituteAliasType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
        Ast.TypeRef replacement = substitutions.get(ref.name());
        if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
        return new Ast.TypeRef(
                ref.name(),
                ref.arguments().stream().map(arg -> substituteAliasType(arg, substitutions)).toList(),
                ref.inferArguments());
    }

    private Type nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private String qualifiedClassName(Ast.ClassDecl klass) {
        String owner = classOwners.get(klass);
        return owner == null || owner.equals("__root__") ? klass.name() : owner + "." + klass.name();
    }

    private String qualifiedInterfaceName(Ast.InterfaceDecl iface) {
        String owner = interfaceOwners.get(iface);
        return owner == null || owner.equals("__root__") ? iface.name() : owner + "." + iface.name();
    }

    private boolean assignable(Type actual, Type expected) {
        if (Types.isAssignable(actual, expected)) return true;

        if (actual instanceof Named actualNamed && expected instanceof Record targetShape) {
            Ast.ClassDecl klass = findClass(actualNamed.name());
            return klass != null && Types.isAssignable(publicClassShape(klass, new LinkedHashSet<>()), targetShape);
        }

        if (actual instanceof Named actualNamed && expected instanceof Named expectedNamed) {
            Ast.ClassDecl actualClass = findClass(actualNamed.name());
            if (actualClass != null) {
                Ast.ClassDecl expectedClass = findClass(expectedNamed.name());
                if (expectedClass != null && classExtends(actualClass, expectedClass, new LinkedHashSet<>())) return true;
                Ast.InterfaceDecl expectedInterface = findInterface(expectedNamed.name());
                if (expectedInterface != null && classImplements(actualClass, expectedInterface, new LinkedHashSet<>())) return true;
            }
        }
        return false;
    }

    private boolean classExtends(Ast.ClassDecl actual, Ast.ClassDecl expected, Set<Ast.ClassDecl> seen) {
        if (actual == expected) return true;
        if (!seen.add(actual)) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, actual);
            if (parent != null && classExtends(parent, expected, seen)) return true;
        }
        return false;
    }

    private boolean classImplements(Ast.ClassDecl klass, Ast.InterfaceDecl expected, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return false;
        for (Ast.TypeRef ifaceRef : klass.interfaces()) {
            Ast.InterfaceDecl iface = findInterface(ifaceRef.name());
            if (iface != null && (iface == expected || interfaceExtends(iface, expected, new LinkedHashSet<>()))) return true;
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent != null && classImplements(parent, expected, seen)) return true;
        }
        return false;
    }

    private boolean interfaceExtends(Ast.InterfaceDecl actual, Ast.InterfaceDecl expected, Set<Ast.InterfaceDecl> seen) {
        if (actual == expected) return true;
        if (!seen.add(actual)) return false;
        for (Ast.TypeRef parentRef : actual.parents()) {
            Ast.InterfaceDecl parent = findInterface(parentRef.name());
            if (parent != null && interfaceExtends(parent, expected, seen)) return true;
        }
        return false;
    }

    private Ast.FunctionDecl findFunction(String name) {
        if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function/routine name '" + name + "'; qualify it with its module");
        return functions.get(name);
    }

    private Ast.ClassDecl findClass(String name) {
        if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class name '" + name + "'; qualify it with its module");
        return classes.get(name);
    }

    private Ast.InterfaceDecl findInterface(String name) {
        if (ambiguousInterfaces.contains(name)) throw new IllegalArgumentException("ambiguous interface name '" + name + "'; qualify it with its module");
        return interfaces.get(name);
    }

    private Ast.TypeAliasDecl findTypeAlias(String name) {
        if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias '" + name + "'; qualify it with its module");
        return typeAliases.get(name);
    }

    private Set<String> uniqueGenerics(List<String> names, String owner) {
        Set<String> result = new HashSet<>();
        for (String name : names) if (!result.add(name)) throw new IllegalArgumentException("duplicate generic '" + name + "' in " + owner);
        return result;
    }

    private boolean constant(Ast.Expr expr) {
        if (expr instanceof Ast.LiteralExpr) return true;
        if (expr instanceof Ast.UnaryExpr unary) return constant(unary.operand());
        if (expr instanceof Ast.BinaryExpr binary) return constant(binary.left()) && constant(binary.right());
        if (expr instanceof Ast.ConditionalExpr conditional) return constant(conditional.condition()) && constant(conditional.whenTrue()) && constant(conditional.whenFalse());
        if (expr instanceof Ast.ListExpr list) return list.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().allMatch(this::constant);
        if (expr instanceof Ast.ObjectExpr object) return object.fields().stream().allMatch(field -> constant(field.value()));
        return false;
    }

    private boolean definitelyReturns(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                boolean allBranches = !conditional.branches().isEmpty()
                        && conditional.branches().stream().allMatch(branch -> definitelyReturns(branch.body()))
                        && !conditional.elseBody().isEmpty()
                        && definitelyReturns(conditional.elseBody());
                if (allBranches) return true;
            }
            if (stmt instanceof Ast.TryStmt attempted) {
                if (definitelyReturns(attempted.finallyBody())) return true;
                if (definitelyReturns(attempted.body()) && definitelyReturns(attempted.catchBody())) return true;
            }
        }
        return false;
    }

    private boolean hasInferredArgs(Ast.TypeRef ref) {
        return ref.inferArguments() || ref.arguments().stream().anyMatch(this::hasInferredArgs);
    }

    private void requireAssignable(Type actual, Type expected, String where) {
        if (!assignable(actual, expected)) throw new IllegalArgumentException(where + " has type " + actual + " but expected " + expected);
    }

    private String methodKey(String name, int arity) {
        return name + "$arity" + arity;
    }

    private void validateRoutineRecursion() {
        for (Ast.FunctionDecl fn : functionOwners.keySet()) {
            if (fn.kind() != Ast.CallableKind.ROUTINE) continue;
            if (reaches(fn, fn, new LinkedHashSet<>())) {
                throw new IllegalArgumentException("routine '" + functionOwners.get(fn) + "." + fn.name()
                        + "' participates in recursion; routines are non-recursive by contract (use fnc or a lambda)");
            }
        }
    }

    private boolean reaches(Ast.FunctionDecl current, Ast.FunctionDecl target, Set<Ast.FunctionDecl> path) {
        if (!path.add(current)) return false;
        String module = functionOwners.get(current);
        for (Ast.FunctionDecl callee : directCallees(current.body(), module)) {
            if (callee == target) return true;
            if (reaches(callee, target, path)) return true;
        }
        path.remove(current);
        return false;
    }

    private Set<Ast.FunctionDecl> directCallees(List<Ast.Stmt> body, String module) {
        Set<Ast.FunctionDecl> result = new LinkedHashSet<>();
        for (Ast.Stmt stmt : body) collectCalls(stmt, module, result);
        return result;
    }

    private void collectCalls(Ast.Stmt stmt, String module, Set<Ast.FunctionDecl> out) {
        if (stmt instanceof Ast.BindingStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.DestructureStmt s) collectCalls(s.initializer(), module, out);
        else if (stmt instanceof Ast.ReturnStmt s && s.value() != null) collectCalls(s.value(), module, out);
        else if (stmt instanceof Ast.ExprStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.DeferStmt s) collectCalls(s.expression(), module, out);
        else if (stmt instanceof Ast.IfStmt s) {
            for (Ast.IfBranch b : s.branches()) {
                collectCalls(b.condition(), module, out);
                for (Ast.Stmt nested : b.body()) collectCalls(nested, module, out);
            }
            for (Ast.Stmt nested : s.elseBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.TryStmt s) {
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.catchBody()) collectCalls(nested, module, out);
            for (Ast.Stmt nested : s.finallyBody()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForOfStmt s) {
            collectCalls(s.iterable(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        } else if (stmt instanceof Ast.ForStmt s) {
            if (s.initializer() != null) collectCalls(s.initializer(), module, out);
            if (s.condition() != null) collectCalls(s.condition(), module, out);
            if (s.update() != null) collectCalls(s.update(), module, out);
            for (Ast.Stmt nested : s.body()) collectCalls(nested, module, out);
        }
    }

    private void collectCalls(Ast.Expr expr, String module, Set<Ast.FunctionDecl> out) {
        if (expr instanceof Ast.CallExpr call) {
            Ast.FunctionDecl callee = resolveStaticCall(call.callee(), module);
            if (callee != null) out.add(callee);
            collectCalls(call.callee(), module, out);
            for (Ast.Expr arg : call.arguments()) collectCalls(arg, module, out);
        } else if (expr instanceof Ast.BinaryExpr e) {
            collectCalls(e.left(), module, out); collectCalls(e.right(), module, out);
        } else if (expr instanceof Ast.UnaryExpr e) collectCalls(e.operand(), module, out);
        else if (expr instanceof Ast.AssignExpr e) collectCalls(e.value(), module, out);
        else if (expr instanceof Ast.ConditionalExpr e) {
            collectCalls(e.condition(), module, out); collectCalls(e.whenTrue(), module, out); collectCalls(e.whenFalse(), module, out);
        } else if (expr instanceof Ast.MemberExpr e) collectCalls(e.receiver(), module, out);
        else if (expr instanceof Ast.IndexExpr e) { collectCalls(e.receiver(), module, out); collectCalls(e.index(), module, out); }
        else if (expr instanceof Ast.NewExpr e) for (Ast.Expr arg : e.arguments()) collectCalls(arg, module, out);
        else if (expr instanceof Ast.AwaitExpr e) collectCalls(e.expression(), module, out);
        else if (expr instanceof Ast.ListExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.TupleExpr e) for (Ast.Expr item : e.elements()) collectCalls(item, module, out);
        else if (expr instanceof Ast.ObjectExpr e) for (Ast.ObjectField f : e.fields()) collectCalls(f.value(), module, out);
        else if (expr instanceof Ast.LambdaExpr e) {
            if (e.expressionBody() != null) collectCalls(e.expressionBody(), module, out);
            if (e.blockBody() != null) for (Ast.Stmt nested : e.blockBody()) collectCalls(nested, module, out);
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

    private static final class Env {
        private final Env parent;
        private final String moduleName;
        private final Ast.ClassDecl constructorClass;
        private final Map<String, Binding> bindings = new HashMap<>();

        private Env(Env parent) {
            this(parent,
                    parent == null ? null : parent.moduleName,
                    parent == null ? null : parent.constructorClass);
        }

        private Env(Env parent, String moduleName) {
            this(parent, moduleName, parent == null ? null : parent.constructorClass);
        }

        private Env(Env parent, String moduleName, Ast.ClassDecl constructorClass) {
            this.parent = parent;
            this.moduleName = moduleName;
            this.constructorClass = constructorClass;
        }
        private void define(String name, Type type, Ast.BindingKind kind) {
            if (bindings.putIfAbsent(name, new Binding(type, kind)) != null) throw new IllegalArgumentException("duplicate binding '" + name + "'");
        }
        private Binding lookup(String name) {
            Binding binding = bindings.get(name);
            return binding != null ? binding : parent == null ? null : parent.lookup(name);
        }
        private void replace(String name, Type type, Ast.BindingKind kind) {
            if (!bindings.containsKey(name)) throw new IllegalArgumentException("unknown binding '" + name + "'");
            bindings.put(name, new Binding(type, kind));
        }
        private record Binding(Type type, Ast.BindingKind kind) { }
    }
}
