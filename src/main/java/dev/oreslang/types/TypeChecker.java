package dev.oreslang.types;

import dev.oreslang.ast.Ast;
import dev.oreslang.types.Types.Function;
import dev.oreslang.types.Types.Any;
import dev.oreslang.types.Types.Borrow;
import dev.oreslang.types.Types.ClassNamespace;
import dev.oreslang.types.Types.Generic;
import dev.oreslang.types.Types.ListType;
import dev.oreslang.types.Types.Named;
import dev.oreslang.types.Types.Never;
import dev.oreslang.types.Types.Primitive;
import dev.oreslang.types.Types.Record;
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
    private final IdentityHashMap<Ast.FunctionDecl, Type> inferredReturnTypes = new IdentityHashMap<>();
    private final Set<Ast.FunctionDecl> resolvingInferredReturns = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

    private static final int INFERENCE_LINE_LIMIT = 10;
    private static final int INFERENCE_COMPLEXITY_LIMIT = 192;

    private final Set<String> ambiguousFunctions = new HashSet<>();
    private final Set<String> ambiguousClasses = new HashSet<>();
    private final Set<String> ambiguousInterfaces = new HashSet<>();
    private final Set<String> ambiguousTypeAliases = new HashSet<>();
    private final Set<String> importedValues = new HashSet<>();
    private final Set<Ast.TypeAliasDecl> resolvingAliases = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<String, Function> singletonActorHandlers = new HashMap<>();

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
                importedValues.add(imported.namespace());
            } else {
                if (imported.names().isEmpty()) throw new IllegalArgumentException("named import must select at least one name");
                for (String name : imported.names()) {
                    if (!exposed.add(name)) throw new IllegalArgumentException("duplicate imported name '" + name + "'");
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
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.FunctionDecl fn) checkFunction(module.name(), fn);
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
                Type signature = functionType(fn);
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

    private void checkFunction(String module, Ast.FunctionDecl fn) {
        Set<String> generics = uniqueGenerics(fn.genericParameters(), (fn.kind() == Ast.CallableKind.ROUTINE ? "routine " : "function ") + fn.name());
        Env env = new Env(null, fn.nonLexical());
        for (Ast.Param param : fn.parameters()) env.define(param.name(), resolveParam(param, generics, null), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
        Type returns = callableReturnType(fn);
        checkBlock(fn.body(), env, generics, returns, null);
        if (returns != Primitive.VOID && !definitelyReturns(fn.body())) {
            throw new IllegalArgumentException("non-void " + fn.kind().name().toLowerCase() + " '" + module + "." + fn.name() + "' must explicitly return on every path");
        }
        if (fn.visibility() == Ast.Visibility.PUBLIC && hasInferredArgs(fn.returnType())) {
            throw new IllegalArgumentException("public callable '" + fn.name() + "' cannot export unresolved <> type arguments");
        }
    }


    private Type callableReturnType(Ast.FunctionDecl fn) {
        Set<String> generics = Set.copyOf(fn.genericParameters());
        if (!fn.returnType().name().equals("$infer$")) return resolve(fn.returnType(), generics, null);

        Type cached = inferredReturnTypes.get(fn);
        if (cached != null) return cached;

        if (fn.sourceLineSpan() >= INFERENCE_LINE_LIMIT || inferenceComplexity(fn.body()) > INFERENCE_COMPLEXITY_LIMIT) {
            Type fallback = hasValueReturn(fn.body()) ? Any.INSTANCE : Primitive.VOID;
            inferredReturnTypes.put(fn, fallback);
            return fallback;
        }

        if (!resolvingInferredReturns.add(fn)) return Any.INSTANCE;
        try {
            Env env = new Env(null, fn.nonLexical());
            for (Ast.Param param : fn.parameters()) {
                env.define(param.name(), resolveParam(param, generics, null),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            ReturnInference inference = new ReturnInference();
            inferBlockReturns(fn.body(), env, generics, null, inference);
            Type result = inference.type == null ? Primitive.VOID : inference.type;
            inferredReturnTypes.put(fn, result);
            return result;
        } finally {
            resolvingInferredReturns.remove(fn);
        }
    }

    private void inferBlockReturns(List<Ast.Stmt> body, Env parent, Set<String> generics, Type self, ReturnInference inference) {
        Env env = new Env(parent);
        for (Ast.Stmt stmt : body) inferStatementReturns(stmt, env, generics, self, inference);
    }

    private void inferStatementReturns(Ast.Stmt stmt, Env env, Set<String> generics, Type self, ReturnInference inference) {
        if (stmt instanceof Ast.BindingStmt binding) {
            Type declaredAhead = binding.declaredType() == null ? null : resolve(binding.declaredType(), generics, self);
            boolean recursiveLambda = binding.initializer() instanceof Ast.LambdaExpr;
            if (recursiveLambda && declaredAhead instanceof Function) env.define(binding.name(), declaredAhead, binding.kind());
            Type actual = declaredAhead == null
                    ? typeOf(binding.initializer(), env, generics, self)
                    : typeOfWithExpected(binding.initializer(), declaredAhead, env, generics, self);
            Type declared = declaredAhead == null
                    ? inferredBindingType(actual, binding.kind())
                    : declaredAhead;
            requireAssignable(actual, declared, "initializer for " + binding.name());
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
                    env.define(binding.name(), inferredBindingType(tuple.elements().get(i), binding.kind()), binding.kind());
                }
            } else if (source instanceof ListType list) {
                for (Ast.DestructureBinding binding : destructure.bindings()) {
                    env.define(binding.name(), inferredBindingType(list.element(), binding.kind()), binding.kind());
                }
            } else if (source == Any.INSTANCE) {
                for (Ast.DestructureBinding binding : destructure.bindings()) env.define(binding.name(), Any.INSTANCE, binding.kind());
            } else throw new IllegalArgumentException("destructuring requires a tuple or array/list value");
            return;
        }
        if (stmt instanceof Ast.ReturnStmt ret) {
            Type actual = ret.value() == null ? Primitive.VOID : typeOf(ret.value(), env, generics, self);
            inference.add(actual, this);
            return;
        }
        if (stmt instanceof Ast.ExprStmt expression) { typeOf(expression.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.DeferStmt defer) { typeOf(defer.expression(), env, generics, self); return; }
        if (stmt instanceof Ast.IfStmt conditional) {
            for (Ast.IfBranch branch : conditional.branches()) {
                requireAssignable(typeOf(branch.condition(), env, generics, self), Primitive.BOOL, "if condition");
                inferBlockReturns(branch.body(), env, generics, self, inference);
            }
            inferBlockReturns(conditional.elseBody(), env, generics, self, inference);
            return;
        }
        if (stmt instanceof Ast.TryStmt attempted) {
            inferBlockReturns(attempted.body(), env, generics, self, inference);
            Env caught = new Env(env);
            caught.define(attempted.errorName(), Unknown.INSTANCE, Ast.BindingKind.VAL);
            inferBlockReturns(attempted.catchBody(), caught, generics, self, inference);
            inferBlockReturns(attempted.finallyBody(), env, generics, self, inference);
            return;
        }
        if (stmt instanceof Ast.ForOfStmt loop) {
            Type iterable = typeOf(loop.iterable(), env, generics, self);
            Type element = iterable == Any.INSTANCE ? Any.INSTANCE : iterableElementType(iterable);
            Env loopEnv = new Env(env);
            loopEnv.define(loop.bindingName(), element, loop.bindingKind());
            inferBlockReturns(loop.body(), loopEnv, generics, self, inference);
            return;
        }
        if (stmt instanceof Ast.ForStmt loop) {
            Env loopEnv = new Env(env);
            if (loop.initializer() != null) inferStatementReturns(loop.initializer(), loopEnv, generics, self, inference);
            if (loop.condition() != null) requireAssignable(typeOf(loop.condition(), loopEnv, generics, self), Primitive.BOOL, "for condition");
            if (loop.update() != null) typeOf(loop.update(), loopEnv, generics, self);
            inferBlockReturns(loop.body(), loopEnv, generics, self, inference);
        }
    }

    private Type inferenceJoin(Type left, Type right) {
        if (left == null) return right;
        if ((left == Primitive.VOID) != (right == Primitive.VOID)) {
            throw new IllegalArgumentException("inferred return types cannot mix bare 'return;' with value-returning paths; add an explicit consistent return type");
        }
        if (left == Any.INSTANCE || right == Any.INSTANCE) return Any.INSTANCE;
        if (left.equals(right)) return left;
        if (Types.isNumeric(left) && Types.isNumeric(right)) return Types.numericJoin(left, right);
        if (isStringLike(left) && isStringLike(right)) return Primitive.STRING;
        if (left instanceof ListType a && right instanceof ListType b) return new ListType(inferenceJoin(a.element(), b.element()));
        if (left instanceof Tuple a && right instanceof Tuple b && a.elements().size() == b.elements().size()) {
            List<Type> joined = new ArrayList<>();
            for (int i = 0; i < a.elements().size(); i++) joined.add(inferenceJoin(a.elements().get(i), b.elements().get(i)));
            return new Tuple(joined);
        }
        if (left instanceof Named a && right instanceof Named b
                && a.name().equals(b.name()) && a.arguments().size() == b.arguments().size()) {
            List<Type> joined = new ArrayList<>();
            for (int i = 0; i < a.arguments().size(); i++) joined.add(inferenceJoin(a.arguments().get(i), b.arguments().get(i)));
            return new Named(a.name(), joined);
        }
        if (assignable(left, right)) return right;
        if (assignable(right, left)) return left;
        return Any.INSTANCE;
    }

    private boolean hasValueReturn(List<Ast.Stmt> body) {
        for (Ast.Stmt stmt : body) {
            if (stmt instanceof Ast.ReturnStmt ret && ret.value() != null) return true;
            if (stmt instanceof Ast.IfStmt conditional) {
                for (Ast.IfBranch branch : conditional.branches()) if (hasValueReturn(branch.body())) return true;
                if (hasValueReturn(conditional.elseBody())) return true;
            } else if (stmt instanceof Ast.TryStmt attempted) {
                if (hasValueReturn(attempted.body()) || hasValueReturn(attempted.catchBody())
                        || hasValueReturn(attempted.finallyBody())) return true;
            } else if (stmt instanceof Ast.ForOfStmt loop) {
                if (hasValueReturn(loop.body())) return true;
            } else if (stmt instanceof Ast.ForStmt loop) {
                if (hasValueReturn(loop.body())) return true;
            }
        }
        return false;
    }

    private int inferenceComplexity(List<Ast.Stmt> body) {
        int total = 0;
        for (Ast.Stmt stmt : body) {
            total += statementComplexity(stmt);
            if (total > INFERENCE_COMPLEXITY_LIMIT) return total;
        }
        return total;
    }

    private int statementComplexity(Ast.Stmt stmt) {
        if (stmt instanceof Ast.BindingStmt s) return 1 + expressionComplexity(s.initializer());
        if (stmt instanceof Ast.DestructureStmt s) return 1 + expressionComplexity(s.initializer());
        if (stmt instanceof Ast.ReturnStmt s) return 1 + (s.value() == null ? 0 : expressionComplexity(s.value()));
        if (stmt instanceof Ast.ExprStmt s) return 1 + expressionComplexity(s.expression());
        if (stmt instanceof Ast.DeferStmt s) return 1 + expressionComplexity(s.expression());
        if (stmt instanceof Ast.IfStmt s) {
            int n = 1;
            for (Ast.IfBranch b : s.branches()) n += expressionComplexity(b.condition()) + inferenceComplexity(b.body());
            return n + inferenceComplexity(s.elseBody());
        }
        if (stmt instanceof Ast.TryStmt s) return 1 + inferenceComplexity(s.body()) + inferenceComplexity(s.catchBody()) + inferenceComplexity(s.finallyBody());
        if (stmt instanceof Ast.ForOfStmt s) return 1 + expressionComplexity(s.iterable()) + inferenceComplexity(s.body());
        if (stmt instanceof Ast.ForStmt s) {
            return 1 + (s.initializer() == null ? 0 : statementComplexity(s.initializer()))
                    + (s.condition() == null ? 0 : expressionComplexity(s.condition()))
                    + (s.update() == null ? 0 : expressionComplexity(s.update()))
                    + inferenceComplexity(s.body());
        }
        return 1;
    }

    private int expressionComplexity(Ast.Expr expr) {
        if (expr == null) return 0;
        if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return 1;
        if (expr instanceof Ast.BinaryExpr e) return 1 + expressionComplexity(e.left()) + expressionComplexity(e.right());
        if (expr instanceof Ast.UnaryExpr e) return 1 + expressionComplexity(e.operand());
        if (expr instanceof Ast.AssignExpr e) return 1 + expressionComplexity(e.target()) + expressionComplexity(e.value());
        if (expr instanceof Ast.ConditionalExpr e) return 1 + expressionComplexity(e.condition()) + expressionComplexity(e.whenTrue()) + expressionComplexity(e.whenFalse());
        if (expr instanceof Ast.CallExpr e) {
            int n = 1 + expressionComplexity(e.callee());
            for (Ast.Expr arg : e.arguments()) n += expressionComplexity(arg);
            return n;
        }
        if (expr instanceof Ast.MemberExpr e) return 1 + expressionComplexity(e.receiver());
        if (expr instanceof Ast.IndexExpr e) return 1 + expressionComplexity(e.receiver()) + expressionComplexity(e.index());
        if (expr instanceof Ast.NewExpr e) {
            int n = 1;
            for (Ast.Expr arg : e.arguments()) n += expressionComplexity(arg);
            return n;
        }
        if (expr instanceof Ast.AwaitExpr e) return 1 + expressionComplexity(e.expression());
        if (expr instanceof Ast.ListExpr e) return 1 + e.elements().stream().mapToInt(this::expressionComplexity).sum();
        if (expr instanceof Ast.TupleExpr e) return 1 + e.elements().stream().mapToInt(this::expressionComplexity).sum();
        if (expr instanceof Ast.ObjectExpr e) return 1 + e.fields().stream().mapToInt(f -> expressionComplexity(f.value())).sum();
        if (expr instanceof Ast.LambdaExpr e) return 1 + (e.blockBody() == null ? 0 : inferenceComplexity(e.blockBody()));
        if (expr instanceof Ast.MatchExpr e) {
            int n = 1 + expressionComplexity(e.scrutinee());
            for (Ast.MatchArm arm : e.arms()) n += 1 + (arm.guard() == null ? 0 : expressionComplexity(arm.guard())) + expressionComplexity(arm.result());
            return n;
        }
        return 1;
    }

    private static final class ReturnInference {
        private Type type;
        private void add(Type next, TypeChecker checker) {
            type = checker.inferenceJoin(type, next);
        }
    }

    private void checkClass(String module, Ast.ClassDecl klass) {
        Set<String> classGenerics = uniqueGenerics(klass.genericParameters(), "class " + klass.name());
        Type self = nominalClassType(klass);

        Set<String> parentNames = new HashSet<>();
        for (Ast.TypeRef parent : klass.parents()) {
            if (!parentNames.add(parent.name())) throw new IllegalArgumentException("duplicate parent class '" + parent.name() + "' on " + klass.name());
            resolveClassParent(parent, klass);
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

        for (Ast.FieldDecl field : klass.fields()) {
            Type fieldType = resolve(field.type(), classGenerics, self);
            if (field.initializer() != null) {
                Type actual = typeOf(field.initializer(), new Env(null), classGenerics, self);
                requireAssignable(actual, fieldType, "field initializer " + klass.name() + "." + field.name());
            }
            if (field.bindingKind() == Ast.BindingKind.CONST && field.initializer() != null && !constant(field.initializer())) {
                throw new IllegalArgumentException("const field '" + field.name() + "' needs a compile-time constant initializer");
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
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", self, Ast.BindingKind.VAL);
            for (Ast.Param param : method.parameters()) env.define(param.name(), resolveParam(param, generics, callableSelf), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            Type returns = resolve(method.returnType(), generics, callableSelf);
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
        if (field.initializer() == null) throw new IllegalArgumentException("module binding '" + field.name() + "' requires an initializer");
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
            Type actual = declaredAhead == null
                    ? typeOf(binding.initializer(), env, generics, self)
                    : typeOfWithExpected(binding.initializer(), declaredAhead, env, generics, self);
            Type declared = declaredAhead == null
                    ? inferredBindingType(actual, binding.kind())
                    : declaredAhead;
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
            Type actual = ret.value() == null
                    ? Primitive.VOID
                    : typeOfWithExpected(ret.value(), expectedReturn, env, generics, self);
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
            Type element = iterable == Any.INSTANCE ? Any.INSTANCE : iterableElementType(iterable);
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
            if (name.name().equals("stdio") || name.name().equals("process") || name.name().equals("actor")) return new Named(name.name(), List.of());
            if (name.name().equals("print")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            if (name.name().equals("None")) return new Named("Option", List.of(Never.INSTANCE));
            Ast.ModuleDecl moduleNamespace = modules.get(name.name());
            if (moduleNamespace != null) return moduleShape(moduleNamespace);
            Ast.ClassDecl classNamespace = findClass(name.name());
            if (classNamespace != null) return new ClassNamespace(qualifiedClassName(classNamespace));
            if (importedValues.contains(name.name())) return Any.INSTANCE;
            Ast.FunctionDecl fn = findFunction(name.name());
            if (fn != null) return functionType(fn);
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
            if (operand == Any.INSTANCE) return Any.INSTANCE;
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
                    if (left != Any.INSTANCE && right != Any.INSTANCE
                            && !(Types.isNumeric(left) && Types.isNumeric(right))
                            && !(isStringLike(left) && isStringLike(right))) {
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
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr builtin) {
                if (builtin.name().equals("actor")) {
                    return actorBuiltinCall(member.member(), call.arguments(), env, generics, self);
                }
                if (builtin.name().equals("process")) {
                    return processBuiltinCall(member.member(), call.arguments(), env, generics, self);
                }
            }
            if (call.callee() instanceof Ast.NameExpr name && env.lookup(name.name()) == null) {
                Ast.FunctionDecl direct = findFunction(name.name());
                if (direct != null && !direct.genericParameters().isEmpty()) {
                    return typeGenericFunctionCall(direct, call, env, generics, self);
                }
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && env.lookup(namespace.name()) == null
                    && modules.containsKey(namespace.name())) {
                Ast.FunctionDecl direct = functions.get(namespace.name() + "." + member.member());
                if (direct != null && !direct.genericParameters().isEmpty()) {
                    return typeGenericFunctionCall(direct, call, env, generics, self);
                }
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
                    List<Type> parameterTemplates = fn.parameters().stream()
                            .map(param -> resolveParam(param, fnGenerics, null))
                            .toList();
                    Type resultTemplate = resolve(fn.returnType(), fnGenerics, null);
                    return typeSpecializedCall(parameterTemplates, resultTemplate, fnGenerics, Map.of(),
                            call.arguments(), env, generics, self, klass.name() + "." + fn.name());
                }
                if (receiver instanceof Named named) {
                    Ast.ClassDecl klass = findClass(named.name());
                    if (klass != null) {
                        Ast.MethodDecl method = findMethod(klass, member.member(), call.arguments().size(), new LinkedHashSet<>());
                        if (method == null) throw new IllegalArgumentException("no method '" + member.member() + "' with arity " + call.arguments().size() + " on " + named.name());
                        Set<String> methodGenerics = new HashSet<>(klass.genericParameters());
                        methodGenerics.addAll(method.genericParameters());
                        Map<String, Type> seedBindings = new LinkedHashMap<>();
                        if (klass.genericParameters().size() == named.arguments().size()) {
                            for (int i = 0; i < klass.genericParameters().size(); i++) {
                                seedBindings.put(klass.genericParameters().get(i), named.arguments().get(i));
                            }
                        }
                        List<Type> parameterTemplates = method.parameters().stream()
                                .map(param -> resolveParam(param, methodGenerics, named))
                                .toList();
                        Type resultTemplate = resolve(method.returnType(), methodGenerics, named);
                        return typeSpecializedCall(parameterTemplates, resultTemplate, methodGenerics, seedBindings,
                                call.arguments(), env, generics, self, named.name() + "." + method.name());
                    }
                }
            }
            Type callee = typeOf(call.callee(), env, generics, self);
            if (callee == Any.INSTANCE) return Any.INSTANCE;
            if (callee == Unknown.INSTANCE) throw new IllegalArgumentException("cannot call a value of type unknown; narrow it first");
            if (!(callee instanceof Function fn)) return Unknown.INSTANCE;
            if (fn.parameters().size() != call.arguments().size()) throw new IllegalArgumentException("call arity mismatch");
            for (int i = 0; i < fn.parameters().size(); i++) {
                requireAssignable(typeOfWithExpected(call.arguments().get(i), fn.parameters().get(i), env, generics, self),
                        fn.parameters().get(i), "argument " + (i + 1));
            }
            return fn.result();
        }
        if (expr instanceof Ast.MemberExpr member) {
            if (member.receiver() instanceof Ast.NameExpr namespace && modules.containsKey(namespace.name())) {
                Ast.ClassDecl memberClass = classes.get(namespace.name() + "." + member.member());
                if (memberClass != null) return new ClassNamespace(qualifiedClassName(memberClass));
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("stdio")) {
                if (member.member().equals("print") || member.member().equals("println")) return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
                if (member.member().equals("stdout")) return new Named("stdio.stdout", List.of());
            }
            Type receiver = typeOf(member.receiver(), env, generics, self);
            if (receiver instanceof Named named && named.name().equals("stdio.stdout") && member.member().equals("write")) {
                return new Function(List.of(Unknown.INSTANCE), Primitive.VOID);
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("actor")) {
                if (member.member().equals("self")) {
                    return new Named("ActorRef", List.of(Unknown.INSTANCE));
                }
                return Unknown.INSTANCE;
            }
            if (member.receiver() instanceof Ast.NameExpr name && name.name().equals("process")) {
                return Unknown.INSTANCE;
            }
            if (member.receiver() instanceof Ast.NameExpr name && importedValues.contains(name.name())) return Any.INSTANCE;

            if (receiver instanceof ClassNamespace classNamespace) {
                Ast.ClassDecl klass = findClass(classNamespace.className());
                if (klass == null) throw new IllegalArgumentException("unknown class namespace '" + classNamespace.className() + "'");
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
                if (named.name().equals("ActorRef") && member.member().equals("id")) {
                    return Primitive.STRING;
                }
                if (named.name().equals("MonitorRef")
                        && (member.member().equals("id") || member.member().equals("target_id"))) {
                    return Primitive.STRING;
                }
                Ast.ClassDecl klass = findClass(named.name());
                if (klass != null) {
                    Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                    if (field != null) {
                        Map<String, Type> bindings = classGenericBindings(klass, named);
                        return substituteGenericType(field, bindings, true);
                    }
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
            if (receiver == Any.INSTANCE) return Any.INSTANCE;
            if (receiver == Unknown.INSTANCE) throw new IllegalArgumentException("cannot index unknown; narrow it first");
            if (receiver instanceof ListType list) return list.element();
            if (receiver instanceof Tuple tuple) return tuple.elements().stream().reduce(Unknown.INSTANCE, this::commonType);
            throw new IllegalArgumentException("indexing requires an array/list or tuple");
        }
        if (expr instanceof Ast.NewExpr created) {
            Ast.ClassDecl klass = findClass(created.type().name());
            if (klass == null) return new Named(created.type().name(), created.type().arguments().stream().map(a -> resolve(a, generics, self)).toList());

            List<Ast.FieldDecl> fields = effectiveFields(klass, new LinkedHashSet<>());
            if (created.arguments().size() > fields.size()) {
                throw new IllegalArgumentException("constructor for " + klass.name() + " received too many positional fields");
            }

            Set<String> classGenerics = Set.copyOf(klass.genericParameters());
            Type nominalTemplate = nominalClassType(klass);
            Map<String, Type> bindings = new LinkedHashMap<>();

            if (!created.type().arguments().isEmpty()) {
                if (created.type().arguments().size() != klass.genericParameters().size()) {
                    throw new IllegalArgumentException("class '" + klass.name() + "' expects "
                            + klass.genericParameters().size() + " type argument(s), got " + created.type().arguments().size());
                }
                for (int i = 0; i < klass.genericParameters().size(); i++) {
                    bindings.put(klass.genericParameters().get(i),
                            resolve(created.type().arguments().get(i), generics, self));
                }
            }

            // Infer class generic arguments from supplied constructor fields.
            for (int i = 0; i < created.arguments().size(); i++) {
                Ast.FieldDecl field = fields.get(i);
                Type template = resolve(field.type(), classGenerics, nominalTemplate);
                Type actual = typeOf(created.arguments().get(i), env, generics, self);
                inferGenericBindings(template, actual, classGenerics, bindings, klass.name());
            }

            Type nominal = substituteGenericType(nominalTemplate, bindings, true);
            for (int i = 0; i < fields.size(); i++) {
                Ast.FieldDecl field = fields.get(i);
                if (i < created.arguments().size()) {
                    Type template = resolve(field.type(), classGenerics, nominalTemplate);
                    Type expected = substituteGenericType(template, bindings, false);
                    Type actual = typeOfWithExpected(created.arguments().get(i), expected, env, generics, self);
                    requireAssignable(actual, expected, "constructor field " + field.name());
                } else if (field.initializer() == null) {
                    throw new IllegalArgumentException("constructor for " + klass.name() + " is missing field '" + field.name() + "'");
                }
            }
            return nominal;
        }
        if (expr instanceof Ast.AwaitExpr awaited) {
            Type awaitedType = typeOf(awaited.expression(), env, generics, self);
            if (awaitedType == Any.INSTANCE) return Any.INSTANCE;
            if (awaitedType instanceof Named named && named.name().equals("Future") && named.arguments().size() == 1) return named.arguments().getFirst();
            return Unknown.INSTANCE;
        }
        if (expr instanceof Ast.ListExpr list) {
            if (list.elements().isEmpty()) return new ListType(Unknown.INSTANCE);
            // Mutable/homogeneous list inference should infer the stable value
            // type, not a singleton literal type such as List<"hello">.
            Type element = widenLiteralType(typeOf(list.elements().getFirst(), env, generics, self));
            for (int i = 1; i < list.elements().size(); i++) {
                element = commonType(element, widenLiteralType(typeOf(list.elements().get(i), env, generics, self)));
            }
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
        if (expr instanceof Ast.MatchExpr match) {
            return typeOfMatch(match, null, env, generics, self);
        }
        if (expr instanceof Ast.LambdaExpr lambda) {
            boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
            Env lambdaEnv = new Env(nonLexical ? null : env, nonLexical);
            List<Type> parameters = new ArrayList<>();
            for (Ast.Param param : lambda.parameters()) {
                Type type = resolveParam(param, generics, self);
                parameters.add(type);
                lambdaEnv.define(param.name(), type, param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            if (lambda.expressionBody() != null) {
                throw new IllegalArgumentException("expression-body lambdas are not supported; lambdas require braces and explicit return");
            }
            Type result = inferLambdaReturn(lambda, lambdaEnv, generics, self);
            checkBlock(lambda.blockBody(), lambdaEnv, generics, result, self);
            if (result != Primitive.VOID && !definitelyReturns(lambda.blockBody())) {
                throw new IllegalArgumentException("non-void inferred lambda must explicitly return on every path");
            }
            return new Function(parameters, result);
        }
        return Unknown.INSTANCE;
    }

    private Type typeOfMatch(Ast.MatchExpr match, Type expected, Env env, Set<String> generics, Type self) {
        Type subject = typeOf(match.scrutinee(), env, generics, self);
        MatchCoverage coverage = new MatchCoverage();
        Type inferred = null;
        boolean bounded = match.sourceLineSpan() < INFERENCE_LINE_LIMIT
                && expressionComplexity(match) <= INFERENCE_COMPLEXITY_LIMIT;

        for (Ast.MatchArm arm : match.arms()) {
            if (coverageExhausts(subject, coverage)) {
                throw new IllegalArgumentException("redundant/unreachable match arm after prior patterns already exhaust the subject type");
            }

            String key = exactPatternKey(arm.pattern());
            if (patternAlreadyCovered(coverage, arm.pattern())) {
                throw new IllegalArgumentException("redundant/unreachable match arm"
                        + (key == null ? "" : " for pattern " + key));
            }

            Env armEnv = new Env(env);
            checkPattern(arm.pattern(), subject, armEnv, generics, self);
            if (arm.guard() != null) {
                requireAssignable(typeOf(arm.guard(), armEnv, generics, self), Primitive.BOOL, "match guard");
            }

            Type armType;
            if (expected != null && expected != Any.INSTANCE && expected != Unknown.INSTANCE) {
                armType = typeOfWithExpected(arm.result(), expected, armEnv, generics, self);
                requireAssignable(armType, expected, "match arm result");
            } else {
                armType = typeOf(arm.result(), armEnv, generics, self);
                if (bounded) inferred = inferenceJoin(inferred, armType);
            }

            if (arm.guard() == null) addCoverage(coverage, arm.pattern());
        }

        requireExhaustiveMatch(subject, coverage);
        if (expected != null && expected != Any.INSTANCE && expected != Unknown.INSTANCE) return expected;
        return bounded ? (inferred == null ? Primitive.VOID : inferred) : Any.INSTANCE;
    }

    private void checkPattern(Ast.Pattern pattern, Type subject, Env env, Set<String> generics, Type self) {
        if (pattern instanceof Ast.WildcardPattern) return;
        if (pattern instanceof Ast.BindingPattern binding) {
            env.define(binding.name(), subject, Ast.BindingKind.VAL);
            return;
        }
        if (pattern instanceof Ast.LiteralPattern literal) {
            Type literalType = literalPatternType(literal.value());
            requirePatternOverlap(subject, literalType, "literal pattern");
            return;
        }
        if (pattern instanceof Ast.TypedPattern typed) {
            Type narrowed = resolve(typed.type(), generics, self);
            requirePatternOverlap(subject, narrowed, "typed pattern");
            checkPattern(typed.pattern(), narrowed, env, generics, self);
            return;
        }
        if (pattern instanceof Ast.OrPattern or) {
            Set<String> expectedBindings = null;
            Map<String, Type> joinedBindings = new LinkedHashMap<>();
            for (Ast.Pattern alternative : or.alternatives()) {
                Set<String> names = patternBindingNames(alternative);
                if (expectedBindings == null) expectedBindings = names;
                else if (!expectedBindings.equals(names)) {
                    throw new IllegalArgumentException("all alternatives in an OR pattern must bind the same names");
                }

                Env alternativeEnv = new Env(env);
                checkPattern(alternative, subject, alternativeEnv, generics, self);
                for (String name : names) {
                    Env.Binding binding = alternativeEnv.bindings.get(name);
                    if (binding == null) throw new IllegalArgumentException("OR-pattern binding '" + name + "' is not present in every alternative");
                    joinedBindings.merge(name, binding.type(), this::inferenceJoin);
                }
            }
            for (Map.Entry<String, Type> binding : joinedBindings.entrySet()) {
                env.define(binding.getKey(), binding.getValue(), Ast.BindingKind.VAL);
            }
            return;
        }
        if (pattern instanceof Ast.RangePattern range) {
            Type lower = literalPatternType(range.lower());
            Type upper = literalPatternType(range.upper());
            if ((!Types.isNumeric(lower) || !Types.isNumeric(upper))
                    && !(isStringLike(lower) && isStringLike(upper))) {
                throw new IllegalArgumentException("range pattern bounds must both be numeric or both strings");
            }
            requirePatternOverlap(subject, inferenceJoin(lower, upper), "range pattern");
            return;
        }
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            Type optionElement = optionElement(subject);
            if (constructor.name().equals("Some")) {
                if (constructor.arguments().size() != 1) throw new IllegalArgumentException("Some pattern expects exactly one nested pattern");
                if (optionElement == null) throw new IllegalArgumentException("Some pattern requires an Option<T> subject");
                checkPattern(constructor.arguments().getFirst(), optionElement, env, generics, self);
                return;
            }
            if (constructor.name().equals("None")) {
                if (!constructor.arguments().isEmpty()) throw new IllegalArgumentException("None pattern takes no nested patterns");
                if (optionElement == null) throw new IllegalArgumentException("None pattern requires an Option<T> subject");
                return;
            }
            if (subject == Any.INSTANCE || subject == Unknown.INSTANCE) {
                for (Ast.Pattern nested : constructor.arguments()) checkPattern(nested, subject, env, generics, self);
                return;
            }
            if (subject instanceof Named named && named.name().equals(constructor.name()) && constructor.arguments().isEmpty()) return;
            throw new IllegalArgumentException("constructor pattern '" + constructor.name() + "' does not match subject type " + subject);
        }
        if (pattern instanceof Ast.TuplePattern tuplePattern) {
            if (subject == Any.INSTANCE || subject == Unknown.INSTANCE) {
                for (Ast.Pattern nested : tuplePattern.elements()) checkPattern(nested, subject, env, generics, self);
                return;
            }
            if (!(subject instanceof Tuple tuple)) throw new IllegalArgumentException("tuple pattern requires a tuple subject");
            if (tuple.elements().size() != tuplePattern.elements().size()) throw new IllegalArgumentException("tuple pattern arity mismatch");
            for (int i = 0; i < tuple.elements().size(); i++) {
                checkPattern(tuplePattern.elements().get(i), tuple.elements().get(i), env, generics, self);
            }
            return;
        }
        if (pattern instanceof Ast.ListPattern listPattern) {
            if (subject == Any.INSTANCE || subject == Unknown.INSTANCE) {
                for (Ast.Pattern nested : listPattern.elements()) checkPattern(nested, subject, env, generics, self);
                return;
            }
            if (!(subject instanceof ListType list)) throw new IllegalArgumentException("list pattern requires Array<T>/List<T>");
            for (Ast.Pattern nested : listPattern.elements()) checkPattern(nested, list.element(), env, generics, self);
            return;
        }
        if (pattern instanceof Ast.RecordPattern recordPattern) {
            if (subject == Any.INSTANCE || subject == Unknown.INSTANCE) {
                for (Ast.PatternField field : recordPattern.fields()) checkPattern(field.pattern(), subject, env, generics, self);
                return;
            }
            Record shape = structuralShape(subject);
            if (shape == null) throw new IllegalArgumentException("record pattern requires a structural object/class value");
            for (Ast.PatternField field : recordPattern.fields()) {
                Type member = shape.members().get(field.name());
                if (member == null) throw new IllegalArgumentException("record pattern references unknown field '" + field.name() + "'");
                checkPattern(field.pattern(), member, env, generics, self);
            }
            return;
        }
        throw new IllegalArgumentException("unsupported match pattern " + pattern);
    }

    private Type optionElement(Type subject) {
        if (subject == Any.INSTANCE) return Any.INSTANCE;
        if (subject == Unknown.INSTANCE) return Unknown.INSTANCE;
        if (subject instanceof Named named && named.name().equals("Option") && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        return null;
    }

    private Record structuralShape(Type type) {
        if (type instanceof Record record) return record;
        if (type instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) return publicClassShape(klass, new LinkedHashSet<>());
        }
        return null;
    }

    private Type literalPatternType(Object value) {
        if (value instanceof Long) return Primitive.INT;
        if (value instanceof Double) return Primitive.FLOAT;
        if (value instanceof Boolean) return Primitive.BOOL;
        if (value instanceof String s) return new StringLiteral(s);
        return Unknown.INSTANCE;
    }

    private void requirePatternOverlap(Type subject, Type pattern, String where) {
        if (subject == Any.INSTANCE || subject == Unknown.INSTANCE || pattern == Any.INSTANCE || pattern == Unknown.INSTANCE) return;
        if (!assignable(pattern, subject) && !assignable(subject, pattern)) {
            throw new IllegalArgumentException(where + " cannot match subject type " + subject + " with " + pattern);
        }
    }

    private Set<String> patternBindingNames(Ast.Pattern pattern) {
        Set<String> result = new LinkedHashSet<>();
        collectPatternBindingNames(pattern, result);
        return result;
    }

    private void collectPatternBindingNames(Ast.Pattern pattern, Set<String> out) {
        if (pattern instanceof Ast.BindingPattern binding) out.add(binding.name());
        else if (pattern instanceof Ast.TypedPattern typed) collectPatternBindingNames(typed.pattern(), out);
        else if (pattern instanceof Ast.ConstructorPattern constructor) {
            for (Ast.Pattern nested : constructor.arguments()) collectPatternBindingNames(nested, out);
        } else if (pattern instanceof Ast.TuplePattern tuple) {
            for (Ast.Pattern nested : tuple.elements()) collectPatternBindingNames(nested, out);
        } else if (pattern instanceof Ast.ListPattern list) {
            for (Ast.Pattern nested : list.elements()) collectPatternBindingNames(nested, out);
        } else if (pattern instanceof Ast.RecordPattern record) {
            for (Ast.PatternField field : record.fields()) collectPatternBindingNames(field.pattern(), out);
        } else if (pattern instanceof Ast.OrPattern or) {
            for (Ast.Pattern nested : or.alternatives()) collectPatternBindingNames(nested, out);
        }
    }

    private boolean patternAlreadyCovered(MatchCoverage coverage, Ast.Pattern pattern) {
        if (coverage.universal) return true;
        if (pattern instanceof Ast.OrPattern or) {
            return or.alternatives().stream().allMatch(alt -> patternAlreadyCovered(coverage, alt));
        }
        if (pattern instanceof Ast.TypedPattern typed) return patternAlreadyCovered(coverage, typed.pattern());
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            if (constructor.name().equals("None") && constructor.arguments().isEmpty()) return coverage.optionNone;
            if (constructor.name().equals("Some") && constructor.arguments().size() == 1 && coverage.optionSome) return true;
        }
        String key = exactPatternKey(pattern);
        return key != null && coverage.exact.contains(key);
    }

    private void addCoverage(MatchCoverage coverage, Ast.Pattern pattern) {
        if (isCatchAllPattern(pattern)) {
            coverage.universal = true;
            return;
        }
        if (pattern instanceof Ast.OrPattern or) {
            for (Ast.Pattern alternative : or.alternatives()) addCoverage(coverage, alternative);
            return;
        }
        if (pattern instanceof Ast.LiteralPattern literal && literal.value() instanceof Boolean value) {
            if (value) coverage.boolTrue = true;
            else coverage.boolFalse = true;
        }
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            if (constructor.name().equals("None") && constructor.arguments().isEmpty()) coverage.optionNone = true;
            if (constructor.name().equals("Some") && constructor.arguments().size() == 1
                    && isCatchAllPattern(constructor.arguments().getFirst())) coverage.optionSome = true;
        }
        String key = exactPatternKey(pattern);
        if (key != null) coverage.exact.add(key);
    }

    private boolean isCatchAllPattern(Ast.Pattern pattern) {
        if (pattern instanceof Ast.WildcardPattern || pattern instanceof Ast.BindingPattern) return true;
        if (pattern instanceof Ast.OrPattern or) return or.alternatives().stream().anyMatch(this::isCatchAllPattern);
        return false;
    }

    private String exactPatternKey(Ast.Pattern pattern) {
        if (pattern instanceof Ast.LiteralPattern literal) return "literal:" + literal.value().getClass().getName() + ":" + literal.value();
        if (pattern instanceof Ast.ConstructorPattern constructor) {
            if (constructor.name().equals("None") && constructor.arguments().isEmpty()) return "None";
            if (constructor.name().equals("Some") && constructor.arguments().size() == 1) {
                String nested = exactPatternKey(constructor.arguments().getFirst());
                return nested == null ? null : "Some(" + nested + ")";
            }
        }
        if (pattern instanceof Ast.RangePattern range) return "range:" + range.lower() + ":" + range.upper() + ":" + range.inclusive();
        if (pattern instanceof Ast.TypedPattern typed) return exactPatternKey(typed.pattern());
        return null;
    }

    private boolean coverageExhausts(Type subject, MatchCoverage coverage) {
        if (coverage.universal) return true;
        if (subject == Primitive.BOOL) return coverage.boolTrue && coverage.boolFalse;
        if (subject instanceof Named named && named.name().equals("Option")) {
            return coverage.optionSome && coverage.optionNone;
        }
        return false;
    }

    private void requireExhaustiveMatch(Type subject, MatchCoverage coverage) {
        if (coverageExhausts(subject, coverage)) return;
        throw new IllegalArgumentException("non-exhaustive match for type " + subject + "; add the missing cases or a '_' catch-all arm");
    }

    private static final class MatchCoverage {
        private boolean universal;
        private boolean boolTrue;
        private boolean boolFalse;
        private boolean optionSome;
        private boolean optionNone;
        private final Set<String> exact = new HashSet<>();
    }

    private Type inferLambdaReturn(Ast.LambdaExpr lambda, Env lambdaEnv, Set<String> generics, Type self) {
        if (lambda.sourceLineSpan() >= INFERENCE_LINE_LIMIT
                || inferenceComplexity(lambda.blockBody()) > INFERENCE_COMPLEXITY_LIMIT) {
            return hasValueReturn(lambda.blockBody()) ? Any.INSTANCE : Primitive.VOID;
        }
        ReturnInference inference = new ReturnInference();
        inferBlockReturns(lambda.blockBody(), lambdaEnv, generics, self, inference);
        return inference.type == null ? Primitive.VOID : inference.type;
    }

    private Type typeOfWithExpected(Ast.Expr expression, Type expected, Env env, Set<String> generics, Type self) {
        if (expression instanceof Ast.CallExpr call && expected != Any.INSTANCE && expected != Unknown.INSTANCE) {
            if (call.callee() instanceof Ast.NameExpr name && env.lookup(name.name()) == null) {
                Ast.FunctionDecl direct = findFunction(name.name());
                if (direct != null && !direct.genericParameters().isEmpty()) {
                    return typeGenericFunctionCall(direct, call, env, generics, self, expected);
                }
            }
            if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace
                    && env.lookup(namespace.name()) == null
                    && modules.containsKey(namespace.name())) {
                Ast.FunctionDecl direct = functions.get(namespace.name() + "." + member.member());
                if (direct != null && !direct.genericParameters().isEmpty()) {
                    return typeGenericFunctionCall(direct, call, env, generics, self, expected);
                }
            }
        }
        if (expression instanceof Ast.MatchExpr match && expected != Any.INSTANCE && expected != Unknown.INSTANCE) {
            return typeOfMatch(match, expected, env, generics, self);
        }
        if (expression instanceof Ast.ListExpr list && expected instanceof ListType expectedList) {
            for (Ast.Expr item : list.elements()) {
                Type actual = typeOfWithExpected(item, expectedList.element(), env, generics, self);
                requireAssignable(actual, expectedList.element(), "list element");
            }
            return expectedList;
        }
        if (expression instanceof Ast.TupleExpr tuple && expected instanceof Tuple expectedTuple) {
            if (tuple.elements().size() != expectedTuple.elements().size()) {
                throw new IllegalArgumentException("tuple arity " + tuple.elements().size()
                        + " does not match expected arity " + expectedTuple.elements().size());
            }
            for (int i = 0; i < tuple.elements().size(); i++) {
                Type actual = typeOfWithExpected(tuple.elements().get(i), expectedTuple.elements().get(i), env, generics, self);
                requireAssignable(actual, expectedTuple.elements().get(i), "tuple element " + (i + 1));
            }
            return expectedTuple;
        }
        if (expression instanceof Ast.ObjectExpr object && expected instanceof Record expectedRecord) {
            Map<String, Type> actualMembers = new LinkedHashMap<>();
            for (Ast.ObjectField field : object.fields()) {
                Type memberExpected = expectedRecord.members().get(field.name());
                Type actual = memberExpected == null
                        ? typeOf(field.value(), env, generics, self)
                        : typeOfWithExpected(field.value(), memberExpected, env, generics, self);
                if (memberExpected != null) requireAssignable(actual, memberExpected, "object field " + field.name());
                actualMembers.put(field.name(), actual);
            }
            Record actualRecord = new Record(actualMembers);
            requireAssignable(actualRecord, expectedRecord, "object literal");
            return expectedRecord;
        }
        if (expression instanceof Ast.LambdaExpr lambda && expected instanceof Function fn) {
            validateLambdaAgainstExpected(lambda, fn, env, generics, self);
            return fn;
        }
        return typeOf(expression, env, generics, self);
    }

    private void validateLambdaAgainstExpected(Ast.LambdaExpr lambda, Function expected, Env parent, Set<String> generics, Type self) {
        if (lambda.expressionBody() != null) {
            throw new IllegalArgumentException("lambdas always require a block body and explicit return for non-void results");
        }
        if (lambda.parameters().size() != expected.parameters().size()) {
            throw new IllegalArgumentException("lambda arity " + lambda.parameters().size() + " does not match expected function arity " + expected.parameters().size());
        }
        boolean nonLexical = lambda.nonLexical() || parent.descendantsNonLexical();
        Env lambdaEnv = new Env(nonLexical ? null : parent, nonLexical);
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
        if (receiver instanceof Named named) {
            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Type field = findFieldType(klass, member.member(), new LinkedHashSet<>());
                if (field != null) return substituteGenericType(field, classGenericBindings(klass, named), true);
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
                    Set<String> iteratorGenerics = new HashSet<>(klass.genericParameters());
                    iteratorGenerics.addAll(iterator.genericParameters());
                    Type result = resolve(iterator.returnType(), iteratorGenerics, named);
                    result = substituteGenericType(result, classGenericBindings(klass, named), true);
                    if (result instanceof ListType list) return list.element();
                    if (result instanceof Tuple tuple) return iterableElementType(tuple);
                    throw new IllegalArgumentException("[Symbol.iterator]() must return Array<T>, List<T>, or a tuple in v0");
                }
            }
        }
        throw new IllegalArgumentException("for-of requires an array/list, tuple, or a class with [Symbol.iterator]()");
    }

    private Type commonType(Type a, Type b) {
        if (a.equals(b)) return a;
        if (Types.isNumeric(a) && Types.isNumeric(b)) return Types.numericJoin(a, b);
        if (isStringLike(a) && isStringLike(b)) return Primitive.STRING;
        if (assignable(a, b)) return b;
        if (assignable(b, a)) return a;
        return Unknown.INSTANCE;
    }

    private Type numericJoin(Type left, Type right, String op) {
        Type result = Types.numericJoin(left, right);
        if (result == Unknown.INSTANCE) throw new IllegalArgumentException("operator '" + op + "' needs numeric operands");
        return result;
    }

    private Type widenLiteralType(Type type) {
        if (type instanceof StringLiteral) return Primitive.STRING;
        return type;
    }

    private Type inferredBindingType(Type type, Ast.BindingKind kind) {
        return kind == Ast.BindingKind.LET ? widenMutableType(type) : type;
    }

    private Type widenMutableType(Type type) {
        if (type instanceof StringLiteral) return Primitive.STRING;
        if (type instanceof ListType list) return new ListType(widenMutableType(list.element()));
        if (type instanceof Tuple tuple) {
            return new Tuple(tuple.elements().stream().map(this::widenMutableType).toList());
        }
        if (type instanceof Named named) {
            return new Named(named.name(), named.arguments().stream().map(this::widenMutableType).toList());
        }
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : record.members().entrySet()) {
                members.put(entry.getKey(), widenMutableType(entry.getValue()));
            }
            return new Record(members);
        }
        return type;
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
        for (Ast.FieldDecl field : klass.fields()) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        for (Ast.MethodDecl method : klass.methods()) {
            if (method.isStatic()) continue;
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
            if (field.visibility() == Ast.Visibility.PUBLIC) mergeMember(members, field.name(), resolve(field.type(), generics, self), "class " + klass.name());
        }
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.visibility() == Ast.Visibility.PUBLIC) {
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
        for (Ast.FieldDecl field : klass.fields()) fields.put(field.name(), field);
        stack.remove(klass);
        return List.copyOf(fields.values());
    }

    private Type findFieldType(Ast.ClassDecl klass, String name, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        Type self = nominalClassType(klass);
        for (Ast.FieldDecl field : klass.fields()) {
            if (field.name().equals(name)) {
                seen.remove(klass);
                return resolve(field.type(), Set.copyOf(klass.genericParameters()), self);
            }
        }
        for (Ast.TypeRef parentRef : klass.parents()) {
            Ast.ClassDecl parent = resolveClassParent(parentRef, klass);
            if (parent == null) continue;
            Type result = findFieldType(parent, name, seen);
            if (result != null) {
                seen.remove(klass);
                return result;
            }
        }
        seen.remove(klass);
        return null;
    }

    private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
        if (!seen.add(klass)) return null;
        for (Ast.MethodDecl method : klass.methods()) {
            if (!method.isStatic() && method.name().equals(name) && method.arity() == arity) {
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
        for (Ast.MethodDecl method : klass.methods()) if (!method.isStatic() && method.name().equals(name)) methods.put(method.arity(), method);
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

    private Function functionType(Ast.FunctionDecl fn) {
        Set<String> generics = Set.copyOf(fn.genericParameters());
        return new Function(fn.parameters().stream().map(p -> resolveParam(p, generics, null)).toList(), callableReturnType(fn));
    }

    private Type typeGenericFunctionCall(
            Ast.FunctionDecl fn,
            Ast.CallExpr call,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf) {
        return typeGenericFunctionCall(fn, call, env, callerGenerics, callerSelf, null);
    }

    private Type typeGenericFunctionCall(
            Ast.FunctionDecl fn,
            Ast.CallExpr call,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf,
            Type contextualResult) {
        Set<String> names = Set.copyOf(fn.genericParameters());
        if (names.isEmpty()) return null;
        List<Type> parameters = fn.parameters().stream()
                .map(param -> resolveParam(param, names, null))
                .toList();
        Type resultTemplate = callableReturnType(fn);
        Map<String, Type> seedBindings = new LinkedHashMap<>();
        if (contextualResult != null && contextualResult != Any.INSTANCE && contextualResult != Unknown.INSTANCE) {
            inferGenericBindings(resultTemplate, contextualResult, names, seedBindings, fn.name());
        }
        Type actualResult = typeSpecializedCall(parameters, resultTemplate, names, seedBindings,
                call.arguments(), env, callerGenerics, callerSelf, fn.name());
        if (contextualResult != null && contextualResult != Any.INSTANCE && contextualResult != Unknown.INSTANCE) {
            requireAssignable(actualResult, contextualResult, "result of " + fn.name());
        }
        return actualResult;
    }

    private Type typeSpecializedCall(
            List<Type> parameterTemplates,
            Type resultTemplate,
            Set<String> inferableGenerics,
            Map<String, Type> seedBindings,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> callerGenerics,
            Type callerSelf,
            String callableName) {
        if (parameterTemplates.size() != arguments.size()) {
            throw new IllegalArgumentException("call arity mismatch for '" + callableName + "'");
        }

        Map<String, Type> bindings = new LinkedHashMap<>(seedBindings);

        // First pass learns generic variables from arguments that do not need
        // contextual typing. This lets a later lambda argument see a concrete
        // Fnc<...> expectation inferred from an earlier argument.
        for (int i = 0; i < arguments.size(); i++) {
            Ast.Expr argument = arguments.get(i);
            if (argument instanceof Ast.LambdaExpr) continue;
            Type actual = typeOf(argument, env, callerGenerics, callerSelf);
            inferGenericBindings(parameterTemplates.get(i), actual, inferableGenerics, bindings, callableName);
        }

        // Second pass validates every argument against the specialized
        // parameter type and can collect any additional safe bindings.
        for (int i = 0; i < arguments.size(); i++) {
            Type expected = substituteGenericType(parameterTemplates.get(i), bindings, false);
            Type actual = typeOfWithExpected(arguments.get(i), expected, env, callerGenerics, callerSelf);
            requireAssignable(actual, expected, "argument " + (i + 1) + " to " + callableName);
            inferGenericBindings(parameterTemplates.get(i), actual, inferableGenerics, bindings, callableName);
        }

        return substituteGenericType(resultTemplate, bindings, true);
    }

    private void inferGenericBindings(
            Type template,
            Type actual,
            Set<String> inferableGenerics,
            Map<String, Type> bindings,
            String callableName) {
        if (template instanceof Generic generic && inferableGenerics.contains(generic.name())) {
            Type previous = bindings.get(generic.name());
            bindings.put(generic.name(), previous == null
                    ? actual
                    : mergeGenericBinding(generic.name(), previous, actual, callableName));
            return;
        }

        if (template instanceof Borrow expected && actual instanceof Borrow observed) {
            inferGenericBindings(expected.target(), observed.target(), inferableGenerics, bindings, callableName);
            return;
        }

        if (template instanceof Named expected && actual instanceof Named observed
                && expected.name().equals(observed.name())
                && expected.arguments().size() == observed.arguments().size()) {
            for (int i = 0; i < expected.arguments().size(); i++) {
                inferGenericBindings(expected.arguments().get(i), observed.arguments().get(i),
                        inferableGenerics, bindings, callableName);
            }
            return;
        }

        if (template instanceof ListType expected && actual instanceof ListType observed) {
            inferGenericBindings(expected.element(), observed.element(), inferableGenerics, bindings, callableName);
            return;
        }

        if (template instanceof Tuple expected && actual instanceof Tuple observed
                && expected.elements().size() == observed.elements().size()) {
            for (int i = 0; i < expected.elements().size(); i++) {
                inferGenericBindings(expected.elements().get(i), observed.elements().get(i),
                        inferableGenerics, bindings, callableName);
            }
            return;
        }

        if (template instanceof Function expected && actual instanceof Function observed
                && expected.parameters().size() == observed.parameters().size()) {
            for (int i = 0; i < expected.parameters().size(); i++) {
                inferGenericBindings(expected.parameters().get(i), observed.parameters().get(i),
                        inferableGenerics, bindings, callableName);
            }
            inferGenericBindings(expected.result(), observed.result(), inferableGenerics, bindings, callableName);
            return;
        }

        if (template instanceof Record expected && actual instanceof Record observed) {
            for (Map.Entry<String, Type> member : expected.members().entrySet()) {
                Type value = observed.members().get(member.getKey());
                if (value != null) {
                    inferGenericBindings(member.getValue(), value, inferableGenerics, bindings, callableName);
                }
            }
        }
    }

    private Type mergeGenericBinding(String name, Type previous, Type actual, String callableName) {
        if (previous.equals(actual)) return previous;
        if (previous == Any.INSTANCE || actual == Any.INSTANCE) return Any.INSTANCE;
        if (previous == Unknown.INSTANCE || actual == Unknown.INSTANCE) return Unknown.INSTANCE;
        if (Types.isNumeric(previous) && Types.isNumeric(actual)) return Types.numericJoin(previous, actual);
        if (isStringLike(previous) && isStringLike(actual)) return Primitive.STRING;
        if (assignable(actual, previous)) return previous;
        if (assignable(previous, actual)) return actual;
        throw new IllegalArgumentException("cannot infer generic '" + name + "' for '" + callableName
                + "': conflicting argument types " + previous + " and " + actual);
    }

    private Type substituteGenericType(Type type, Map<String, Type> bindings, boolean unknownIfUnbound) {
        if (type instanceof Generic generic) {
            Type bound = bindings.get(generic.name());
            return bound != null ? bound : unknownIfUnbound ? Unknown.INSTANCE : type;
        }
        if (type instanceof Borrow borrow) {
            return new Borrow(substituteGenericType(borrow.target(), bindings, unknownIfUnbound), borrow.mutable());
        }
        if (type instanceof Named named) {
            return new Named(named.name(), named.arguments().stream()
                    .map(arg -> substituteGenericType(arg, bindings, unknownIfUnbound)).toList());
        }
        if (type instanceof ListType list) {
            return new ListType(substituteGenericType(list.element(), bindings, unknownIfUnbound));
        }
        if (type instanceof Tuple tuple) {
            return new Tuple(tuple.elements().stream()
                    .map(element -> substituteGenericType(element, bindings, unknownIfUnbound)).toList());
        }
        if (type instanceof Function fn) {
            return new Function(fn.parameters().stream()
                    .map(param -> substituteGenericType(param, bindings, unknownIfUnbound)).toList(),
                    substituteGenericType(fn.result(), bindings, unknownIfUnbound));
        }
        if (type instanceof Record record) {
            Map<String, Type> members = new LinkedHashMap<>();
            for (Map.Entry<String, Type> entry : record.members().entrySet()) {
                members.put(entry.getKey(), substituteGenericType(entry.getValue(), bindings, unknownIfUnbound));
            }
            return new Record(members);
        }
        return type;
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
            case "str", "string", "String" -> Primitive.STRING;
            case "void" -> Primitive.VOID;
            case "any" -> Any.INSTANCE;
            case "unknown" -> Unknown.INSTANCE;
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
            case "ActorRef", "Shared" -> {
                if (ref.inferArguments() || ref.arguments().size() != 1) {
                    throw new IllegalArgumentException(ref.name() + " requires exactly one explicit type argument");
                }
                yield new Named(ref.name(), List.of(resolve(ref.arguments().getFirst(), generics, self)));
            }
            case "MonitorRef" -> {
                if (!ref.arguments().isEmpty()) throw new IllegalArgumentException("MonitorRef does not take type arguments");
                yield new Named("MonitorRef", List.of());
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

    private Type actorBuiltinCall(
            String member,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> generics,
            Type self) {
        return switch (member) {
            case "spawn" -> actorSpawnType(arguments, false, env, generics, self);
            case "singleton" -> actorSpawnType(arguments, true, env, generics, self);
            case "send" -> {
                requireBuiltinArity("actor.send", arguments, 2);
                Type messageType = actorMessageType(typeOf(arguments.getFirst(), env, generics, self), "actor.send target");
                requireActorTransportType(messageType, "actor.send mailbox type", new HashSet<>());
                Type actual = unwrapShared(typeOfWithExpected(
                        arguments.get(1),
                        messageType,
                        env,
                        generics,
                        self));
                requireAssignable(actual, messageType, "actor.send message");
                yield Primitive.VOID;
            }
            case "stop", "join" -> {
                requireBuiltinArity("actor." + member, arguments, 1);
                actorMessageType(typeOf(arguments.getFirst(), env, generics, self), "actor." + member + " target");
                yield Primitive.BOOL;
            }
            case "status" -> {
                requireBuiltinArity("actor.status", arguments, 1);
                actorMessageType(typeOf(arguments.getFirst(), env, generics, self), "actor.status target");
                yield actorStatusType();
            }
            case "gc" -> {
                requireBuiltinArity("actor.gc", arguments, 0);
                yield gcResultType();
            }
            case "monitor" -> {
                if (arguments.size() != 1 && arguments.size() != 2) {
                    throw new IllegalArgumentException("actor.monitor expects target or target, watcher");
                }
                actorMessageType(typeOf(arguments.getFirst(), env, generics, self), "actor.monitor target");
                if (arguments.size() == 2) {
                    Type watcherMessage = actorMessageType(
                            typeOf(arguments.get(1), env, generics, self),
                            "actor.monitor watcher");
                    requireAssignable(downMessageType(), watcherMessage, "actor.monitor DOWN message");
                }
                yield new Named("MonitorRef", List.of());
            }
            case "demonitor" -> {
                if (arguments.size() != 1 && arguments.size() != 2) {
                    throw new IllegalArgumentException("actor.demonitor expects monitor or monitor, watcher");
                }
                Type monitor = typeOf(arguments.getFirst(), env, generics, self);
                requireAssignable(monitor, new Named("MonitorRef", List.of()), "actor.demonitor monitor");
                if (arguments.size() == 2) {
                    actorMessageType(typeOf(arguments.get(1), env, generics, self), "actor.demonitor watcher");
                }
                yield Primitive.BOOL;
            }
            default -> throw new IllegalArgumentException("unknown actor API '" + member + "'");
        };
    }

    private Type actorSpawnType(
            List<Ast.Expr> arguments,
            boolean singleton,
            Env env,
            Set<String> generics,
            Type self) {
        int handlerIndex = singleton ? 1 : 0;
        int stateIndex = singleton ? 2 : 1;
        int minimum = singleton ? 2 : 1;
        int maximum = singleton ? 3 : 2;
        String api = singleton ? "actor.singleton" : "actor.spawn";

        if (arguments.size() < minimum || arguments.size() > maximum) {
            throw new IllegalArgumentException(
                    api + " expects " + (singleton ? "name, handler[, initial_state]" : "handler[, initial_state]"));
        }
        if (singleton) {
            requireAssignable(
                    typeOf(arguments.getFirst(), env, generics, self),
                    Primitive.STRING,
                    "actor.singleton name");
        }

        boolean stateful = arguments.size() == maximum;
        int expectedArity = stateful ? 2 : 1;
        Ast.Expr handlerExpression = arguments.get(handlerIndex);

        final Function handler;
        if (handlerExpression instanceof Ast.LambdaExpr lambda) {
            if (!(lambda.nonLexical() || env.descendantsNonLexical())) {
                throw new IllegalArgumentException(api + " inline lambda handler must be nlex");
            }
            if (lambda.parameters().size() != expectedArity) {
                throw new IllegalArgumentException(
                        api + " handler must accept exactly " + expectedArity + " argument(s)");
            }
            Ast.Param messageParam = lambda.parameters().getFirst();
            if (messageParam.type().name().equals("$infer$")) {
                throw new IllegalArgumentException(api + " inline lambda message parameter requires an explicit type");
            }
            Type messageParamType = resolveParam(messageParam, generics, self);
            if (stateful) {
                Ast.Param stateParam = lambda.parameters().get(1);
                Type initialType = unwrapShared(typeOf(arguments.get(stateIndex), env, generics, self));
                Type stateType = stateParam.type().name().equals("$infer$")
                        ? initialType
                        : resolveParam(stateParam, generics, self);
                Function expected = new Function(List.of(messageParamType, stateType), stateType);
                typeOfWithExpected(handlerExpression, expected, env, generics, self);
                handler = expected;
            } else {
                Function expected = new Function(List.of(messageParamType), Primitive.VOID);
                typeOfWithExpected(handlerExpression, expected, env, generics, self);
                handler = expected;
            }
        } else {
            Type handlerType = typeOf(handlerExpression, env, generics, self);
            if (!(handlerType instanceof Function fn)) {
                throw new IllegalArgumentException(api + " handler must be a function");
            }
            handler = fn;
            if (handler.parameters().size() != expectedArity) {
                throw new IllegalArgumentException(
                        api + " handler must accept exactly " + expectedArity + " argument(s)");
            }
        }

        Type messageType = handler.parameters().getFirst();
        requireActorTransportType(messageType, api + " mailbox type", new HashSet<>());
        if (messageType instanceof Named namedMessage && namedMessage.name().equals("Shared")) {
            throw new IllegalArgumentException(
                    api + " mailbox type must be T, not Shared<T>; Shared<T> is a send-time transport handle");
        }
        if (stateful) {
            Type stateType = handler.parameters().get(1);
            requireActorTransportType(stateType, api + " state type", new HashSet<>());
            Type initialType = unwrapShared(typeOfWithExpected(
                    arguments.get(stateIndex),
                    stateType,
                    env,
                    generics,
                    self));
            requireAssignable(initialType, stateType, api + " initial state");
            requireAssignable(handler.result(), stateType, api + " handler return state");
        } else {
            requireAssignable(handler.result(), Primitive.VOID, api + " stateless handler return");
        }

        if (singleton
                && arguments.getFirst() instanceof Ast.LiteralExpr literal
                && literal.value() instanceof String singletonName) {
            Function previous = singletonActorHandlers.putIfAbsent(singletonName, handler);
            if (previous != null
                    && (!Types.isAssignable(previous, handler) || !Types.isAssignable(handler, previous))) {
                throw new IllegalArgumentException(
                        "actor.singleton '" + singletonName + "' was already declared with an incompatible handler type");
            }
        }

        return new Named("ActorRef", List.of(messageType));
    }

    private Type processBuiltinCall(
            String member,
            List<Ast.Expr> arguments,
            Env env,
            Set<String> generics,
            Type self) {
        return switch (member) {
            case "gc" -> {
                requireBuiltinArity("process.gc", arguments, 0);
                yield gcResultType();
            }
            case "share_readonly" -> {
                requireBuiltinArity("process.share_readonly", arguments, 1);
                Type value = typeOf(arguments.getFirst(), env, generics, self);
                requireActorTransportType(value, "process.share_readonly value", new HashSet<>());
                yield new Named("Shared", List.of(value));
            }
            default -> throw new IllegalArgumentException("unknown process API '" + member + "'");
        };
    }

    private void requireActorTransportType(Type type, String where, Set<Type> seen) {
        Type actual = deref(type);
        if (actual == Unknown.INSTANCE || actual instanceof Generic || actual instanceof StringLiteral) return;
        if (!seen.add(actual)) return;

        if (actual instanceof Function || actual instanceof ClassNamespace || type instanceof Borrow) {
            throw new IllegalArgumentException(where + " cannot contain callable, namespace, or borrowed values");
        }
        if (actual == Primitive.VOID) {
            throw new IllegalArgumentException(where + " cannot be void");
        }
        if (actual instanceof ListType list) {
            requireActorTransportType(list.element(), where, seen);
            return;
        }
        if (actual instanceof Tuple tuple) {
            for (Type element : tuple.elements()) requireActorTransportType(element, where, seen);
            return;
        }
        if (actual instanceof Record record) {
            for (Type member : record.members().values()) requireActorTransportType(member, where, seen);
            return;
        }
        if (actual instanceof Named named) {
            if (named.name().equals("Shared")) {
                throw new IllegalArgumentException(
                        where + " must use the underlying T; Shared<T> is a transport handle, not a delivered value");
            }
            for (Type argument : named.arguments()) {
                requireActorTransportType(argument, where, seen);
            }

            Ast.ClassDecl klass = findClass(named.name());
            if (klass != null) {
                Set<String> classGenerics = Set.copyOf(klass.genericParameters());
                for (Ast.FieldDecl field : effectiveFields(klass, new LinkedHashSet<>())) {
                    requireActorTransportType(
                            resolve(field.type(), classGenerics, named),
                            where + " field " + klass.name() + "." + field.name(),
                            seen);
                }
            }
        }
    }

    private Type actorMessageType(Type type, String where) {
        Type actual = deref(type);
        if (actual == Unknown.INSTANCE) return Unknown.INSTANCE;
        if (actual instanceof Named named
                && named.name().equals("ActorRef")
                && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        throw new IllegalArgumentException(where + " must be ActorRef<T>");
    }

    private Type unwrapShared(Type type) {
        Type actual = deref(type);
        if (actual instanceof Named named
                && named.name().equals("Shared")
                && named.arguments().size() == 1) {
            return named.arguments().getFirst();
        }
        return actual;
    }

    private Record downMessageType() {
        return new Record(Map.of(
                "event", Primitive.STRING,
                "reason", Primitive.STRING,
                "monitor_id", Primitive.STRING,
                "actor_id", Primitive.STRING));
    }

    private Record gcResultType() {
        return new Record(Map.of(
                "scope", Primitive.STRING,
                "semantic_policy", Primitive.STRING,
                "backing_collector", Primitive.STRING,
                "host_gc_hint_enabled", Primitive.BOOL,
                "host_gc_hint_executed", Primitive.BOOL,
                "actor_local_physical_collection", Primitive.BOOL));
    }

    private Record actorStatusType() {
        return new Record(Map.of(
                "id", Primitive.STRING,
                "state", Primitive.STRING,
                "mailbox_messages", Primitive.INT,
                "mailbox_bytes", Primitive.INT,
                "active_message_bytes", Primitive.INT,
                "owned_state_bytes", Primitive.INT,
                "estimated_actor_heap_bytes", Primitive.INT,
                "heap_backend", Primitive.STRING,
                "physical_heap_isolation", Primitive.BOOL,
                "manual_gc_requests", Primitive.INT));
    }

    private void requireBuiltinArity(String api, List<Ast.Expr> arguments, int arity) {
        if (arguments.size() != arity) {
            throw new IllegalArgumentException(api + " expects exactly " + arity + " argument(s)");
        }
    }

    private Type nominalClassType(Ast.ClassDecl klass) {
        return new Named(qualifiedClassName(klass), klass.genericParameters().stream().map(Generic::new).map(Type.class::cast).toList());
    }

    private Map<String, Type> classGenericBindings(Ast.ClassDecl klass, Named instance) {
        if (klass.genericParameters().size() != instance.arguments().size()) return Map.of();
        Map<String, Type> bindings = new LinkedHashMap<>();
        for (int i = 0; i < klass.genericParameters().size(); i++) {
            bindings.put(klass.genericParameters().get(i), instance.arguments().get(i));
        }
        return bindings;
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
        else if (expr instanceof Ast.MatchExpr e) {
            collectCalls(e.scrutinee(), module, out);
            for (Ast.MatchArm arm : e.arms()) {
                if (arm.guard() != null) collectCalls(arm.guard(), module, out);
                collectCalls(arm.result(), module, out);
            }
        }
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
        private final boolean descendantsNonLexical;
        private final Map<String, Binding> bindings = new HashMap<>();
        private Env(Env parent) { this(parent, parent != null && parent.descendantsNonLexical); }
        private Env(Env parent, boolean descendantsNonLexical) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }
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
