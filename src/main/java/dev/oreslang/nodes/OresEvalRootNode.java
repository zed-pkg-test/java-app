package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.imports.ImportRules;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import dev.oreslang.runtime.OresSymbol;
import dev.oreslang.runtime.PatternMatchError;
import dev.oreslang.runtime.ProcessGlobalRegistry;
import dev.oreslang.runtime.MemorySlotSingletonRegistry;
import dev.oreslang.runtime.SandboxForbiddenCapability;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.ExecutionTerminated;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/** Executable Truffle root. Parsing and static checks happen before this node is created. */
public final class OresEvalRootNode extends RootNode {
    public static final String LINK_ONLY_COMMAND = "__ores_internal_link_only__";
    public static final String INIT_ONLY_COMMAND = "__ores_internal_init_only__";
    public static final String MAIN_ONLY_COMMAND = "__ores_internal_main_only__";
    public static final String INVOKE_PUBLIC_COMMAND = "__ores_internal_invoke_public__";

    private final Ast.Program program;
    private final String codeUnitId;
    private volatile Evaluator evaluator;

    public OresEvalRootNode(OresLanguage language, Ast.Program program) {
        this(language, program, "<anonymous>");
    }

    public OresEvalRootNode(OresLanguage language, Ast.Program program, String codeUnitId) {
        super(language);
        this.program = program;
        this.codeUnitId = codeUnitId;
    }

    @Override public String getName() { return "ores-eval"; }
    @Override public boolean isInternal() { return true; }

    @Override
    public Object execute(VirtualFrame frame) {
        return executeBoundary(OresContext.get(this), frame.getArguments());
    }

    @TruffleBoundary
    private Object executeBoundary(OresContext context, Object[] arguments) {
        CapabilityChecker.check(program, context.isolatePolicy());
        Evaluator current = evaluator(context);
        if (isControl(arguments, LINK_ONLY_COMMAND)) {
            current.link();
            return null;
        }
        if (isControl(arguments, INIT_ONLY_COMMAND)) {
            current.link();
            return current.initialize();
        }
        if (isControl(arguments, MAIN_ONLY_COMMAND)) {
            current.link();
            return current.executeMain(new Object[0]);
        }
        if (arguments.length >= 2
                && INVOKE_PUBLIC_COMMAND.equals(arguments[0])
                && arguments[1] instanceof String functionName) {
            current.link();
            return current.invokePublic(functionName, java.util.Arrays.copyOfRange(arguments, 2, arguments.length));
        }

        // Backward-compatible single-source execution. Multi-file hosts use
        // link/init/main commands to install a full import graph before init.
        current.link();
        current.initialize();
        return current.executeMain(arguments);
    }

    private Evaluator evaluator(OresContext context) {
        Evaluator current = evaluator;
        if (current != null) return current;
        synchronized (this) {
            current = evaluator;
            if (current == null) evaluator = current = new Evaluator(program, context, codeUnitId);
            return current;
        }
    }

    private static boolean isControl(Object[] arguments, String command) {
        return arguments.length == 1 && command.equals(arguments[0]);
    }

    private static final class Evaluator {
        private final Ast.Program program;
        private final OresContext context;
        private final String codeUnitId;
        private final Map<String, Ast.FunctionDecl> functions = new HashMap<>();
        private final Map<String, Ast.ClassDecl> classes = new HashMap<>();
        private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
        private final Map<String, ImportedBinding> namedImports = new HashMap<>();
        private final Map<String, Ast.ImportDecl> namespaceImports = new HashMap<>();
        private final Map<String, HostClassFacade> hostClasses = new HashMap<>();
        private final Map<String, Invokable> hostFunctions = new HashMap<>();
        private final Map<String, HostClassFacade> hostSymbols = new HashMap<>();
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final Set<String> ambiguousTypeAliases = new LinkedHashSet<>();
        private boolean initialized;

        private Evaluator(Ast.Program program, OresContext context, String codeUnitId) {
            this.program = program;
            this.context = context;
            this.codeUnitId = normalizeUnitId(codeUnitId);
            indexImports();
            indexDeclarations();
        }

        private void indexImports() {
            for (Ast.ImportDecl imported : program.imports()) {
                ImportRules.validate(imported);
                if (ImportRules.isJavaPath(imported.path())) {
                    indexHostImport(imported);
                    continue;
                }

                if (imported.wildcard()) {
                    namespaceImports.put(imported.namespace(), imported);
                } else {
                    for (String sourceName : imported.names()) {
                        String localName = ImportRules.localName(imported, sourceName);
                        ImportedBinding previous = namedImports.putIfAbsent(
                                localName,
                                new ImportedBinding(imported, sourceName));
                        if (previous != null) {
                            throw new IllegalArgumentException("duplicate import binding " + localName);
                        }
                    }
                }
            }
        }

        private void indexHostImport(Ast.ImportDecl imported) {
            String className = ImportRules.javaClassName(imported.path());
            HostClassFacade symbol = hostSymbols.computeIfAbsent(
                    className,
                    ignored -> new HostClassFacade(className, context.lookupHostSymbol(className), true));

            if (imported.kind() == Ast.ImportKind.CLASS) {
                String sourceName = imported.names().getFirst();
                putHostClass(ImportRules.localName(imported, sourceName), symbol);
                return;
            }

            if (imported.kind() == Ast.ImportKind.FUNCTION && imported.wildcard()) {
                putHostClass(imported.namespace(), new HostClassFacade(className, symbol.symbol(), false));
                return;
            }

            if (imported.kind() == Ast.ImportKind.FUNCTION) {
                InteropLibrary interop = InteropLibrary.getUncached(symbol.symbol());
                for (String sourceName : imported.names()) {
                    if (!interop.isMemberInvocable(symbol.symbol(), sourceName)) {
                        throw new IllegalArgumentException("Java host class '" + className
                                + "' does not export invocable static member '" + sourceName + "'");
                    }
                    String localName = ImportRules.localName(imported, sourceName);
                    putHostFunction(localName, args -> {
                        context.requireCapability(
                                IsolatePolicy.Capability.JAVA_INTEROP,
                                "Java host method " + className + "." + sourceName);
                        return invokeHostMember(symbol.symbol(), sourceName, args);
                    });
                }
                return;
            }

            if (imported.kind() == Ast.ImportKind.ALL) {
                putHostClass(imported.namespace(), symbol);
                return;
            }

            throw new IllegalArgumentException("unsupported Java host import kind: " + imported.kind());
        }

        private void putHostClass(String name, HostClassFacade value) {
            if (hostClasses.putIfAbsent(name, value) != null || hostFunctions.containsKey(name)) {
                throw new IllegalArgumentException("duplicate Java host import binding " + name);
            }
        }

        private void putHostFunction(String name, Invokable value) {
            if (hostFunctions.putIfAbsent(name, value) != null || hostClasses.containsKey(name)) {
                throw new IllegalArgumentException("duplicate Java host import binding " + name);
            }
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    else if (decl instanceof Ast.TypeAliasDecl alias) index(typeAliases, ambiguousTypeAliases, module.name(), alias.name(), alias);
                }
            }
        }

        private static <T> void index(Map<String, T> map, Set<String> ambiguous, String module, String name, T value) {
            map.put(module + "." + name, value);
            T previous = map.putIfAbsent(name, value);
            if (previous != null && previous != value) {
                ambiguous.add(name);
                map.remove(name);
            }
        }

        private Ast.FunctionDecl findFunction(String name) {
            if (ambiguousFunctions.contains(name)) throw new IllegalArgumentException("ambiguous function " + name + "; qualify it with its module");
            return functions.get(name);
        }

        private Ast.ClassDecl findClass(String name) {
            if (ambiguousClasses.contains(name)) throw new IllegalArgumentException("ambiguous class " + name + "; qualify it with its module");
            return classes.get(name);
        }

        private Ast.TypeAliasDecl findTypeAlias(String name) {
            if (ambiguousTypeAliases.contains(name)) throw new IllegalArgumentException("ambiguous type alias " + name + "; qualify it with its module");
            return typeAliases.get(name);
        }

        private synchronized void link() {
            context.registerLinkedCodeUnit(codeUnitId, this);
        }

        private synchronized Object initialize() {
            if (initialized) return null;
            // Mark before invocation so a recursive path cannot run init twice.
            initialized = true;
            Ast.FunctionDecl init = functions.get(Parser.ROOT_MODULE + ".init");
            if (init == null) return null;
            return callFunction(init, List.of());
        }

        private Object executeMain(Object[] arguments) {
            Ast.FunctionDecl main = functions.get(Parser.ROOT_MODULE + ".main");
            if (main == null) main = findFunction("main");
            if (main == null) return null;
            return callFunction(main, List.of(arguments));
        }

        private Object invokePublic(String name, Object[] arguments) {
            Ast.FunctionDecl fn = findFunction(name);
            if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) {
                throw new IllegalArgumentException("code unit '" + codeUnitId
                        + "' does not export public function '" + name + "'");
            }
            Object result = callFunction(fn, java.util.Arrays.asList(arguments));
            return result instanceof HostObjectFacade host ? host.value() : result;
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            List<?> normalized = normalizeFunctionArguments(fn, args);
            if (fn.actorKind() == Ast.ActorKind.NONE) {
                return callFunctionBody(fn, normalized);
            }

            ActorRuntime.ActorKind runtimeKind = switch (fn.actorKind()) {
                case NONE -> throw new AssertionError("non-actor callable reached actor lowering");
                case PRIVATE -> ActorRuntime.ActorKind.PRIVATE;
                case SHARED -> ActorRuntime.ActorKind.SHARED;
            };

            return context.actors().invoke(
                    runtimeKind,
                    normalized,
                    (delivered, actorContext) -> callFunctionBody(fn, delivered));
        }

        private List<?> normalizeFunctionArguments(Ast.FunctionDecl fn, List<?> args) {
            if (args.size() != fn.parameters().size()) {
                if (fn.parameters().isEmpty()
                        && args.size() == 1
                        && args.getFirst() instanceof Object[] array
                        && array.length == 0) {
                    return List.of();
                }
                throw new IllegalArgumentException(
                        "function " + fn.name() + " expects " + fn.parameters().size()
                                + " arguments, got " + args.size());
            }
            return args;
        }

        private Object callFunctionBody(Ast.FunctionDecl fn, List<?> args) {
            Env env = new Env(null, fn.nonLexical());
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(
                        fn.returnType(),
                        signal.value,
                        "function " + fn.name());
            }
        }

        private Object callMethod(OresObject receiver, Ast.MethodDecl method, List<?> args) {
            if (args.size() != method.parameters().size()) throw new IllegalArgumentException("method " + method.name() + " arity mismatch");
            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) { return shapeReturnedValue(method.returnType(), signal.value, "method " + method.name()); }
        }

        private void executeBlock(List<Ast.Stmt> statements, Env parent) {
            Env env = new Env(parent);
            ArrayDeque<Ast.Expr> deferred = new ArrayDeque<>();
            boolean abnormalExit = false;
            try {
                for (Ast.Stmt stmt : statements) executeStatement(stmt, env, deferred);
            } catch (ReturnSignal signal) {
                throw signal;
            } catch (RuntimeException | Error failure) {
                abnormalExit = true;
                throw failure;
            } finally {
                boolean deferredFailure = false;
                try {
                    while (!deferred.isEmpty()) eval(deferred.pop(), env);
                } catch (RuntimeException | Error failure) {
                    deferredFailure = true;
                    throw failure;
                } finally {
                    env.releaseMutexGuards(abnormalExit || deferredFailure);
                }
            }
        }

        private void executeStatement(Ast.Stmt stmt, Env env, ArrayDeque<Ast.Expr> deferred) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    env.reserve(binding.name(), binding.kind());
                    env.initialize(binding.name(), eval(binding.initializer(), env));
                } else {
                    env.define(binding.name(), eval(binding.initializer(), env), binding.kind());
                }
                return;
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                Object value = eval(destructure.initializer(), env);
                if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
                    List<?> items = asSequence(value);
                    if (items.size() != destructure.bindings().size()) {
                        throw new IllegalArgumentException("destructure arity mismatch: value has " + items.size()
                                + " element(s), pattern has " + destructure.bindings().size());
                    }
                    for (int i = 0; i < items.size(); i++) {
                        Ast.DestructureBinding binding = destructure.bindings().get(i);
                        if (!binding.isDiscard()) env.define(binding.name(), items.get(i), binding.kind());
                    }
                } else {
                    for (Ast.DestructureBinding binding : destructure.bindings()) {
                        if (!binding.isDiscard()) {
                            env.define(binding.name(), destructureMember(value, binding.name()), binding.kind());
                        }
                    }
                }
                return;
            }
            if (stmt instanceof Ast.ReturnStmt ret) throw new ReturnSignal(ret.value() == null ? null : eval(ret.value(), env));
            if (stmt instanceof Ast.ExprStmt expression) { eval(expression.expression(), env); return; }
            if (stmt instanceof Ast.DeferStmt defer) { deferred.push(defer.expression()); return; }
            if (stmt instanceof Ast.IfStmt ifStmt) {
                for (Ast.IfBranch branch : ifStmt.branches()) {
                    if (truth(eval(branch.condition(), env))) { executeBlock(branch.body(), env); return; }
                }
                executeBlock(ifStmt.elseBody(), env);
                return;
            }
            if (stmt instanceof Ast.SwitchStmt switched) {
                Object subject = eval(switched.subject(), env);
                for (Ast.SwitchClause clause : switched.clauses()) {
                    Env clauseEnv = new Env(env);
                    if (!matchPattern(clause.pattern(), subject, clauseEnv)) continue;
                    if (clause.guard() != null) {
                        requirePurePatternGuard(clause.guard());
                        if (!truth(evalPatternGuard(clause.guard(), clauseEnv))) continue;
                    }
                    executeBlock(clause.body(), clauseEnv);
                    return;
                }
                throw new PatternMatchError(
                        "no switch pattern matched value of type "
                                + (subject == null ? "null" : subject.getClass().getSimpleName()));
            }
            if (stmt instanceof Ast.TryStmt tried) {
                try { executeBlock(tried.body(), env); }
                catch (ReturnSignal signal) { throw signal; }
                catch (OresPanic panic) { throw panic; }
                catch (ExecutionTerminated terminated) { throw terminated; }
                catch (RuntimeException failure) {
                    Env catchEnv = new Env(env);
                    catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                    executeBlock(tried.catchBody(), catchEnv);
                } finally { executeBlock(tried.finallyBody(), env); }
                return;
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                Object iterable = eval(loop.iterable(), env);
                for (Object item : iterableValues(iterable)) {
                    context.schedulerSafepoint();
                    Env iteration = new Env(env);
                    iteration.define(loop.bindingName(), item, loop.bindingKind());
                    executeBlock(loop.body(), iteration);
                }
                return;
            }
            if (stmt instanceof Ast.ForStmt loop) {
                Env loopEnv = new Env(env);
                if (loop.initializer() != null) executeStatement(loop.initializer(), loopEnv, new ArrayDeque<>());
                while (loop.condition() == null || truth(eval(loop.condition(), loopEnv))) {
                    context.schedulerSafepoint();
                    executeBlock(loop.body(), loopEnv);
                    if (loop.update() != null) eval(loop.update(), loopEnv);
                }
            }
        }

        private boolean matchPattern(Ast.Pattern pattern, Object value, Env env) {
            if (pattern instanceof Ast.WildcardPattern) return true;

            if (pattern instanceof Ast.BindingPattern binding) {
                env.define(binding.name(), value, Ast.BindingKind.VAL);
                return true;
            }

            if (pattern instanceof Ast.LiteralPattern literal) {
                Object expected = literal.value() instanceof Ast.Symbol symbol
                        ? OresSymbol.processLiteral(symbol.name(), context.isolatePolicy())
                        : literal.value();
                return Objects.equals(expected, value);
            }

            if (pattern instanceof Ast.TypePattern typed) {
                if (!matchesNominalType(value, typed.type().name())) return false;
                if (typed.bindingName() != null) {
                    env.define(typed.bindingName(), value, Ast.BindingKind.VAL);
                }
                return true;
            }

            if (pattern instanceof Ast.SequencePattern sequence) {
                if (!(value instanceof List<?> list)
                        || list.size() != sequence.elements().size()) {
                    return false;
                }
                for (int i = 0; i < list.size(); i++) {
                    if (!matchPattern(sequence.elements().get(i), list.get(i), env)) {
                        return false;
                    }
                }
                return true;
            }

            if (pattern instanceof Ast.ObjectPattern objectPattern) {
                for (Ast.ObjectPatternField field : objectPattern.fields()) {
                    Object child;
                    if (value instanceof OresObject object) {
                        Ast.FieldDecl declaration = effectiveFields(
                                        object.klass, new LinkedHashSet<>())
                                .stream()
                                .filter(candidate -> candidate.name().equals(field.name()))
                                .findFirst()
                                .orElse(null);
                        if (declaration == null
                                || declaration.visibility() != Ast.Visibility.PUBLIC
                                || !object.fields.containsKey(field.name())) {
                            return false;
                        }
                        child = object.fields.get(field.name());
                    } else if (value instanceof DynamicStructValue dynamic) {
                        if (!dynamic.fields.containsKey(field.name())) return false;
                        child = dynamic.fields.get(field.name());
                    } else if (value instanceof Map<?, ?> map) {
                        if (!map.containsKey(field.name())) return false;
                        child = map.get(field.name());
                    } else {
                        return false;
                    }
                    if (!matchPattern(field.pattern(), child, env)) return false;
                }
                return true;
            }

            if (pattern instanceof Ast.VariantPattern variant) {
                String name = variant.name();

                if (name.equals("Ok") || name.equals("Err")) {
                    if (!(value instanceof ResultValue result)
                            || result.ok() != name.equals("Ok")
                            || variant.arguments().size() != 1) {
                        return false;
                    }
                    return matchPattern(variant.arguments().getFirst(), result.value(), env);
                }

                if (name.equals("Some")) {
                    if (!(value instanceof OptionValue option)
                            || !option.present()
                            || variant.arguments().size() != 1) {
                        return false;
                    }
                    return matchPattern(variant.arguments().getFirst(), option.value(), env);
                }

                if (name.equals("None")) {
                    return value instanceof OptionValue option
                            && !option.present()
                            && variant.arguments().isEmpty();
                }

                if (value instanceof OresObject object && matchesNominalType(object, name)) {
                    List<Ast.FieldDecl> fields =
                            effectiveFields(object.klass, new LinkedHashSet<>())
                                    .stream()
                                    .filter(field -> field.visibility() == Ast.Visibility.PUBLIC)
                                    .toList();
                    if (fields.size() != variant.arguments().size()) return false;
                    for (int i = 0; i < fields.size(); i++) {
                        Object child = object.fields.get(fields.get(i).name());
                        if (!matchPattern(variant.arguments().get(i), child, env)) return false;
                    }
                    return true;
                }

                return variant.arguments().isEmpty() && matchesNominalType(value, name);
            }

            throw new IllegalStateException(
                    "unsupported switch pattern " + pattern.getClass().getSimpleName());
        }

        private boolean matchesNominalType(Object value, String typeName) {
            if (value == null) return false;
            if (value instanceof OresObject object) {
                return classIsOrExtends(object.klass, typeName, new LinkedHashSet<>());
            }
            for (Class<?> type = value.getClass(); type != null; type = type.getSuperclass()) {
                if (type.getSimpleName().equals(typeName) || type.getName().equals(typeName)) {
                    return true;
                }
            }
            for (Class<?> iface : value.getClass().getInterfaces()) {
                if (iface.getSimpleName().equals(typeName) || iface.getName().equals(typeName)) {
                    return true;
                }
            }
            return false;
        }

        private boolean classIsOrExtends(
                Ast.ClassDecl klass,
                String typeName,
                Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) return false;
            if (klass.name().equals(typeName)) return true;
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals(typeName)) return true;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent != null && classIsOrExtends(parent, typeName, seen)) return true;
            }
            return false;
        }

        /**
         * Runtime defense in depth for Erlang-style guards. Compiler checking
         * applies the same rule, but malformed AST must not smuggle effects into
         * clause selection.
         */
        private static void requirePurePatternGuard(Ast.Expr expr) {
            if (expr instanceof Ast.LiteralExpr || expr instanceof Ast.NameExpr) return;
            if (expr instanceof Ast.MemberExpr member) {
                requirePurePatternGuard(member.receiver());
                return;
            }
            if (expr instanceof Ast.IndexExpr indexed) {
                requirePurePatternGuard(indexed.receiver());
                requirePurePatternGuard(indexed.index());
                return;
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                if (unary.operator().equals("&") || unary.operator().equals("&mut")) {
                    throw new IllegalArgumentException(
                            "pattern guard cannot borrow or mutate state");
                }
                requirePurePatternGuard(unary.operand());
                return;
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                requirePurePatternGuard(binary.left());
                requirePurePatternGuard(binary.right());
                return;
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                requirePurePatternGuard(conditional.condition());
                requirePurePatternGuard(conditional.whenTrue());
                requirePurePatternGuard(conditional.whenFalse());
                return;
            }
            throw new IllegalArgumentException(
                    "pattern guard must be side-effect free; calls, assignment, allocation, await, "
                            + "lambdas, and container construction are not allowed");
        }

        private Object evalPatternGuard(Ast.Expr expr, Env env) {
            if (expr instanceof Ast.LiteralExpr literal) {
                Object value = literal.value() instanceof Ast.Symbol symbol
                        ? OresSymbol.processLiteral(symbol.name(), context.isolatePolicy())
                        : literal.value();
                return requireGuardData(value);
            }
            if (expr instanceof Ast.NameExpr name) {
                Object value = env.lookup(name.name());
                if (value == Env.MISSING) {
                    throw new IllegalArgumentException(
                            "pattern guard may reference only clause/local data; unknown or authority-bearing name '"
                                    + name.name() + "'");
                }
                return requireGuardData(value);
            }
            if (expr instanceof Ast.MemberExpr member) {
                Object receiver = requireGuardData(evalPatternGuard(member.receiver(), env));
                if (receiver instanceof OresObject object) {
                    Ast.FieldDecl field = effectiveFields(object.klass, new LinkedHashSet<>())
                            .stream()
                            .filter(candidate -> candidate.name().equals(member.member()))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "pattern guard references unknown field " + member.member()));
                    boolean selfAccess = member.receiver() instanceof Ast.NameExpr name
                            && name.name().equals("self");
                    if (field.visibility() != Ast.Visibility.PUBLIC && !selfAccess) {
                        throw new IllegalArgumentException(
                                "pattern guard cannot read private field '" + member.member()
                                        + "' except through self");
                    }
                    return requireGuardData(object.fields.get(member.member()));
                }
                if (receiver instanceof DynamicStructValue dynamic) {
                    if (!dynamic.fields.containsKey(member.member())) {
                        throw new IllegalArgumentException(
                                "pattern guard references unknown DynamicStruct member " + member.member());
                    }
                    return requireGuardData(dynamic.fields.get(member.member()));
                }
                if (receiver instanceof Map<?, ?> map) {
                    if (!map.containsKey(member.member())) {
                        throw new IllegalArgumentException(
                                "pattern guard references unknown map member " + member.member());
                    }
                    return requireGuardData(map.get(member.member()));
                }
                throw new IllegalArgumentException(
                        "pattern guard member reads are limited to Oreslang data objects/maps");
            }
            if (expr instanceof Ast.IndexExpr indexed) {
                Object receiver = requireGuardData(evalPatternGuard(indexed.receiver(), env));
                Object index = requireGuardData(evalPatternGuard(indexed.index(), env));
                if (receiver instanceof DynamicStructValue dynamic) {
                    if (!(index instanceof String key) || !dynamic.fields.containsKey(key)) {
                        throw new IllegalArgumentException("invalid DynamicStruct guard index");
                    }
                    return requireGuardData(dynamic.fields.get(key));
                }
                if (receiver instanceof Map<?, ?> map) {
                    if (!map.containsKey(index)) {
                        throw new IllegalArgumentException("unknown map key in pattern guard");
                    }
                    return requireGuardData(map.get(index));
                }
                if (!(index instanceof Number number)) {
                    throw new IllegalArgumentException("pattern guard sequence index must be an integer");
                }
                int i = Math.toIntExact(number.longValue());
                if (receiver instanceof List<?> list) return requireGuardData(list.get(i));
                if (receiver instanceof Object[] array) return requireGuardData(array[i]);
                throw new IllegalArgumentException(
                        "pattern guard indexing is limited to data lists/maps");
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                Object value = evalPatternGuard(unary.operand(), env);
                return switch (unary.operator()) {
                    case "!" -> !truth(value);
                    case "~" -> ~integralLong(value);
                    case "+" -> value;
                    case "-" -> negate(value);
                    default -> throw new IllegalArgumentException(
                            "unsupported pattern guard unary operator " + unary.operator());
                };
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (binary.operator().equals("&&")) {
                    Object left = evalPatternGuard(binary.left(), env);
                    return truth(left) && truth(evalPatternGuard(binary.right(), env));
                }
                if (binary.operator().equals("||")) {
                    Object left = evalPatternGuard(binary.left(), env);
                    return truth(left) || truth(evalPatternGuard(binary.right(), env));
                }
                if (binary.operator().equals("^^")) {
                    return truth(evalPatternGuard(binary.left(), env))
                            ^ truth(evalPatternGuard(binary.right(), env));
                }
                Object left = evalPatternGuard(binary.left(), env);
                Object right = evalPatternGuard(binary.right(), env);
                if (binary.operator().equals("|")
                        && left instanceof Boolean lb
                        && right instanceof Boolean rb) {
                    return lb || rb;
                }
                return requireGuardData(binary(binary.operator(), left, right));
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                return truth(evalPatternGuard(conditional.condition(), env))
                        ? evalPatternGuard(conditional.whenTrue(), env)
                        : evalPatternGuard(conditional.whenFalse(), env);
            }
            throw new IllegalArgumentException(
                    "pattern guard contains an effectful or authority-bearing expression");
        }

        private Object requireGuardData(Object value) {
            if (value instanceof SandboxForbiddenCapability
                    || value instanceof OresMutex.Local<?>
                    || value instanceof OresMutex.Shared<?>
                    || value instanceof OresMutex.Guard<?>
                    || value instanceof CompletionStage<?>
                    || value instanceof Invokable
                    || value instanceof HostClassFacade
                    || value instanceof HostObjectFacade
                    || value instanceof ModuleFacade
                    || value instanceof ClassFacade
                    || value instanceof ImportedNamespace
                    || value instanceof ProcessFacade
                    || value instanceof ActorFacade
                    || value instanceof StdioFacade
                    || value instanceof StdoutFacade) {
                throw new IllegalArgumentException(
                        "pattern guard cannot observe a live capability, callable, host object, or module authority");
            }
            return value;
        }

        private Object eval(Ast.Expr expr, Env env) {
            if (expr instanceof Ast.LiteralExpr literal) {
                if (literal.value() == null) throw new IllegalArgumentException("standalone null values are forbidden");
                if (literal.value() instanceof Ast.Imaginary imaginary) return new Complex(0.0, imaginary.coefficient());
                if (literal.value() instanceof Ast.Symbol symbol) {
                    return OresSymbol.processLiteral(symbol.name(), context.isolatePolicy());
                }
                return literal.value();
            }
            if (expr instanceof Ast.NameExpr name) {
                Object local = env.lookup(name.name());
                if (local != Env.MISSING) return local;
                if (name.name().equals("stdio")) return new StdioFacade(context);
                if (name.name().equals("process")) return new ProcessFacade(context);
                if (name.name().equals("actor")) return new ActorFacade(context);
                if (name.name().equals("Mutex")) return new MutexFactory(false, context);
                if (name.name().equals("SharedMutex")) return new MutexFactory(true, context);
                if (name.name().equals("print")) return (Invokable) args -> {
                    context.requireCapability(IsolatePolicy.Capability.STDOUT, "print");
                    requireOne(args, "print"); context.output().print(display(args.getFirst())); context.output().flush(); return null;
                };
                if (name.name().equals("Some")) return (Invokable) args -> {
                    requireOne(args, "Some");
                    return new OptionValue(true, args.getFirst());
                };
                if (name.name().equals("None")) return new OptionValue(false, null);
                if (name.name().equals("Ok")) return (Invokable) args -> {
                    requireOne(args, "Ok");
                    return new ResultValue(true, args.getFirst());
                };
                if (name.name().equals("Err")) return (Invokable) args -> {
                    requireOne(args, "Err");
                    return new ResultValue(false, args.getFirst());
                };
                HostClassFacade hostClass = hostClasses.get(name.name());
                if (hostClass != null) {
                    context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                            "Java host class " + hostClass.className());
                    return hostClass;
                }
                Invokable hostFunction = hostFunctions.get(name.name());
                if (hostFunction != null) return hostFunction;
                Ast.ModuleDecl module = modules.get(name.name());
                if (module != null) return new ModuleFacade(this, module);
                Ast.ClassDecl klass = findClass(name.name());
                if (klass != null) return new ClassFacade(this, klass);
                Object imported = importedValue(name.name());
                if (imported != Env.MISSING) return imported;
                Ast.FunctionDecl fn = findFunction(name.name());
                if (fn != null) return (Invokable) args -> callFunction(fn, args);
                throw new IllegalArgumentException("unknown name " + name.name());
            }
            if (expr instanceof Ast.AssignExpr assignment) {
                Object value = eval(assignment.value(), env);
                if (assignment.target() instanceof Ast.NameExpr target) {
                    env.assign(target.name(), value);
                    return value;
                }
                if (assignment.target() instanceof Ast.MemberExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    if (receiver instanceof OresMutex.Guard<?> guard) receiver = guard.value();
                    if (receiver instanceof OresObject object) {
                        if (!object.fields.containsKey(target.member())) {
                            throw new IllegalArgumentException("unknown field " + target.member());
                        }
                        Ast.FieldDecl field = effectiveFields(object.klass, new LinkedHashSet<>()).stream()
                                .filter(candidate -> candidate.name().equals(target.member()))
                                .findFirst()
                                .orElseThrow(() -> new IllegalArgumentException("unknown field " + target.member()));
                        boolean selfAccess = target.receiver() instanceof Ast.NameExpr name
                                && name.name().equals("self");
                        if (field.visibility() != Ast.Visibility.PUBLIC && !selfAccess) {
                            throw new IllegalArgumentException(
                                    "private field '" + object.klass.name() + "."
                                            + target.member() + "' is only assignable through self");
                        }
                        if (field.bindingKind() != Ast.BindingKind.LET) {
                            throw new IllegalArgumentException("field '" + object.klass.name() + "."
                                    + target.member() + "' is immutable");
                        }
                        object.fields.put(target.member(), value);
                        return value;
                    }
                    if (receiver instanceof DynamicStructValue dynamic) {
                        dynamic.fields.put(target.member(), value);
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance, DynamicStruct, or mutex guard");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    Object index = eval(target.index(), env);
                    if (receiver instanceof DynamicStructValue dynamic) {
                        if (!(index instanceof String key)) {
                            throw new IllegalArgumentException("DynamicStruct key must be a string");
                        }
                        dynamic.fields.put(key, value);
                        return value;
                    }
                    if (!(index instanceof Number number)) throw new IllegalArgumentException("array/list index must be an integer");
                    int i = Math.toIntExact(number.longValue());
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        list.set(i, value);
                        return value;
                    }
                    throw new IllegalArgumentException("indexed assignment requires a mutable array/list or DynamicStruct");
                }
                throw new IllegalArgumentException("unsupported assignment target");
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                return truth(eval(conditional.condition(), env))
                        ? eval(conditional.whenTrue(), env)
                        : eval(conditional.whenFalse(), env);
            }
            if (expr instanceof Ast.UnaryExpr unary) {
                Object value = eval(unary.operand(), env);
                return switch (unary.operator()) {
                    case "&", "&mut" -> value;
                    case "!" -> !truth(value);
                    case "~" -> ~integralLong(value);
                    case "+" -> value;
                    case "-" -> negate(value);
                    default -> throw new IllegalArgumentException("unsupported unary operator " + unary.operator());
                };
            }
            if (expr instanceof Ast.BinaryExpr binary) {
                if (binary.operator().equals("&&")) {
                    Object left = eval(binary.left(), env);
                    return truth(left) && truth(eval(binary.right(), env));
                }
                if (binary.operator().equals("||")) {
                    Object left = eval(binary.left(), env);
                    return truth(left) || truth(eval(binary.right(), env));
                }
                if (binary.operator().equals("^^")) {
                    return truth(eval(binary.left(), env)) ^ truth(eval(binary.right(), env));
                }
                Object left = eval(binary.left(), env);
                Object right = eval(binary.right(), env);
                if (binary.operator().equals("|") && left instanceof Boolean lb && right instanceof Boolean rb) {
                    return lb || rb;
                }
                return binary(binary.operator(), left, right);
            }
            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.MemberExpr methodCall) {
                    Object receiver = eval(methodCall.receiver(), env);
                    List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                    if (receiver instanceof OresObject object) {
                        return object.owner.invokeMethod(object, methodCall.member(), args);
                    }
                    if (receiver instanceof ClassFacade klass) {
                        return klass.owner().invokeStaticFunction(klass.klass(), methodCall.member(), args);
                    }
                    Object callee = member(receiver, methodCall.member());
                    if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                    return invokable.call(args);
                }
                Object callee = eval(call.callee(), env);
                List<Object> args = call.arguments().stream().map(arg -> eval(arg, env)).toList();
                if (!(callee instanceof Invokable invokable)) throw new IllegalArgumentException("value is not callable: " + callee);
                return invokable.call(args);
            }
            if (expr instanceof Ast.MemberExpr member) {
                boolean selfAccess = member.receiver() instanceof Ast.NameExpr name
                        && name.name().equals("self");
                return member(eval(member.receiver(), env), member.member(), selfAccess);
            }
            if (expr instanceof Ast.IndexExpr indexed) {
                Object receiver = eval(indexed.receiver(), env);
                Object index = eval(indexed.index(), env);
                if (receiver instanceof DynamicStructValue dynamic) {
                    if (!(index instanceof String key)) {
                        throw new IllegalArgumentException("DynamicStruct key must be a string");
                    }
                    if (!dynamic.fields.containsKey(key)) {
                        throw new IllegalArgumentException("unknown DynamicStruct key " + key);
                    }
                    return dynamic.fields.get(key);
                }
                if (receiver instanceof Map<?, ?> map) {
                    if (!(index instanceof String key)) {
                        throw new IllegalArgumentException("object/map key must be a string");
                    }
                    if (!map.containsKey(key)) {
                        throw new IllegalArgumentException("unknown object/map key " + key);
                    }
                    return map.get(key);
                }
                if (!(index instanceof Number number)) throw new IllegalArgumentException("array/list index must be an integer");
                int i = Math.toIntExact(number.longValue());
                if (receiver instanceof List<?> list) return list.get(i);
                if (receiver instanceof Object[] array) return array[i];
                throw new IllegalArgumentException("value is not indexable: " + receiver);
            }
            if (expr instanceof Ast.NewExpr created) {
                if (created.type().name().equals("DynamicStruct")) {
                    if (!created.arguments().isEmpty()) {
                        throw new IllegalArgumentException("DynamicStruct<T> constructor takes no positional arguments");
                    }
                    return new DynamicStructValue();
                }
                HostClassFacade hostClass = hostClasses.get(created.type().name());
                if (hostClass != null) {
                    context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                            "Java host constructor " + hostClass.className());
                    if (!hostClass.constructible()) {
                        throw new IllegalArgumentException(
                                "Java function namespace '" + created.type().name() + "' is not constructible");
                    }
                    List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                    return instantiateHost(hostClass, args);
                }

                Ast.ClassDecl klass = findClass(created.type().name());
                Evaluator owner = this;
                if (klass == null) {
                    Object imported = importedValue(created.type().name());
                    if (imported instanceof ClassFacade externalClass) {
                        owner = externalClass.owner();
                        klass = externalClass.klass();
                    }
                }
                if (klass == null) throw new IllegalArgumentException("unknown class " + created.type().name());
                List<Object> args = created.arguments().stream().map(arg -> eval(arg, env)).toList();
                return owner.instantiate(klass, args);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                Object value = eval(awaited.expression(), env);
                if (value instanceof CompletionStage<?> stage) {
                    var future = stage.toCompletableFuture();
                    if (ActorRuntime.inActorExecution() && !future.isDone()) {
                        throw new IllegalStateException(
                                "await would block an actor dispatcher carrier; actor continuation lowering must suspend/resume the mailbox turn");
                    }
                    return future.join();
                }
                return value;
            }
            if (expr instanceof Ast.ListExpr list) {
                ArrayList<Object> result = new ArrayList<>(list.elements().size());
                for (Ast.Expr item : list.elements()) result.add(eval(item, env));
                return result;
            }
            if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().map(item -> eval(item, env)).toList();
            if (expr instanceof Ast.ObjectExpr object) {
                boolean dynamicKeys = object.fields().stream().anyMatch(Ast.ObjectField::isDynamic);
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Ast.ObjectField field : object.fields()) {
                    String key;
                    if (field.isDynamic()) {
                        Object evaluatedKey = eval(field.dynamicName(), env);
                        if (!(evaluatedKey instanceof String stringKey)) {
                            throw new IllegalArgumentException("dynamic obj key must evaluate to a string");
                        }
                        key = stringKey;
                    } else {
                        key = field.name();
                    }
                    if (result.putIfAbsent(key, eval(field.value(), env)) != null) {
                        throw new IllegalArgumentException("duplicate obj field " + key);
                    }
                }
                return dynamicKeys ? new DynamicStructValue(result) : Map.copyOf(result);
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                boolean nonLexical = lambda.nonLexical() || env.descendantsNonLexical();
                Env captured = nonLexical ? null : env.snapshot();
                return (Invokable) args -> {
                    if (args.size() != lambda.parameters().size()) throw new IllegalArgumentException("lambda arity mismatch");
                    Env local = new Env(captured, nonLexical);
                    for (int i = 0; i < lambda.parameters().size(); i++) {
                        Ast.Param param = lambda.parameters().get(i);
                        local.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
                    }
                    if (lambda.expressionBody() != null) return eval(lambda.expressionBody(), local);
                    try { executeBlock(lambda.blockBody(), local); return null; }
                    catch (ReturnSignal signal) { return signal.value; }
                };
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private Object member(Object receiver, String name) {
            return member(receiver, name, false);
        }

        private Object member(Object receiver, String name, boolean allowPrivate) {
            if (receiver instanceof HostClassFacade host) {
                context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host class " + host.className());
                return hostMember(host.symbol(), host.className(), name);
            }
            if (receiver instanceof HostObjectFacade host) {
                context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host object member " + name);
                return hostMember(host.value(), "host object", name);
            }
            if (receiver instanceof StdioFacade stdio) {
                return switch (name) {
                    case "print" -> (Invokable) stdio::print;
                    case "println" -> (Invokable) stdio::println;
                    case "stdout" -> new StdoutFacade(stdio.context());
                    default -> throw new IllegalArgumentException("unknown stdio member " + name);
                };
            }
            if (receiver instanceof StdoutFacade stdout) {
                return switch (name) {
                    case "write" -> (Invokable) stdout::write;
                    case "println" -> (Invokable) stdout::println;
                    default -> throw new IllegalArgumentException("unknown stdout member " + name);
                };
            }
            if (receiver instanceof ProcessFacade process) {
                return switch (name) {
                    case "context_id" -> process.contextId();
                    case "descriptor" -> process.descriptor();
                    case "share_readonly" -> (Invokable) process::shareReadonly;
                    case "gc" -> (Invokable) process::gc;
                    default -> throw new IllegalArgumentException("unknown process member " + name);
                };
            }
            if (receiver instanceof ActorFacade actor) {
                return switch (name) {
                    case "gc" -> (Invokable) actor::gc;
                    default -> throw new IllegalArgumentException("unknown actor member " + name);
                };
            }
            if (receiver instanceof MutexFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown mutex factory member " + name);
                return (Invokable) factory::create;
            }
            if (receiver instanceof OptionValue option) return optionMember(option, name);
            if (receiver instanceof ResultValue result) return resultMember(result, name);
            if (receiver instanceof OresMutex.Lock<?> lock) return mutexMember(lock, name);
            if (receiver instanceof OresMutex.Guard<?> guard) {
                return switch (name) {
                    case "release" -> (Invokable) args -> { requireZero(args, "MutexGuard.release"); guard.release(); return null; };
                    case "is_released" -> (Invokable) args -> { requireZero(args, "MutexGuard.is_released"); return guard.released(); };
                    default -> member(guard.value(), name);
                };
            }
            if (receiver instanceof ImportedNamespace namespace) return namespace.owner().exportValue(namespace.kind(), name);
            if (receiver instanceof ModuleFacade namespace) return namespace.owner().moduleMember(namespace.module(), name);
            if (receiver instanceof GlobalRefFacade global) {
                return global.owner().globalRefMember(global, name);
            }
            if (receiver instanceof SingletonRefFacade singleton) {
                return singleton.owner().singletonRefMember(singleton, name);
            }
            if (receiver instanceof ClassFacade klass) {
                List<Ast.MethodDecl> functions = klass.owner().findStaticFunctionsByName(klass.klass(), name, new LinkedHashSet<>());
                if (functions.size() == 1) {
                    Ast.MethodDecl fn = functions.getFirst();
                    return (Invokable) args -> klass.owner().callStaticFunction(klass.klass(), fn, args);
                }
                if (functions.size() > 1) throw new IllegalArgumentException("overloaded static function " + klass.klass().name() + "." + name + " must be called so arity can select it");
                throw new IllegalArgumentException("unknown static member " + klass.klass().name() + "." + name);
            }
            if (receiver instanceof OresObject object) {
                if (object.fields.containsKey(name)) {
                    Ast.FieldDecl field = object.owner.effectiveFields(
                                    object.klass, new LinkedHashSet<>())
                            .stream()
                            .filter(candidate -> candidate.name().equals(name))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException(
                                    "unknown field " + object.klass.name() + "." + name));
                    if (field.visibility() != Ast.Visibility.PUBLIC && !allowPrivate) {
                        throw new IllegalArgumentException(
                                "private field '" + object.klass.name() + "."
                                        + name + "' is only accessible through self");
                    }
                    return object.fields.get(name);
                }
                return new BoundMethod(object.owner, object, name);
            }
            if (receiver instanceof DynamicStructValue dynamic) {
                if (!dynamic.fields.containsKey(name)) {
                    throw new IllegalArgumentException("unknown DynamicStruct member " + name);
                }
                return dynamic.fields.get(name);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            InteropLibrary foreign = InteropLibrary.getUncached(receiver);
            if (foreign.hasMembers(receiver)) {
                context.requireCapability(IsolatePolicy.Capability.JAVA_INTEROP,
                        "Java host object member " + name);
                return hostMember(receiver, "host object", name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
        }

        private Object hostMember(Object receiver, String ownerName, String name) {
            InteropLibrary interop = InteropLibrary.getUncached(receiver);
            if (interop.isMemberInvocable(receiver, name)) {
                return (Invokable) args -> {
                    context.requireCapability(
                            IsolatePolicy.Capability.JAVA_INTEROP,
                            "Java host member " + ownerName + "." + name);
                    return invokeHostMember(receiver, name, args);
                };
            }
            if (interop.isMemberReadable(receiver, name)) {
                try {
                    return normalizeHostResult(interop.readMember(receiver, name));
                } catch (Exception failure) {
                    throw hostInteropError("read Java member " + ownerName + "." + name, failure);
                }
            }
            throw new IllegalArgumentException(
                    "Java member is not exported by HostAccess: " + ownerName + "." + name);
        }

        private Object invokeHostMember(Object receiver, String name, List<Object> args) {
            try {
                Object[] unwrapped = args.stream().map(this::unwrapHostArgument).toArray();
                Object result = InteropLibrary.getUncached(receiver).invokeMember(receiver, name, unwrapped);
                return normalizeHostResult(result);
            } catch (Exception failure) {
                throw hostInteropError("invoke Java member " + name, failure);
            }
        }

        private Object instantiateHost(HostClassFacade hostClass, List<Object> args) {
            InteropLibrary interop = InteropLibrary.getUncached(hostClass.symbol());
            if (!interop.isInstantiable(hostClass.symbol())) {
                throw new IllegalArgumentException(
                        "allowlisted Java host class is not constructible: " + hostClass.className());
            }
            try {
                Object[] unwrapped = args.stream().map(this::unwrapHostArgument).toArray();
                Object value = interop.instantiate(hostClass.symbol(), unwrapped);
                return new HostObjectFacade(value);
            } catch (Exception failure) {
                throw hostInteropError("construct Java host class " + hostClass.className(), failure);
            }
        }

        private Object normalizeHostResult(Object value) {
            if (value == null) return new OptionValue(false, null);
            if (value instanceof Number || value instanceof Boolean || value instanceof String
                    || value instanceof Character || value instanceof CompletionStage<?>) {
                return value;
            }
            return new HostObjectFacade(value);
        }

        private Object unwrapHostArgument(Object value) {
            return value instanceof HostObjectFacade host ? host.value() : value;
        }

        private RuntimeException hostInteropError(String operation, Exception failure) {
            return new IllegalArgumentException(operation + " failed: " + failure.getMessage(), failure);
        }

        private Object optionMember(OptionValue option, String name) {
            return switch (name) {
                case "is_some" -> (Invokable) args -> { requireZero(args, "Option.is_some"); return option.present(); };
                case "is_none" -> (Invokable) args -> { requireZero(args, "Option.is_none"); return !option.present(); };
                case "unwrap" -> (Invokable) args -> {
                    requireZero(args, "Option.unwrap");
                    if (!option.present()) throw new OresPanic("called Option::unwrap() on a None value");
                    return option.value();
                };
                case "unwrap_safe" -> (Invokable) args -> {
                    requireZero(args, "Option.unwrap_safe");
                    return option.present()
                            ? new ResultValue(true, option.value())
                            : new ResultValue(false, new OptionUnwrapError("None"));
                };
                case "expect" -> (Invokable) args -> {
                    String message = requireStringArg(args, "Option.expect");
                    if (!option.present()) throw new OresPanic(message);
                    return option.value();
                };
                case "unwrap_or" -> (Invokable) args -> {
                    requireOne(args, "Option.unwrap_or");
                    return option.present() ? option.value() : args.getFirst();
                };
                default -> throw new IllegalArgumentException("unknown Option member " + name);
            };
        }

        private Object resultMember(ResultValue result, String name) {
            return switch (name) {
                case "is_ok" -> (Invokable) args -> { requireZero(args, "Result.is_ok"); return result.ok(); };
                case "is_err" -> (Invokable) args -> { requireZero(args, "Result.is_err"); return !result.ok(); };
                case "unwrap" -> (Invokable) args -> {
                    requireZero(args, "Result.unwrap");
                    if (!result.ok()) {
                        throw new OresPanic("called Result::unwrap() on an Err value: " + display(result.value()));
                    }
                    return result.value();
                };
                case "unwrap_safe" -> (Invokable) args -> {
                    requireZero(args, "Result.unwrap_safe");
                    return result;
                };
                case "expect" -> (Invokable) args -> {
                    String message = requireStringArg(args, "Result.expect");
                    if (!result.ok()) throw new OresPanic(message + ": " + display(result.value()));
                    return result.value();
                };
                case "unwrap_or" -> (Invokable) args -> {
                    requireOne(args, "Result.unwrap_or");
                    return result.ok() ? result.value() : args.getFirst();
                };
                default -> throw new IllegalArgumentException("unknown Result member " + name);
            };
        }

        @SuppressWarnings("unchecked")
        private Object mutexMember(OresMutex.Lock<?> rawLock, String name) {
            if (rawLock instanceof OresMutex.Shared<?>) {
                context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex." + name);
            }
            OresMutex.Lock<Object> lock = (OresMutex.Lock<Object>) rawLock;
            return switch (name) {
                case "lock" -> (Invokable) args -> { requireZero(args, "Mutex.lock"); return lock.lock(); };
                case "try_lock" -> (Invokable) args -> {
                    requireZero(args, "Mutex.try_lock");
                    var guard = lock.tryLock();
                    return guard.isPresent() ? new OptionValue(true, guard.get()) : new OptionValue(false, null);
                };
                case "lock_async" -> (Invokable) args -> { requireZero(args, "Mutex.lock_async"); return lock.lockAsync(); };
                case "with_lock" -> (Invokable) args -> {
                    requireOne(args, "Mutex.with_lock");
                    if (!(args.getFirst() instanceof Invokable callback)) {
                        throw new IllegalArgumentException("Mutex.with_lock expects a one-argument lambda/function");
                    }
                    return lock.withLock(value -> {
                        Object result = callback.call(List.of(value));
                        if (result != null) {
                            throw new IllegalArgumentException(
                                    "Mutex.with_lock callback must return void");
                        }
                        return null;
                    });
                };
                case "is_poisoned" -> (Invokable) args -> { requireZero(args, "Mutex.is_poisoned"); return lock.isPoisoned(); };
                case "recover" -> {
                    if (!(lock instanceof OresMutex.Shared<?> sharedRaw)) {
                        throw new IllegalArgumentException("recover is only available on SharedMutex<T>");
                    }
                    OresMutex.Shared<Object> shared = (OresMutex.Shared<Object>) sharedRaw;
                    yield (Invokable) args -> {
                        requireOne(args, "SharedMutex.recover");
                        if (!(args.getFirst() instanceof Invokable callback)) {
                            throw new IllegalArgumentException("SharedMutex.recover expects a one-argument lambda/function");
                        }
                        return shared.recover(value -> {
                            Object result = callback.call(List.of(value));
                            if (result != null) {
                                throw new IllegalArgumentException(
                                        "SharedMutex.recover callback must return void");
                            }
                            return null;
                        });
                    };
                }
                default -> throw new IllegalArgumentException("unknown mutex member " + name);
            };
        }

        private Object invokeMethod(OresObject receiver, String name, List<Object> args) {
            Ast.MethodDecl method = findMethod(receiver.klass, name, args.size(), new LinkedHashSet<>());
            if (method == null) throw new IllegalArgumentException("no method " + receiver.klass.name() + "." + name + " with arity " + args.size());
            return callMethod(receiver, method, args);
        }

        private Object invokeStaticFunction(Ast.ClassDecl klass, String name, List<Object> args) {
            Ast.MethodDecl fn = findStaticFunction(klass, name, args.size(), new LinkedHashSet<>());
            if (fn == null) throw new IllegalArgumentException("no static function " + klass.name() + "." + name + " with arity " + args.size());
            return callStaticFunction(klass, fn, args);
        }

        private Object callStaticFunction(Ast.ClassDecl klass, Ast.MethodDecl fn, List<?> args) {
            if (!fn.isStatic()) throw new IllegalArgumentException("not a static class function: " + klass.name() + "." + fn.name());
            if (args.size() != fn.parameters().size()) throw new IllegalArgumentException("static function " + fn.name() + " arity mismatch");
            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(param.name(), args.get(i), param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) { return shapeReturnedValue(fn.returnType(), signal.value, "static function " + fn.name()); }
        }

        /**
         * Go-style method value: one shared method definition per class plus a
         * tiny (receiver, method-name) pair only when a method is extracted as
         * a first-class callback. Direct receiver.method(...) calls allocate no
         * bound-method object.
         */
        private static final class BoundMethod implements Invokable {
            private final Evaluator owner;
            private final OresObject receiver;
            private final String methodName;

            private BoundMethod(Evaluator owner, OresObject receiver, String methodName) {
                this.owner = owner;
                this.receiver = receiver;
                this.methodName = methodName;
            }

            @Override public Object call(List<Object> arguments) {
                return owner.invokeMethod(receiver, methodName, arguments);
            }
        }

        private Object importedValue(String name) {
            ImportedBinding direct = namedImports.get(name);
            if (direct != null) {
                return importedTarget(direct.declaration())
                        .exportValue(direct.declaration().kind(), direct.sourceName());
            }
            Ast.ImportDecl namespace = namespaceImports.get(name);
            if (namespace != null) return new ImportedNamespace(importedTarget(namespace), namespace.kind());
            return Env.MISSING;
        }

        private Evaluator importedTarget(Ast.ImportDecl imported) {
            String targetId = resolveImportUnitId(imported.path());
            Object target = context.linkedCodeUnit(targetId);
            if (!(target instanceof Evaluator evaluator)) {
                throw new IllegalStateException(
                        "import target '" + imported.path() + "' for '" + codeUnitId
                                + "' is not linked yet; all members of an import cycle must be linked before init");
            }
            return evaluator;
        }

        private String resolveImportUnitId(String rawPath) {
            String raw = rawPath.replace('\\', '/');
            Path parent = Path.of(codeUnitId).getParent();
            Path candidatePath = raw.startsWith(".")
                    ? (parent == null ? Path.of(raw) : parent.resolve(raw)).normalize()
                    : Path.of(raw).normalize();
            String candidate = normalizeUnitId(candidatePath.toString());
            if (!context.hasLinkedCodeUnit(candidate)
                    && !candidate.endsWith(".ores")
                    && !candidate.endsWith(".java")) {
                if (context.hasLinkedCodeUnit(candidate + ".ores")) candidate += ".ores";
                else if (context.hasLinkedCodeUnit(candidate + ".java")) candidate += ".java";
            }
            return candidate;
        }

        private Object exportValue(Ast.ImportKind kind, String name) {
            return switch (kind) {
                case FUNCTION -> {
                    Ast.FunctionDecl fn = findFunction(name);
                    if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) {
                        throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export function '" + name + "'");
                    }
                    yield (Invokable) args -> callFunction(fn, args);
                }
                case CLASS -> {
                    Ast.ClassDecl klass = findClass(name);
                    if (klass == null) throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export class '" + name + "'");
                    yield new ClassFacade(this, klass);
                }
                case MODULE -> {
                    Ast.ModuleDecl module = modules.get(name);
                    if (module == null) throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export module '" + name + "'");
                    yield new ModuleFacade(this, module);
                }
                case ALL -> exportAny(name);
            };
        }

        private Object exportAny(String name) {
            Ast.ModuleDecl module = modules.get(name);
            if (module != null && !module.name().equals(Parser.ROOT_MODULE)) return new ModuleFacade(this, module);
            Ast.ClassDecl klass = findClass(name);
            if (klass != null) return new ClassFacade(this, klass);
            Ast.FunctionDecl fn = findFunction(name);
            if (fn != null && fn.visibility() == Ast.Visibility.PUBLIC) return (Invokable) args -> callFunction(fn, args);
            for (Ast.ModuleDecl candidate : program.modules()) {
                for (Ast.Decl decl : candidate.declarations()) {
                    if (decl instanceof Ast.FieldDecl field
                            && field.visibility() == Ast.Visibility.PUBLIC
                            && field.name().equals(name)) {
                        if (field.initializer() == null) throw new IllegalArgumentException("exported binding has no initializer: " + name);
                        return eval(field.initializer(), new Env(null));
                    }
                }
            }
            throw new IllegalArgumentException("code unit '" + codeUnitId + "' does not export '" + name + "'");
        }

        private String statefulModuleKey(Ast.ModuleDecl module) {
            return codeUnitId + "::" + module.name();
        }

        private String statefulModuleSchema(Ast.ModuleDecl module) {
            StringBuilder schema = new StringBuilder(module.name()).append('|');
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FieldDecl field)
                        || field.bindingKind() == Ast.BindingKind.CONST) continue;
                schema.append(field.name())
                        .append(':')
                        .append(field.bindingKind())
                        .append(':')
                        .append(field.type())
                        .append(';');
            }
            return schema.toString();
        }

        private ModuleState initializeStatefulModule(Ast.ModuleDecl module) {
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            Env env = new Env(null);
            for (Ast.Decl decl : module.declarations()) {
                if (!(decl instanceof Ast.FieldDecl field)) continue;
                if (field.initializer() == null) {
                    throw new IllegalArgumentException(
                            "stateful module field has no initializer: "
                                    + module.name() + "." + field.name());
                }
                Object value = eval(field.initializer(), env);
                if (field.bindingKind() != Ast.BindingKind.CONST) {
                    fields.put(field.name(), value);
                }
                env.define(field.name(), value, field.bindingKind());
            }
            return new ModuleState(
                    module.name(),
                    statefulModuleSchema(module),
                    fields);
        }

        private void requireCompatibleStatefulModule(
                ModuleState state,
                Ast.ModuleDecl implementationModule) {
            String currentSchema = statefulModuleSchema(implementationModule);
            if (!state.moduleName.equals(implementationModule.name())
                    || !state.schema.equals(currentSchema)) {
                throw new IllegalStateException(
                        "stateful module schema changed for "
                                + implementationModule.name()
                                + "; explicit state migration is required before hot reload");
            }
        }

        private Object callStatefulModuleFunction(
                ModuleState state,
                Ast.ModuleDecl implementationModule,
                Ast.FunctionDecl fn,
                List<?> args) {
            requireCompatibleStatefulModule(state, implementationModule);
            List<?> normalized = normalizeFunctionArguments(fn, args);
            Env env = new Env(null, fn.nonLexical());

            for (Ast.Decl decl : implementationModule.declarations()) {
                if (!(decl instanceof Ast.FieldDecl field)) continue;
                Object value;
                if (field.bindingKind() == Ast.BindingKind.CONST) {
                    value = eval(field.initializer(), env);
                } else {
                    value = state.fields.get(field.name());
                    if (value == null && !state.fields.containsKey(field.name())) {
                        throw new IllegalStateException(
                                "stateful module field missing from persisted state: "
                                        + implementationModule.name() + "." + field.name());
                    }
                }
                env.define(field.name(), value, field.bindingKind());
            }
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(
                        param.name(),
                        normalized.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }

            Object result = null;
            boolean succeeded = false;
            try {
                executeBlock(fn.body(), env);
                succeeded = true;
            } catch (ReturnSignal signal) {
                result = shapeReturnedValue(
                        fn.returnType(),
                        signal.value,
                        "module function " + implementationModule.name() + "." + fn.name());
                succeeded = true;
            }

            if (succeeded) {
                LinkedHashMap<String, Object> replacements = new LinkedHashMap<>();
                for (Ast.Decl decl : implementationModule.declarations()) {
                    if (!(decl instanceof Ast.FieldDecl field)
                            || field.bindingKind() != Ast.BindingKind.LET) continue;
                    Object updated = env.lookup(field.name());
                    if (updated == Env.MISSING) {
                        throw new IllegalStateException(
                                "module field disappeared during execution: " + field.name());
                    }
                    replacements.put(field.name(), updated);
                }

                Runnable publish = () -> replacements.forEach(state.fields::put);
                if (implementationModule.storage() == Ast.ModuleStorage.GLOBAL) {
                    ProcessGlobalRegistry.commitIfActive(publish);
                } else {
                    publish.run();
                }
            }
            return result;
        }

        @SuppressWarnings("unchecked")
        private ProcessGlobalRegistry.Handle<ModuleState> globalHandle(Ast.ModuleDecl module) {
            context.requireCapability(
                    IsolatePolicy.Capability.PROCESS_GLOBAL,
                    "global module " + module.name());
            return ProcessGlobalRegistry.getOrCreate(
                    statefulModuleKey(module),
                    context.isolatePolicy(),
                    () -> initializeStatefulModule(module));
        }

        private MemorySlotSingletonRegistry.Handle<ModuleState> singletonHandle(
                Ast.ModuleDecl module) {
            context.requireCapability(
                    IsolatePolicy.Capability.SINGLETON_STATE,
                    "singleton module " + module.name());
            return context.actors().singletons().getOrCreate(
                    statefulModuleKey(module),
                    () -> initializeStatefulModule(module));
        }

        private Ast.Annotation annotation(Ast.FieldDecl field, String name) {
            for (Ast.Annotation annotation : field.annotations()) {
                if (annotation.name().equals(name)) return annotation;
            }
            return null;
        }

        private Ast.ModuleDecl injectedTarget(Ast.FieldDecl field, String annotationName) {
            Ast.Annotation annotation = annotation(field, annotationName);
            if (annotation == null) return null;
            Ast.TypeRef targetRef = annotation.arguments().isEmpty()
                    ? field.type().arguments().getFirst()
                    : annotation.arguments().getFirst();
            Ast.ModuleDecl target = modules.get(targetRef.name());
            if (target == null) {
                throw new IllegalArgumentException(
                        "unknown injected module " + targetRef.name());
            }
            return target;
        }

        private Object injectedValue(Ast.FieldDecl field) {
            Ast.ModuleDecl global = injectedTarget(field, "InjectGlobal");
            if (global != null) {
                return new GlobalRefFacade(this, global, globalHandle(global));
            }
            Ast.ModuleDecl singleton = injectedTarget(field, "InjectSingleton");
            if (singleton != null) {
                return new SingletonRefFacade(this, singleton, singletonHandle(singleton));
            }
            return Env.MISSING;
        }

        private OresObject instantiate(Ast.ClassDecl klass, List<Object> args) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalStateException("actor '" + klass.name()
                        + "' cannot be constructed with new; actor state must be initialized inside ActorRuntime");
            }
            List<Ast.FieldDecl> classFields = effectiveFields(klass, new LinkedHashSet<>());
            long ordinaryFieldCount = classFields.stream()
                    .filter(field -> annotation(field, "InjectGlobal") == null
                            && annotation(field, "InjectSingleton") == null)
                    .count();
            if (args.size() > ordinaryFieldCount) {
                throw new IllegalArgumentException(
                        "too many constructor arguments for " + klass.name()
                                + "; injected fields are not constructor arguments");
            }

            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            Env env = new Env(null);
            int argumentIndex = 0;
            for (Ast.FieldDecl field : classFields) {
                Object injected = injectedValue(field);
                Object value;
                if (injected != Env.MISSING) {
                    value = injected;
                } else if (argumentIndex < args.size()) {
                    value = args.get(argumentIndex++);
                } else if (field.initializer() != null) {
                    value = eval(field.initializer(), env);
                } else {
                    throw new IllegalArgumentException(
                            "missing constructor field " + klass.name() + "." + field.name());
                }
                fields.put(field.name(), value);
                env.define(field.name(), value, field.bindingKind());
            }
            return new OresObject(this, klass, fields);
        }

        private static String normalizeUnitId(String id) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("code unit id cannot be blank");
            return Path.of(id).normalize().toString().replace('\\', '/');
        }

        private Object moduleMember(Ast.ModuleDecl module, String name) {
            for (Ast.Decl decl : module.declarations()) {
                if (decl instanceof Ast.ClassDecl klass && klass.name().equals(name)) {
                    return new ClassFacade(this, klass);
                }
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.name().equals(name)
                        && fn.visibility() == Ast.Visibility.PUBLIC) {
                    return switch (module.storage()) {
                        case ORDINARY -> (Invokable) args -> callFunction(fn, args);
                        case GLOBAL -> {
                            ProcessGlobalRegistry.Handle<ModuleState> handle = globalHandle(module);
                            yield (Invokable) args -> handle.call(
                                    args,
                                    (state, delivered) ->
                                            callStatefulModuleFunction(state, module, fn, delivered));
                        }
                        case SINGLETON -> {
                            MemorySlotSingletonRegistry.Handle<ModuleState> handle =
                                    singletonHandle(module);
                            yield (Invokable) args -> handle.call(
                                    args,
                                    (state, delivered) ->
                                            callStatefulModuleFunction(state, module, fn, delivered));
                        }
                    };
                }
                if (decl instanceof Ast.FieldDecl field
                        && field.name().equals(name)
                        && field.visibility() == Ast.Visibility.PUBLIC) {
                    // Mutable public state is rejected statically. Public
                    // const/val reads still execute through the owning authority.
                    if (field.bindingKind() == Ast.BindingKind.CONST) {
                        if (field.initializer() == null) {
                            throw new IllegalArgumentException(
                                    "module const has no initializer: "
                                            + module.name() + "." + name);
                        }
                        return eval(field.initializer(), new Env(null));
                    }
                    return switch (module.storage()) {
                        case ORDINARY -> {
                            if (field.initializer() == null) {
                                throw new IllegalArgumentException(
                                        "module field has no initializer: "
                                                + module.name() + "." + name);
                            }
                            yield eval(field.initializer(), new Env(null));
                        }
                        case GLOBAL -> globalHandle(module).call(
                                List.of(),
                                (state, ignored) -> {
                                    requireCompatibleStatefulModule(state, module);
                                    return state.fields.get(field.name());
                                });
                        case SINGLETON -> singletonHandle(module).call(
                                List.of(),
                                (state, ignored) -> {
                                    requireCompatibleStatefulModule(state, module);
                                    return state.fields.get(field.name());
                                });
                    };
                }
            }
            throw new IllegalArgumentException(
                    "module '" + module.name() + "' does not export '" + name + "'");
        }

        private Object globalRefMember(GlobalRefFacade ref, String name) {
            for (Ast.Decl decl : ref.module().declarations()) {
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.name().equals(name)) {
                    return (Invokable) args -> ref.handle().call(
                            args,
                            (state, delivered) ->
                                callStatefulModuleFunction(state, ref.module(), fn, delivered));
                }
            }
            throw new IllegalArgumentException(
                    "global ref '" + ref.module().name()
                            + "' has no public function '" + name + "'");
        }

        private Object singletonRefMember(SingletonRefFacade ref, String name) {
            for (Ast.Decl decl : ref.module().declarations()) {
                if (decl instanceof Ast.FunctionDecl fn
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.name().equals(name)) {
                    return (Invokable) args -> ref.handle().call(
                            args,
                            (state, delivered) ->
                                callStatefulModuleFunction(state, ref.module(), fn, delivered));
                }
            }
            throw new IllegalArgumentException(
                    "singleton ref '" + ref.module().name()
                            + "' has no public function '" + name + "'");
        }

        private List<Ast.FieldDecl> effectiveFields(Ast.ClassDecl klass, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            LinkedHashMap<String, Ast.FieldDecl> result = new LinkedHashMap<>();
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) throw new IllegalArgumentException("unknown parent class " + parentRef.name());
                for (Ast.FieldDecl field : effectiveFields(parent, seen)) result.putIfAbsent(field.name(), field);
            }
            for (Ast.FieldDecl field : klass.fields()) result.put(field.name(), field);
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private Ast.MethodDecl findMethod(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl method : klass.methods()) {
                if (!method.isStatic() && method.name().equals(name) && method.parameters().size() == arity) {
                    seen.remove(klass);
                    return method;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
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

        private Ast.MethodDecl findStaticFunction(Ast.ClassDecl klass, String name, int arity, Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) throw new IllegalArgumentException("inheritance cycle involving " + klass.name());
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name) && fn.parameters().size() == arity) {
                    seen.remove(klass);
                    return fn;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
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
            LinkedHashMap<Integer, Ast.MethodDecl> result = new LinkedHashMap<>();
            for (Ast.MethodDecl fn : klass.methods()) {
                if (fn.isStatic() && fn.name().equals(name)) result.put(fn.parameters().size(), fn);
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object") || parentRef.name().equals("List")) continue;
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent == null) continue;
                for (Ast.MethodDecl fn : findStaticFunctionsByName(parent, name, seen)) result.putIfAbsent(fn.parameters().size(), fn);
            }
            seen.remove(klass);
            return List.copyOf(result.values());
        }

        private List<?> iterableValues(Object value) {
            if (value instanceof List<?> list) return list;
            if (value instanceof Object[] array) return List.of(array);
            if (value instanceof OresObject object) {
                Ast.MethodDecl iterator = findMethod(object.klass, "Symbol.iterator", 0, new LinkedHashSet<>());
                if (iterator == null) throw new IllegalArgumentException("value has no [Symbol.iterator]()");
                Object produced = callMethod(object, iterator, List.of());
                return iterableValues(produced);
            }
            throw new IllegalArgumentException("value is not iterable");
        }

        private Object binary(String op, Object left, Object right) {
            return switch (op) {
                case "+" -> add(left, right); case "-" -> numeric(left, right, '-'); case "*" -> numeric(left, right, '*');
                case "/" -> numeric(left, right, '/'); case "%" -> numeric(left, right, '%');
                case "==" -> Objects.equals(left, right); case "!=" -> !Objects.equals(left, right);
                case "<" -> compare(left, right) < 0; case "<=" -> compare(left, right) <= 0;
                case ">" -> compare(left, right) > 0; case ">=" -> compare(left, right) >= 0;
                case "&" -> integralLong(left) & integralLong(right);
                case "|" -> integralLong(left) | integralLong(right);
                case "^" -> integralLong(left) ^ integralLong(right);
                case "<<" -> integralLong(left) << shiftDistance(right);
                case ">>" -> integralLong(left) >> shiftDistance(right);
                case ">>>" -> integralLong(left) >>> shiftDistance(right);
                default -> throw new IllegalArgumentException("unsupported operator " + op);
            };
        }

        private Object add(Object left, Object right) {
            if (left instanceof String && right instanceof String) return ((String) left) + right;
            return numeric(left, right, '+');
        }

        private Object numeric(Object left, Object right, char op) {
            if (left instanceof Complex || right instanceof Complex) {
                Complex a = asComplex(left), b = asComplex(right);
                return switch (op) {
                    case '+' -> a.add(b); case '-' -> a.sub(b); case '*' -> a.mul(b); case '/' -> a.div(b);
                    default -> throw new IllegalArgumentException("operator " + op + " is not supported for complex numbers");
                };
            }
            if (!(left instanceof Number a) || !(right instanceof Number b)) throw new IllegalArgumentException("numeric operator requires numbers");
            boolean integral = isIntegral(a) && isIntegral(b) && op != '/';
            if (integral) {
                long x = a.longValue(), y = b.longValue();
                return switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '%' -> x % y; default -> throw new IllegalArgumentException("bad numeric operator"); };
            }
            double x = a.doubleValue(), y = b.doubleValue();
            return switch (op) { case '+' -> x + y; case '-' -> x - y; case '*' -> x * y; case '/' -> x / y; case '%' -> x % y; default -> throw new IllegalArgumentException("bad numeric operator"); };
        }

        private long integralLong(Object value) {
            if (!(value instanceof Number number) || !isIntegral(number)) {
                throw new IllegalArgumentException("bitwise operator requires integer operands");
            }
            return number.longValue();
        }

        private int shiftDistance(Object value) {
            long distance = integralLong(value);
            if (distance < 0 || distance > 63) {
                throw new IllegalArgumentException("shift distance must be between 0 and 63");
            }
            return (int) distance;
        }

        private Object negate(Object value) {
            if (value instanceof Complex c) return new Complex(-c.real, -c.imaginary);
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) return -((Number) value).longValue();
            if (value instanceof Number number) return -number.doubleValue();
            throw new IllegalArgumentException("unary - requires a number");
        }

        private int compare(Object left, Object right) {
            if (left instanceof Number a && right instanceof Number b) return Double.compare(a.doubleValue(), b.doubleValue());
            if (left instanceof String a && right instanceof String b) return a.compareTo(b);
            throw new IllegalArgumentException("values are not comparable");
        }

        private boolean truth(Object value) { if (value instanceof Boolean b) return b; throw new IllegalArgumentException("condition must be bool"); }
        private boolean isIntegral(Number value) { return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long; }
        private Complex asComplex(Object value) { if (value instanceof Complex c) return c; if (value instanceof Number n) return new Complex(n.doubleValue(),0); throw new IllegalArgumentException("value is not numeric"); }
        private Object shapeReturnedValue(Ast.TypeRef declared, Object value, String callable) {
            return shapeReturnedValue(declared, value, callable, new LinkedHashSet<>());
        }

        private Object shapeReturnedValue(Ast.TypeRef declared, Object value, String callable, Set<Ast.TypeAliasDecl> resolving) {
            if (declared == null) return value;

            Ast.TypeAliasDecl alias = findTypeAlias(declared.name());
            if (alias != null) {
                if (alias.genericParameters().size() != declared.arguments().size()) {
                    throw new IllegalArgumentException("type alias '" + alias.name() + "' expects "
                            + alias.genericParameters().size() + " type argument(s), got " + declared.arguments().size());
                }
                if (!resolving.add(alias)) throw new IllegalArgumentException("type alias cycle involving '" + alias.name() + "'");
                try {
                    Map<String, Ast.TypeRef> substitutions = new HashMap<>();
                    for (int i = 0; i < alias.genericParameters().size(); i++) {
                        substitutions.put(alias.genericParameters().get(i), declared.arguments().get(i));
                    }
                    return shapeReturnedValue(substituteReturnType(alias.target(), substitutions), value, callable, resolving);
                } finally {
                    resolving.remove(alias);
                }
            }

            if (declared.isUnion()) {
                List<String> failures = new ArrayList<>();
                for (Ast.TypeRef option : declared.arguments()) {
                    try {
                        return shapeReturnedValue(option, value, callable, new LinkedHashSet<>(resolving));
                    } catch (IllegalArgumentException error) {
                        failures.add(error.getMessage());
                    }
                }
                throw new IllegalArgumentException(callable + " return value does not match any union alternative: " + failures);
            }

            if (declared.isTupleType()) {
                List<?> items = asSequence(value);
                if (items.size() != declared.arguments().size()) {
                    throw new IllegalArgumentException(callable + " returned " + items.size()
                            + " tuple element(s), expected " + declared.arguments().size());
                }
                Object[] fixed = items.toArray();
                for (int i = 0; i < fixed.length; i++) {
                    fixed[i] = shapeReturnedValue(declared.arguments().get(i), fixed[i], callable + " tuple[" + i + "]", resolving);
                }
                return java.util.Arrays.asList(fixed);
            }

            if (declared.isRecordType()) {
                for (Map.Entry<String, Ast.TypeRef> member : declared.recordMembers().entrySet()) {
                    Object nested = destructureMember(value, member.getKey());
                    shapeReturnedValue(member.getValue(), nested, callable + "." + member.getKey(), resolving);
                }
                return value;
            }

            if (declared.name().equals("Array") || declared.name().equals("List")) {
                if (declared.arguments().size() != 1) return value;
                List<?> items = asSequence(value);
                ArrayList<Object> shaped = new ArrayList<>(items.size());
                for (int i = 0; i < items.size(); i++) {
                    shaped.add(shapeReturnedValue(declared.arguments().getFirst(), items.get(i), callable + "[" + i + "]", resolving));
                }
                return shaped;
            }

            if (declared.name().equals("DynamicStruct")) {
                if (declared.arguments().size() != 1 || !(value instanceof DynamicStructValue dynamic)) {
                    throw returnTypeMismatch(callable, declared, value);
                }
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    shapeReturnedValue(
                            declared.arguments().getFirst(),
                            entry.getValue(),
                            callable + "[" + entry.getKey() + "]",
                            resolving);
                }
                return value;
            }

            if (declared.name().equals("bool") || declared.name().equals("Bool")) {
                if (!(value instanceof Boolean)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (declared.name().equals("string") || declared.name().equals("String")) {
                if (!(value instanceof String)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (java.util.Set.of("i8","i16","i32","i64","u8","u16","u32","u64","int","uint","bigint").contains(declared.name())) {
                if (!(value instanceof Number number) || !isIntegral(number)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (java.util.Set.of("f32","f64","float","decimal").contains(declared.name())) {
                if (!(value instanceof Number)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (java.util.Set.of("complex64","complex128","complex").contains(declared.name())) {
                if (!(value instanceof Number) && !(value instanceof Complex)) throw returnTypeMismatch(callable, declared, value);
                return value;
            }
            if (declared.name().equals("void")) {
                if (value != null) throw returnTypeMismatch(callable, declared, value);
                return null;
            }

            return value;
        }

        private Ast.TypeRef substituteReturnType(Ast.TypeRef ref, Map<String, Ast.TypeRef> substitutions) {
            Ast.TypeRef replacement = substitutions.get(ref.name());
            if (replacement != null && ref.arguments().isEmpty() && !ref.inferArguments()) return replacement;
            return new Ast.TypeRef(
                    ref.name(),
                    ref.arguments().stream().map(arg -> substituteReturnType(arg, substitutions)).toList(),
                    ref.inferArguments());
        }

        private IllegalArgumentException returnTypeMismatch(String callable, Ast.TypeRef declared, Object value) {
            return new IllegalArgumentException(callable + " returned " + (value == null ? "null" : value.getClass().getSimpleName())
                    + " but declared " + declared);
        }

        private List<?> asSequence(Object value) { if (value instanceof List<?> l) return l; if (value instanceof Object[] a) return List.of(a); throw new IllegalArgumentException("value is not sequence-destructurable"); }
        private Object destructureMember(Object value, String name) {
            if (value instanceof DynamicStructValue dynamic) {
                if (!dynamic.fields.containsKey(name)) {
                    throw new IllegalArgumentException("object destructure missing member " + name);
                }
                return dynamic.fields.get(name);
            }
            if (value instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("object destructure missing member " + name);
                return map.get(name);
            }
            if (value instanceof OresObject object) {
                if (!object.fields.containsKey(name)) throw new IllegalArgumentException("object destructure missing field " + name);
                return object.fields.get(name);
            }
            throw new IllegalArgumentException("value is not object-destructurable");
        }
        private String display(Object value) { return value instanceof Complex c ? c.toString() : String.valueOf(value); }
    }

    @FunctionalInterface private interface Invokable { Object call(List<Object> arguments); }

    private static final class Env {
        private static final Object MISSING = new Object();
        private final Env parent;
        private final boolean descendantsNonLexical;
        private final Map<String, Slot> slots = new HashMap<>();
        private Env(Env parent) { this(parent, parent != null && parent.descendantsNonLexical); }
        private Env(Env parent, boolean descendantsNonLexical) {
            this.parent = parent;
            this.descendantsNonLexical = descendantsNonLexical;
        }
        private boolean descendantsNonLexical() { return descendantsNonLexical; }
        private void define(String name, Object value, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(value, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void reserve(String name, Ast.BindingKind kind) {
            if (slots.putIfAbsent(name, new Slot(MISSING, kind)) != null) throw new IllegalArgumentException("duplicate binding " + name);
        }
        private void initialize(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot == null) throw new IllegalArgumentException("unknown binding " + name);
            slot.value = value;
        }
        private Object lookup(String name) { Slot s=slots.get(name); return s!=null?s.value:parent==null?MISSING:parent.lookup(name); }
        private void assign(String name, Object value) {
            Slot slot = slots.get(name);
            if (slot != null) {
                if (slot.kind != Ast.BindingKind.LET) throw new IllegalArgumentException("cannot reassign " + slot.kind.name().toLowerCase() + " binding " + name);
                slot.value = value;
                return;
            }
            if (parent != null) { parent.assign(name, value); return; }
            throw new IllegalArgumentException("unknown binding " + name);
        }
        private Env snapshot() {
            Env cp = new Env(parent == null ? null : parent.snapshot(), descendantsNonLexical);
            cp.slots.putAll(slots);
            return cp;
        }
        private void releaseMutexGuards(boolean failed) {
            Set<Object> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            for (Slot slot : slots.values()) releaseMutexGuardsInValue(slot.value, failed, seen);
        }

        private static void releaseMutexGuardsInValue(Object value, boolean failed, Set<Object> seen) {
            if (value == null) return;
            if (value instanceof OresMutex.Guard<?> guard) {
                if (!guard.released()) {
                    if (failed) guard.fail();
                    else guard.release();
                }
                return;
            }
            if (!seen.add(value)) return;

            if (value instanceof OptionValue option) {
                if (option.present()) releaseMutexGuardsInValue(option.value(), failed, seen);
                return;
            }
            if (value instanceof ResultValue result) {
                releaseMutexGuardsInValue(result.value(), failed, seen);
                return;
            }
            if (value instanceof OresMutex.GuardFuture<?> future) {
                if (!future.isDone()) {
                    future.cancel(true);
                    return;
                }
                if (!future.isCancelled() && !future.isCompletedExceptionally()) {
                    releaseMutexGuardsInValue(future.getNow(null), failed, seen);
                }
                return;
            }
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) {
                    releaseMutexGuardsInValue(field, failed, seen);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) releaseMutexGuardsInValue(item, failed, seen);
                return;
            }
            if (value instanceof Set<?> set) {
                for (Object item : set) releaseMutexGuardsInValue(item, failed, seen);
                return;
            }
            if (value instanceof DynamicStructValue dynamic) {
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    releaseMutexGuardsInValue(entry.getKey(), failed, seen);
                    releaseMutexGuardsInValue(entry.getValue(), failed, seen);
                }
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    releaseMutexGuardsInValue(entry.getKey(), failed, seen);
                    releaseMutexGuardsInValue(entry.getValue(), failed, seen);
                }
                return;
            }
            if (value instanceof Object[] array) {
                for (Object item : array) releaseMutexGuardsInValue(item, failed, seen);
            }
        }
    }

    private static final class Slot {
        private Object value;
        private final Ast.BindingKind kind;
        private Slot(Object value, Ast.BindingKind kind) { this.value=value; this.kind=kind; }
    }

    private static final class ReturnSignal extends RuntimeException {
        private final Object value;
        private ReturnSignal(Object value) { super(null,null,false,false); this.value=value; }
    }

    private record Complex(double real, double imaginary) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return List.of();}
        private Complex add(Complex o){return new Complex(real+o.real,imaginary+o.imaginary);}
        private Complex sub(Complex o){return new Complex(real-o.real,imaginary-o.imaginary);}
        private Complex mul(Complex o){return new Complex(real*o.real-imaginary*o.imaginary,real*o.imaginary+imaginary*o.real);}
        private Complex div(Complex o){double d=o.real*o.real+o.imaginary*o.imaginary;return new Complex((real*o.real+imaginary*o.imaginary)/d,(imaginary*o.real-real*o.imaginary)/d);}
        @Override public String toString(){return real+(imaginary<0?"":"+")+imaginary+"i";}
    }

    private static final class DynamicStructValue implements OresMutex.SharedState {
        private final LinkedHashMap<String, Object> fields;
        private DynamicStructValue() { this.fields = new LinkedHashMap<>(); }
        private DynamicStructValue(Map<String, Object> initial) { this.fields = new LinkedHashMap<>(initial); }
        @Override public Iterable<?> sharedStateChildren() { return fields.values(); }
        @Override public String toString() { return "DynamicStruct" + fields; }
    }

    private static final class OresObject implements OresMutex.SharedState {
        private final Evaluator owner;
        private final Ast.ClassDecl klass;
        private final Map<String,Object> fields;
        private OresObject(Evaluator owner, Ast.ClassDecl klass, Map<String,Object> fields) {
            this.owner = owner;
            this.klass = klass;
            this.fields = fields;
        }
        @Override public Iterable<?> sharedStateChildren(){return fields.values();}
        @Override public String toString(){return klass.name()+fields;}
    }

    private record ImportedBinding(Ast.ImportDecl declaration, String sourceName) { }
    private record ImportedNamespace(Evaluator owner, Ast.ImportKind kind) { }
    private static final class ModuleState {
        private final String moduleName;
        private final String schema;
        private final LinkedHashMap<String, Object> fields;

        private ModuleState(
                String moduleName,
                String schema,
                LinkedHashMap<String, Object> fields) {
            this.moduleName = Objects.requireNonNull(moduleName);
            this.schema = Objects.requireNonNull(schema);
            this.fields = Objects.requireNonNull(fields);
        }
    }

    private record ModuleFacade(Evaluator owner, Ast.ModuleDecl module) { }

    private record GlobalRefFacade(
            Evaluator owner,
            Ast.ModuleDecl module,
            ProcessGlobalRegistry.Handle<ModuleState> handle)
            implements SandboxForbiddenCapability { }

    private record SingletonRefFacade(
            Evaluator owner,
            Ast.ModuleDecl module,
            MemorySlotSingletonRegistry.Handle<ModuleState> handle)
            implements SandboxForbiddenCapability { }

    private record ClassFacade(Evaluator owner, Ast.ClassDecl klass) { }
    private record HostClassFacade(String className, Object symbol, boolean constructible) { }
    private record HostObjectFacade(Object value) {
        @Override public String toString() { return String.valueOf(value); }
    }
    private record MutexFactory(boolean shared, OresContext context) {
        private Object create(List<Object> args) {
            requireOne(args, shared ? "SharedMutex.new" : "Mutex.new");
            if (shared) {
                context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex.new");
                Object value = args.getFirst();
                if (!runtimeSharedSafe(value, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()))) {
                    throw new IllegalArgumentException(
                            "SharedMutex<T> runtime admission rejected non-shared-safe state");
                }
                return OresMutex.shared(value);
            }
            return OresMutex.local(args.getFirst());
        }

        private static boolean runtimeSharedSafe(Object value, Set<Object> seen) {
            if (value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                    || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                    || value instanceof Float || value instanceof Double || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal || value instanceof Enum<?> || value instanceof java.util.UUID
                    || value instanceof Complex || value instanceof ActorRuntime.ActorId || value instanceof ActorRuntime.ActorRef<?>
                    || value instanceof OresSymbol) {
                return true;
            }

            if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>
                    || value instanceof CompletionStage<?> || value instanceof Invokable
                    || value instanceof HostClassFacade || value instanceof HostObjectFacade) {
                return false;
            }

            // Nested shared locks require recursive publication and lock-order
            // semantics that are intentionally not part of the current model.
            if (value instanceof OresMutex.Shared<?>) return false;

            if (!seen.add(value)) return true;

            if (value instanceof OptionValue option) {
                return !option.present() || runtimeSharedSafe(option.value(), seen);
            }
            if (value instanceof ResultValue result) {
                return runtimeSharedSafe(result.value(), seen);
            }
            if (value instanceof OptionUnwrapError) return true;
            if (value instanceof ActorRuntime.Shared<?> readonly) {
                return runtimeSharedSafe(readonly.value(), seen);
            }
            if (value instanceof OresObject object) {
                for (Object field : object.fields.values()) {
                    if (!runtimeSharedSafe(field, seen)) return false;
                }
                return true;
            }
            if (value instanceof DynamicStructValue dynamic) {
                for (Map.Entry<String, Object> entry : dynamic.fields.entrySet()) {
                    if (!runtimeSharedSafe(entry.getKey(), seen)
                            || !runtimeSharedSafe(entry.getValue(), seen)) {
                        return false;
                    }
                }
                return true;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) if (!runtimeSharedSafe(item, seen)) return false;
                return true;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!runtimeSharedSafe(entry.getKey(), seen) || !runtimeSharedSafe(entry.getValue(), seen)) return false;
                }
                return true;
            }
            if (value instanceof Object[] array) {
                for (Object item : array) if (!runtimeSharedSafe(item, seen)) return false;
                return true;
            }

            // Guest code has no unrestricted host access. Reject unknown host
            // values rather than silently turning SharedMutex into an escape
            // hatch for Java references.
            return false;
        }
    }
    private record OptionValue(boolean present, Object value) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return present ? List.of(value) : List.of();}
        @Override public String toString(){return present ? "Some(" + value + ")" : "None";}
    }
    private record ResultValue(boolean ok, Object value) implements OresMutex.SharedState {
        @Override public Iterable<?> sharedStateChildren(){return List.of(value);}
        @Override public String toString(){return ok ? "Ok(" + value + ")" : "Err(" + value + ")";}
    }
    private record OptionUnwrapError(String reason) {
        @Override public String toString(){return "OptionUnwrapError(" + reason + ")";}
    }
    private static final class OresPanic extends RuntimeException {
        private OresPanic(String message) { super(message, null, false, false); }
    }
    private record StdioFacade(OresContext context) {
        private Object print(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.print");requireOne(args,"stdio.print");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.println");requireOne(args,"stdio.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record StdoutFacade(OresContext context) {
        private Object write(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.write");requireOne(args,"stdio.stdout.write");context.output().print(String.valueOf(args.getFirst()));context.output().flush();return null;}
        private Object println(List<Object> args){context.requireCapability(IsolatePolicy.Capability.STDOUT,"stdio.stdout.println");requireOne(args,"stdio.stdout.println");context.output().println(String.valueOf(args.getFirst()));return null;}
    }
    private record ProcessFacade(OresContext context) {
        private String contextId(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.context_id");return context.contextId().toString();}
        private Map<String,Object> descriptor(){context.requireCapability(IsolatePolicy.Capability.PROCESS_INFO,"process.descriptor");return context.processDescriptor();}
        private Object shareReadonly(List<Object> args){context.requireCapability(IsolatePolicy.Capability.ACTOR_SHARE_READONLY,"process.share_readonly");requireOne(args,"process.share_readonly");return context.actors().shareReadonly(args.getFirst());}
        private Map<String,Object> gc(List<Object> args){context.requireCapability(IsolatePolicy.Capability.GC_CONTROL,"process.gc");requireZero(args,"process.gc");return context.garbageCollector().collectProcess().asMap();}
    }
    private record ActorFacade(OresContext context) {
        private Map<String,Object> gc(List<Object> args){requireZero(args,"actor.gc");return context.garbageCollector().collectCurrentActor().asMap();}
    }
    private static void requireZero(List<Object> args,String name){if(!args.isEmpty())throw new IllegalArgumentException(name+" expects no arguments");}
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
    private static String requireStringArg(List<Object> args,String name){
        requireOne(args,name);
        if(!(args.getFirst() instanceof String message)) throw new IllegalArgumentException(name+" expects a String message");
        return message;
    }
}
