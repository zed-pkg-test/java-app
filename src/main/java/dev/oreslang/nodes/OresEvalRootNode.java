package dev.oreslang.nodes;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import dev.oreslang.OresLanguage;
import dev.oreslang.ast.Ast;
import dev.oreslang.parser.Parser;
import dev.oreslang.runtime.OresContext;
import dev.oreslang.runtime.CapabilityChecker;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresMutex;
import dev.oreslang.runtime.OresRwLock;
import dev.oreslang.runtime.OresFutures;
import dev.oreslang.runtime.OresFuture;
import dev.oreslang.runtime.OresAsyncTrace;
import dev.oreslang.runtime.OresScheduler;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.Awaitable;

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

        // RootNode is already executing inside an entered Graal context. It
        // must not hop to another carrier here. Official launchers place the
        // entire Context lifecycle on the process SHARED/root carrier before
        // entering Graal; direct embedders retain ownership of their entry
        // thread unless they opt into ActorRuntime.executeProcessRoot(...).
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
        private final Map<String, Ast.InterfaceDecl> interfaces = new HashMap<>();
        private final Map<String, Ast.TypeAliasDecl> typeAliases = new HashMap<>();
        private final Map<String, Ast.ModuleDecl> modules = new HashMap<>();
        private final Map<String, Ast.ImportDecl> namedImports = new HashMap<>();
        private final Map<String, Ast.ImportDecl> namespaceImports = new HashMap<>();
        private final Set<String> ambiguousFunctions = new LinkedHashSet<>();
        private final Set<String> ambiguousClasses = new LinkedHashSet<>();
        private final Set<String> ambiguousInterfaces = new LinkedHashSet<>();
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
                if (imported.wildcard()) {
                    namespaceImports.put(imported.namespace(), imported);
                } else {
                    for (String name : imported.names()) namedImports.put(name, imported);
                }
            }
        }

        private void indexDeclarations() {
            for (Ast.ModuleDecl module : program.modules()) {
                modules.put(module.name(), module);
                for (Ast.Decl decl : module.declarations()) {
                    if (decl instanceof Ast.FunctionDecl fn) index(functions, ambiguousFunctions, module.name(), fn.name(), fn);
                    else if (decl instanceof Ast.ClassDecl klass) index(classes, ambiguousClasses, module.name(), klass.name(), klass);
                    else if (decl instanceof Ast.InterfaceDecl iface) index(interfaces, ambiguousInterfaces, module.name(), iface.name(), iface);
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

        private Ast.InterfaceDecl findInterface(String name) {
            if (ambiguousInterfaces.contains(name)) {
                throw new IllegalArgumentException(
                        "ambiguous interface " + name + "; qualify it with its module");
            }
            return interfaces.get(name);
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
            final Ast.FunctionDecl entryMain = main;

            boolean hostEntry = OresScheduler.current() == null
                    && !ActorRuntime.inRootExecution()
                    && !ActorRuntime.inActorExecution();

            if (hostEntry) {
                List<?> normalized = normalizeFunctionArguments(
                        entryMain,
                        List.of(arguments));
                OresFuture<Object> task = entryMain.async()
                        ? startAsyncFunction(entryMain, normalized)
                        : context.actors().rootScheduler().startSync(
                                () -> callFunctionBody(entryMain, normalized));

                // The host/embedder thread may block waiting for the root task;
                // no Ores carrier is consumed. Completion is published only
                // after the final scheduler turn fully unwinds.
                return task.join();
            }

            // Internal callers already executing under an Ores scheduler keep
            // that scheduler. Async callables return their Future to the
            // enclosing Ores frame, which may await it normally.
            return callFunction(entryMain, List.of(arguments));
        }

        private Object callFunction(Ast.FunctionDecl fn, List<?> args) {
            List<?> normalized = normalizeFunctionArguments(fn, args);
            if (fn.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalStateException(
                        "actor callable '" + fn.name()
                                + "' cannot be invoked directly; use spawn " + fn.name() + "(...)");
            }
            if (fn.async()) return startAsyncFunction(fn, normalized);
            return callFunctionBody(fn, normalized);
        }

        private Object spawnFunction(Ast.CallExpr call, Env env) {
            Ast.FunctionDecl fn = resolveSpawnTarget(call);
            List<Object> evaluated =
                    call.arguments().stream().map(arg -> eval(arg, env)).toList();
            return spawnFunctionWithArguments(fn, evaluated);
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


        private OresScheduler asyncScheduler() {
            OresScheduler current = OresScheduler.current();
            return current != null ? current : context.actors().rootScheduler();
        }

        private OresAsyncTrace.SourceSite traceSite(Ast.SourceSite site) {
            Ast.SourceSite actual = site == null ? Ast.SourceSite.UNKNOWN : site;
            return new OresAsyncTrace.SourceSite(
                    codeUnitId,
                    actual.line(),
                    actual.column(),
                    context.codeGenerationId());
        }

        private OresAsyncTrace.Frame functionTraceFrame(Ast.FunctionDecl fn) {
            return new OresAsyncTrace.Frame(fn.name(), traceSite(fn.site()));
        }

        private OresAsyncTrace.Frame methodTraceFrame(
                Ast.ClassDecl klass,
                Ast.MethodDecl method) {
            return new OresAsyncTrace.Frame(
                    klass.name() + "." + method.name(),
                    traceSite(method.site()));
        }

        private OresAsyncTrace.Frame lambdaTraceFrame() {
            return new OresAsyncTrace.Frame(
                    "<async-lambda>",
                    OresAsyncTrace.SourceSite.unknown(
                            codeUnitId,
                            context.codeGenerationId()));
        }

        private OresAsyncTrace.Trace traceForInvocation(OresAsyncTrace.Frame frame) {
            OresAsyncTrace.Trace parent = OresAsyncTrace.current();
            return parent == null
                    ? OresAsyncTrace.root(frame)
                    : parent.child(frame, frame.site());
        }

        private record AsyncCallableContext(
                Ast.TypeRef returnType,
                Set<String> genericParameters,
                boolean tailTransfersAllowed) {
            private AsyncCallableContext {
                genericParameters = Set.copyOf(genericParameters);
            }

            private AsyncCallableContext withTailTransfers(boolean allowed) {
                if (tailTransfersAllowed == allowed) return this;
                return new AsyncCallableContext(
                        returnType,
                        genericParameters,
                        allowed);
            }
        }

        private static Set<String> asyncGenericParameters(
                Ast.ClassDecl klass,
                Ast.MethodDecl method) {
            LinkedHashSet<String> names = new LinkedHashSet<>(klass.genericParameters());
            names.addAll(method.genericParameters());
            return Set.copyOf(names);
        }

        private static boolean tailTypeIsConcrete(
                Ast.TypeRef type,
                Set<String> genericParameters) {
            if (type == null
                    || type.inferArguments()
                    || type.name().equals("$infer$")
                    || genericParameters.contains(type.name())) {
                return false;
            }
            for (Ast.TypeRef argument : type.arguments()) {
                if (!tailTypeIsConcrete(argument, genericParameters)) {
                    return false;
                }
            }
            return true;
        }

        private OresFuture<Object> startAsyncFunction(Ast.FunctionDecl fn, List<?> args) {
            return asyncScheduler().start(
                    new AsyncPlanTask(
                            asyncFunctionPlan(fn, args),
                            traceForInvocation(functionTraceFrame(fn))));
        }

        private AsyncPlan asyncFunctionPlan(Ast.FunctionDecl fn, List<?> args) {
            Env base = new Env(null, fn.nonLexical());
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                base.define(
                        param.name(),
                        args.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }

            AsyncCallableContext callable = new AsyncCallableContext(
                    fn.returnType(),
                    Set.copyOf(fn.genericParameters()),
                    fn.actorKind() == Ast.ActorKind.NONE);
            AsyncPlan body = asyncBlock(fn.body(), base, callable);
            return asyncFlatMap(body, flow -> {
                Object raw =
                        flow instanceof AsyncReturn returned
                                ? returned.value()
                                : null;
                return asyncPure(shapeReturnedValue(
                        fn.returnType(),
                        raw,
                        "function " + fn.name()));
            });
        }

        private OresFuture<Object> startAsyncMethod(
                OresObject receiver,
                Ast.MethodDecl method,
                List<?> args) {
            AsyncPlan completed = asyncMethodPlan(receiver, method, args);
            return asyncScheduler().start(
                    new AsyncPlanTask(
                            completed,
                            traceForInvocation(methodTraceFrame(receiver.klass, method))));
        }

        private AsyncPlan asyncMethodPlan(
                OresObject receiver,
                Ast.MethodDecl method,
                List<?> args) {
            Env base = new Env(null);
            if (!method.isStatic()) base.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                base.define(
                        param.name(),
                        args.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }

            AsyncCallableContext callable = new AsyncCallableContext(
                    method.returnType(),
                    asyncGenericParameters(receiver.klass, method),
                    receiver.klass.actorKind() == Ast.ActorKind.NONE);
            AsyncPlan body = asyncBlock(method.body(), base, callable);
            return asyncFlatMap(body, flow -> {
                Object raw = flow instanceof AsyncReturn returned ? returned.value() : null;
                return asyncPure(shapeReturnedValue(
                        method.returnType(),
                        raw,
                        "method " + method.name()));
            });
        }

        private OresFuture<Object> startAsyncStaticFunction(
                Ast.ClassDecl klass,
                Ast.MethodDecl fn,
                List<?> args) {
            AsyncPlan completed = asyncStaticFunctionPlan(klass, fn, args);
            return asyncScheduler().start(
                    new AsyncPlanTask(
                            completed,
                            traceForInvocation(methodTraceFrame(klass, fn))));
        }

        private AsyncPlan asyncStaticFunctionPlan(
                Ast.ClassDecl klass,
                Ast.MethodDecl fn,
                List<?> args) {
            Env base = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                base.define(
                        param.name(),
                        args.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }

            AsyncCallableContext callable = new AsyncCallableContext(
                    fn.returnType(),
                    asyncGenericParameters(klass, fn),
                    klass.actorKind() == Ast.ActorKind.NONE);
            AsyncPlan body = asyncBlock(fn.body(), base, callable);
            return asyncFlatMap(body, flow -> {
                Object raw = flow instanceof AsyncReturn returned ? returned.value() : null;
                return asyncPure(shapeReturnedValue(
                        fn.returnType(),
                        raw,
                        "static function " + klass.name() + "." + fn.name()));
            });
        }

        private OresFuture<Object> startAsyncLambda(
                Ast.LambdaExpr lambda,
                Env captured,
                List<Object> args) {
            return startAsyncLambdaOn(asyncScheduler(), lambda, captured, args);
        }

        private OresFuture<Object> startAsyncLambdaOn(
                OresScheduler scheduler,
                Ast.LambdaExpr lambda,
                Env captured,
                List<Object> args) {
            Objects.requireNonNull(scheduler, "scheduler");
            if (args.size() != lambda.parameters().size()) {
                throw new IllegalArgumentException("lambda arity mismatch");
            }
            Env base = new Env(captured, lambda.nonLexical());
            for (int i = 0; i < lambda.parameters().size(); i++) {
                Ast.Param param = lambda.parameters().get(i);
                base.define(
                        param.name(),
                        args.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            AsyncCallableContext callable = new AsyncCallableContext(
                    Ast.TypeRef.inferred(),
                    Set.of(),
                    false);
            AsyncPlan body = asyncBlock(lambda.blockBody(), base, callable);
            AsyncPlan completed = asyncFlatMap(body, flow ->
                    asyncPure(flow instanceof AsyncReturn returned ? returned.value() : null));
            return scheduler.start(
                    new AsyncPlanTask(
                            completed,
                            traceForInvocation(lambdaTraceFrame())));
        }

        private final class AsyncLambdaValue implements Invokable {
            private final Ast.LambdaExpr lambda;
            private final Env captured;

            private AsyncLambdaValue(Ast.LambdaExpr lambda, Env captured) {
                this.lambda = Objects.requireNonNull(lambda, "lambda");
                this.captured = captured;
            }

            @Override
            public Object call(List<Object> args) {
                return startAsyncLambda(lambda, captured, args);
            }

            private OresFuture<Object> startOn(
                    OresScheduler scheduler,
                    List<Object> args) {
                return startAsyncLambdaOn(scheduler, lambda, captured, args);
            }
        }

        private final class SchedulerFacade {
            private final OresScheduler scheduler;

            private SchedulerFacade(int parallelism) {
                this.scheduler = context.createUserScheduler(parallelism);
            }

            private Object member(String name) {
                return switch (name) {
                    case "start" -> (Invokable) args -> {
                        requireOne(args, "OresScheduler.start");
                        Object work = args.getFirst();
                        if (work instanceof AsyncLambdaValue asyncLambda) {
                            return asyncLambda.startOn(scheduler, List.of());
                        }
                        if (work instanceof Invokable synchronous) {
                            return scheduler.startSync(
                                    () -> synchronous.call(List.of()));
                        }
                        throw new IllegalArgumentException(
                                "OresScheduler.start requires a zero-argument lambda");
                    };
                    case "parallelism" -> (Invokable) args -> {
                        requireZero(args, "OresScheduler.parallelism");
                        return (long) scheduler.parallelism();
                    };
                    case "is_closed" -> (Invokable) args -> {
                        requireZero(args, "OresScheduler.is_closed");
                        return scheduler.isClosed();
                    };
                    case "close" -> (Invokable) args -> {
                        requireZero(args, "OresScheduler.close");
                        context.closeUserScheduler(scheduler);
                        return null;
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown OresScheduler member " + name);
                };
            }
        }

        private sealed interface AsyncPlan
                permits AsyncPure, AsyncFailure, AsyncAwait, AsyncTailTransfer, AsyncThunk { }

        private record AsyncPure(Object value) implements AsyncPlan { }

        private record AsyncFailure(Throwable failure) implements AsyncPlan {
            private AsyncFailure {
                Objects.requireNonNull(failure, "failure");
            }
        }

        @FunctionalInterface
        private interface AsyncResume {
            AsyncPlan resume(Object value, Throwable failure);
        }

        private record AsyncAwait(
                OresFuture<?> future,
                AsyncResume continuation,
                OresAsyncTrace.SourceSite site) implements AsyncPlan {
            private AsyncAwait {
                Objects.requireNonNull(future, "future");
                Objects.requireNonNull(continuation, "continuation");
                Objects.requireNonNull(site, "site");
            }
        }

        /**
         * Verified proper async tail transfer. The caller has no remaining
         * cleanup or result work, so the current task can replace its logical
         * frame with {@code next} instead of allocating a child Future/task.
         */
        private record AsyncTailTransfer(
                AsyncPlan next,
                OresAsyncTrace.Frame targetFrame,
                OresAsyncTrace.SourceSite site) implements AsyncPlan {
            private AsyncTailTransfer {
                Objects.requireNonNull(next, "next");
                Objects.requireNonNull(targetFrame, "targetFrame");
                Objects.requireNonNull(site, "site");
            }
        }

        @FunctionalInterface
        private interface AsyncThunkBody {
            AsyncPlan run();
        }

        private record AsyncThunk(AsyncThunkBody body) implements AsyncPlan {
            private AsyncThunk {
                Objects.requireNonNull(body, "body");
            }
        }

        private static final Object ASYNC_NORMAL = new Object();
        private record AsyncReturn(Object value) { }

        @FunctionalInterface
        private interface AsyncMapper {
            AsyncPlan apply(Object value);
        }

        @FunctionalInterface
        private interface AsyncFailureMapper {
            AsyncPlan apply(Throwable failure);
        }

        private static AsyncPlan asyncPure(Object value) {
            return new AsyncPure(value);
        }

        private static AsyncPlan asyncFailure(Throwable failure) {
            return new AsyncFailure(failure);
        }

        private static AsyncPlan safePlan(AsyncThunkBody body) {
            try {
                return Objects.requireNonNull(body.run(), "async plan body returned null");
            } catch (VirtualMachineError fatal) {
                throw fatal;
            } catch (ThreadDeath fatal) {
                throw fatal;
            } catch (LinkageError fatal) {
                throw fatal;
            } catch (Throwable failure) {
                return asyncFailure(failure);
            }
        }

        /**
         * Tail transfers deliberately bypass mapper/recovery/finally wrappers.
         * They are emitted only after tail-position validation proves that the
         * caller has no work left. Letting a wrapper capture them would rebuild
         * the continuation chain that proper async tail transfer is meant to
         * eliminate.
         */
        private static AsyncPlan asyncFlatMap(AsyncPlan plan, AsyncMapper next) {
            if (plan instanceof AsyncTailTransfer) return plan;
            if (plan instanceof AsyncPure pure) {
                return new AsyncThunk(() -> safePlan(() -> next.apply(pure.value())));
            }
            if (plan instanceof AsyncFailure) return plan;
            if (plan instanceof AsyncThunk thunk) {
                return new AsyncThunk(() ->
                        asyncFlatMap(safePlan(thunk.body()), next));
            }
            AsyncAwait awaited = (AsyncAwait) plan;
            return new AsyncAwait(
                    awaited.future(),
                    (value, failure) -> asyncFlatMap(
                            safePlan(() -> awaited.continuation().resume(value, failure)),
                            next),
                    awaited.site());
        }

        private static AsyncPlan asyncRecover(
                AsyncPlan plan,
                AsyncFailureMapper recover) {
            if (plan instanceof AsyncTailTransfer) return plan;
            if (plan instanceof AsyncPure) return plan;
            if (plan instanceof AsyncFailure failed) {
                return new AsyncThunk(() ->
                        safePlan(() -> recover.apply(failed.failure())));
            }
            if (plan instanceof AsyncThunk thunk) {
                return new AsyncThunk(() ->
                        asyncRecover(safePlan(thunk.body()), recover));
            }
            AsyncAwait awaited = (AsyncAwait) plan;
            return new AsyncAwait(
                    awaited.future(),
                    (value, failure) -> asyncRecover(
                            safePlan(() -> awaited.continuation().resume(value, failure)),
                            recover),
                    awaited.site());
        }

        private static AsyncPlan asyncFold(
                AsyncPlan plan,
                AsyncMapper success,
                AsyncFailureMapper failure) {
            if (plan instanceof AsyncTailTransfer) return plan;
            if (plan instanceof AsyncPure pure) {
                return new AsyncThunk(() ->
                        safePlan(() -> success.apply(pure.value())));
            }
            if (plan instanceof AsyncFailure failed) {
                return new AsyncThunk(() ->
                        safePlan(() -> failure.apply(failed.failure())));
            }
            if (plan instanceof AsyncThunk thunk) {
                return new AsyncThunk(() ->
                        asyncFold(safePlan(thunk.body()), success, failure));
            }
            AsyncAwait awaited = (AsyncAwait) plan;
            return new AsyncAwait(
                    awaited.future(),
                    (value, problem) -> asyncFold(
                            safePlan(() -> awaited.continuation().resume(value, problem)),
                            success,
                            failure),
                    awaited.site());
        }

        private final class AsyncPlanTask implements OresScheduler.Task<Object> {
            private AsyncPlan current;
            private AsyncAwait waiting;
            private final OresAsyncTrace.Trace trace;
            private boolean tailDispatchPending;

            private AsyncPlanTask(
                    AsyncPlan initial,
                    OresAsyncTrace.Trace trace) {
                this.current = Objects.requireNonNull(initial, "initial");
                this.trace = Objects.requireNonNull(trace, "trace");
            }

            @Override
            public OresScheduler.Step<Object> resume(OresScheduler.Resume resume) {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(trace)) {
                    if (waiting != null) {
                        AsyncAwait awaited = waiting;
                        waiting = null;
                        current = safePlan(() -> awaited.continuation().resume(
                                resume.value(),
                                resume.failure()));
                    } else if (tailDispatchPending) {
                        if (resume.initial()) {
                            throw new IllegalStateException(
                                    "tail-await replacement resumed as an initial task turn");
                        }
                        if (resume.failure() != null) {
                            throw propagateAsyncFailure(resume.failure());
                        }
                        tailDispatchPending = false;
                    } else if (!resume.initial()) {
                        throw new IllegalStateException(
                                "async source task resumed without a captured await");
                    }

                    while (true) {
                        if (current instanceof AsyncThunk thunk) {
                            current = safePlan(thunk.body());
                            continue;
                        }
                        if (current instanceof AsyncFailure failed) {
                            Throwable failure = unwrapFutureFailure(failed.failure());
                            OresAsyncTrace.attach(failure, trace);
                            throw propagateAsyncFailure(failure);
                        }
                        if (current instanceof AsyncPure pure) {
                            return OresScheduler.done(pure.value());
                        }
                        if (current instanceof AsyncTailTransfer tail) {
                            trace.tailAwait(tail.targetFrame(), tail.site());
                            current = tail.next();
                            tailDispatchPending = true;
                            return OresScheduler.tailAwait();
                        }

                        AsyncAwait awaited = (AsyncAwait) current;
                        trace.awaitAt(awaited.site());
                        waiting = awaited;
                        return OresScheduler.await(awaited.future());
                    }
                }
            }
        }

        private final class ActorPlanRunner {
            private AsyncPlan current;
            private AsyncAwait waiting;
            private final ActorRuntime.InvocationCompletion<Object> completion;
            private final OresAsyncTrace.Trace trace;

            private ActorPlanRunner(
                    AsyncPlan initial,
                    ActorRuntime.InvocationCompletion<Object> completion,
                    OresAsyncTrace.Trace trace) {
                this.current = Objects.requireNonNull(initial, "initial");
                this.completion = Objects.requireNonNull(completion, "completion");
                this.trace = Objects.requireNonNull(trace, "trace");
            }

            private void start(ActorRuntime.ActorContext<?> actorContext) {
                advance(actorContext, true, null, null);
            }

            private void resume(
                    Object value,
                    Throwable failure,
                    ActorRuntime.ActorContext<?> actorContext) {
                advance(actorContext, false, value, failure);
            }

            private void advance(
                    ActorRuntime.ActorContext<?> actorContext,
                    boolean initial,
                    Object resumeValue,
                    Throwable resumeFailure) {
                try (OresAsyncTrace.Scope ignored = OresAsyncTrace.install(trace)) {
                    if (waiting != null) {
                        AsyncAwait awaited = waiting;
                        waiting = null;
                        current = safePlan(() -> awaited.continuation().resume(
                                resumeValue,
                                resumeFailure));
                    } else if (!initial) {
                        IllegalStateException invalid =
                                new IllegalStateException(
                                        "actor async frame resumed without a captured await");
                        OresAsyncTrace.attach(invalid, trace);
                        completion.fail(invalid);
                        throw invalid;
                    }

                    while (true) {
                        actorContext.checkpoint();

                        if (current instanceof AsyncThunk thunk) {
                            current = safePlan(thunk.body());
                            continue;
                        }
                        if (current instanceof AsyncFailure failed) {
                            Throwable failure = unwrapFutureFailure(failed.failure());
                            OresAsyncTrace.attach(failure, trace);
                            completion.fail(failure);
                            throw propagateAsyncFailure(failure);
                        }
                        if (current instanceof AsyncPure pure) {
                            completion.complete(pure.value());
                            return;
                        }
                        if (current instanceof AsyncTailTransfer) {
                            IllegalStateException invalid = new IllegalStateException(
                                    "actor async plans may not fuse tail calls across actor execution boundaries");
                            OresAsyncTrace.attach(invalid, trace);
                            completion.fail(invalid);
                            throw invalid;
                        }

                        AsyncAwait awaited = (AsyncAwait) current;
                        trace.awaitAt(awaited.site());
                        waiting = awaited;
                        actorContext.suspendOn(
                                awaited.future(),
                                (value, failure, resumedContext) ->
                                        resume(value, failure, resumedContext));
                        throw new AssertionError(
                                "ActorContext.suspendOn must unwind the actor turn");
                    }
                }
            }
        }

        private static Throwable unwrapFutureFailure(Throwable failure) {
            Throwable current = failure;
            while ((current instanceof java.util.concurrent.CompletionException
                            || current instanceof java.util.concurrent.ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
            }
            return current;
        }

        private static RuntimeException propagateAsyncFailure(Throwable failure) {
            Throwable unwrapped = unwrapFutureFailure(failure);
            if (unwrapped instanceof RuntimeException runtime) return runtime;
            if (unwrapped instanceof Error error) throw error;
            return new RuntimeException(unwrapped);
        }

        private AsyncPlan asyncBlock(
                List<Ast.Stmt> statements,
                Env parent,
                AsyncCallableContext callable) {
            Env env = new Env(parent);
            ArrayDeque<Ast.Expr> deferred = new ArrayDeque<>();
            AsyncPlan body = asyncStatements(
                    statements,
                    0,
                    env,
                    deferred,
                    callable);
            return asyncFinalizeBlock(body, deferred, env);
        }

        /**
         * Finalize one lexical async scope without rebuilding the caller
         * continuation chain. Proper tail transfer still has to perform the
         * lexical cleanup that an ordinary return would perform.
         */
        private AsyncPlan asyncFinalizeBlock(
                AsyncPlan plan,
                ArrayDeque<Ast.Expr> deferred,
                Env env) {
            if (plan instanceof AsyncTailTransfer tail) {
                return new AsyncThunk(() -> safePlan(() -> {
                    if (!deferred.isEmpty()) {
                        return asyncFailure(new IllegalStateException(
                                "tail-await reached a lexical scope with pending defer cleanup"));
                    }
                    env.releaseMutexGuards(false);
                    return tail;
                }));
            }
            if (plan instanceof AsyncPure pure) {
                return asyncFlatMap(
                        asyncRunDeferred(deferred, env),
                        ignored -> safePlan(() -> {
                            env.releaseMutexGuards(false);
                            return asyncPure(pure.value());
                        }));
            }
            if (plan instanceof AsyncFailure failed) {
                return asyncFold(
                        asyncRunDeferred(deferred, env),
                        ignored -> safePlan(() -> {
                            env.releaseMutexGuards(true);
                            return asyncFailure(failed.failure());
                        }),
                        deferredFailure -> safePlan(() -> {
                            env.releaseMutexGuards(true);
                            return asyncFailure(deferredFailure);
                        }));
            }
            if (plan instanceof AsyncThunk thunk) {
                return new AsyncThunk(() ->
                        asyncFinalizeBlock(
                                safePlan(thunk.body()),
                                deferred,
                                env));
            }

            AsyncAwait awaited = (AsyncAwait) plan;
            return new AsyncAwait(
                    awaited.future(),
                    (value, failure) -> asyncFinalizeBlock(
                            safePlan(() -> awaited.continuation().resume(value, failure)),
                            deferred,
                            env),
                    awaited.site());
        }

        private AsyncPlan asyncRunDeferred(ArrayDeque<Ast.Expr> deferred, Env env) {
            return new AsyncThunk(() -> {
                if (deferred.isEmpty()) return asyncPure(null);
                Ast.Expr expression = deferred.pop();
                return asyncFlatMap(
                        asyncEval(expression, env),
                        ignored -> asyncRunDeferred(deferred, env));
            });
        }

        private AsyncPlan asyncStatements(
                List<Ast.Stmt> statements,
                int index,
                Env env,
                ArrayDeque<Ast.Expr> deferred,
                AsyncCallableContext callable) {
            return new AsyncThunk(() -> {
                if (index >= statements.size()) return asyncPure(ASYNC_NORMAL);
                Ast.Stmt stmt = statements.get(index);
                return asyncFlatMap(
                        asyncStatement(stmt, env, deferred, callable),
                        flow -> flow instanceof AsyncReturn
                                ? asyncPure(flow)
                                : asyncStatements(
                                        statements,
                                        index + 1,
                                        env,
                                        deferred,
                                        callable));
            });
        }

        private AsyncPlan asyncStatement(
                Ast.Stmt stmt,
                Env env,
                ArrayDeque<Ast.Expr> deferred,
                AsyncCallableContext callable) {
            if (stmt instanceof Ast.BindingStmt binding) {
                if (binding.initializer() instanceof Ast.LambdaExpr) {
                    env.reserve(binding.name(), binding.kind());
                    return asyncFlatMap(
                            asyncEval(binding.initializer(), env),
                            value -> {
                                env.initialize(binding.name(), value);
                                return asyncPure(ASYNC_NORMAL);
                            });
                }
                return asyncFlatMap(
                        asyncEval(binding.initializer(), env),
                        value -> {
                            env.define(binding.name(), value, binding.kind());
                            return asyncPure(ASYNC_NORMAL);
                        });
            }
            if (stmt instanceof Ast.DestructureStmt destructure) {
                return asyncFlatMap(asyncEval(destructure.initializer(), env), value -> {
                    if (destructure.kind() == Ast.DestructureKind.SEQUENCE) {
                        List<?> items = asSequence(value);
                        if (items.size() != destructure.bindings().size()) {
                            return asyncFailure(new IllegalArgumentException(
                                    "destructure arity mismatch: value has "
                                            + items.size()
                                            + " element(s), pattern has "
                                            + destructure.bindings().size()));
                        }
                        for (int i = 0; i < items.size(); i++) {
                            Ast.DestructureBinding binding =
                                    destructure.bindings().get(i);
                            if (!binding.isDiscard()) {
                                env.define(
                                        binding.name(),
                                        items.get(i),
                                        binding.kind());
                            }
                        }
                    } else {
                        for (Ast.DestructureBinding binding : destructure.bindings()) {
                            if (!binding.isDiscard()) {
                                env.define(
                                        binding.name(),
                                        destructureMember(value, binding.name()),
                                        binding.kind());
                            }
                        }
                    }
                    return asyncPure(ASYNC_NORMAL);
                });
            }
            if (stmt instanceof Ast.ReturnStmt returned) {
                if (returned.value() == null) {
                    return asyncPure(new AsyncReturn(null));
                }

                if (callable.tailTransfersAllowed()
                        && deferred.isEmpty()
                        && returned.value() instanceof Ast.AwaitExpr awaited
                        && awaited.expression() instanceof Ast.CallExpr call) {
                    AsyncPlan tail = asyncTailAwaitCall(
                            call,
                            awaited,
                            env,
                            callable);
                    if (tail != null) return tail;
                }

                return asyncFlatMap(
                        asyncEval(returned.value(), env),
                        value -> asyncPure(new AsyncReturn(value)));
            }
            if (stmt instanceof Ast.ExprStmt expression) {
                return asyncFlatMap(
                        asyncEval(expression.expression(), env),
                        ignored -> asyncPure(ASYNC_NORMAL));
            }
            if (stmt instanceof Ast.DeferStmt defer) {
                deferred.push(defer.expression());
                return asyncPure(ASYNC_NORMAL);
            }

            AsyncCallableContext nested = callable.withTailTransfers(
                    callable.tailTransfersAllowed() && deferred.isEmpty());

            if (stmt instanceof Ast.IfStmt conditional) {
                return asyncIf(conditional, 0, env, nested);
            }
            if (stmt instanceof Ast.TryStmt tried) {
                // catch/finally are post-call semantics. Never let a tail
                // transfer skip them, even when the finally block is empty.
                return asyncTry(
                        tried,
                        env,
                        callable.withTailTransfers(false));
            }
            if (stmt instanceof Ast.ForOfStmt loop) {
                // Loop iteration environments own control/lexical state that is
                // not represented by the callee frame. Keep tail transfer
                // conservative until loop-scope cleanup is explicitly modeled.
                AsyncCallableContext noLoopTail =
                        callable.withTailTransfers(false);
                return asyncFlatMap(asyncEval(loop.iterable(), env), iterable ->
                        asyncForOf(
                                loop,
                                iterableValues(iterable),
                                0,
                                env,
                                noLoopTail));
            }
            if (stmt instanceof Ast.ForStmt loop) {
                AsyncCallableContext noLoopTail =
                        callable.withTailTransfers(false);
                Env loopEnv = new Env(env);
                AsyncPlan initialized = loop.initializer() == null
                        ? asyncPure(ASYNC_NORMAL)
                        : asyncStatement(
                                loop.initializer(),
                                loopEnv,
                                new ArrayDeque<>(),
                                noLoopTail);
                return asyncFlatMap(
                        initialized,
                        ignored -> asyncFor(loop, loopEnv, noLoopTail));
            }
            return asyncFailure(new IllegalArgumentException(
                    "unsupported async statement " + stmt));
        }

        private AsyncPlan asyncIf(
                Ast.IfStmt conditional,
                int index,
                Env env,
                AsyncCallableContext callable) {
            if (index >= conditional.branches().size()) {
                return asyncBlock(conditional.elseBody(), env, callable);
            }
            Ast.IfBranch branch = conditional.branches().get(index);
            return asyncFlatMap(asyncEval(branch.condition(), env), condition ->
                    truth(condition)
                            ? asyncBlock(branch.body(), env, callable)
                            : asyncIf(conditional, index + 1, env, callable));
        }

        private AsyncPlan asyncForOf(
                Ast.ForOfStmt loop,
                List<?> values,
                int index,
                Env env,
                AsyncCallableContext callable) {
            return new AsyncThunk(() -> {
                if (index >= values.size()) return asyncPure(ASYNC_NORMAL);
                Env iteration = new Env(env);
                iteration.define(
                        loop.bindingName(),
                        values.get(index),
                        loop.bindingKind());
                return asyncFlatMap(
                        asyncBlock(loop.body(), iteration, callable),
                        flow -> flow instanceof AsyncReturn
                                ? asyncPure(flow)
                                : asyncForOf(
                                        loop,
                                        values,
                                        index + 1,
                                        env,
                                        callable));
            });
        }

        private AsyncPlan asyncFor(
                Ast.ForStmt loop,
                Env loopEnv,
                AsyncCallableContext callable) {
            return new AsyncThunk(() -> {
                AsyncPlan condition = loop.condition() == null
                        ? asyncPure(Boolean.TRUE)
                        : asyncEval(loop.condition(), loopEnv);
                return asyncFlatMap(condition, value -> {
                    if (!truth(value)) return asyncPure(ASYNC_NORMAL);
                    return asyncFlatMap(
                            asyncBlock(loop.body(), loopEnv, callable),
                            flow -> {
                                if (flow instanceof AsyncReturn) return asyncPure(flow);
                                AsyncPlan updated = loop.update() == null
                                        ? asyncPure(null)
                                        : asyncEval(loop.update(), loopEnv);
                                return asyncFlatMap(
                                        updated,
                                        ignored -> asyncFor(loop, loopEnv, callable));
                            });
                });
            });
        }

        private AsyncPlan asyncTry(
                Ast.TryStmt tried,
                Env env,
                AsyncCallableContext noTail) {
            AsyncPlan attempted = asyncBlock(tried.body(), env, noTail);
            AsyncPlan caught = asyncRecover(attempted, failure -> {
                if (failure instanceof OresPanic
                        || failure instanceof VirtualMachineError
                        || failure instanceof ThreadDeath
                        || failure instanceof LinkageError) {
                    return asyncFailure(failure);
                }
                Env catchEnv = new Env(env);
                catchEnv.define(tried.errorName(), failure, Ast.BindingKind.VAL);
                return asyncBlock(tried.catchBody(), catchEnv, noTail);
            });

            return asyncFold(
                    caught,
                    originalFlow -> asyncFlatMap(
                            asyncBlock(tried.finallyBody(), env, noTail),
                            finallyFlow -> finallyFlow instanceof AsyncReturn
                                    ? asyncPure(finallyFlow)
                                    : asyncPure(originalFlow)),
                    originalFailure -> asyncFold(
                            asyncBlock(tried.finallyBody(), env, noTail),
                            finallyFlow -> finallyFlow instanceof AsyncReturn
                                    ? asyncPure(finallyFlow)
                                    : asyncFailure(originalFailure),
                            finallyFailure -> asyncFailure(finallyFailure)));
        }

        private record AsyncFunctionTarget(
                Evaluator owner,
                Ast.FunctionDecl function) { }

        private AsyncFunctionTarget directAsyncFunctionTarget(
                Ast.NameExpr name,
                int arity,
                Env env) {
            if (env.lookup(name.name()) != Env.MISSING) return null;

            Ast.FunctionDecl fn = findFunction(name.name());
            Evaluator owner = this;

            if (fn == null) {
                Ast.ImportDecl imported = namedImports.get(name.name());
                if (imported == null || imported.kind() != Ast.ImportKind.FUNCTION) {
                    return null;
                }
                owner = importedTarget(imported);
                fn = owner.findFunction(name.name());
                if (fn == null || fn.visibility() != Ast.Visibility.PUBLIC) return null;
            }

            if (!fn.async()
                    || fn.actorKind() != Ast.ActorKind.NONE
                    || fn.parameters().size() != arity) {
                return null;
            }
            return new AsyncFunctionTarget(owner, fn);
        }

        private static boolean tailResultCompatible(
                AsyncCallableContext caller,
                Ast.TypeRef callee,
                Set<String> calleeGenericParameters) {
            return tailTypeIsConcrete(
                            caller.returnType(),
                            caller.genericParameters())
                    && tailTypeIsConcrete(callee, calleeGenericParameters)
                    && Objects.equals(caller.returnType(), callee);
        }

        /**
         * Try to lower a source-level {@code return await call(...)} into a
         * proper async tail transfer. Returning null means that the generic
         * await path must be used instead.
         */
        private AsyncPlan asyncTailAwaitCall(
                Ast.CallExpr call,
                Ast.AwaitExpr awaited,
                Env env,
                AsyncCallableContext callable) {
            OresAsyncTrace.SourceSite callSite = traceSite(awaited.site());

            if (call.callee() instanceof Ast.NameExpr name) {
                AsyncFunctionTarget target = directAsyncFunctionTarget(
                        name,
                        call.arguments().size(),
                        env);
                if (target == null
                        || !tailResultCompatible(
                                callable,
                                target.function().returnType(),
                                Set.copyOf(target.function().genericParameters()))) {
                    return null;
                }

                return asyncFlatMap(
                        asyncEvalArguments(
                                call.arguments(),
                                0,
                                env,
                                new ArrayList<>()),
                        rawArgs -> {
                            List<?> normalized = target.owner().normalizeFunctionArguments(
                                    target.function(),
                                    castObjectList(rawArgs));
                            return new AsyncTailTransfer(
                                    target.owner().asyncFunctionPlan(
                                            target.function(),
                                            normalized),
                                    target.owner().functionTraceFrame(
                                            target.function()),
                                    callSite);
                        });
            }

            if (!(call.callee() instanceof Ast.MemberExpr memberCall)) {
                return null;
            }

            return asyncFlatMap(
                    asyncEval(memberCall.receiver(), env),
                    receiver -> asyncFlatMap(
                            asyncEvalArguments(
                                    call.arguments(),
                                    0,
                                    env,
                                    new ArrayList<>()),
                            rawArgs -> {
                                List<Object> args = castObjectList(rawArgs);
                                AsyncPlan transfer = asyncTailMemberTransfer(
                                        receiver,
                                        memberCall.member(),
                                        args,
                                        callable,
                                        callSite);
                                if (transfer != null) return transfer;

                                return safePlan(() -> asyncAwaitReturnValue(
                                        invokeEvaluatedMemberCall(
                                                receiver,
                                                memberCall.member(),
                                                args),
                                        callSite));
                            }));
        }

        private AsyncPlan asyncTailMemberTransfer(
                Object receiver,
                String memberName,
                List<Object> args,
                AsyncCallableContext callable,
                OresAsyncTrace.SourceSite callSite) {
            if (receiver instanceof OresObject object
                    && object.klass.actorKind() == Ast.ActorKind.NONE) {
                Ast.MethodDecl method = object.owner.findMethod(
                        object.klass,
                        memberName,
                        args.size(),
                        new LinkedHashSet<>());
                if (method != null
                        && method.async()
                        && tailResultCompatible(
                                callable,
                                method.returnType(),
                                asyncGenericParameters(object.klass, method))) {
                    return new AsyncTailTransfer(
                            object.owner.asyncMethodPlan(
                                    object,
                                    method,
                                    args),
                            object.owner.methodTraceFrame(
                                    object.klass,
                                    method),
                            callSite);
                }
                return null;
            }

            if (receiver instanceof ClassFacade klass
                    && klass.klass().actorKind() == Ast.ActorKind.NONE) {
                Ast.MethodDecl fn = klass.owner().findStaticFunction(
                        klass.klass(),
                        memberName,
                        args.size(),
                        new LinkedHashSet<>());
                if (fn != null
                        && fn.async()
                        && tailResultCompatible(
                                callable,
                                fn.returnType(),
                                asyncGenericParameters(klass.klass(), fn))) {
                    return new AsyncTailTransfer(
                            klass.owner().asyncStaticFunctionPlan(
                                    klass.klass(),
                                    fn,
                                    args),
                            klass.owner().methodTraceFrame(
                                    klass.klass(),
                                    fn),
                            callSite);
                }
                return null;
            }

            if (receiver instanceof ModuleFacade module) {
                Ast.FunctionDecl fn = null;
                for (Ast.Decl declaration : module.module().declarations()) {
                    if (declaration instanceof Ast.FunctionDecl candidate
                            && candidate.name().equals(memberName)
                            && candidate.parameters().size() == args.size()
                            && candidate.visibility() == Ast.Visibility.PUBLIC) {
                        fn = candidate;
                        break;
                    }
                }
                if (fn != null
                        && fn.async()
                        && fn.actorKind() == Ast.ActorKind.NONE
                        && tailResultCompatible(
                                callable,
                                fn.returnType(),
                                Set.copyOf(fn.genericParameters()))) {
                    return new AsyncTailTransfer(
                            module.owner().asyncFunctionPlan(fn, args),
                            module.owner().functionTraceFrame(fn),
                            callSite);
                }
                return null;
            }

            if (receiver instanceof ImportedNamespace namespace
                    && (namespace.kind() == Ast.ImportKind.FUNCTION
                            || namespace.kind() == Ast.ImportKind.ALL)) {
                Ast.FunctionDecl fn = namespace.owner().findFunction(memberName);
                if (fn != null
                        && fn.visibility() == Ast.Visibility.PUBLIC
                        && fn.async()
                        && fn.actorKind() == Ast.ActorKind.NONE
                        && fn.parameters().size() == args.size()
                        && tailResultCompatible(
                                callable,
                                fn.returnType(),
                                Set.copyOf(fn.genericParameters()))) {
                    return new AsyncTailTransfer(
                            namespace.owner().asyncFunctionPlan(fn, args),
                            namespace.owner().functionTraceFrame(fn),
                            callSite);
                }
            }

            // Actor handles/spawns deliberately fall through. Even a syntactic
            // tail position may only forward the result across an actor
            // boundary; actor execution frames and scheduler domains are never
            // fused into the caller task.
            return null;
        }

        private AsyncPlan asyncAwaitReturnValue(
                Object value,
                OresAsyncTrace.SourceSite site) {
            OresFuture<?> future = awaitableFuture(value);
            return new AsyncAwait(
                    future,
                    (result, failure) -> failure == null
                            ? asyncPure(new AsyncReturn(result))
                            : asyncFailure(unwrapFutureFailure(failure)),
                    site);
        }

        private OresFuture<?> awaitableFuture(Object value) {
            Objects.requireNonNull(value, "awaitable");

            if (value instanceof Awaitable<?> awaitable) {
                return Objects.requireNonNull(
                        awaitable.getAwaited(),
                        "Awaitable.get_awaited() returned null");
            }

            if (value instanceof CompletionStage<?> stage) {
                return OresFuture.from(stage);
            }

            if (value instanceof OresObject object
                    && classImplementsAwaitable(object.klass, new LinkedHashSet<>())) {
                Ast.MethodDecl method =
                        findMethod(object.klass, "get_awaited", 0, new LinkedHashSet<>());
                if (method == null) {
                    throw new IllegalStateException(
                            "class " + object.klass.name()
                                    + " implements Awaitable<T> but has no get_awaited() method");
                }
                Object projected = callMethod(object, method, List.of());
                if (projected instanceof Awaitable<?> awaitable) {
                    return Objects.requireNonNull(
                            awaitable.getAwaited(),
                            "Awaitable.get_awaited() returned null");
                }
                if (projected instanceof CompletionStage<?> stage) {
                    return OresFuture.from(stage);
                }
                throw new IllegalStateException(
                        "Awaitable.get_awaited() on " + object.klass.name()
                                + " must return Future<T>");
            }

            throw new IllegalArgumentException(
                    "await requires Awaitable<T>; value of runtime type "
                            + value.getClass().getName() + " is not awaitable");
        }

        private boolean classImplementsAwaitable(
                Ast.ClassDecl klass,
                Set<Ast.ClassDecl> seen) {
            if (!seen.add(klass)) return false;
            for (Ast.TypeRef ifaceRef : klass.interfaces()) {
                if (ifaceRef.name().equals("Awaitable")) return true;
                Ast.InterfaceDecl iface = findInterface(ifaceRef.name());
                if (iface != null
                        && interfaceExtendsAwaitable(iface, new LinkedHashSet<>())) {
                    return true;
                }
            }
            for (Ast.TypeRef parentRef : klass.parents()) {
                if (parentRef.name().equals("Object")
                        || parentRef.name().equals("List")) {
                    continue;
                }
                Ast.ClassDecl parent = findClass(parentRef.name());
                if (parent != null && classImplementsAwaitable(parent, seen)) {
                    return true;
                }
            }
            return false;
        }

        private boolean interfaceExtendsAwaitable(
                Ast.InterfaceDecl iface,
                Set<Ast.InterfaceDecl> seen) {
            if (!seen.add(iface)) return false;
            for (Ast.TypeRef parentRef : iface.parents()) {
                if (parentRef.name().equals("Awaitable")) return true;
                Ast.InterfaceDecl parent = findInterface(parentRef.name());
                if (parent != null
                        && interfaceExtendsAwaitable(parent, seen)) {
                    return true;
                }
            }
            return false;
        }

        private AsyncPlan asyncEval(Ast.Expr expr, Env env) {
            if (!containsAwait(expr)) {
                return new AsyncThunk(() -> safePlan(() -> asyncPure(eval(expr, env))));
            }

            if (expr instanceof Ast.AwaitExpr awaited) {
                return asyncFlatMap(asyncEval(awaited.expression(), env), value -> {
                    OresFuture<?> future = awaitableFuture(value);
                    return new AsyncAwait(
                            future,
                            (result, failure) -> failure == null
                                    ? asyncPure(result)
                                    : asyncFailure(unwrapFutureFailure(failure)),
                            traceSite(awaited.site()));
                });
            }

            if (expr instanceof Ast.ConditionalExpr conditional) {
                return asyncFlatMap(asyncEval(conditional.condition(), env), value ->
                        truth(value)
                                ? asyncEval(conditional.whenTrue(), env)
                                : asyncEval(conditional.whenFalse(), env));
            }

            if (expr instanceof Ast.UnaryExpr unary) {
                return asyncFlatMap(asyncEval(unary.operand(), env), value ->
                        safePlan(() -> asyncPure(switch (unary.operator()) {
                            case "&", "&mut" -> value;
                            case "!" -> !truth(value);
                            case "~" -> ~integralLong(value);
                            case "+" -> value;
                            case "-" -> negate(value);
                            default -> throw new IllegalArgumentException(
                                    "unsupported unary operator " + unary.operator());
                        })));
            }

            if (expr instanceof Ast.BinaryExpr binaryExpr) {
                return asyncFlatMap(asyncEval(binaryExpr.left(), env), left -> {
                    if (binaryExpr.operator().equals("&&") && !truth(left)) {
                        return asyncPure(Boolean.FALSE);
                    }
                    if (binaryExpr.operator().equals("||") && truth(left)) {
                        return asyncPure(Boolean.TRUE);
                    }
                    return asyncFlatMap(asyncEval(binaryExpr.right(), env), right -> {
                        if (binaryExpr.operator().equals("&&")) {
                            return asyncPure(truth(left) && truth(right));
                        }
                        if (binaryExpr.operator().equals("||")) {
                            return asyncPure(truth(left) || truth(right));
                        }
                        if (binaryExpr.operator().equals("^^")) {
                            return asyncPure(truth(left) ^ truth(right));
                        }
                        if (binaryExpr.operator().equals("|")
                                && left instanceof Boolean lb
                                && right instanceof Boolean rb) {
                            return asyncPure(lb || rb);
                        }
                        return safePlan(() ->
                                asyncPure(binary(binaryExpr.operator(), left, right)));
                    });
                });
            }

            if (expr instanceof Ast.AssignExpr assignment) {
                return asyncFlatMap(asyncEval(assignment.value(), env), value ->
                        asyncAssign(assignment.target(), value, env));
            }

            if (expr instanceof Ast.CallExpr call) {
                if (call.callee() instanceof Ast.MemberExpr memberCall) {
                    return asyncFlatMap(asyncEval(memberCall.receiver(), env), receiver ->
                            asyncFlatMap(asyncEvalArguments(call.arguments(), 0, env, new ArrayList<>()), args ->
                                    safePlan(() -> asyncPure(invokeEvaluatedMemberCall(
                                            receiver,
                                            memberCall.member(),
                                            castObjectList(args))))));
                }
                return asyncFlatMap(asyncEval(call.callee(), env), callee ->
                        asyncFlatMap(asyncEvalArguments(call.arguments(), 0, env, new ArrayList<>()), args -> {
                            if (!(callee instanceof Invokable invokable)) {
                                return asyncFailure(new IllegalArgumentException(
                                        "value is not callable: " + callee));
                            }
                            return safePlan(() ->
                                    asyncPure(invokable.call(castObjectList(args))));
                        }));
            }

            if (expr instanceof Ast.MemberExpr memberExpr) {
                return asyncFlatMap(
                        asyncEval(memberExpr.receiver(), env),
                        receiver -> safePlan(() ->
                                asyncPure(member(receiver, memberExpr.member()))));
            }

            if (expr instanceof Ast.IndexExpr indexed) {
                return asyncFlatMap(asyncEval(indexed.receiver(), env), receiver ->
                        asyncFlatMap(asyncEval(indexed.index(), env), index -> {
                            if (!(index instanceof Number number)) {
                                return asyncFailure(new IllegalArgumentException(
                                        "index must be an integer"));
                            }
                            int i = Math.toIntExact(number.longValue());
                            if (receiver instanceof List<?> list) {
                                return asyncPure(list.get(i));
                            }
                            if (receiver instanceof Object[] array) {
                                return asyncPure(array[i]);
                            }
                            return asyncFailure(new IllegalArgumentException(
                                    "value is not indexable: " + receiver));
                        }));
            }

            if (expr instanceof Ast.NewExpr created) {
                return asyncFlatMap(
                        asyncEvalArguments(created.arguments(), 0, env, new ArrayList<>()),
                        rawArgs -> safePlan(() -> {
                            List<Object> args = castObjectList(rawArgs);
                            if (created.type().name().equals("OresScheduler")) {
                                if (args.size() != 1 || !(args.getFirst() instanceof Number number)) {
                                    throw new IllegalArgumentException(
                                            "new OresScheduler(...) expects exactly one integer parallelism");
                                }
                                return asyncPure(new SchedulerFacade(
                                        Math.toIntExact(number.longValue())));
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
                            if (klass == null) {
                                throw new IllegalArgumentException(
                                        "unknown class " + created.type().name());
                            }
                            return asyncPure(owner.instantiate(
                                    klass,
                                    castObjectList(args)));
                        }));
            }

            if (expr instanceof Ast.SpawnExpr spawned) {
                Ast.FunctionDecl target = resolveSpawnTarget(spawned.call());
                return asyncFlatMap(
                        asyncEvalArguments(
                                spawned.call().arguments(),
                                0,
                                env,
                                new ArrayList<>()),
                        args -> safePlan(() ->
                                asyncPure(spawnFunctionWithArguments(
                                        target,
                                        castObjectList(args)))));
            }

            if (expr instanceof Ast.ListExpr list) {
                return asyncEvalArguments(list.elements(), 0, env, new ArrayList<>());
            }

            if (expr instanceof Ast.TupleExpr tuple) {
                return asyncEvalArguments(tuple.elements(), 0, env, new ArrayList<>());
            }

            if (expr instanceof Ast.ObjectExpr object) {
                return asyncObjectFields(
                        object.fields(),
                        0,
                        env,
                        new LinkedHashMap<>());
            }

            return new AsyncThunk(() -> safePlan(() -> asyncPure(eval(expr, env))));
        }

        private AsyncPlan asyncAssign(Ast.Expr target, Object value, Env env) {
            if (target instanceof Ast.NameExpr name) {
                env.assign(name.name(), value);
                return asyncPure(value);
            }
            if (target instanceof Ast.MemberExpr memberTarget) {
                return asyncFlatMap(asyncEval(memberTarget.receiver(), env), receiver ->
                        safePlan(() -> {
                            Object actual = receiver;
                            if (actual instanceof OresMutex.Guard<?> guard) {
                                actual = guard.value();
                            }
                            if (actual instanceof OresObject object) {
                                if (!object.fields.containsKey(memberTarget.member())) {
                                    throw new IllegalArgumentException(
                                            "unknown field " + memberTarget.member());
                                }
                                Ast.FieldDecl field = effectiveFields(
                                                object.klass,
                                                new LinkedHashSet<>())
                                        .stream()
                                        .filter(candidate ->
                                                candidate.name().equals(memberTarget.member()))
                                        .findFirst()
                                        .orElseThrow(() ->
                                                new IllegalArgumentException(
                                                        "unknown field "
                                                                + memberTarget.member()));
                                if (field.bindingKind() != Ast.BindingKind.LET) {
                                    throw new IllegalArgumentException(
                                            "field '"
                                                    + object.klass.name()
                                                    + "."
                                                    + memberTarget.member()
                                                    + "' is immutable");
                                }
                                object.fields.put(memberTarget.member(), value);
                                return asyncPure(value);
                            }
                            throw new IllegalArgumentException(
                                    "member assignment requires a class instance "
                                            + "or mutex guard over a class instance");
                        }));
            }
            if (target instanceof Ast.IndexExpr indexedTarget) {
                return asyncFlatMap(asyncEval(indexedTarget.receiver(), env), receiver ->
                        asyncFlatMap(asyncEval(indexedTarget.index(), env), index -> {
                            if (!(index instanceof Number number)) {
                                return asyncFailure(new IllegalArgumentException(
                                        "index must be an integer"));
                            }
                            int i = Math.toIntExact(number.longValue());
                            if (receiver instanceof List<?> raw) {
                                @SuppressWarnings("unchecked")
                                List<Object> list = (List<Object>) raw;
                                list.set(i, value);
                                return asyncPure(value);
                            }
                            return asyncFailure(new IllegalArgumentException(
                                    "indexed assignment requires a mutable array/list"));
                        }));
            }
            return asyncFailure(new IllegalArgumentException(
                    "unsupported assignment target"));
        }

        private AsyncPlan asyncEvalArguments(
                List<? extends Ast.Expr> expressions,
                int index,
                Env env,
                ArrayList<Object> values) {
            if (index >= expressions.size()) {
                return asyncPure(List.copyOf(values));
            }
            return asyncFlatMap(asyncEval(expressions.get(index), env), value -> {
                values.add(value);
                return asyncEvalArguments(expressions, index + 1, env, values);
            });
        }

        private AsyncPlan asyncObjectFields(
                List<Ast.ObjectField> fields,
                int index,
                Env env,
                LinkedHashMap<String, Object> values) {
            if (index >= fields.size()) return asyncPure(Map.copyOf(values));
            Ast.ObjectField field = fields.get(index);
            return asyncFlatMap(asyncEval(field.value(), env), value -> {
                if (values.putIfAbsent(field.name(), value) != null) {
                    return asyncFailure(new IllegalArgumentException(
                            "duplicate obj field " + field.name()));
                }
                return asyncObjectFields(fields, index + 1, env, values);
            });
        }

        @SuppressWarnings("unchecked")
        private static List<Object> castObjectList(Object value) {
            return (List<Object>) value;
        }

        private Object invokeEvaluatedMemberCall(
                Object receiver,
                String memberName,
                List<Object> args) {
            if (receiver instanceof OresObject object) {
                return object.owner.invokeMethod(object, memberName, args);
            }
            if (receiver instanceof ClassFacade klass) {
                return klass.owner().invokeStaticFunction(
                        klass.klass(),
                        memberName,
                        args);
            }
            Object callee = member(receiver, memberName);
            if (!(callee instanceof Invokable invokable)) {
                throw new IllegalArgumentException(
                        "value is not callable: " + callee);
            }
            return invokable.call(args);
        }

        private Ast.FunctionDecl resolveSpawnTarget(Ast.CallExpr call) {
            Ast.FunctionDecl fn;
            if (call.callee() instanceof Ast.NameExpr name) {
                fn = findFunction(name.name());
            } else if (call.callee() instanceof Ast.MemberExpr member
                    && member.receiver() instanceof Ast.NameExpr namespace) {
                fn = functions.get(namespace.name() + "." + member.member());
            } else {
                throw new IllegalArgumentException(
                        "spawn requires a direct actor fnc/routine call");
            }
            if (fn == null) throw new IllegalArgumentException("unknown spawn target");
            if (fn.actorKind() == Ast.ActorKind.NONE) {
                throw new IllegalArgumentException(
                        "spawn target '" + fn.name() + "' is not an actor callable");
            }
            return fn;
        }

        private Object spawnFunctionWithArguments(
                Ast.FunctionDecl fn,
                List<Object> evaluated) {
            ActorRuntime.ActorKind runtimeKind = switch (fn.actorKind()) {
                case NONE -> throw new AssertionError(
                        "non-actor callable reached spawn lowering");
                case PRIVATE -> ActorRuntime.ActorKind.PRIVATE;
                case SHARED -> ActorRuntime.ActorKind.SHARED;
                case UNTRUSTED -> throw new SecurityException(
                        "untrusted actor callables require GraalWasm sandbox lowering; "
                                + "the ordinary JVM interpreter must not execute hostile guest code");
            };

            List<?> normalized = normalizeFunctionArguments(fn, evaluated);
            OresAsyncTrace.Trace actorTrace =
                    traceForInvocation(functionTraceFrame(fn));
            actorTrace.boundary(
                    OresAsyncTrace.BoundaryKind.ACTOR_MESSAGE,
                    functionTraceFrame(fn).site());

            return context.actors().spawnSuspendingInvocation(
                    runtimeKind,
                    normalized,
                    (delivered, actorContext, completion) -> {
                        @SuppressWarnings("unchecked")
                        ActorRuntime.InvocationCompletion<Object> result =
                                (ActorRuntime.InvocationCompletion<Object>) completion;
                        ActorPlanRunner runner = new ActorPlanRunner(
                                asyncFunctionPlan(fn, delivered),
                                result,
                                actorTrace);
                        runner.start(actorContext);
                    });
        }

        private static boolean containsAwait(Ast.Expr expr) {
            if (expr instanceof Ast.AwaitExpr) return true;
            if (expr instanceof Ast.LambdaExpr) return false;
            if (expr instanceof Ast.UnaryExpr unary) return containsAwait(unary.operand());
            if (expr instanceof Ast.BinaryExpr binary) {
                return containsAwait(binary.left()) || containsAwait(binary.right());
            }
            if (expr instanceof Ast.AssignExpr assignment) {
                return containsAwait(assignment.target())
                        || containsAwait(assignment.value());
            }
            if (expr instanceof Ast.ConditionalExpr conditional) {
                return containsAwait(conditional.condition())
                        || containsAwait(conditional.whenTrue())
                        || containsAwait(conditional.whenFalse());
            }
            if (expr instanceof Ast.CallExpr call) {
                if (containsAwait(call.callee())) return true;
                for (Ast.Expr argument : call.arguments()) {
                    if (containsAwait(argument)) return true;
                }
                return false;
            }
            if (expr instanceof Ast.MemberExpr member) {
                return containsAwait(member.receiver());
            }
            if (expr instanceof Ast.IndexExpr indexed) {
                return containsAwait(indexed.receiver())
                        || containsAwait(indexed.index());
            }
            if (expr instanceof Ast.NewExpr created) {
                for (Ast.Expr argument : created.arguments()) {
                    if (containsAwait(argument)) return true;
                }
                return false;
            }
            if (expr instanceof Ast.SpawnExpr spawned) {
                for (Ast.Expr argument : spawned.call().arguments()) {
                    if (containsAwait(argument)) return true;
                }
                return false;
            }
            if (expr instanceof Ast.ListExpr list) {
                for (Ast.Expr element : list.elements()) {
                    if (containsAwait(element)) return true;
                }
                return false;
            }
            if (expr instanceof Ast.TupleExpr tuple) {
                for (Ast.Expr element : tuple.elements()) {
                    if (containsAwait(element)) return true;
                }
                return false;
            }
            if (expr instanceof Ast.ObjectExpr object) {
                for (Ast.ObjectField field : object.fields()) {
                    if (containsAwait(field.value())) return true;
                }
            }
            return false;
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
            if (args.size() != method.parameters().size()) {
                throw new IllegalArgumentException(
                        "method " + method.name() + " arity mismatch");
            }
            if (method.async() && receiver.klass.actorKind() == Ast.ActorKind.NONE) {
                return startAsyncMethod(receiver, method, args);
            }

            Env env = new Env(null);
            if (!method.isStatic()) env.define("self", receiver, Ast.BindingKind.VAL);
            for (int i = 0; i < method.parameters().size(); i++) {
                Ast.Param param = method.parameters().get(i);
                env.define(
                        param.name(),
                        args.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(method.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(
                        method.returnType(),
                        signal.value,
                        "method " + method.name());
            }
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
            // Every actor kind observes stop/turn-overrun signals at statement
            // boundaries. UNTRUSTED actors additionally consume fuel here and
            // at expression boundaries below.
            if (ActorRuntime.inActorExecution() || ActorRuntime.inRootExecution()) {
                context.schedulerSafepoint();
            }
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
            if (stmt instanceof Ast.TryStmt tried) {
                try { executeBlock(tried.body(), env); }
                catch (ReturnSignal signal) { throw signal; }
                catch (OresPanic panic) { throw panic; }
                catch (RuntimeException failure) {
                    if (ActorRuntime.isActorControlAbort(failure)) throw failure;
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

        private Object eval(Ast.Expr expr, Env env) {
            if (ActorRuntime.currentActorKind() == ActorRuntime.ActorKind.UNTRUSTED) {
                context.schedulerSafepoint();
            }
            if (expr instanceof Ast.LiteralExpr literal) {
                if (literal.value() == null) throw new IllegalArgumentException("standalone null values are forbidden");
                if (literal.value() instanceof Ast.Imaginary imaginary) return new Complex(0.0, imaginary.coefficient());
                return literal.value();
            }
            if (expr instanceof Ast.NameExpr name) {
                Object local = env.lookup(name.name());
                if (local != Env.MISSING) return local;
                if (name.name().equals("stdio")) return new StdioFacade(context);
                if (name.name().equals("process")) return new ProcessFacade(context);
                if (name.name().equals("actor")) return new ActorFacade(context);
                if (name.name().equals("Futures")) return new FuturesFacade();
                if (name.name().equals("Future")) return new FutureFactory();
                if (name.name().equals("Mutex")) return new MutexFactory(false, context);
                if (name.name().equals("SharedMutex")) return new MutexFactory(true, context);
                if (name.name().equals("RwLock")) return new RwLockFactory(context);
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
                        if (field.bindingKind() != Ast.BindingKind.LET) {
                            throw new IllegalArgumentException("field '" + object.klass.name() + "."
                                    + target.member() + "' is immutable");
                        }
                        object.fields.put(target.member(), value);
                        return value;
                    }
                    throw new IllegalArgumentException("member assignment requires a class instance or mutex guard over a class instance");
                }
                if (assignment.target() instanceof Ast.IndexExpr target) {
                    Object receiver = eval(target.receiver(), env);
                    Object index = eval(target.index(), env);
                    if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                    int i = Math.toIntExact(number.longValue());
                    if (receiver instanceof List<?> raw) {
                        @SuppressWarnings("unchecked") List<Object> list = (List<Object>) raw;
                        list.set(i, value);
                        return value;
                    }
                    throw new IllegalArgumentException("indexed assignment requires a mutable array/list");
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
            if (expr instanceof Ast.MemberExpr member) return member(eval(member.receiver(), env), member.member());
            if (expr instanceof Ast.IndexExpr indexed) {
                Object receiver = eval(indexed.receiver(), env);
                Object index = eval(indexed.index(), env);
                if (!(index instanceof Number number)) throw new IllegalArgumentException("index must be an integer");
                int i = Math.toIntExact(number.longValue());
                if (receiver instanceof List<?> list) return list.get(i);
                if (receiver instanceof Object[] array) return array[i];
                throw new IllegalArgumentException("value is not indexable: " + receiver);
            }
            if (expr instanceof Ast.NewExpr created) {
                if (created.type().name().equals("OresScheduler")) {
                    if (created.arguments().size() != 1) {
                        throw new IllegalArgumentException(
                                "new OresScheduler(...) expects exactly one integer parallelism");
                    }
                    Object value = eval(created.arguments().getFirst(), env);
                    if (!(value instanceof Number number)) {
                        throw new IllegalArgumentException(
                                "OresScheduler parallelism must be an integer");
                    }
                    return new SchedulerFacade(Math.toIntExact(number.longValue()));
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
            if (expr instanceof Ast.SpawnExpr spawned) {
                return spawnFunction(spawned.call(), env);
            }
            if (expr instanceof Ast.AwaitExpr awaited) {
                OresFuture<?> future = awaitableFuture(eval(awaited.expression(), env));

                // Recursive evaluator is host/root compatibility only. Async
                // source and actor turns must use the stackless plan lowering.
                if (ActorRuntime.inActorExecution()) {
                    throw new IllegalStateException(
                            "source await inside an actor requires continuation lowering; "
                                    + "the recursive evaluator must not block or inline-resume an actor carrier");
                }
                if (ActorRuntime.currentRootIsAdversarial() && !future.isDone()) {
                    throw new IllegalStateException(
                            "await would block an adversarial serialized root context; "
                                    + "continuation lowering must suspend/resume before awaiting readiness/result");
                }
                return future.join();
            }
            if (expr instanceof Ast.ListExpr list) {
                ArrayList<Object> result = new ArrayList<>(list.elements().size());
                for (Ast.Expr item : list.elements()) result.add(eval(item, env));
                return result;
            }
            if (expr instanceof Ast.TupleExpr tuple) return tuple.elements().stream().map(item -> eval(item, env)).toList();
            if (expr instanceof Ast.ObjectExpr object) {
                LinkedHashMap<String, Object> result = new LinkedHashMap<>();
                for (Ast.ObjectField field : object.fields()) {
                    if (result.putIfAbsent(field.name(), eval(field.value(), env)) != null) throw new IllegalArgumentException("duplicate obj field " + field.name());
                }
                return Map.copyOf(result);
            }
            if (expr instanceof Ast.LambdaExpr lambda) {
                boolean nonLexical =
                        lambda.nonLexical() || env.descendantsNonLexical();
                Env captured = nonLexical ? null : env.snapshot();
                if (lambda.async()) {
                    return new AsyncLambdaValue(lambda, captured);
                }
                return (Invokable) args -> {
                    if (args.size() != lambda.parameters().size()) {
                        throw new IllegalArgumentException("lambda arity mismatch");
                    }
                    Env local = new Env(captured, nonLexical);
                    for (int i = 0; i < lambda.parameters().size(); i++) {
                        Ast.Param param = lambda.parameters().get(i);
                        local.define(
                                param.name(),
                                args.get(i),
                                param.mutable()
                                        ? Ast.BindingKind.LET
                                        : Ast.BindingKind.VAL);
                    }
                    if (lambda.expressionBody() != null) {
                        return eval(lambda.expressionBody(), local);
                    }
                    try {
                        executeBlock(lambda.blockBody(), local);
                        return null;
                    } catch (ReturnSignal signal) {
                        return signal.value;
                    }
                };
            }
            throw new IllegalArgumentException("unsupported expression " + expr);
        }

        private Object member(Object receiver, String name) {
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
            if (receiver instanceof SchedulerFacade scheduler) {
                return scheduler.member(name);
            }
            if (receiver instanceof FuturesFacade futures) {
                return switch (name) {
                    case "all" -> (Invokable) futures::all;
                    case "race" -> (Invokable) futures::race;
                    default -> throw new IllegalArgumentException("unknown Futures member " + name);
                };
            }
            if (receiver instanceof FutureFactory factory) {
                return switch (name) {
                    case "from_callback" -> (Invokable) factory::fromCallback;
                    default -> throw new IllegalArgumentException(
                            "unknown Future static member " + name);
                };
            }
            if (receiver instanceof CallbackFacade callback) {
                return callback.member(name);
            }
            if (receiver instanceof ActorRuntime.ActorSpawn<?, ?> spawn) {
                return switch (name) {
                    case "id" -> spawn.id();
                    case "ready" -> spawn.ready();
                    case "done" -> spawn.done();
                    case "result" -> spawn.result();
                    case "get_awaited" -> (Invokable) args -> {
                        requireZero(args, "ActorSpawn.get_awaited");
                        return spawn.getAwaited();
                    };
                    default -> throw new IllegalArgumentException("unknown ActorSpawn member " + name);
                };
            }
            if (receiver instanceof ActorRuntime.ActorRef<?> ref) {
                return switch (name) {
                    case "id" -> ref.id();
                    case "is_alive" -> (Invokable) args -> {
                        requireZero(args, "ActorRef.is_alive");
                        return ref.isAlive();
                    };
                    default -> throw new IllegalArgumentException("unknown ActorRef member " + name);
                };
            }
            if (receiver instanceof OresFuture<?> future) {
                return switch (name) {
                    case "is_done" -> (Invokable) args -> {
                        requireZero(args, "Future.is_done");
                        return future.isDone();
                    };
                    case "is_cancelled" -> (Invokable) args -> {
                        requireZero(args, "Future.is_cancelled");
                        return future.isCancelled();
                    };
                    case "cancel" -> (Invokable) args -> {
                        requireZero(args, "Future.cancel");
                        return future.cancel(true);
                    };
                    case "get_awaited" -> (Invokable) args -> {
                        requireZero(args, "Future.get_awaited");
                        return future.getAwaited();
                    };
                    case "attach_callback" -> (Invokable) args -> {
                        requireOne(args, "Future.attach_callback");
                        if (!(args.getFirst() instanceof Invokable registrar)) {
                            throw new IllegalArgumentException(
                                    "Future.attach_callback expects a callback registrar");
                        }
                        @SuppressWarnings("unchecked")
                        OresFuture<Object> source = (OresFuture<Object>) future;
                        return source.attachCallback(
                                asyncScheduler(),
                                (value, completion) -> {
                                    Object returned = registrar.call(List.of(
                                            value,
                                            new CallbackFacade(
                                                    (OresFuture.Callback<Object>) completion)));
                                    if (returned != null) {
                                        throw new IllegalArgumentException(
                                                "Future.attach_callback registrar must return void");
                                    }
                                });
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown Future member " + name + "; use await to obtain its value");
                };
            }
            if (receiver instanceof CompletionStage<?> stage) {
                var future = stage.toCompletableFuture();
                return switch (name) {
                    case "is_done" -> (Invokable) args -> {
                        requireZero(args, "Future.is_done");
                        return future.isDone();
                    };
                    case "is_cancelled" -> (Invokable) args -> {
                        requireZero(args, "Future.is_cancelled");
                        return future.isCancelled();
                    };
                    case "cancel" -> (Invokable) args -> {
                        requireZero(args, "Future.cancel");
                        return future.cancel(true);
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown Future member " + name + "; use await to obtain its value");
                };
            }
            if (receiver instanceof MutexFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown mutex factory member " + name);
                return (Invokable) factory::create;
            }
            if (receiver instanceof RwLockFactory factory) {
                if (!name.equals("new")) throw new IllegalArgumentException("unknown RwLock factory member " + name);
                return (Invokable) factory::create;
            }
            if (receiver instanceof OptionValue option) return optionMember(option, name);
            if (receiver instanceof ResultValue result) return resultMember(result, name);
            if (receiver instanceof OresRwLock<?> rwLock) return rwLockMember(rwLock, name);
            if (receiver instanceof OresRwLock.ReadGuard<?> guard) {
                return switch (name) {
                    case "value" -> (Invokable) args -> {
                        requireZero(args, "RwReadGuard.value");
                        return guard.value();
                    };
                    case "release" -> (Invokable) args -> {
                        requireZero(args, "RwReadGuard.release");
                        guard.close();
                        return null;
                    };
                    case "is_released" -> (Invokable) args -> {
                        requireZero(args, "RwReadGuard.is_released");
                        return guard.closed();
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown RwReadGuard member " + name);
                };
            }
            if (receiver instanceof OresRwLock.WriteGuard<?> guard) {
                return switch (name) {
                    case "value" -> (Invokable) args -> {
                        requireZero(args, "RwWriteGuard.value");
                        return guard.value();
                    };
                    case "replace" -> (Invokable) args -> {
                        requireOne(args, "RwWriteGuard.replace");
                        @SuppressWarnings("unchecked")
                        OresRwLock.WriteGuard<Object> writable =
                                (OresRwLock.WriteGuard<Object>) guard;
                        writable.replace(args.getFirst());
                        return null;
                    };
                    case "release" -> (Invokable) args -> {
                        requireZero(args, "RwWriteGuard.release");
                        guard.close();
                        return null;
                    };
                    case "is_released" -> (Invokable) args -> {
                        requireZero(args, "RwWriteGuard.is_released");
                        return guard.closed();
                    };
                    default -> throw new IllegalArgumentException(
                            "unknown RwWriteGuard member " + name);
                };
            }
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
                if (object.fields.containsKey(name)) return object.fields.get(name);
                return new BoundMethod(object.owner, object, name);
            }
            if (receiver instanceof Map<?, ?> map) {
                if (!map.containsKey(name)) throw new IllegalArgumentException("unknown obj member " + name);
                return map.get(name);
            }
            throw new IllegalArgumentException("cannot access member '" + name + "' on " + receiver);
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
        private Object rwLockMember(OresRwLock<?> rawLock, String name) {
            context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "RwLock." + name);
            OresRwLock<Object> lock = (OresRwLock<Object>) rawLock;
            return switch (name) {
                case "read_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.read_lock");
                    return lock.readLock();
                };
                case "try_read_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.try_read_lock");
                    var guard = lock.tryReadLock();
                    return guard.isPresent()
                            ? new OptionValue(true, guard.get())
                            : new OptionValue(false, null);
                };
                case "write_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.write_lock");
                    return lock.writeLock();
                };
                case "try_write_lock" -> (Invokable) args -> {
                    requireZero(args, "RwLock.try_write_lock");
                    var guard = lock.tryWriteLock();
                    return guard.isPresent()
                            ? new OptionValue(true, guard.get())
                            : new OptionValue(false, null);
                };
                default -> throw new IllegalArgumentException("unknown RwLock member " + name);
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
            if (!fn.isStatic()) {
                throw new IllegalArgumentException(
                        "not a static class function: " + klass.name() + "." + fn.name());
            }
            if (args.size() != fn.parameters().size()) {
                throw new IllegalArgumentException(
                        "static function " + fn.name() + " arity mismatch");
            }
            if (fn.async()) return startAsyncStaticFunction(klass, fn, args);

            Env env = new Env(null);
            for (int i = 0; i < fn.parameters().size(); i++) {
                Ast.Param param = fn.parameters().get(i);
                env.define(
                        param.name(),
                        args.get(i),
                        param.mutable() ? Ast.BindingKind.LET : Ast.BindingKind.VAL);
            }
            try {
                executeBlock(fn.body(), env);
                return null;
            } catch (ReturnSignal signal) {
                return shapeReturnedValue(
                        fn.returnType(),
                        signal.value,
                        "static function " + fn.name());
            }
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
            Ast.ImportDecl direct = namedImports.get(name);
            if (direct != null) return importedTarget(direct).exportValue(direct.kind(), name);
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
                    && context.hasLinkedCodeUnit(candidate + ".ores")) {
                candidate += ".ores";
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

        private OresObject instantiate(Ast.ClassDecl klass, List<Object> args) {
            if (klass.actorKind() != Ast.ActorKind.NONE) {
                throw new IllegalStateException("actor '" + klass.name()
                        + "' cannot be constructed with new; actor state must be initialized inside ActorRuntime");
            }
            List<Ast.FieldDecl> classFields = effectiveFields(klass, new LinkedHashSet<>());
            if (args.size() > classFields.size()) throw new IllegalArgumentException("too many constructor arguments for " + klass.name());
            LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
            Env env = new Env(null);
            for (int i = 0; i < classFields.size(); i++) {
                Ast.FieldDecl field = classFields.get(i);
                Object value;
                if (i < args.size()) value = args.get(i);
                else if (field.initializer() != null) value = eval(field.initializer(), env);
                else throw new IllegalArgumentException("missing constructor field " + klass.name() + "." + field.name());
                fields.put(field.name(), value);
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
                if (decl instanceof Ast.FunctionDecl fn && fn.name().equals(name) && fn.visibility() == Ast.Visibility.PUBLIC) {
                    return (Invokable) args -> callFunction(fn, args);
                }
                if (decl instanceof Ast.FieldDecl field && field.name().equals(name) && field.visibility() == Ast.Visibility.PUBLIC) {
                    if (field.initializer() == null) throw new IllegalArgumentException("module field has no initializer: " + module.name() + "." + name);
                    return eval(field.initializer(), new Env(null));
                }
            }
            throw new IllegalArgumentException("module '" + module.name() + "' does not export '" + name + "'");
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

        private long integral(Object value, String operator) {
            if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                return ((Number) value).longValue();
            }
            throw new IllegalArgumentException(operator + " requires integer operands");
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
            if (value instanceof OresRwLock.ReadGuard<?> guard) {
                if (!guard.closed()) guard.close();
                return;
            }
            if (value instanceof OresRwLock.WriteGuard<?> guard) {
                if (!guard.closed()) guard.close();
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

    private record ImportedNamespace(Evaluator owner, Ast.ImportKind kind) { }
    private record ModuleFacade(Evaluator owner, Ast.ModuleDecl module) { }
    private record ClassFacade(Evaluator owner, Ast.ClassDecl klass) { }
    private record RwLockFactory(OresContext context) {
        private Object create(List<Object> args) {
            requireOne(args, "RwLock.new");
            context.requireCapability(IsolatePolicy.Capability.SHARED_MEMORY, "RwLock.new");
            Object value = args.getFirst();
            if (!MutexFactory.runtimeSharedSafe(
                    value,
                    java.util.Collections.newSetFromMap(
                            new java.util.IdentityHashMap<>()))) {
                throw new IllegalArgumentException(
                        "RwLock<T> runtime admission rejected non-shared-safe state");
            }
            return new OresRwLock<>(value);
        }
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
                    || value instanceof Complex || value instanceof ActorRuntime.ActorId || value instanceof ActorRuntime.ActorRef<?>) {
                return true;
            }

            if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>
                    || value instanceof OresFuture<?> || value instanceof CompletionStage<?>
                    || value instanceof Invokable) {
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

    private static final class FutureFactory {
        private Object fromCallback(List<Object> args) {
            requireOne(args, "Future.from_callback");
            if (!(args.getFirst() instanceof Invokable registrar)) {
                throw new IllegalArgumentException(
                        "Future.from_callback expects a callback registrar");
            }
            return OresFuture.fromCallback(completion -> {
                Object returned = registrar.call(List.of(
                        new CallbackFacade(
                                (OresFuture.Callback<Object>) completion)));
                if (returned != null) {
                    throw new IllegalArgumentException(
                            "Future.from_callback registrar must return void");
                }
            });
        }
    }

    private static final class CallbackFacade implements Invokable {
        private final OresFuture.Callback<Object> callback;

        private CallbackFacade(OresFuture.Callback<Object> callback) {
            this.callback = Objects.requireNonNull(callback, "callback");
        }

        @Override
        public Object call(List<Object> args) {
            if (args.size() == 1) {
                callback.resolve(args.getFirst());
                return null;
            }
            if (args.size() == 2) {
                Object error = args.get(0);
                Object value = args.get(1);
                if (error == null
                        || (error instanceof OptionValue option
                                && !option.present())) {
                    callback.resolve(value);
                } else if (error instanceof OptionValue option) {
                    callback.reject(callbackFailure(option.value()));
                } else {
                    callback.reject(callbackFailure(error));
                }
                return null;
            }
            throw new IllegalArgumentException(
                    "Callback<T> expects cb(value) or error-first cb(error, value)");
        }

        private Object member(String name) {
            return switch (name) {
                case "resolve" -> (Invokable) args -> {
                    requireOne(args, "Callback.resolve");
                    callback.resolve(args.getFirst());
                    return null;
                };
                case "reject" -> (Invokable) args -> {
                    requireOne(args, "Callback.reject");
                    callback.reject(callbackFailure(args.getFirst()));
                    return null;
                };
                case "cancel" -> (Invokable) args -> {
                    requireZero(args, "Callback.cancel");
                    callback.cancel();
                    return null;
                };
                case "is_done" -> (Invokable) args -> {
                    requireZero(args, "Callback.is_done");
                    return callback.isDone();
                };
                default -> throw new IllegalArgumentException(
                        "unknown Callback member " + name);
            };
        }

        private Throwable callbackFailure(Object error) {
            if (error instanceof Throwable failure) return failure;
            return new IllegalStateException(
                    "callback rejected: " + String.valueOf(error));
        }
    }

    private record FuturesFacade() {
        private Object all(List<Object> args) {
            return OresFutures.all(requireFutures(args, "Futures.all"));
        }

        private Object race(List<Object> args) {
            return OresFutures.race(requireFutures(args, "Futures.race"));
        }

        private static List<Object> requireFutures(
                List<Object> args,
                String operation) {
            requireOne(args, operation);
            if (!(args.getFirst() instanceof List<?> values)) {
                throw new IllegalArgumentException(operation + " expects a list of Future values");
            }
            ArrayList<Object> futures = new ArrayList<>(values.size());
            for (Object value : values) {
                if (!(value instanceof OresFuture<?>)
                        && !(value instanceof CompletionStage<?>)) {
                    throw new IllegalArgumentException(
                            operation + " expects every list element to be a Future");
                }
                futures.add(value);
            }
            return List.copyOf(futures);
        }
    }
    private static void requireZero(List<Object> args,String name){if(!args.isEmpty())throw new IllegalArgumentException(name+" expects no arguments");}
    private static void requireOne(List<Object> args,String name){if(args.size()!=1)throw new IllegalArgumentException(name+" expects one argument");}
    private static String requireStringArg(List<Object> args,String name){
        requireOne(args,name);
        if(!(args.getFirst() instanceof String message)) throw new IllegalArgumentException(name+" expects a String message");
        return message;
    }
}
