package dev.oreslang.runtime;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Host-side actor substrate used by the first interpreter.
 *
 * Oreslang follows the core Akka-style execution invariant: actors are
 * multiplexed over dispatcher threads, but one actor processes its mailbox
 * serially. Carrier-thread identity is never actor identity.
 *
 * PRIVATE and SHARED actors are deliberately bulkheaded onto different
 * dispatchers. Private actors also receive a confined logical memory slice;
 * shared actors may coordinate through explicitly synchronized shared cells.
 */
public final class ActorRuntime implements AutoCloseable {
    private static final int MAX_MESSAGE_GRAPH_DEPTH = 256;
    private static final int MAX_MESSAGE_GRAPH_NODES = 100_000;
    private static final long CLOSE_WAIT_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    public static final Duration HARD_MAX_UNTRUSTED_LIFETIME = Duration.ofSeconds(300);
    private static final long DEFAULT_UNTRUSTED_FUEL_PER_TURN = 100_000L;
    private static final long DEFAULT_UNTRUSTED_MAILBOX_RETURN_BYTES = 1024L * 1024L;
    private static final long DEFAULT_HTTP_REQUEST_BYTES = 16L * 1024L * 1024L;
    private static final long DEFAULT_HTTP_RESPONSE_BYTES = 16L * 1024L * 1024L;
    private static final int MAX_HTTP_REQUEST_HEADER_LOOKUPS = 128;
    private static final long MAX_HTTP_REQUEST_METADATA_BYTES = 64L * 1024L;
    private static final long MAX_HTTP_REQUEST_PATH_BYTES = 8L * 1024L;
    private static final long MAX_HTTP_REQUEST_HEADER_VALUE_BYTES = 16L * 1024L;
    private static final int MAX_HTTP_RESPONSE_HEADERS = 128;
    private static final long MAX_HTTP_RESPONSE_HEADER_BYTES = 64L * 1024L;
    private static final ThreadLocal<Boolean> ACTOR_CARRIER = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<ActorExecutionContext> CURRENT_ACTOR_EXECUTION = new ThreadLocal<>();
    private static final ThreadLocal<ActorRuntime> CURRENT_ROOT_RUNTIME = new ThreadLocal<>();
    private static final ThreadLocal<Long> CURRENT_ROOT_DEADLINE_NANOS = new ThreadLocal<>();

    private record ActorExecutionContext(
            ActorRuntime runtime,
            ActorId actorId,
            ActorKind kind,
            IsolatePolicy policy,
            Object executionDomain) { }

    @FunctionalInterface
    public interface TurnExecutor {
        void execute(Runnable turn);

        static TurnExecutor direct() {
            return Runnable::run;
        }
    }

    public static boolean isActorCarrierThread() {
        return Boolean.TRUE.equals(ACTOR_CARRIER.get());
    }

    public static boolean inActorExecution() {
        return CURRENT_ACTOR_EXECUTION.get() != null;
    }

    public static boolean inRootExecution() {
        return CURRENT_ROOT_RUNTIME.get() != null;
    }

    public static ActorRuntime currentRootRuntime() {
        return CURRENT_ROOT_RUNTIME.get();
    }

    public static IsolatePolicy currentActorPolicy() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.policy();
    }

    public static ActorRuntime currentActorRuntime() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.runtime();
    }

    public static ActorKind currentActorKind() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.kind();
    }

    /**
     * Control-plane aborts must bypass guest catch/recover semantics.
     * They are scheduler/sandbox decisions, not application exceptions.
     */
    public static final class ActorTurnExceededException extends RuntimeException {
        private final ActorId actorId;
        private final ActorKind actorKind;

        private ActorTurnExceededException(
                ActorId actorId,
                ActorKind actorKind,
                String message) {
            super(message);
            this.actorId = actorId;
            this.actorKind = actorKind;
        }

        public ActorId actorId() { return actorId; }
        public ActorKind actorKind() { return actorKind; }
    }

    public static boolean isActorControlAbort(Throwable failure) {
        if (failure instanceof ActorBudgetExceededException
                || failure instanceof ActorLifetimeExceededException
                || failure instanceof ActorTurnExceededException) {
            return true;
        }
        return failure instanceof CancellationException
                && currentActorKind() == ActorKind.UNTRUSTED;
    }

    public static Object currentExecutionDomain() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? Thread.currentThread() : current.executionDomain();
    }

    /**
     * Stable actor execution domain for actor-local runtime services, or null
     * when the caller is not currently inside an actor mailbox turn.
     */
    public static Object currentActorExecutionDomain() {
        ActorExecutionContext current = CURRENT_ACTOR_EXECUTION.get();
        return current == null ? null : current.executionDomain();
    }

    public enum ActorKind {
        PRIVATE,
        SHARED,
        UNTRUSTED;

        public boolean memoryIsolated() {
            return this != SHARED;
        }

        public boolean untrusted() {
            return this == UNTRUSTED;
        }
    }

    /**
     * Actor dispatcher configuration.
     *
     * Fairness is bounded by both mailbox throughput and wall-clock batch time.
     * The wall-clock limit is intentionally a between-message quantum: arbitrary
     * host Java stacks are not asynchronously stopped. UNTRUSTED execution has
     * a stricter one-message quantum plus fuel/deadline/isolate enforcement.
     */
    public record DispatcherConfig(
            int privateParallelism,
            int sharedParallelism,
            int untrustedParallelism,
            int throughput,
            long maxBatchNanos,
            long maxMessageNanos,
            int maxCompensatingThreads,
            int maxActors) {
        public static final long DEFAULT_MAX_BATCH_NANOS = TimeUnit.MILLISECONDS.toNanos(2);
        public static final long DEFAULT_MAX_MESSAGE_NANOS = TimeUnit.MILLISECONDS.toNanos(250);

        public DispatcherConfig {
            if (privateParallelism <= 0) throw new IllegalArgumentException("privateParallelism must be > 0");
            if (sharedParallelism <= 0) throw new IllegalArgumentException("sharedParallelism must be > 0");
            if (untrustedParallelism <= 0) throw new IllegalArgumentException("untrustedParallelism must be > 0");
            if (throughput <= 0) throw new IllegalArgumentException("throughput must be > 0");
            if (maxBatchNanos <= 0) throw new IllegalArgumentException("maxBatchNanos must be > 0");
            if (maxMessageNanos <= 0) throw new IllegalArgumentException("maxMessageNanos must be > 0");
            if (maxMessageNanos < maxBatchNanos) {
                throw new IllegalArgumentException("maxMessageNanos must be >= maxBatchNanos");
            }
            if (maxCompensatingThreads < 0) {
                throw new IllegalArgumentException("maxCompensatingThreads must be >= 0");
            }
            if (maxActors <= 0) throw new IllegalArgumentException("maxActors must be > 0");
            if (maxActors > 1_000_000) {
                throw new IllegalArgumentException(
                        "maxActors cannot exceed 1,000,000; use multiple supervised runtimes instead");
            }
        }

        /**
         * Existing explicit scheduler configurations keep historical behavior:
         * message watchdog/compensation are opt-in unless the full constructor
         * or defaults() is used.
         */
        public DispatcherConfig(
                int privateParallelism,
                int sharedParallelism,
                int untrustedParallelism,
                int throughput,
                long maxBatchNanos,
                int maxActors) {
            this(
                    privateParallelism,
                    sharedParallelism,
                    untrustedParallelism,
                    throughput,
                    maxBatchNanos,
                    Long.MAX_VALUE,
                    0,
                    maxActors);
        }

        public DispatcherConfig(
                int privateParallelism,
                int sharedParallelism,
                int untrustedParallelism,
                int throughput,
                int maxActors) {
            this(
                    privateParallelism,
                    sharedParallelism,
                    untrustedParallelism,
                    throughput,
                    DEFAULT_MAX_BATCH_NANOS,
                    Long.MAX_VALUE,
                    0,
                    maxActors);
        }

        public DispatcherConfig(int privateParallelism, int sharedParallelism, int throughput, int maxActors) {
            this(
                    privateParallelism,
                    sharedParallelism,
                    privateParallelism,
                    throughput,
                    DEFAULT_MAX_BATCH_NANOS,
                    Long.MAX_VALUE,
                    0,
                    maxActors);
        }

        public DispatcherConfig(int privateParallelism, int sharedParallelism, int throughput) {
            this(privateParallelism, sharedParallelism, throughput, 16_384);
        }

        /**
         * Production defaults partition the hot CPU budget instead of giving
         * every pool availableProcessors() workers. The untrusted domain gets
         * the smallest slice; shared/root keeps at least two workers so a
         * synchronous root awaiting a shared actor cannot consume its only
         * carrier.
         */
        public static DispatcherConfig defaults() {
            return defaultsForProcessors(Runtime.getRuntime().availableProcessors());
        }

        public static DispatcherConfig defaultsForProcessors(int availableProcessors) {
            int cpus = Math.max(1, availableProcessors);
            int untrusted = Math.max(1, cpus / 8);
            int remaining = Math.max(1, cpus - untrusted);
            int shared = Math.max(2, (remaining + 1) / 2);
            int isolated = Math.max(1, cpus - untrusted - shared);
            int compensation = Math.max(1, Math.min(4, cpus / 4));
            return new DispatcherConfig(
                    isolated,
                    shared,
                    untrusted,
                    64,
                    DEFAULT_MAX_BATCH_NANOS,
                    DEFAULT_MAX_MESSAGE_NANOS,
                    compensation,
                    16_384);
        }

        public static DispatcherConfig defaultsWithMaxActors(int maxActors) {
            DispatcherConfig defaults = defaults();
            return new DispatcherConfig(
                    defaults.privateParallelism(),
                    defaults.sharedParallelism(),
                    defaults.untrustedParallelism(),
                    defaults.throughput(),
                    defaults.maxBatchNanos(),
                    defaults.maxMessageNanos(),
                    defaults.maxCompensatingThreads(),
                    maxActors);
        }

        /** Reserve most actor identities for trusted domains during hostile load. */
        public int maxUntrustedActors() {
            return Math.max(1, maxActors / 8);
        }

        public int throughputFor(ActorKind kind) {
            Objects.requireNonNull(kind, "kind");
            return kind == ActorKind.UNTRUSTED ? 1 : throughput;
        }

        public int parallelismFor(ActorKind kind) {
            return switch (kind) {
                case PRIVATE -> privateParallelism;
                case SHARED -> sharedParallelism;
                case UNTRUSTED -> untrustedParallelism;
            };
        }
    }

    /**
     * Hard sandbox limits for one untrusted actor. The five-minute lifetime is
     * an absolute ceiling; mailbox return data is deliberately much smaller
     * than direct HTTP streaming data.
     */
    public record UntrustedActorLimits(
            Duration maxLifetime,
            long fuelPerTurn,
            long maxMailboxReturnBytes,
            long maxHttpRequestBytes,
            long maxHttpResponseBytes) {
        public UntrustedActorLimits {
            Objects.requireNonNull(maxLifetime, "maxLifetime");
            if (maxLifetime.isZero() || maxLifetime.isNegative()) {
                throw new IllegalArgumentException("untrusted actor maxLifetime must be positive");
            }
            if (maxLifetime.compareTo(HARD_MAX_UNTRUSTED_LIFETIME) > 0) {
                throw new IllegalArgumentException(
                        "untrusted actor maxLifetime cannot exceed "
                                + HARD_MAX_UNTRUSTED_LIFETIME.toSeconds() + " seconds");
            }
            if (fuelPerTurn <= 0) throw new IllegalArgumentException("untrusted actor fuelPerTurn must be positive");
            if (maxMailboxReturnBytes <= 0) throw new IllegalArgumentException("maxMailboxReturnBytes must be positive");
            if (maxHttpRequestBytes <= 0) throw new IllegalArgumentException("maxHttpRequestBytes must be positive");
            if (maxHttpResponseBytes <= 0) throw new IllegalArgumentException("maxHttpResponseBytes must be positive");
        }

        public UntrustedActorLimits(
                Duration maxLifetime,
                long fuelPerTurn,
                long maxMailboxReturnBytes,
                long maxHttpResponseBytes) {
            this(
                    maxLifetime,
                    fuelPerTurn,
                    maxMailboxReturnBytes,
                    DEFAULT_HTTP_REQUEST_BYTES,
                    maxHttpResponseBytes);
        }

        public static UntrustedActorLimits defaults() {
            return new UntrustedActorLimits(
                    HARD_MAX_UNTRUSTED_LIFETIME,
                    DEFAULT_UNTRUSTED_FUEL_PER_TURN,
                    DEFAULT_UNTRUSTED_MAILBOX_RETURN_BYTES,
                    DEFAULT_HTTP_REQUEST_BYTES,
                    DEFAULT_HTTP_RESPONSE_BYTES);
        }
    }

    /** Hard byte ceilings for a direct HTTP transport handed to an isolated actor. */
    public record HttpTransportLimits(
            long maxRequestBytes,
            long maxResponseBytes) {
        public HttpTransportLimits {
            if (maxRequestBytes <= 0) {
                throw new IllegalArgumentException("maxRequestBytes must be positive");
            }
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException("maxResponseBytes must be positive");
            }
        }

        public static HttpTransportLimits defaults() {
            return new HttpTransportLimits(
                    DEFAULT_HTTP_REQUEST_BYTES,
                    DEFAULT_HTTP_RESPONSE_BYTES);
        }
    }

    public static final class ActorBudgetExceededException extends RuntimeException {
        public ActorBudgetExceededException(String message) { super(message); }
    }

    public static final class ActorLifetimeExceededException extends RuntimeException {
        public ActorLifetimeExceededException(String message) { super(message); }
    }

    public static final class HttpRequestLimitExceededException extends RuntimeException {
        public HttpRequestLimitExceededException(String message) { super(message); }
    }

    public static final class HttpResponseLimitExceededException extends RuntimeException {
        public HttpResponseLimitExceededException(String message) { super(message); }
    }

    /** Host adapter for one already-admitted HTTP request, not general NETWORK authority. */
    public interface HttpRequestTransport {
        String method();
        String path();
        default Optional<String> header(String name) { return Optional.empty(); }
        int read(ByteBuffer target) throws IOException;
        default void cancel(Throwable cause) { }
    }

    /**
     * Host adapter for one HTTP response. Implementations must be non-blocking
     * or scheduler-aware; isolated guest code never receives a raw socket/fd.
     */
    public interface HttpResponseTransport {
        default void status(int statusCode) throws IOException { }
        default void header(String name, String value) throws IOException { }
        int write(ByteBuffer source) throws IOException;
        default void flush() throws IOException { }
        default void complete() throws IOException { }
        default void abort(Throwable cause) { }
    }

    /** Lightweight scheduler telemetry suitable for ores-otel/JMX export. */
    public record DispatcherStats(
            int parallelism,
            int activeThreads,
            int queuedActorTurns,
            long completedActorTurns,
            int compensatingThreads,
            long overrunTurns,
            long rejectedTurns,
            int largestPoolSize) { }

    public record ActorId(UUID value) {
        public ActorId { Objects.requireNonNull(value); }
        public static ActorId create() { return new ActorId(UUID.randomUUID()); }
    }

    public static final class ActorTerminatedException extends IllegalStateException {
        private final ActorId actorId;
        private final ActorKind actorKind;

        private ActorTerminatedException(ActorId actorId, ActorKind actorKind, Throwable cause) {
            super("actor " + actorId + " (" + actorKind + ") is terminated", cause);
            this.actorId = actorId;
            this.actorKind = actorKind;
        }

        public ActorId actorId() { return actorId; }
        public ActorKind actorKind() { return actorKind; }
    }

    /**
     * Owner-bound, non-Sendable HTTP request capability for a memory-isolated actor. It reads from the
     * host's already-admitted HTTP request stream without copying request-body
     * chunks through the actor mailbox.
     */
    public final class HttpRequestCapability {
        private final ActorId owner;
        private final HttpRequestTransport transport;
        private final HttpTransportLease transportLease;
        private final long maxBytes;
        private final AtomicLong readBytes = new AtomicLong();
        private final AtomicLong metadataBytes = new AtomicLong();
        private final AtomicInteger headerLookups = new AtomicInteger();
        private final AtomicReference<String> cachedMethod = new AtomicReference<>();
        private final AtomicReference<String> cachedPath = new AtomicReference<>();
        private final AtomicBoolean eof = new AtomicBoolean();

        private HttpRequestCapability(
                ActorId owner,
                HttpRequestTransport transport,
                HttpTransportLease transportLease,
                long maxBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.transport = Objects.requireNonNull(transport);
            this.transportLease = Objects.requireNonNull(transportLease);
            this.maxBytes = maxBytes;
        }

        public long maxBytes() { return maxBytes; }
        public long readBytes() { return readBytes.get(); }
        public long remainingBytes() { return Math.max(0L, maxBytes - readBytes.get()); }
        public long metadataBytes() { return metadataBytes.get(); }
        public int headerLookups() { return headerLookups.get(); }
        public boolean eof() { return eof.get(); }

        private ActorCell<?> requireOwner(String operation) {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || !cell.kind.memoryIsolated() || !cell.ref.id().equals(owner)) {
                throw new SecurityException(
                        "HTTP request capability may only be used by its owning isolated actor: " + operation);
            }
            if (cell.kind == ActorKind.UNTRUSTED) cell.checkUntrustedBudget(1);
            transportLease.assertActorOwner(owner, operation);
            return cell;
        }

        public String method() {
            requireOwner("method");
            String cached = cachedMethod.get();
            if (cached != null) return cached;

            String method = Objects.requireNonNull(
                    transportLease.withActorOperation(owner, "request.method", transport::method),
                    "HTTP request method");
            if (!method.matches("[!#$%&'*+.^_|~0-9A-Za-z-]{1,32}")) {
                throw new HttpRequestLimitExceededException(
                        "untrusted actor HTTP request method is invalid or too large");
            }
            chargeRequestMetadata(method.getBytes(StandardCharsets.UTF_8).length);
            cachedMethod.compareAndSet(null, method);
            return cachedMethod.get();
        }

        public String path() {
            requireOwner("path");
            String cached = cachedPath.get();
            if (cached != null) return cached;

            String path = Objects.requireNonNull(
                    transportLease.withActorOperation(owner, "request.path", transport::path),
                    "HTTP request path");
            if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("HTTP request path cannot contain CR/LF");
            }
            long bytes = path.getBytes(StandardCharsets.UTF_8).length;
            if (bytes > MAX_HTTP_REQUEST_PATH_BYTES) {
                throw new HttpRequestLimitExceededException(
                        "untrusted actor HTTP request path exceeds "
                                + MAX_HTTP_REQUEST_PATH_BYTES + " bytes");
            }
            chargeRequestMetadata(bytes);
            cachedPath.compareAndSet(null, path);
            return cachedPath.get();
        }

        public Optional<String> header(String name) {
            requireOwner("header");
            Objects.requireNonNull(name, "header name");
            if (!name.matches("[!#$%&'*+.^_|~0-9A-Za-z-]{1,256}")) {
                throw new IllegalArgumentException("invalid or oversized HTTP request header name");
            }
            int lookups = headerLookups.incrementAndGet();
            if (lookups > MAX_HTTP_REQUEST_HEADER_LOOKUPS) {
                headerLookups.decrementAndGet();
                throw new HttpRequestLimitExceededException(
                        "untrusted actor HTTP request header lookup limit exceeded: "
                                + MAX_HTTP_REQUEST_HEADER_LOOKUPS);
            }

            Optional<String> result = Objects.requireNonNull(
                    transportLease.withActorOperation(
                            owner,
                            "request.header",
                            () -> transport.header(name)),
                    "HTTP request header result");
            if (result.isEmpty()) {
                chargeRequestMetadata(name.length());
                return result;
            }

            String value = Objects.requireNonNull(result.orElseThrow(), "HTTP request header value");
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("HTTP request header value cannot contain CR/LF");
            }
            long valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (valueBytes > MAX_HTTP_REQUEST_HEADER_VALUE_BYTES) {
                throw new HttpRequestLimitExceededException(
                        "untrusted actor HTTP request header value exceeds "
                                + MAX_HTTP_REQUEST_HEADER_VALUE_BYTES + " bytes");
            }
            chargeRequestMetadata((long) name.length() + valueBytes);
            return Optional.of(value);
        }

        private void chargeRequestMetadata(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("HTTP request metadata bytes cannot be negative");
            long next = metadataBytes.addAndGet(bytes);
            if (next > MAX_HTTP_REQUEST_METADATA_BYTES) {
                metadataBytes.addAndGet(-bytes);
                throw new HttpRequestLimitExceededException(
                        "untrusted actor HTTP request metadata limit exceeded: "
                                + MAX_HTTP_REQUEST_METADATA_BYTES + " bytes");
            }
        }

        /**
         * Reads directly from the host request-body stream. A larger caller
         * buffer is temporarily windowed to the remaining sandbox quota.
         */
        public int read(ByteBuffer target) throws IOException {
            requireOwner("read");
            Objects.requireNonNull(target, "target");
            if (eof.get()) return -1;
            if (!target.hasRemaining()) return 0;

            long remaining = remainingBytes();
            if (remaining == 0L) {
                throw new HttpRequestLimitExceededException(
                        "untrusted actor HTTP request limit exceeded: max=" + maxBytes);
            }

            int originalLimit = target.limit();
            int permitted = (int) Math.min((long) target.remaining(), remaining);
            int beforePosition = target.position();
            target.limit(beforePosition + permitted);
            final int read;
            try {
                read = transportLease.withActorIo(
                        owner,
                        "request.read",
                        () -> transport.read(target));
            } finally {
                target.limit(originalLimit);
            }

            if (read == -1) {
                if (target.position() != beforePosition) {
                    throw new IllegalStateException(
                            "HTTP request transport advanced buffer position while reporting EOF");
                }
                eof.set(true);
                return -1;
            }
            if (read < 0 || read > permitted || target.position() != beforePosition + read) {
                throw new IllegalStateException(
                        "HTTP request transport violated ByteBuffer read contract");
            }
            readBytes.addAndGet(read);
            return read;
        }

        private void cancelFromRuntime(Throwable cause) {
            if (eof.compareAndSet(false, true)) {
                try {
                    transport.cancel(cause);
                } catch (RuntimeException ignored) {
                    // Actor teardown must not be retained by a bad host adapter.
                }
            }
        }
    }

    /**
     * Owner-bound, non-Sendable HTTP response capability for a memory-isolated actor. This deliberately
     * bypasses actor mailboxes for response body bytes while preserving a hard
     * byte limit and actor ownership check on every operation.
     */
    public final class HttpResponseCapability {
        private final ActorId owner;
        private final HttpResponseTransport transport;
        private final HttpTransportLease transportLease;
        private final long maxBytes;
        private final AtomicLong writtenBytes = new AtomicLong();
        private final AtomicLong headerBytes = new AtomicLong();
        private final AtomicInteger headerCount = new AtomicInteger();
        private final AtomicBoolean statusSet = new AtomicBoolean();
        private final AtomicBoolean responseStarted = new AtomicBoolean();
        private final AtomicBoolean completed = new AtomicBoolean();

        private HttpResponseCapability(
                ActorId owner,
                HttpResponseTransport transport,
                HttpTransportLease transportLease,
                long maxBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.transport = Objects.requireNonNull(transport);
            this.transportLease = Objects.requireNonNull(transportLease);
            this.maxBytes = maxBytes;
        }

        public long maxBytes() { return maxBytes; }
        public long writtenBytes() { return writtenBytes.get(); }
        public long remainingBytes() { return Math.max(0L, maxBytes - writtenBytes.get()); }
        public long headerBytes() { return headerBytes.get(); }
        public int headerCount() { return headerCount.get(); }
        public boolean completed() { return completed.get(); }
        public boolean responseStarted() { return responseStarted.get(); }

        private ActorCell<?> requireOwner(String operation) {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || !cell.kind.memoryIsolated() || !cell.ref.id().equals(owner)) {
                throw new SecurityException(
                        "HTTP response capability may only be used by its owning isolated actor: " + operation);
            }
            if (cell.kind == ActorKind.UNTRUSTED) cell.checkUntrustedBudget(1);
            transportLease.assertActorOwner(owner, operation);
            if (completed.get()) {
                throw new IllegalStateException("HTTP response is already completed");
            }
            return cell;
        }

        public void status(int statusCode) throws IOException {
            requireOwner("status");
            if (responseStarted.get()) {
                throw new IllegalStateException("HTTP status cannot change after response streaming starts");
            }
            if (statusCode < 100 || statusCode > 599) {
                throw new IllegalArgumentException("invalid HTTP status code " + statusCode);
            }
            if (!statusSet.compareAndSet(false, true)) {
                throw new IllegalStateException("HTTP response status may be set only once");
            }
            transportLease.withActorIo(owner, "response.status", () -> {
                transport.status(statusCode);
                return null;
            });
        }

        public void header(String name, String value) throws IOException {
            requireOwner("header");
            if (responseStarted.get()) {
                throw new IllegalStateException("HTTP headers cannot change after response streaming starts");
            }
            Objects.requireNonNull(name, "header name");
            Objects.requireNonNull(value, "header value");
            if (name.length() > 256) {
                throw new HttpResponseLimitExceededException("HTTP response header name exceeds 256 characters");
            }
            if (value.length() > MAX_HTTP_RESPONSE_HEADER_BYTES) {
                throw new HttpResponseLimitExceededException(
                        "HTTP response header value exceeds metadata ceiling");
            }
            if (!name.matches("[!#$%&'*+.^_|~0-9A-Za-z-]+")) {
                throw new IllegalArgumentException("invalid HTTP header name");
            }
            if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("HTTP header value cannot contain CR/LF");
            }
            int nextCount = headerCount.incrementAndGet();
            if (nextCount > MAX_HTTP_RESPONSE_HEADERS) {
                headerCount.decrementAndGet();
                throw new HttpResponseLimitExceededException(
                        "untrusted actor HTTP response header-count limit exceeded: "
                                + MAX_HTTP_RESPONSE_HEADERS);
            }
            long bytes = (long) name.length()
                    + value.getBytes(StandardCharsets.UTF_8).length;
            long nextBytes = headerBytes.addAndGet(bytes);
            if (nextBytes > MAX_HTTP_RESPONSE_HEADER_BYTES) {
                headerBytes.addAndGet(-bytes);
                headerCount.decrementAndGet();
                throw new HttpResponseLimitExceededException(
                        "untrusted actor HTTP response header-byte limit exceeded: "
                                + MAX_HTTP_RESPONSE_HEADER_BYTES);
            }
            transportLease.withActorIo(owner, "response.header", () -> {
                transport.header(name, value);
                return null;
            });
        }

        /**
         * Writes directly through the host's response transport. No actor
         * mailbox copy is performed. The transport may be backed directly by a
         * SocketChannel/HTTP stream. Partial non-blocking writes are allowed.
         */
        public int write(ByteBuffer source) throws IOException {
            requireOwner("write");
            Objects.requireNonNull(source, "source");
            int requested = source.remaining();
            if (requested == 0) return 0;
            long remaining = remainingBytes();
            if (requested > remaining) {
                throw new HttpResponseLimitExceededException(
                        "untrusted actor HTTP response limit exceeded: requested="
                                + requested + " remaining=" + remaining + " max=" + maxBytes);
            }
            responseStarted.set(true);
            int before = source.remaining();
            int written = transportLease.withActorIo(
                    owner,
                    "response.write",
                    () -> transport.write(source));
            if (written < 0 || written > before || source.remaining() != before - written) {
                throw new IllegalStateException(
                        "HTTP response transport violated ByteBuffer write contract");
            }
            writtenBytes.addAndGet(written);
            return written;
        }

        public void flush() throws IOException {
            requireOwner("flush");
            responseStarted.set(true);
            transportLease.withActorIo(owner, "response.flush", () -> {
                transport.flush();
                return null;
            });
        }

        public void complete() throws IOException {
            requireOwner("complete");
            responseStarted.set(true);
            if (!completed.compareAndSet(false, true)) return;
            try {
                transportLease.withActorIo(owner, "response.complete", () -> {
                    transport.complete();
                    return null;
                });
            } catch (IOException | RuntimeException | Error failure) {
                try {
                    transport.abort(failure);
                } catch (RuntimeException ignored) {
                    // The original completion failure remains authoritative.
                }
                throw failure;
            }
        }

        private void abortFromRuntime(Throwable cause) {
            if (completed.compareAndSet(false, true)) {
                try {
                    transport.abort(cause);
                } catch (RuntimeException ignored) {
                    // Teardown must not retain actor memory because a host
                    // response adapter misbehaved during abort.
                }
            }
        }
    }

    /**
     * Deeply immutable runtime-owned shared value. The backing graph is frozen
     * once, quota-accounted once, and retained until runtime teardown.
     */
    public final class Shared<T> {
        private final AtomicBoolean sharedClosed = new AtomicBoolean();
        private T value;
        private long reservedBytes;

        private Shared(T value, long reservedBytes) {
            this.value = value;
            this.reservedBytes = reservedBytes;
        }

        public T value() {
            rejectPrivateActorSharedMemoryAccess("Shared.value");
            if (sharedClosed.get()) throw new IllegalStateException("Shared value belongs to a closed actor runtime");
            return value;
        }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        private void closeFromRuntime() {
            if (!sharedClosed.compareAndSet(false, true)) return;
            long bytes = reservedBytes;
            reservedBytes = 0L;
            value = null;
            releaseSharedRuntimeBytes(bytes);
            sharedValues.remove(this);
        }
    }

    /**
     * Physical carrier/control executors.
     *
     * OresContext instances in one OS process share PROCESS_DISPATCHERS so hot
     * reload generations do not multiply the three actor carrier pools. Direct
     * ActorRuntime construction intentionally owns a dedicated group for tests
     * and explicit host isolation.
     */
    private static final class DispatcherGroup {
        private final DispatcherConfig config;
        private final NativeCarrierExecutor privateDispatcher;
        private final NativeCarrierExecutor sharedDispatcher;
        private final NativeCarrierExecutor untrustedDispatcher;
        private final ScheduledThreadPoolExecutor untrustedWatchdog;
        private final ScheduledThreadPoolExecutor messageWatchdog;
        private final Semaphore rootSlots;
        private final AtomicInteger privateCompensatingThreads = new AtomicInteger();
        private final AtomicInteger sharedCompensatingThreads = new AtomicInteger();
        private final AtomicInteger untrustedCompensatingThreads = new AtomicInteger();
        private final AtomicLong privateOverrunTurns = new AtomicLong();
        private final AtomicLong sharedOverrunTurns = new AtomicLong();
        private final AtomicLong untrustedOverrunTurns = new AtomicLong();
        private final AtomicLong privateRejectedTurns = new AtomicLong();
        private final AtomicLong sharedRejectedTurns = new AtomicLong();
        private final AtomicLong untrustedRejectedTurns = new AtomicLong();
        private int actorCount;
        private int privateActorCount;
        private int sharedActorCount;
        private int untrustedActorCount;
        private final long maxActorMemoryBytes;
        private long actorMemoryBytes;

        private DispatcherGroup(DispatcherConfig config, String prefix) {
            this.config = Objects.requireNonNull(config);
            this.maxActorMemoryBytes = configuredProcessActorMemoryLimit();
            int rootPermits = Math.max(1, config.sharedParallelism() - 1);
            int readyQueueCapacity;
            try {
                readyQueueCapacity = Math.addExact(config.maxActors(), rootPermits);
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("dispatcher ready-queue capacity overflow", overflow);
            }
            this.privateDispatcher = newDispatcher(
                    config.privateParallelism(),
                    readyQueueCapacity,
                    config.maxCompensatingThreads(),
                    prefix + "private-actor-dispatcher-");
            this.sharedDispatcher = newDispatcher(
                    config.sharedParallelism(),
                    readyQueueCapacity,
                    config.maxCompensatingThreads(),
                    prefix + "shared-actor-dispatcher-");
            this.untrustedDispatcher = newDispatcher(
                    config.untrustedParallelism(),
                    readyQueueCapacity,
                    config.maxCompensatingThreads(),
                    prefix + "untrusted-actor-dispatcher-");
            this.untrustedWatchdog = newUntrustedWatchdog(
                    namedFactory(prefix + "untrusted-watchdog-"));
            this.messageWatchdog = newUntrustedWatchdog(
                    namedFactory(prefix + "actor-turn-watchdog-"));
            this.rootSlots = new Semaphore(rootPermits, true);
        }

        private synchronized void reserveActor(ActorKind kind) {
            if (actorCount >= config.maxActors()) {
                throw new IllegalStateException(
                        "process actor limit exceeded: maximum " + config.maxActors());
            }
            if (kind == ActorKind.UNTRUSTED
                    && untrustedActorCount >= config.maxUntrustedActors()) {
                throw new IllegalStateException(
                        "untrusted actor limit exceeded: maximum "
                                + config.maxUntrustedActors());
            }
            actorCount++;
            switch (kind) {
                case PRIVATE -> privateActorCount++;
                case SHARED -> sharedActorCount++;
                case UNTRUSTED -> untrustedActorCount++;
            }
        }

        private synchronized void releaseActor(ActorKind kind) {
            actorCount--;
            switch (kind) {
                case PRIVATE -> privateActorCount--;
                case SHARED -> sharedActorCount--;
                case UNTRUSTED -> untrustedActorCount--;
            }
            if (actorCount < 0
                    || privateActorCount < 0
                    || sharedActorCount < 0
                    || untrustedActorCount < 0) {
                throw new IllegalStateException(
                        "process actor carrier-group accounting underflow");
            }
        }

        private synchronized int actorCount() {
            return actorCount;
        }

        private synchronized void reserveMemory(long bytes, String purpose) {
            if (bytes < 0) throw new IllegalArgumentException("memory reservation cannot be negative");
            if (bytes == 0) return;
            long next;
            try {
                next = Math.addExact(actorMemoryBytes, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(
                        purpose + " process actor-memory accounting overflow");
            }
            if (next > maxActorMemoryBytes) {
                throw new IllegalStateException(
                        purpose + " process actor-memory limit exceeded"
                                + ": requested=" + bytes
                                + " used=" + actorMemoryBytes
                                + " limit=" + maxActorMemoryBytes);
            }
            actorMemoryBytes = next;
        }

        private synchronized void releaseMemory(long bytes) {
            if (bytes == 0) return;
            if (bytes < 0 || bytes > actorMemoryBytes) {
                throw new IllegalStateException(
                        "process actor-memory accounting underflow: release="
                                + bytes + " used=" + actorMemoryBytes);
            }
            actorMemoryBytes -= bytes;
        }

        private synchronized long actorMemoryBytes() {
            return actorMemoryBytes;
        }

        private void shutdownNow() {
            privateDispatcher.shutdownNow();
            sharedDispatcher.shutdownNow();
            untrustedDispatcher.shutdownNow();
            untrustedWatchdog.shutdownNow();
            messageWatchdog.shutdownNow();
        }
    }

    /**
     * The production/default OresContext carrier bulkhead: exactly three actor
     * carrier pools for the OS process, independent of the number of contexts
     * or hot-reload generations.
     */
    private static long configuredProcessActorMemoryLimit() {
        long maxHeap = Runtime.getRuntime().maxMemory();

        // Actor-accounted memory must not be allowed to consume the entire JVM
        // heap: scheduler queues, code, Truffle metadata, GC bookkeeping and
        // ordinary host/runtime objects need headroom to make progress during
        // pressure. The default therefore reserves 25% of the max heap.
        long fallback = maxHeap - Math.max(1L, maxHeap / 4L);
        String configured = System.getProperty("ores.actor.process-memory-bytes");
        if (configured == null || configured.isBlank()) return Math.max(1L, fallback);
        try {
            long parsed = Long.parseLong(configured);
            if (parsed <= 0) {
                throw new IllegalArgumentException(
                        "ores.actor.process-memory-bytes must be positive");
            }
            if (parsed > maxHeap) {
                throw new IllegalArgumentException(
                        "ores.actor.process-memory-bytes cannot exceed JVM max heap "
                                + maxHeap);
            }
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(
                    "invalid ores.actor.process-memory-bytes value", invalid);
        }
    }

    private static final DispatcherGroup PROCESS_DISPATCHERS =
            new DispatcherGroup(DispatcherConfig.defaults(), "ores-process-");

    private final Map<ActorId, ActorCell<?>> actors = new ConcurrentHashMap<>();
    private final AtomicInteger actorCount = new AtomicInteger();
    private final AtomicInteger privateActorCount = new AtomicInteger();
    private final AtomicInteger sharedActorCount = new AtomicInteger();
    private final AtomicInteger untrustedActorCount = new AtomicInteger();
    private final AtomicInteger activeRootTasks = new AtomicInteger();
    private final Set<RootTask<?>> rootTasks = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicLong privateMemoryBytes = new AtomicLong();
    private final AtomicLong sharedMemoryBytes = new AtomicLong();
    private final Object memoryBudgetLock = new Object();
    private final Object runtimeLifecycleLock = new Object();
    private final Set<SyncCell<?>> syncCells = ConcurrentHashMap.newKeySet();
    private final Set<Shared<?>> sharedValues = ConcurrentHashMap.newKeySet();
    private final IsolatePolicy policyCeiling;
    private final DispatcherConfig dispatcherConfig;
    private final TurnExecutor turnExecutor;
    private final DispatcherGroup dispatcherGroup;
    private final boolean ownsDispatcherGroup;
    private volatile Consumer<Object> actorExitHook = ignored -> { };
    private final NativeCarrierExecutor privateDispatcher;
    private final NativeCarrierExecutor sharedDispatcher;
    private final NativeCarrierExecutor untrustedDispatcher;
    private final ScheduledThreadPoolExecutor untrustedWatchdog;
    private final ScheduledThreadPoolExecutor messageWatchdog;
    private final AtomicInteger privateCompensatingThreads;
    private final AtomicInteger sharedCompensatingThreads;
    private final AtomicInteger untrustedCompensatingThreads;
    private final AtomicLong privateOverrunTurns;
    private final AtomicLong sharedOverrunTurns;
    private final AtomicLong untrustedOverrunTurns;
    private final AtomicLong privateRejectedTurns;
    private final AtomicLong sharedRejectedTurns;
    private final AtomicLong untrustedRejectedTurns;
    private final ThreadLocal<ActorCell<?>> currentActor = new ThreadLocal<>();
    private final ThreadLocal<SyncCell<?>> currentSyncCell = new ThreadLocal<>();

    public ActorRuntime() {
        this(IsolatePolicy.developer(), DispatcherConfig.defaults(), TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling) {
        this(policyCeiling, DispatcherConfig.defaults(), TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, int maxActors) {
        this(
                policyCeiling,
                DispatcherConfig.defaultsWithMaxActors(maxActors),
                TurnExecutor.direct());
    }

    public ActorRuntime(IsolatePolicy policyCeiling, DispatcherConfig dispatcherConfig) {
        this(policyCeiling, dispatcherConfig, TurnExecutor.direct());
    }

    public ActorRuntime(
            IsolatePolicy policyCeiling,
            DispatcherConfig dispatcherConfig,
            TurnExecutor turnExecutor) {
        this(
                policyCeiling,
                dispatcherConfig,
                turnExecutor,
                new DispatcherGroup(dispatcherConfig, "ores-"),
                true);
    }

    /**
     * Production OresContext factory. Multiple contexts/hot-reload generations
     * share the one process carrier bulkhead while retaining independent actor
     * registries, policies, memory accounting, and Truffle entry callbacks.
     */
    public static ActorRuntime processShared(
            IsolatePolicy policyCeiling,
            TurnExecutor turnExecutor) {
        return new ActorRuntime(
                policyCeiling,
                PROCESS_DISPATCHERS.config,
                turnExecutor,
                PROCESS_DISPATCHERS,
                false);
    }

    /**
     * Host/launcher entry point for main-process work. The complete Graal
     * context lifecycle must be submitted from outside Context.eval/RootNode;
     * hopping threads after a context is already entered violates Graal
     * thread-affinity rules.
     */
    public static <T> T executeProcessRoot(
            IsolatePolicy policyCeiling,
            Supplier<T> task) {
        Objects.requireNonNull(policyCeiling, "policyCeiling");
        Objects.requireNonNull(task, "task");
        ActorRuntime hostRuntime = processShared(policyCeiling, TurnExecutor.direct());
        try {
            return hostRuntime.executeRootTask(task);
        } finally {
            hostRuntime.close();
        }
    }

    private ActorRuntime(
            IsolatePolicy policyCeiling,
            DispatcherConfig dispatcherConfig,
            TurnExecutor turnExecutor,
            DispatcherGroup dispatcherGroup,
            boolean ownsDispatcherGroup) {
        this.policyCeiling = Objects.requireNonNull(policyCeiling);
        this.dispatcherConfig = Objects.requireNonNull(dispatcherConfig);
        this.turnExecutor = Objects.requireNonNull(turnExecutor);
        this.dispatcherGroup = Objects.requireNonNull(dispatcherGroup);
        this.ownsDispatcherGroup = ownsDispatcherGroup;
        if (dispatcherGroup.config != dispatcherConfig
                && !dispatcherGroup.config.equals(dispatcherConfig)) {
            throw new IllegalArgumentException(
                    "ActorRuntime dispatcher config does not match its carrier group");
        }

        this.privateDispatcher = dispatcherGroup.privateDispatcher;
        this.sharedDispatcher = dispatcherGroup.sharedDispatcher;
        this.untrustedDispatcher = dispatcherGroup.untrustedDispatcher;
        this.untrustedWatchdog = dispatcherGroup.untrustedWatchdog;
        this.messageWatchdog = dispatcherGroup.messageWatchdog;
        this.privateCompensatingThreads = dispatcherGroup.privateCompensatingThreads;
        this.sharedCompensatingThreads = dispatcherGroup.sharedCompensatingThreads;
        this.untrustedCompensatingThreads = dispatcherGroup.untrustedCompensatingThreads;
        this.privateOverrunTurns = dispatcherGroup.privateOverrunTurns;
        this.sharedOverrunTurns = dispatcherGroup.sharedOverrunTurns;
        this.untrustedOverrunTurns = dispatcherGroup.untrustedOverrunTurns;
        this.privateRejectedTurns = dispatcherGroup.privateRejectedTurns;
        this.sharedRejectedTurns = dispatcherGroup.sharedRejectedTurns;
        this.untrustedRejectedTurns = dispatcherGroup.untrustedRejectedTurns;
    }

    public IsolatePolicy policyCeiling() { return policyCeiling; }
    public DispatcherConfig dispatcherConfig() { return dispatcherConfig; }
    public boolean usesProcessSharedDispatchers() { return !ownsDispatcherGroup; }
    public int maxActors() { return dispatcherConfig.maxActors(); }

    /**
     * Installs a host-owned hook invoked exactly once when an actor execution
     * domain is retired. Guest code cannot mutate this hook.
     */
    public void setActorExitHook(Consumer<Object> actorExitHook) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        this.actorExitHook = Objects.requireNonNull(actorExitHook, "actorExitHook");
    }
    public int actorCount() { return actorCount.get(); }
    public int processCarrierActorCount() { return dispatcherGroup.actorCount(); }

    /**
     * Snapshot one carrier pool without exposing the executor itself. Queue
     * depth + active threads are the minimum signals needed to detect sustained
     * saturation/starvation from outside the runtime.
     */
    public DispatcherStats dispatcherStats(ActorKind kind) {
        Objects.requireNonNull(kind, "kind");
        NativeCarrierExecutor executor = dispatcherFor(kind);
        return new DispatcherStats(
                dispatcherConfig.parallelismFor(kind),
                executor.getActiveCount(),
                executor.getQueueSize(),
                executor.getCompletedTaskCount(),
                compensationCounter(kind).get(),
                overrunCounter(kind).get(),
                rejectionCounter(kind).get(),
                executor.getLargestPoolSize());
    }

    /**
     * Run trusted root/main-process guest work on the SHARED carrier domain.
     * This is not an actor turn, so it has no mailbox identity, but it shares
     * the same CPU bulkhead as shared actors and therefore cannot consume an
     * isolate/untrusted carrier.
     */
    public <T> T executeRootTask(Supplier<T> task) {
        requireSupervisorContext("execute root/main process work");
        Objects.requireNonNull(task, "task");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");

        try {
            dispatcherGroup.rootSlots.acquire();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException(
                    "interrupted while waiting for a shared/root execution lane");
        }

        RootTask<T> rootTask;
        synchronized (runtimeLifecycleLock) {
            if (closed.get()) {
                dispatcherGroup.rootSlots.release();
                throw new IllegalStateException("actor runtime is closed");
            }
            activeRootTasks.incrementAndGet();
            rootTask = new RootTask<>(task);
            rootTasks.add(rootTask);
        }

        try {
            sharedDispatcher.execute(rootTask);
        } catch (RejectedExecutionException rejected) {
            sharedRejectedTurns.incrementAndGet();
            rootTask.cancelBeforeStart(rejected);
            throw rejected;
        }

        try {
            return rootTask.completion.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("root/main process execution interrupted");
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        }
    }

    private void releaseRootTask(RootTask<?> rootTask) {
        if (!rootTasks.remove(rootTask)) return;
        dispatcherGroup.rootSlots.release();
        synchronized (runtimeLifecycleLock) {
            int remaining = activeRootTasks.decrementAndGet();
            if (remaining < 0) {
                activeRootTasks.incrementAndGet();
                throw new IllegalStateException("root task lifecycle accounting underflow");
            }
            runtimeLifecycleLock.notifyAll();
        }
    }

    private final class RootTask<T> implements Runnable {
        private static final int QUEUED = 0;
        private static final int RUNNING = 1;
        private static final int FINISHED = 2;

        private final Supplier<T> task;
        private final CompletableFuture<T> completion = new CompletableFuture<>();
        private final AtomicInteger phase = new AtomicInteger(QUEUED);
        private volatile Thread carrier;
        private volatile ScheduledFuture<?> deadlineFuture;
        private final AtomicBoolean compensationClaimed = new AtomicBoolean();
        private volatile long deadlineNanos = Long.MAX_VALUE;

        private RootTask(Supplier<T> task) {
            this.task = task;
        }

        @Override
        public void run() {
            if (!phase.compareAndSet(QUEUED, RUNNING)) return;
            carrier = Thread.currentThread();
            ACTOR_CARRIER.set(Boolean.TRUE);
            CURRENT_ROOT_RUNTIME.set(ActorRuntime.this);
            deadlineNanos = rootDeadlineNanos();
            CURRENT_ROOT_DEADLINE_NANOS.set(deadlineNanos);
            armRootDeadline();
            if (closed.get()) carrier.interrupt();
            try {
                turnExecutor.execute(() -> {
                    try {
                        schedulerSafepoint();
                        completion.complete(task.get());
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                        if (failure instanceof VirtualMachineError fatal) throw fatal;
                        if (failure instanceof ThreadDeath fatal) throw fatal;
                        if (failure instanceof LinkageError fatal) throw fatal;
                    }
                });
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                disarmRootDeadline();
                Thread.interrupted();
                carrier = null;
                CURRENT_ROOT_DEADLINE_NANOS.remove();
                CURRENT_ROOT_RUNTIME.remove();
                ACTOR_CARRIER.remove();
                phase.set(FINISHED);
                releaseRootTask(this);
            }
        }

        private long rootDeadlineNanos() {
            long durationNanos;
            try {
                durationNanos = policyCeiling.maxWallTime().toNanos();
            } catch (ArithmeticException overflow) {
                durationNanos = Long.MAX_VALUE / 4L;
            }
            durationNanos = Math.max(1L, Math.min(durationNanos, Long.MAX_VALUE / 4L));

            // nanoTime has an arbitrary signed origin and may wrap. Deadline
            // checks use subtraction, which remains valid for intervals below
            // 2^63 ns, so deliberately allow addition to wrap naturally.
            return System.nanoTime() + durationNanos;
        }

        private void armRootDeadline() {
            if (deadlineNanos == Long.MAX_VALUE) return;
            long delay = Math.max(1L, deadlineNanos - System.nanoTime());
            deadlineFuture = messageWatchdog.schedule(
                    this::expireRootTask,
                    delay,
                    TimeUnit.NANOSECONDS);
        }

        private void expireRootTask() {
            if (phase.get() != RUNNING) return;
            Thread running = carrier;
            if (running == null) return;

            sharedOverrunTurns.incrementAndGet();
            if (compensationClaimed.compareAndSet(false, true)
                    && !claimCompensatingThread(ActorKind.SHARED)) {
                compensationClaimed.set(false);
            }
            completion.completeExceptionally(new CancellationException(
                    "root/main process exceeded max wall time "
                            + policyCeiling.maxWallTime()));
            running.interrupt();
        }

        private void disarmRootDeadline() {
            ScheduledFuture<?> deadline = deadlineFuture;
            deadlineFuture = null;
            if (deadline != null) deadline.cancel(false);
            if (compensationClaimed.compareAndSet(true, false)) {
                releaseCompensatingThread(ActorKind.SHARED);
            }
        }

        private void cancelBeforeStart(Throwable failure) {
            if (!phase.compareAndSet(QUEUED, FINISHED)) return;
            sharedDispatcher.remove(this);
            completion.completeExceptionally(failure);
            releaseRootTask(this);
        }

        private void cancelFromRuntimeClose() {
            if (phase.compareAndSet(QUEUED, FINISHED)) {
                sharedDispatcher.remove(this);
                completion.completeExceptionally(
                        new CancellationException("root/main execution cancelled by runtime close"));
                releaseRootTask(this);
                return;
            }
            if (phase.get() == RUNNING) {
                Thread running = carrier;
                if (running != null) running.interrupt();
            }
        }
    }

    public long privateMemoryBytes() { return privateMemoryBytes.get(); }
    public long sharedMemoryBytes() { return sharedMemoryBytes.get(); }
    public long actorMemoryBytes() { return privateMemoryBytes.get() + sharedMemoryBytes.get(); }
    public long processCarrierMemoryBytes() { return dispatcherGroup.actorMemoryBytes(); }

    /** Runtime observability: canceled deadlines are removed immediately. */
    public int pendingUntrustedDeadlineCount() {
        return untrustedWatchdog.getQueue().size();
    }

    /**
     * Logical actor-confined memory slice for one private actor.
     *
     * This is independent of carrier threads. Mailbox payloads and persistent
     * actor-state allocations share one budget. The current JVM backend uses
     * accounting plus alias isolation; a native/polyglot-isolate backend can map
     * this same contract to a physically separate heap/arena.
     */
    public final class ActorMemorySlice implements AutoCloseable {
        private final ActorId owner;
        private final long limitBytes;
        private final AtomicLong usedBytes = new AtomicLong();
        private final AtomicBoolean sliceClosed = new AtomicBoolean();
        private final Set<PrivateMemoryBlock> blocks = ConcurrentHashMap.newKeySet();

        private ActorMemorySlice(ActorId owner, long limitBytes) {
            this.owner = Objects.requireNonNull(owner);
            this.limitBytes = limitBytes;
        }

        public ActorId owner() { return owner; }
        public long limitBytes() { return limitBytes; }
        public long usedBytes() { return usedBytes.get(); }
        public long remainingBytes() { return Math.max(0L, limitBytes - usedBytes.get()); }
        public boolean closed() { return sliceClosed.get(); }

        /**
         * Reserve persistent private-actor heap. Compiler/interpreter lowering
         * should retain the reservation for as long as the state allocation is
         * live and close it when that allocation dies.
         */
        public MemoryReservation reserveHeap(long bytes) {
            requireCurrentOwner();
            return reserve(bytes, "private actor heap");
        }

        /**
         * Allocate actor-confined native memory from a closeable FFM arena.
         * The ByteBuffer view never escapes this wrapper; every access verifies
         * the owning ActorId. Arena.close() deterministically releases the
         * native region at block/actor teardown instead of relying on a direct
         * buffer cleaner or ordinary JVM GC timing.
         */
        public PrivateMemoryBlock allocatePrivateBytes(int bytes) {
            requireCurrentOwner();
            if (bytes < 0) throw new IllegalArgumentException("private memory block size cannot be negative");
            MemoryReservation reservation = reserve(bytes, "private actor direct heap");
            try {
                PrivateMemoryBlock block = new PrivateMemoryBlock(this, reservation, bytes);
                blocks.add(block);
                return block;
            } catch (RuntimeException | Error failure) {
                reservation.close();
                throw failure;
            }
        }

        private MemoryReservation reserveMailbox(Object isolatedMessage) {
            return reserve(estimateFrozenBytes(isolatedMessage), "private actor mailbox");
        }

        private synchronized MemoryReservation reserve(long bytes, String purpose) {
            if (bytes < 0) throw new IllegalArgumentException("memory reservation cannot be negative");
            if (sliceClosed.get()) throw new IllegalStateException("private actor memory slice is closed");
            if (bytes == 0) return new MemoryReservation(this, 0);

            long current = usedBytes.get();
            long next;
            try {
                next = Math.addExact(current, bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " accounting overflow");
            }
            if (next > limitBytes) {
                throw new IllegalStateException(purpose + " limit exceeded for " + owner
                        + ": requested=" + bytes + " used=" + current + " limit=" + limitBytes);
            }

            reservePrivateRuntimeBytes(bytes, owner, purpose);
            usedBytes.set(next);
            return new MemoryReservation(this, bytes);
        }

        private void requireCurrentOwner() {
            ActorCell<?> cell = currentActor.get();
            if (cell == null || !cell.kind.memoryIsolated() || !cell.ref.id().equals(owner)) {
                throw new IllegalStateException(
                        "private actor memory slice may only be reserved by its owning actor");
            }
        }

        private synchronized void release(long bytes) {
            if (bytes == 0 || sliceClosed.get()) return;
            long remaining = usedBytes.addAndGet(-bytes);
            if (remaining < 0) {
                usedBytes.addAndGet(bytes);
                throw new IllegalStateException("private actor memory accounting underflow for " + owner);
            }
            try {
                releasePrivateRuntimeBytes(bytes, owner);
            } catch (RuntimeException failure) {
                usedBytes.addAndGet(bytes);
                throw failure;
            }
        }

        @Override
        public synchronized void close() {
            if (!sliceClosed.compareAndSet(false, true)) return;
            for (PrivateMemoryBlock block : List.copyOf(blocks)) block.invalidateFromSlice();
            blocks.clear();
            long bytes = usedBytes.getAndSet(0);
            if (bytes != 0) releasePrivateRuntimeBytes(bytes, owner);
        }

        private void unregister(PrivateMemoryBlock block) {
            blocks.remove(block);
        }
    }

    /**
     * Owner-checked direct memory owned by exactly one private actor.
     *
     * No mutable buffer reference escapes this wrapper. Closing the block or
     * terminating the actor overwrites the entire region before invalidation.
     */
    public final class PrivateMemoryBlock implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final MemoryReservation reservation;
        private final int capacity;
        private volatile Arena arena;
        private volatile ByteBuffer memory;
        private final AtomicBoolean blockClosed = new AtomicBoolean();

        private PrivateMemoryBlock(
                ActorMemorySlice slice,
                MemoryReservation reservation,
                int bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.reservation = Objects.requireNonNull(reservation);
            this.capacity = bytes;
            Arena allocatedArena = Arena.ofShared();
            try {
                this.memory = allocatedArena.allocate(bytes)
                        .asByteBuffer()
                        .order(ByteOrder.LITTLE_ENDIAN);
                this.arena = allocatedArena;
            } catch (RuntimeException | Error failure) {
                allocatedArena.close();
                throw failure;
            }
        }

        public int capacity() { return capacity; }
        public ActorId owner() { return slice.owner(); }
        public boolean closed() { return blockClosed.get(); }

        public byte readByte(int index) {
            return openMemory().get(index);
        }

        public void writeByte(int index, byte value) {
            openMemory().put(index, value);
        }

        public int readInt(int index) {
            return openMemory().getInt(index);
        }

        public void writeInt(int index, int value) {
            openMemory().putInt(index, value);
        }

        public long readLong(int index) {
            return openMemory().getLong(index);
        }

        public void writeLong(int index, long value) {
            openMemory().putLong(index, value);
        }

        public double readDouble(int index) {
            return openMemory().getDouble(index);
        }

        public void writeDouble(int index, double value) {
            openMemory().putDouble(index, value);
        }

        public byte[] copyOut() {
            ByteBuffer live = openMemory();
            byte[] out = new byte[capacity];
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            duplicate.get(out);
            return out;
        }

        public void copyIn(byte[] bytes) {
            Objects.requireNonNull(bytes);
            ByteBuffer live = openMemory();
            if (bytes.length != capacity) {
                throw new IllegalArgumentException(
                        "private memory copy size mismatch: expected " + capacity
                                + " bytes but got " + bytes.length);
            }
            ByteBuffer duplicate = live.duplicate();
            duplicate.clear();
            duplicate.put(bytes);
        }

        /**
         * Streams request bytes directly into this actor-owned native region.
         * The temporary ByteBuffer is a view over the FFM segment; no mailbox
         * or heap byte-array staging is required.
         */
        public int readFrom(
                HttpRequestCapability request,
                int offset,
                int length) throws IOException {
            Objects.requireNonNull(request, "request");
            return request.read(window(offset, length));
        }

        /**
         * Streams bytes directly from this actor-owned native region to the
         * response transport. A SocketChannel-backed transport can therefore
         * write from native actor memory toward the kernel without a mailbox
         * or intermediate heap-array copy.
         */
        public int writeTo(
                HttpResponseCapability response,
                int offset,
                int length) throws IOException {
            Objects.requireNonNull(response, "response");
            return response.write(window(offset, length));
        }

        private ByteBuffer window(int offset, int length) {
            if (offset < 0 || length < 0 || offset > capacity - length) {
                throw new IndexOutOfBoundsException(
                        "private memory window out of bounds: offset=" + offset
                                + " length=" + length + " capacity=" + capacity);
            }
            ByteBuffer duplicate = openMemory().duplicate();
            duplicate.position(offset);
            duplicate.limit(offset + length);
            return duplicate.slice().order(ByteOrder.LITTLE_ENDIAN);
        }

        private ByteBuffer openMemory() {
            if (blockClosed.get() || slice.closed()) {
                throw new IllegalStateException("private actor memory block is closed");
            }
            slice.requireCurrentOwner();
            ByteBuffer live = memory;
            if (live == null) throw new IllegalStateException("private actor memory block is closed");
            return live;
        }

        private void zeroAndDetachMemory() {
            ByteBuffer live = memory;
            Arena liveArena = arena;
            memory = null;
            arena = null;
            if (live != null) {
                ByteBuffer duplicate = live.duplicate();
                duplicate.clear();
                while (duplicate.hasRemaining()) duplicate.put((byte) 0);
            }
            if (liveArena != null) {
                liveArena.close();
            }
        }

        private void invalidateFromSlice() {
            if (!blockClosed.compareAndSet(false, true)) return;
            zeroAndDetachMemory();
        }

        @Override
        public void close() {
            openMemory(); // owner + liveness check before invalidation
            if (!blockClosed.compareAndSet(false, true)) return;
            zeroAndDetachMemory();
            slice.unregister(this);
            reservation.close();
        }
    }

    public final class MemoryReservation implements AutoCloseable {
        private final ActorMemorySlice slice;
        private final long bytes;
        private final AtomicBoolean released = new AtomicBoolean();

        private MemoryReservation(ActorMemorySlice slice, long bytes) {
            this.slice = Objects.requireNonNull(slice);
            this.bytes = bytes;
        }

        public ActorId owner() { return slice.owner(); }
        public long bytes() { return bytes; }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) slice.release(bytes);
        }
    }

    private record MessageEnvelope(Object value, Runnable release) implements AutoCloseable {
        @Override
        public void close() {
            if (release != null) release.run();
        }
    }

    /**
     * Explicit synchronized shared-memory cell.
     *
     * Actor fields do not use this: a mailbox turn already provides exclusive
     * mutation of actor-owned state. SyncCell is for state intentionally shared
     * by multiple SHARED actors.
     */
    public final class SyncCell<T> implements AutoCloseable {
        private final ReentrantLock lock = new ReentrantLock(true);
        private final AtomicBoolean cellClosed = new AtomicBoolean();
        private T value;
        private long reservedBytes;

        @SuppressWarnings("unchecked")
        private SyncCell(T initialValue) {
            Object frozen = freeze(initialValue);
            rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
            long bytes = estimateFrozenBytes(frozen);
            reserveSharedRuntimeBytes(bytes, "shared SyncCell");
            this.value = (T) frozen;
            this.reservedBytes = bytes;
        }

        public boolean closed() { return cellClosed.get(); }

        private boolean ownedBy(ActorRuntime runtime) {
            return ActorRuntime.this == runtime;
        }

        public T snapshot() {
            rejectPrivateActorSharedMemoryAccess("SyncCell.snapshot");
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                return value;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        public <R> R read(Function<? super T, ? extends R> reader) {
            Objects.requireNonNull(reader);
            requireSharedActorTurn();
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                Object frozen = freeze(reader.apply(value));
                rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
                @SuppressWarnings("unchecked")
                R result = (R) frozen;
                return result;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        @SuppressWarnings("unchecked")
        public T update(UnaryOperator<T> updater) {
            Objects.requireNonNull(updater);
            requireSharedActorTurn();
            boolean entered = enterSyncCell(this);
            lock.lock();
            try {
                requireOpen();
                Object frozen = freeze(updater.apply(value));
                rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
                requireOpen();
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                long nextBytes = estimateFrozenBytes(frozen);
                long delta = nextBytes - reservedBytes;
                if (delta > 0) reserveSharedRuntimeBytes(delta, "shared SyncCell update");
                value = (T) frozen;
                if (delta < 0) releaseSharedRuntimeBytes(-delta);
                reservedBytes = nextBytes;
                return value;
            } finally {
                lock.unlock();
                exitSyncCell(entered);
            }
        }

        private void requireOpen() {
            if (cellClosed.get()) throw new IllegalStateException("SyncCell is closed");
        }

        @Override
        public void close() {
            rejectPrivateActorSharedMemoryAccess("SyncCell.close");
            boolean entered = enterSyncCell(this);
            try {
                closeFromRuntime();
            } finally {
                exitSyncCell(entered);
            }
        }

        private void closeFromRuntime() {
            lock.lock();
            try {
                if (!cellClosed.compareAndSet(false, true)) return;
                long bytes = reservedBytes;
                reservedBytes = 0L;
                value = null;
                releaseSharedRuntimeBytes(bytes);
                syncCells.remove(this);
            } finally {
                lock.unlock();
            }
        }

        private void invalidateFromRuntime() {
            lock.lock();
            try {
                if (!cellClosed.compareAndSet(false, true)) return;
                long bytes = reservedBytes;
                reservedBytes = 0L;
                value = null;
                if (bytes != 0) releaseSharedRuntimeBytes(bytes);
                syncCells.remove(this);
            } finally {
                lock.unlock();
            }
        }
    }

    @FunctionalInterface
    public interface Behavior<M> {
        void onMessage(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler/interpreter callback for a one-shot source-level actor callable.
     * Guest code never receives this host callback object.
     */
    @FunctionalInterface
    public interface Invocation<M, R> {
        R run(M message, ActorContext<M> context) throws Exception;
    }

    /**
     * Compiler-facing actor constructor. The actor context is available before
     * state initialization, so private actor fields can reserve/allocate in the
     * actor's confined memory slice rather than being captured from the caller.
     */
    @FunctionalInterface
    public interface BehaviorFactory<M> {
        Behavior<M> create(ActorContext<M> context) throws Exception;
    }

    public interface ActorContext<M> {
        ActorRef<M> self();
        ActorRuntime runtime();
        IsolatePolicy policy();
        ActorKind kind();
        Optional<ActorMemorySlice> privateMemory();

        /** Present only for a memory-isolated actor explicitly bound to one HTTP request. */
        Optional<HttpRequestCapability> httpRequest();

        /** Present only for a memory-isolated actor explicitly bound to one HTTP response. */
        Optional<HttpResponseCapability> httpResponse();

        /** Mandatory compiler/runtime scheduling checkpoint. */
        void checkpoint();

        /** Remaining per-message execution fuel, or Long.MAX_VALUE for trusted actors. */
        long fuelRemaining();

        /** Native carrier CPU charged to this actor across completed turns. */
        long cpuTimeNanos();

        /** Remaining hard lifetime; trusted actors report their policy wall-time. */
        Duration remainingLifetime();
    }

    public final class ActorRef<M> {
        private final ActorId id;
        private final ActorKind kind;
        private final AtomicReference<Throwable> terminationCause = new AtomicReference<>();
        private final AtomicLong cpuTimeNanos = new AtomicLong();

        private ActorRef(ActorId id, ActorKind kind) {
            this.id = id;
            this.kind = kind;
        }

        public ActorId id() { return id; }
        public ActorKind kind() { return kind; }
        private boolean ownedBy(ActorRuntime runtime) { return ActorRuntime.this == runtime; }
        public boolean isAlive() { return ActorRuntime.this.isAlive(this); }
        public long cpuTimeNanos() {
            ActorExecutionContext caller = CURRENT_ACTOR_EXECUTION.get();
            if (caller != null
                    && caller.kind() == ActorKind.UNTRUSTED
                    && !caller.actorId().equals(id)) {
                throw new SecurityException(
                        "untrusted actors cannot inspect another actor's CPU accounting");
            }
            return cpuTimeNanos.get();
        }
        public Optional<Throwable> failure() {
            ActorExecutionContext caller = CURRENT_ACTOR_EXECUTION.get();
            if (caller != null
                    && caller.kind() == ActorKind.UNTRUSTED
                    && !caller.actorId().equals(id)) {
                throw new SecurityException(
                        "untrusted actors cannot inspect another actor's raw failure object");
            }
            return Optional.ofNullable(terminationCause.get());
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            Objects.requireNonNull(unit);
            if (timeout < 0) throw new IllegalArgumentException("timeout must be non-negative");
            ActorExecutionContext caller = CURRENT_ACTOR_EXECUTION.get();
            if (caller != null && caller.kind() == ActorKind.UNTRUSTED) {
                throw new SecurityException(
                        "untrusted actors cannot synchronously await actor termination; use messages/monitoring");
            }
            ActorCell<?> cell = actors.get(id);
            if (cell == null) return true;
            cell.awaitFinalized(unit.toNanos(timeout));
            return cell.finalized();
        }

        public void send(M message) {
            ActorRuntime.this.send(this, message);
        }

        public void stop() {
            ActorRuntime.this.stop(this);
        }

        /** Narrow this reference to send-only authority. */
        public Recipient<M> recipient() {
            return new Recipient<>(this);
        }

        @Override
        public String toString() {
            return "ActorRef[" + kind + ":" + id.value() + "]";
        }
    }

    /**
     * Send-only actor capability inspired by Actix Recipient<M>.
     * It deliberately exposes no lifecycle, waiting, or failure-inspection API.
     */
    public final class Recipient<M> {
        private final ActorRef<M> target;

        private Recipient(ActorRef<M> target) {
            this.target = Objects.requireNonNull(target);
        }

        private boolean ownedBy(ActorRuntime runtime) {
            return target.ownedBy(runtime);
        }

        public void send(M message) {
            ActorRuntime.this.send(target, message);
        }

        @Override
        public String toString() {
            return "Recipient[" + target.kind + ":" + target.id.value() + "]";
        }
    }

    private void requireCallerRuntimeAffinity(String operation) {
        ActorRuntime caller = currentActorRuntime();
        if (caller != null && caller != this) {
            throw new SecurityException(
                    "actor cannot " + operation + " through another ActorRuntime");
        }
    }

    private static void requireSupervisorContext(String operation) {
        if (inActorExecution() || inRootExecution()) {
            throw new SecurityException(
                    "guest code cannot " + operation
                            + "; this operation belongs to the host/supervisor");
        }
    }

    private IsolatePolicy defaultSpawnPolicy() {
        IsolatePolicy caller = currentActorPolicy();
        return caller == null ? policyCeiling : caller;
    }

    private void requireWithinCallerPolicy(IsolatePolicy child) {
        IsolatePolicy caller = currentActorPolicy();
        if (caller == null) return;

        if (!caller.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = child.capabilities().isEmpty()
                    ? java.util.EnumSet.noneOf(IsolatePolicy.Capability.class)
                    : java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(caller.capabilities());
            throw new SecurityException("child actor policy exceeds caller actor capabilities: " + excess);
        }
        if (child.maxHeapBytes() > caller.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds caller actor policy");
        }
        if (child.maxMailboxMessages() > caller.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds caller actor policy");
        }
        if (child.maxWallTime().compareTo(caller.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds caller actor policy");
        }
        if (caller.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial caller policy");
        }
    }

    /** Backward-compatible default: an unqualified runtime actor is private. */
    public <M> ActorRef<M> spawn(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawn(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policy, behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnPrivate(policyCeiling, behaviorFactory);
    }

    /**
     * Trusted-host compatibility path. Compiler-generated actors should prefer
     * the context-aware BehaviorFactory overload.
     */
    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier private actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.PRIVATE, policy, context -> behaviorFactory.get(), true);
    }

    /**
     * Isolation-safe private actor construction. The factory itself must be
     * stateless/capture-free; mutable actor state must be created after the
     * actor context is installed and stored in actor-owned memory.
     */
    public <M> ActorRef<M> spawnPrivate(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivate(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.PRIVATE, policy, behaviorFactory);
    }

    /**
     * Explicit host-only escape hatch for tests/embedding code that needs a
     * context-aware factory with captured Java objects. Never used by Oreslang
     * compiler lowering and forbidden for adversarial policies.
     */
    public <M> ActorRef<M> spawnPrivateTrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnPrivateTrusted(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivateTrusted(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("use trusted captured private actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.PRIVATE, policy, behaviorFactory, true);
    }


    /**
     * Host/supervisor entry point for handing one admitted HTTP stream directly
     * to an ordinary memory-isolated actor without mailbox-copying the body.
     */
    public <M> ActorRef<M> spawnPrivateHttp(
            IsolatePolicy policy,
            HttpTransportLimits httpLimits,
            HttpRequestTransport requestTransport,
            HttpResponseTransport responseTransport,
            BehaviorFactory<M> behaviorFactory) {
        return spawnPrivateHttp(
                policy,
                httpLimits,
                requestTransport,
                responseTransport,
                null,
                behaviorFactory);
    }

    public <M> ActorRef<M> spawnPrivateHttp(
            IsolatePolicy policy,
            HttpTransportLimits httpLimits,
            HttpRequestTransport requestTransport,
            HttpResponseTransport responseTransport,
            HttpTransportLease transportLease,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("spawn private actor with HTTP transport ownership");
        Objects.requireNonNull(policy);
        Objects.requireNonNull(httpLimits);
        Objects.requireNonNull(behaviorFactory);
        if (requestTransport == null && responseTransport == null) {
            throw new IllegalArgumentException(
                    "spawnPrivateHttp requires a request or response transport");
        }
        if (transportLease == null) transportLease = new HttpTransportLease();
        return spawnInternal(
                ActorKind.PRIVATE,
                policy,
                behaviorFactory,
                false,
                null,
                httpLimits,
                requestTransport,
                responseTransport,
                transportLease);
    }

    public <M> ActorRef<M> spawnShared(Supplier<? extends Behavior<M>> behaviorFactory) {
        return spawnShared(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier shared actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.SHARED, policy, context -> behaviorFactory.get(), true);
    }

    public <M> ActorRef<M> spawnShared(BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnShared(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        return spawn(ActorKind.SHARED, policy, behaviorFactory);
    }

    /**
     * Explicit host-only escape hatch for context-aware shared actor factories
     * that intentionally capture host objects. Compiler lowering must never use
     * this path. Adversarial policies reject it.
     */
    public <M> ActorRef<M> spawnSharedTrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnSharedTrusted(defaultSpawnPolicy(), behaviorFactory);
    }

    public <M> ActorRef<M> spawnSharedTrusted(
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("use trusted captured shared actor construction");
        Objects.requireNonNull(behaviorFactory);
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(ActorKind.SHARED, policy, behaviorFactory, true);
    }

    /**
     * Host/supervisor entry point for code that must be treated as malicious.
     * Untrusted actors are always memory-isolated, adversarial, capture-free,
     * lifetime-bounded, fuel-metered, and placed on a dedicated dispatcher.
     */
    public <M> ActorRef<M> spawnUntrusted(BehaviorFactory<M> behaviorFactory) {
        return spawnUntrusted(
                IsolatePolicy.untrustedActor(),
                UntrustedActorLimits.defaults(),
                null,
                behaviorFactory);
    }

    public <M> ActorRef<M> spawnUntrusted(
            IsolatePolicy policy,
            UntrustedActorLimits limits,
            HttpResponseTransport responseTransport,
            BehaviorFactory<M> behaviorFactory) {
        return spawnUntrusted(
                policy,
                limits,
                null,
                responseTransport,
                behaviorFactory);
    }

    public <M> ActorRef<M> spawnUntrusted(
            IsolatePolicy policy,
            UntrustedActorLimits limits,
            HttpRequestTransport requestTransport,
            HttpResponseTransport responseTransport,
            BehaviorFactory<M> behaviorFactory) {
        return spawnUntrusted(
                policy,
                limits,
                requestTransport,
                responseTransport,
                null,
                behaviorFactory);
    }

    /**
     * Ownership-hardened HTTP handoff. The supplied lease starts supervisor-owned
     * and is atomically transferred to the newly-created actor before it can run.
     */
    public <M> ActorRef<M> spawnUntrusted(
            IsolatePolicy policy,
            UntrustedActorLimits limits,
            HttpRequestTransport requestTransport,
            HttpResponseTransport responseTransport,
            HttpTransportLease transportLease,
            BehaviorFactory<M> behaviorFactory) {
        requireSupervisorContext("spawn untrusted actors");
        Objects.requireNonNull(policy);
        Objects.requireNonNull(limits);
        Objects.requireNonNull(behaviorFactory);
        if ((requestTransport != null || responseTransport != null) && transportLease == null) {
            transportLease = new HttpTransportLease();
        }
        return spawnInternal(
                ActorKind.UNTRUSTED,
                policy,
                behaviorFactory,
                false,
                limits,
                (requestTransport != null || responseTransport != null)
                        ? new HttpTransportLimits(
                                limits.maxHttpRequestBytes(),
                                limits.maxHttpResponseBytes())
                        : null,
                requestTransport,
                responseTransport,
                transportLease);
    }

    /** Compatibility path for trusted host callers. */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            Supplier<? extends Behavior<M>> behaviorFactory) {
        requireSupervisorContext("use trusted Supplier actor construction");
        Objects.requireNonNull(behaviorFactory);
        if (kind == ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "UNTRUSTED actors require spawnUntrusted with a capture-free BehaviorFactory");
        }
        requireTrustedSupplierPolicy(policy);
        return spawnInternal(kind, policy, context -> behaviorFactory.get(), true);
    }

    private static void requireStatelessActorFactory(Object factory) {
        for (Class<?> type = factory.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(modifiers);
                boolean isFinal = java.lang.reflect.Modifier.isFinal(modifiers);

                if (!isStatic) {
                    throw new SecurityException(
                            "actor BehaviorFactory must be stateless; captured host state must enter through explicit actor messages/capabilities");
                }
                if (!isFinal) {
                    throw new SecurityException(
                            "actor BehaviorFactory declares mutable static JVM state '"
                                    + field.getName() + "'; actor construction cannot share static state");
                }
                if (!field.trySetAccessible()) {
                    throw new SecurityException(
                            "actor BehaviorFactory contains inaccessible static state: " + field.getName());
                }
                final Object value;
                try {
                    value = field.get(null);
                } catch (IllegalAccessException impossible) {
                    throw new SecurityException(
                            "cannot inspect actor BehaviorFactory static state: " + field.getName(),
                            impossible);
                }
                if (!isPrivateStaticConstant(value)) {
                    throw new SecurityException(
                            "actor BehaviorFactory declares shared static object '"
                                    + field.getName()
                                    + "'; only immutable scalar constants are allowed");
                }
            }
        }
    }

    private void validatePrivateBehaviorState(ActorId owner, Behavior<?> behavior) {
        for (Class<?> type = behavior.getClass();
             type != null && type != Object.class;
             type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                int modifiers = field.getModifiers();
                boolean isStatic = java.lang.reflect.Modifier.isStatic(modifiers);
                boolean isFinal = java.lang.reflect.Modifier.isFinal(modifiers);

                if (isStatic) {
                    if (!isFinal) {
                        throw new SecurityException(
                                "private actor behavior class declares mutable static JVM state '"
                                        + field.getName() + "'; private actors cannot share static state");
                    }
                    if (!field.trySetAccessible()) {
                        throw new SecurityException(
                                "private actor behavior contains inaccessible static state: " + field.getName());
                    }
                    final Object staticValue;
                    try {
                        staticValue = field.get(null);
                    } catch (IllegalAccessException impossible) {
                        throw new SecurityException(
                                "cannot inspect private actor static state: " + field.getName(),
                                impossible);
                    }
                    if (!isPrivateStaticConstant(staticValue)) {
                        throw new SecurityException(
                                "private actor behavior class declares shared static object '"
                                        + field.getName()
                                        + "'; only immutable scalar constants are allowed");
                    }
                    continue;
                }

                if (!isFinal) {
                    throw new SecurityException(
                            "private actor behavior field '" + field.getName()
                                    + "' is mutable JVM state; persistent mutable state must use context.privateMemory()");
                }
                if (!field.trySetAccessible()) {
                    throw new SecurityException(
                            "private actor behavior contains inaccessible captured state: " + field.getName());
                }
                final Object value;
                try {
                    value = field.get(behavior);
                } catch (IllegalAccessException impossible) {
                    throw new SecurityException(
                            "cannot inspect private actor behavior capture: " + field.getName(),
                            impossible);
                }
                validatePrivateBehaviorCapture(owner, field.getName(), value);
            }
        }
    }

    private static boolean isPrivateStaticConstant(Object value) {
        return value == null
                || isScalar(value)
                || value instanceof Class<?>;
    }

    private void validatePrivateBehaviorCapture(ActorId owner, String fieldName, Object value) {
        if (value == null || isScalar(value) || value instanceof Class<?>) return;

        if (value instanceof PrivateMemoryBlock block) {
            if (!block.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory block in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorMemorySlice slice) {
            if (!slice.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory slice in " + fieldName);
            }
            return;
        }
        if (value instanceof MemoryReservation reservation) {
            if (!reservation.owner().equals(owner)) {
                throw new SecurityException(
                        "private actor behavior captured another actor's memory reservation in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new SecurityException(
                        "private actor behavior captured an ActorRef from another runtime in " + fieldName);
            }
            return;
        }
        if (value instanceof Recipient<?> recipient) {
            if (!recipient.ownedBy(this)) {
                throw new SecurityException(
                        "private actor behavior captured a Recipient from another runtime in " + fieldName);
            }
            return;
        }
        if (value instanceof ActorContext<?> actorContext) {
            if (!actorContext.self().id().equals(owner) || actorContext.runtime() != this) {
                throw new SecurityException(
                        "private actor behavior captured a foreign actor context in " + fieldName);
            }
            return;
        }

        throw new SecurityException(
                "private actor behavior captured mutable/non-private JVM state in "
                        + fieldName + " (" + value.getClass().getName()
                        + "); allocate persistent state through context.privateMemory()");
    }

    private void requireTrustedSupplierPolicy(IsolatePolicy policy) {
        Objects.requireNonNull(policy);
        if (policy.adversarial()) {
            throw new SecurityException(
                    "adversarial actors require the context-aware BehaviorFactory path; "
                            + "Supplier factories can capture host/shared mutable references");
        }
    }

    /**
     * Creates the actor identity and private memory slice immediately. Behavior
     * initialization later runs on that actor's dispatcher with the actor
     * context already installed.
     */
    public <M> ActorRef<M> spawn(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory) {
        if (kind == ActorKind.UNTRUSTED) {
            throw new SecurityException(
                    "UNTRUSTED actors require spawnUntrusted so hard limits cannot be omitted");
        }
        return spawnInternal(kind, policy, behaviorFactory, false);
    }

    private <M> ActorRef<M> spawnInternal(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory,
            boolean trustedFactory) {
        return spawnInternal(kind, policy, behaviorFactory, trustedFactory, null, null, null, null, null);
    }

    private <M> ActorRef<M> spawnInternal(
            ActorKind kind,
            IsolatePolicy policy,
            BehaviorFactory<M> behaviorFactory,
            boolean trustedFactory,
            UntrustedActorLimits untrustedLimits,
            HttpTransportLimits httpLimits,
            HttpRequestTransport requestTransport,
            HttpResponseTransport responseTransport,
            HttpTransportLease suppliedTransportLease) {
        requireCallerRuntimeAffinity("spawn actors");
        ActorCell<?> callerCell = currentActor.get();
        if (callerCell != null && callerCell.kind == ActorKind.UNTRUSTED) {
            throw new SecurityException("untrusted actors cannot spawn child actors");
        }
        Objects.requireNonNull(kind);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(behaviorFactory);
        if (kind != ActorKind.UNTRUSTED) {
            requireWithinCeiling(policy);
        }
        if (kind == ActorKind.UNTRUSTED && untrustedLimits == null) {
            throw new SecurityException("untrusted actor hard limits are required");
        }
        if (kind != ActorKind.UNTRUSTED && untrustedLimits != null) {
            throw new IllegalArgumentException(
                    "untrusted limits may only be attached to UNTRUSTED actors");
        }
        boolean hasHttpTransport = requestTransport != null || responseTransport != null;
        if (hasHttpTransport && !kind.memoryIsolated()) {
            throw new IllegalArgumentException(
                    "HTTP transport ownership may only be attached to memory-isolated actors");
        }
        if (hasHttpTransport && httpLimits == null) {
            throw new IllegalArgumentException("HTTP transport limits are required");
        }
        if (!hasHttpTransport && (httpLimits != null || suppliedTransportLease != null)) {
            throw new IllegalArgumentException(
                    "HTTP transport limits/lease require a request or response transport");
        }

        IsolatePolicy effectivePolicy = kind.memoryIsolated()
                ? policy.withoutCapabilities(
                        IsolatePolicy.Capability.SHARED_MEMORY,
                        IsolatePolicy.Capability.ACTOR_SHARE_READONLY)
                : policy;
        if (kind == ActorKind.UNTRUSTED) {
            effectivePolicy = restrictUntrustedPolicy(effectivePolicy, untrustedLimits);
            effectivePolicy = intersectUntrustedWithRuntimeCeiling(effectivePolicy);
            requireWithinCeiling(effectivePolicy);
        }
        requireWithinCallerPolicy(effectivePolicy);
        if (kind == ActorKind.SHARED) {
            effectivePolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "shared actor spawn");
        }
        if (kind == ActorKind.UNTRUSTED && trustedFactory) {
            throw new SecurityException("untrusted actor factories can never use the trusted/capturing path");
        }
        if (!trustedFactory) {
            requireStatelessActorFactory(behaviorFactory);
        }

        synchronized (runtimeLifecycleLock) {
            if (closed.get()) throw new IllegalStateException("actor runtime is closed");
            reserveActorSlot(kind);

            ActorCell<M> cell = null;
            ActorId transferredActorId = null;
            HttpTransportLease transportLease = suppliedTransportLease;
            try {
                ActorId id = ActorId.create();
                ActorRef<M> ref = new ActorRef<>(id, kind);
                if (kind.memoryIsolated()
                        && (requestTransport != null || responseTransport != null)) {
                    if (transportLease == null) {
                        transportLease = new HttpTransportLease();
                    }
                } else if (transportLease != null) {
                    throw new IllegalArgumentException(
                            "HTTP transport lease requires a memory-isolated actor with request/response transport");
                }
                cell = new ActorCell<>(
                        ref,
                        kind,
                        effectivePolicy,
                        behaviorFactory,
                        trustedFactory,
                        untrustedLimits,
                        httpLimits,
                        requestTransport,
                        responseTransport,
                        transportLease);
                actors.put(id, cell);
                if (transportLease != null) {
                    transportLease.transferToActor(id);
                    transferredActorId = id;
                }
                cell.armLifetimeLimit();
                return ref;
            } catch (RuntimeException | Error failure) {
                if (cell != null) {
                    actors.remove(cell.ref.id(), cell);
                    if (cell.httpRequest != null) cell.httpRequest.cancelFromRuntime(failure);
                    if (cell.httpResponse != null) cell.httpResponse.abortFromRuntime(failure);
                    if (cell.httpTransportLease != null && transferredActorId != null) {
                        cell.httpTransportLease.revokeFromActor(transferredActorId);
                    }
                    if (cell.memorySlice != null) cell.memorySlice.close();
                } else if (transportLease != null && transferredActorId != null) {
                    transportLease.revokeFromActor(transferredActorId);
                }
                releaseReservedActorSlot(kind);
                throw failure;
            }
        }
    }

    /**
     * Compiler-only lowering target for source-level actor/isoactor callables.
     *
     * A fresh actor receives exactly one transported message, computes one
     * data-only result, then terminates. Synchronous nesting from an actor turn
     * is rejected so a bounded dispatcher cannot be starved by callers waiting
     * on actors scheduled onto the same runtime.
     */
    public <M, R> R invoke(
            ActorKind kind,
            M message,
            Invocation<M, R> invocation) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(invocation, "invocation");
        requireCallerRuntimeAffinity("invoke actor callables");
        if (inActorExecution()) {
            throw new IllegalStateException(
                    "synchronous actor-callable invocation from an actor turn is forbidden; "
                            + "use mailbox-oriented actor composition");
        }
        if (inRootExecution() && policyCeiling.adversarial()) {
            throw new SecurityException(
                    "adversarial root/main execution cannot synchronously invoke an actor; "
                            + "the strict Truffle turn is serialized, so use an async/sandbox actor boundary");
        }

        IsolatePolicy policy = defaultSpawnPolicy();
        CompletableFuture<R> completion = new CompletableFuture<>();
        ActorRef<M> ref = spawnInternal(
                kind,
                policy,
                factoryContext -> (delivered, turnContext) -> {
                    try {
                        @SuppressWarnings("unchecked")
                        R frozen = (R) freeze(invocation.run(delivered, turnContext));
                        completion.complete(frozen);
                    } catch (VirtualMachineError fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (ThreadDeath fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (LinkageError fatal) {
                        completion.completeExceptionally(fatal);
                        throw fatal;
                    } catch (Throwable failure) {
                        completion.completeExceptionally(failure);
                    } finally {
                        turnContext.self().stop();
                    }
                },
                true);

        try {
            try {
                send(ref, message);
            } catch (ActorTerminatedException terminatedBeforeDelivery) {
                Throwable startupFailure = ref.terminationCause.get();
                if (startupFailure instanceof RuntimeException runtime) throw runtime;
                if (startupFailure instanceof Error error) throw error;
                if (startupFailure != null) throw new RuntimeException(startupFailure);
                throw terminatedBeforeDelivery;
            }
            long timeoutNanos;
            try {
                timeoutNanos = policy.maxWallTime().toNanos();
            } catch (ArithmeticException overflow) {
                timeoutNanos = Long.MAX_VALUE;
            }
            if (timeoutNanos <= 0) timeoutNanos = 1;

            return completion.get(timeoutNanos, TimeUnit.NANOSECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("actor callable invocation interrupted");
        } catch (TimeoutException timedOut) {
            throw new IllegalStateException(
                    "actor callable exceeded max wall time " + policy.maxWallTime(),
                    timedOut);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new RuntimeException(cause);
        } finally {
            if (ref.isAlive()) {
                try {
                    stop(ref);
                } catch (IllegalStateException alreadyStopping) {
                    if (ref.isAlive()) throw alreadyStopping;
                }
            }
        }
    }

    private IsolatePolicy restrictUntrustedPolicy(
            IsolatePolicy policy,
            UntrustedActorLimits limits) {
        IsolatePolicy stripped = policy.withoutCapabilities(
                IsolatePolicy.Capability.STDIN,
                IsolatePolicy.Capability.STDOUT,
                IsolatePolicy.Capability.PROCESS_INFO,
                IsolatePolicy.Capability.ACTOR_SHARE_READONLY,
                IsolatePolicy.Capability.SHARED_MEMORY,
                IsolatePolicy.Capability.NETWORK,
                IsolatePolicy.Capability.FILESYSTEM_READ,
                IsolatePolicy.Capability.FILESYSTEM_WRITE,
                IsolatePolicy.Capability.ENVIRONMENT,
                IsolatePolicy.Capability.HOT_CODE_LOAD,
                IsolatePolicy.Capability.FFI,
                IsolatePolicy.Capability.NATIVE,
                IsolatePolicy.Capability.REFLECTION,
                IsolatePolicy.Capability.CHILD_PROCESS,
                IsolatePolicy.Capability.THREAD_CREATE,
                IsolatePolicy.Capability.POLYGLOT);
        Duration wall = stripped.maxWallTime().compareTo(limits.maxLifetime()) <= 0
                ? stripped.maxWallTime()
                : limits.maxLifetime();
        int mailbox = Math.min(stripped.maxMailboxMessages(), 256);
        return new IsolatePolicy(
                stripped.capabilities(),
                stripped.maxHeapBytes(),
                mailbox,
                wall,
                true);
    }

    private IsolatePolicy intersectUntrustedWithRuntimeCeiling(IsolatePolicy policy) {
        java.util.Set<IsolatePolicy.Capability> caps =
                new java.util.HashSet<>(policy.capabilities());
        caps.retainAll(policyCeiling.capabilities());
        return new IsolatePolicy(
                caps,
                Math.min(policy.maxHeapBytes(), policyCeiling.maxHeapBytes()),
                Math.min(policy.maxMailboxMessages(), policyCeiling.maxMailboxMessages()),
                policy.maxWallTime().compareTo(policyCeiling.maxWallTime()) <= 0
                        ? policy.maxWallTime()
                        : policyCeiling.maxWallTime(),
                true);
    }

    private AtomicInteger actorCountFor(ActorKind kind) {
        return switch (kind) {
            case PRIVATE -> privateActorCount;
            case SHARED -> sharedActorCount;
            case UNTRUSTED -> untrustedActorCount;
        };
    }

    private void reserveActorSlot(ActorKind kind) {
        Objects.requireNonNull(kind);
        dispatcherGroup.reserveActor(kind);
        boolean localReserved = false;
        try {
            if (actorCount.get() >= dispatcherConfig.maxActors()) {
                throw new IllegalStateException(
                        "actor runtime limit exceeded: maximum " + dispatcherConfig.maxActors());
            }
            if (kind == ActorKind.UNTRUSTED
                    && untrustedActorCount.get() >= dispatcherConfig.maxUntrustedActors()) {
                throw new IllegalStateException(
                        "untrusted actor limit exceeded: maximum "
                                + dispatcherConfig.maxUntrustedActors());
            }
            actorCount.incrementAndGet();
            actorCountFor(kind).incrementAndGet();
            localReserved = true;
        } finally {
            if (!localReserved) dispatcherGroup.releaseActor(kind);
        }
    }

    private void releaseReservedActorSlot(ActorKind kind) {
        int remaining = actorCount.decrementAndGet();
        int domainRemaining = actorCountFor(kind).decrementAndGet();
        if (remaining < 0 || domainRemaining < 0) {
            if (remaining < 0) actorCount.incrementAndGet();
            if (domainRemaining < 0) actorCountFor(kind).incrementAndGet();
            throw new IllegalStateException("reserved actor slot accounting underflow");
        }
        dispatcherGroup.releaseActor(kind);
    }

    private void unregisterActor(ActorCell<?> cell) {
        if (actors.remove(cell.ref.id(), cell)) {
            int remaining = actorCount.decrementAndGet();
            int domainRemaining = actorCountFor(cell.kind).decrementAndGet();
            if (remaining < 0 || domainRemaining < 0) {
                if (remaining < 0) actorCount.incrementAndGet();
                if (domainRemaining < 0) actorCountFor(cell.kind).incrementAndGet();
                throw new IllegalStateException("actor count accounting underflow");
            }
            dispatcherGroup.releaseActor(cell.kind);
        }
    }

    public <T> SyncCell<T> syncCell(T initialValue) {
        requireCallerRuntimeAffinity("create shared SyncCell values");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        IsolatePolicy callerPolicy = currentActorPolicy();
        if (callerPolicy != null) {
            callerPolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SyncCell");
        } else {
            policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SyncCell");
        }
        rejectPrivateActorSharedMemoryAccess("SyncCell creation");
        SyncCell<T> cell = new SyncCell<>(initialValue);
        syncCells.add(cell);
        if (closed.get()) {
            cell.close();
            throw new IllegalStateException("actor runtime is closed");
        }
        return cell;
    }

    private boolean enterSyncCell(SyncCell<?> cell) {
        SyncCell<?> held = currentSyncCell.get();
        if (held == null) {
            currentSyncCell.set(cell);
            return true;
        }
        if (held != cell) {
            throw new IllegalStateException(
                    "nested synchronization across different SyncCell values is forbidden; "
                            + "snapshot values first or use one shared cell");
        }
        return false;
    }

    private void exitSyncCell(boolean entered) {
        if (entered) currentSyncCell.remove();
    }

    private void rejectPrivateActorSharedMemoryAccess(String operation) {
        ActorCell<?> current = currentActor.get();
        if (current == null) return;
        if (current.kind == ActorKind.PRIVATE) {
            throw new IllegalStateException(
                    "private actors cannot access synchronized shared memory via " + operation);
        }
        if (current.kind == ActorKind.UNTRUSTED) {
            throw new IllegalStateException(
                    "untrusted actors cannot access synchronized shared memory via " + operation);
        }
    }

    private void requireSharedActorTurn() {
        ActorCell<?> cell = currentActor.get();
        if (cell == null || cell.kind != ActorKind.SHARED) {
            throw new IllegalStateException("shared state mutation requires a shared actor mailbox turn");
        }
    }

    private void reservePrivateRuntimeBytes(long bytes, ActorId owner, String purpose) {
        synchronized (memoryBudgetLock) {
            long privateBytes = privateMemoryBytes.get();
            long sharedBytes = sharedMemoryBytes.get();
            long total;
            try {
                total = Math.addExact(Math.addExact(privateBytes, sharedBytes), bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (total > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded for " + owner
                        + ": requested=" + bytes + " privateUsed=" + privateBytes
                        + " sharedUsed=" + sharedBytes + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            dispatcherGroup.reserveMemory(bytes, purpose);
            privateMemoryBytes.addAndGet(bytes);
        }
    }

    private void releasePrivateRuntimeBytes(long bytes, ActorId owner) {
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long current = privateMemoryBytes.get();
            if (bytes > current) {
                throw new IllegalStateException(
                        "private actor aggregate memory accounting underflow for " + owner
                                + ": release=" + bytes + " privateUsed=" + current);
            }
            privateMemoryBytes.set(current - bytes);
            try {
                dispatcherGroup.releaseMemory(bytes);
            } catch (RuntimeException failure) {
                privateMemoryBytes.set(current);
                throw failure;
            }
        }
    }

    private void reserveSharedRuntimeBytes(long bytes, String purpose) {
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        if (bytes < 0) throw new IllegalArgumentException("shared memory reservation cannot be negative");
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long privateBytes = privateMemoryBytes.get();
            long sharedBytes = sharedMemoryBytes.get();
            long total;
            try {
                total = Math.addExact(Math.addExact(privateBytes, sharedBytes), bytes);
            } catch (ArithmeticException overflow) {
                throw new IllegalStateException(purpose + " aggregate accounting overflow");
            }
            if (total > policyCeiling.maxHeapBytes()) {
                throw new IllegalStateException(purpose + " aggregate runtime limit exceeded"
                        + ": requested=" + bytes + " privateUsed=" + privateBytes
                        + " sharedUsed=" + sharedBytes + " runtimeLimit=" + policyCeiling.maxHeapBytes());
            }
            dispatcherGroup.reserveMemory(bytes, purpose);
            sharedMemoryBytes.addAndGet(bytes);
        }
    }

    private void releaseSharedRuntimeBytes(long bytes) {
        if (bytes == 0) return;
        synchronized (memoryBudgetLock) {
            long current = sharedMemoryBytes.get();
            if (bytes > current) {
                throw new IllegalStateException("shared actor memory accounting underflow");
            }
            sharedMemoryBytes.set(current - bytes);
            try {
                dispatcherGroup.releaseMemory(bytes);
            } catch (RuntimeException failure) {
                sharedMemoryBytes.set(current);
                throw failure;
            }
        }
    }

    private void requireWithinCeiling(IsolatePolicy child) {
        if (!policyCeiling.capabilities().containsAll(child.capabilities())) {
            java.util.Set<IsolatePolicy.Capability> excess = java.util.EnumSet.copyOf(child.capabilities());
            excess.removeAll(policyCeiling.capabilities());
            throw new SecurityException("child actor policy exceeds parent capabilities: " + excess);
        }
        if (child.maxHeapBytes() > policyCeiling.maxHeapBytes()) {
            throw new SecurityException("child actor maxHeapBytes exceeds parent policy");
        }
        if (child.maxMailboxMessages() > policyCeiling.maxMailboxMessages()) {
            throw new SecurityException("child actor mailbox limit exceeds parent policy");
        }
        if (child.maxWallTime().compareTo(policyCeiling.maxWallTime()) > 0) {
            throw new SecurityException("child actor wall-time limit exceeds parent policy");
        }
        if (policyCeiling.adversarial() && !child.adversarial()) {
            throw new SecurityException("child actor cannot weaken an adversarial parent policy");
        }
    }

    public boolean isAlive(ActorRef<?> ref) {
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) return false;
        ActorCell<?> cell = actors.get(ref.id());
        // A stopped actor may still be finishing its current mailbox turn and
        // deterministic teardown. Keep it alive until finalization has closed
        // private memory, drained reservations, and unregistered the cell.
        return cell != null && !cell.finalized();
    }

    public void stop(ActorRef<?> ref) {
        requireCallerRuntimeAffinity("stop actors");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException("ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<?> caller = currentActor.get();
        if (caller != null
                && caller.kind == ActorKind.UNTRUSTED
                && !caller.ref.id().equals(ref.id())) {
            throw new SecurityException(
                    "untrusted actors cannot stop other actors; ActorRef grants bounded messaging only");
        }
        ActorCell<?> cell = actors.get(ref.id());
        if (cell == null) return;

        cell.stop();

        // A host/supervisor stop is a synchronization point: once it returns,
        // private actor memory and actor-count quota have been reclaimed. A
        // self-stop from inside the actor turn cannot wait for itself; endTurn()
        // finalizes it immediately after the current turn unwinds.
        if (currentActor.get() == cell) return;

        try {
            cell.awaitFinalized(CLOSE_WAIT_NANOS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for actor " + ref.id() + " to finalize",
                    interrupted);
        }
        if (!cell.finalized()) {
            throw new IllegalStateException(
                    "actor " + ref.id() + " did not finalize within the stop deadline");
        }
    }

    private ActorTerminatedException terminated(ActorRef<?> ref) {
        Throwable cause = ref.terminationCause.get();
        ActorCell<?> caller = currentActor.get();
        if (caller != null
                && caller.kind == ActorKind.UNTRUSTED
                && !caller.ref.id().equals(ref.id())) {
            cause = null;
        }
        return new ActorTerminatedException(ref.id(), ref.kind(), cause);
    }

    @SuppressWarnings("unchecked")
    public <M> void send(ActorRef<M> ref, M message) {
        requireCallerRuntimeAffinity("send messages");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        Objects.requireNonNull(ref);
        if (!ref.ownedBy(this)) {
            throw new IllegalArgumentException("ActorRef belongs to a different ActorRuntime");
        }
        ActorCell<M> cell = (ActorCell<M>) actors.get(ref.id());
        if (cell == null || cell.stopped.get()) throw terminated(ref);

        ActorCell<?> sender = currentActor.get();
        if (sender != null && sender.kind == ActorKind.UNTRUSTED) {
            sender.checkUntrustedBudget(1);
            try {
                estimatePrivateTransportBytes(
                        message,
                        new IdentityHashMap<>(),
                        0,
                        sender.untrustedLimits.maxMailboxReturnBytes());
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "untrusted actor outbound mailbox payload exceeds "
                                + sender.untrustedLimits.maxMailboxReturnBytes()
                                + " bytes; stream large HTTP responses through context.httpResponse()",
                        tooLarge);
            }
        }

        if (!cell.reserveMailboxSlot()) {
            throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
        }
        boolean mailboxSlotTransferred = false;
        try {
        validateMessageGraph(message);
        requireMutexTransport(cell, message, new IdentityHashMap<>(), 0);
        requireOwnedActorRefs(message, new IdentityHashMap<>(), 0);
        long runtimeRemaining = Math.max(0L, policyCeiling.maxHeapBytes() - actorMemoryBytes());
        if (cell.kind == ActorKind.SHARED) {
            requireOwnedSharedHandles(message, new IdentityHashMap<>(), 0);
            long actorRemaining = Math.max(0L, cell.policy.maxHeapBytes() - cell.sharedMailboxBytes.get());
            long allowed = Math.min(actorRemaining, runtimeRemaining);
            try {
                estimateSharedTransportBytes(message, new IdentityHashMap<>(), 0, allowed);
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "shared actor mailbox memory limit exceeded for " + ref.id() + ": " + tooLarge.getMessage(),
                        tooLarge);
            }
        } else {
            long actorRemaining = cell.memorySlice.remainingBytes();
            try {
                estimatePrivateTransportBytes(message, new IdentityHashMap<>(), 0, actorRemaining);
            } catch (IllegalStateException tooLarge) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + tooLarge.getMessage(),
                        tooLarge);
            }
            try {
                estimatePrivateTransportBytes(message, new IdentityHashMap<>(), 0, runtimeRemaining);
            } catch (IllegalStateException aggregateExceeded) {
                throw new IllegalStateException(
                        "private actor aggregate runtime limit exceeded for " + ref.id()
                                + ": " + aggregateExceeded.getMessage(),
                        aggregateExceeded);
            }
        }

        if (closed.get()) throw new IllegalStateException("actor runtime is closed");

        Object prepared = cell.kind.memoryIsolated() ? isolateCopy(message) : freezeForTransport(message);
        Runnable release;
        if (cell.kind.memoryIsolated()) {
            MemoryReservation reservation;
            try {
                reservation = cell.memorySlice.reserveMailbox(prepared);
            } catch (IllegalStateException exceeded) {
                throw new IllegalStateException(
                        "private actor mailbox limit exceeded for " + ref.id() + ": " + exceeded.getMessage(),
                        exceeded);
            }
            release = reservation::close;
        } else {
            long bytes = estimateSharedMailboxBytes(prepared, new IdentityHashMap<>(), 0);
            cell.reserveSharedMailbox(bytes);
            release = () -> cell.releaseSharedMailbox(bytes);
        }

        MessageEnvelope envelope = new MessageEnvelope(prepared, release);
        List<OresMutex.Shared<?>> sharedMutexReservations = List.of();
        boolean admitted = false;
        try {
            synchronized (runtimeLifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (cell.kind == ActorKind.SHARED) {
                    sharedMutexReservations = reserveSharedMutexBindings(prepared);
                }
                synchronized (cell.lifecycleLock) {
                    if (cell.stopped.get()) {
                        throw terminated(ref);
                    }
                    if (!cell.mailbox.offer(envelope)) {
                        throw new IllegalStateException("actor mailbox limit exceeded for " + ref.id());
                    }
                    mailboxSlotTransferred = true;
                    admitted = true;
                    commitSharedMutexBindings(sharedMutexReservations);
                }
            }
        } finally {
            if (!admitted) {
                abortSharedMutexBindings(sharedMutexReservations);
                envelope.close();
            }
        }
        cell.schedule();
        } finally {
            if (!mailboxSlotTransferred) cell.releaseMailboxSlot();
        }
    }

    /**
     * Compiler-injected scheduling/cancellation checkpoint.
     *
     * Do not use Thread.yield() here: yielding the carrier thread does not
     * return the current actor to the dispatcher, so a single-worker pool still
     * cannot run a queued peer. Actor fairness is provided at resumable mailbox
     * boundaries by the bounded message/time quantum. UNTRUSTED guest code is
     * additionally charged fuel here and is hard-bounded by its sandbox
     * deadline/isolate. A future resumable Oreslang fiber/continuation backend
     * may turn this checkpoint into a true mid-message cooperative handoff.
     */
    public void schedulerSafepoint() {
        if (closed.get()) throw new CancellationException("actor runtime is closing");
        if (CURRENT_ROOT_RUNTIME.get() == this) {
            Long rootDeadline = CURRENT_ROOT_DEADLINE_NANOS.get();
            if (rootDeadline != null
                    && rootDeadline != Long.MAX_VALUE
                    && System.nanoTime() - rootDeadline >= 0) {
                throw new CancellationException(
                        "root/main process exceeded max wall time "
                                + policyCeiling.maxWallTime());
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("root/main execution interrupted");
            }
        }
        ActorCell<?> cell = currentActor.get();
        if (cell != null) {
            cell.throwIfControlStopped();
            cell.checkCpuBudget();
            cell.checkUntrustedBudget(1);
        }
        if (Thread.currentThread().isInterrupted()) {
            if (cell != null) cell.throwIfControlStopped();
            throw new CancellationException("actor execution interrupted");
        }
    }

    @SuppressWarnings("unchecked")
    public <T> Shared<T> shareReadonly(T value) {
        requireCallerRuntimeAffinity("share readonly values");
        if (closed.get()) throw new IllegalStateException("actor runtime is closed");
        IsolatePolicy callerPolicy = currentActorPolicy();
        if (callerPolicy != null) {
            callerPolicy.require(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "shareReadonly");
        } else {
            policyCeiling.require(IsolatePolicy.Capability.ACTOR_SHARE_READONLY, "shareReadonly");
        }
        rejectPrivateActorSharedMemoryAccess("shareReadonly");
        requireOwnedSharedHandles(value, new IdentityHashMap<>(), 0);
        Object frozen = freeze(value);
        rejectSharedMutableHandles(frozen, new IdentityHashMap<>(), 0);
        long bytes = estimateFrozenBytes(frozen);
        reserveSharedRuntimeBytes(bytes, "shared readonly value");
        Shared<T> shared = new Shared<>((T) frozen, bytes);
        sharedValues.add(shared);
        if (closed.get()) {
            shared.closeFromRuntime();
            throw new IllegalStateException("actor runtime is closed");
        }
        return shared;
    }

    private void requireMutexTransport(
            ActorCell<?> target,
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.Recipient<?>) return;
        if (value instanceof OresMutex.Local<?>) {
            throw new IllegalArgumentException("Mutex<T> is actor-local state and cannot cross actor mailboxes");
        }
        if (value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("MutexGuard<T> is lexical and cannot cross actor mailboxes");
        }
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (target.kind != ActorKind.SHARED) {
                throw new SecurityException("memory-isolated actors cannot receive SharedMutex<T>");
            }
            ActorKind senderKind = currentActorKind();
            if (senderKind != null && senderKind != ActorKind.SHARED) {
                throw new SecurityException("memory-isolated actors cannot send SharedMutex<T>");
            }
            IsolatePolicy senderPolicy = currentActorPolicy();
            if (senderPolicy != null) {
                senderPolicy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor send");
            } else {
                policyCeiling.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex host send");
            }
            target.policy.require(IsolatePolicy.Capability.SHARED_MEMORY, "SharedMutex actor receive");
            sharedMutex.inspectForTransport(payload ->
                    requireSharedMutexPayloadSafe(
                            payload,
                            new IdentityHashMap<>(),
                            depth + 1));
            return;
        }
        if (value instanceof Shared<?> shared) {
            requireMutexTransport(target, shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof SyncCell<?>) return;
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireMutexTransport(target, item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireMutexTransport(target, item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireMutexTransport(target, entry.getKey(), visiting, depth + 1);
                    requireMutexTransport(target, entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireMutexTransport(target, Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void requireSharedMutexPayloadSafe(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;

        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SharedMutex payload contains ActorRef from another ActorRuntime");
            }
            return;
        }
        if (value instanceof ActorRuntime.Recipient<?> recipient) {
            if (!recipient.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "Recipient belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            if (!shared.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SharedMutex payload contains Shared value from another ActorRuntime");
            }
            requireSharedMutexPayloadSafe(shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain another SharedMutex; nested shared locks are not transport-safe until recursive lock-order semantics are defined");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain actor-local mutex state");
        }
        if (value instanceof SyncCell<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain SyncCell writable shared state");
        }
        if (value instanceof HttpRequestCapability
                || value instanceof HttpResponseCapability
                || value instanceof HttpTransportLease) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain live HTTP transport capabilities");
        }
        if (value instanceof java.util.concurrent.CompletionStage<?>) {
            throw new IllegalArgumentException(
                    "SharedMutex payload cannot contain pending/asynchronous computation state");
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic SharedMutex payload is not runtime-shared-safe");
        }
        try {
            if (value instanceof OresMutex.SharedState aggregate) {
                for (Object child : aggregate.sharedStateChildren()) {
                    requireSharedMutexPayloadSafe(child, visiting, depth + 1);
                }
                return;
            }
            if (value instanceof List<?> list) {
                for (Object item : list) requireSharedMutexPayloadSafe(item, visiting, depth + 1);
                return;
            }
            if (value instanceof Set<?> set) {
                for (Object item : set) requireSharedMutexPayloadSafe(item, visiting, depth + 1);
                return;
            }
            if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireSharedMutexPayloadSafe(entry.getKey(), visiting, depth + 1);
                    requireSharedMutexPayloadSafe(entry.getValue(), visiting, depth + 1);
                }
                return;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireSharedMutexPayloadSafe(Array.get(value, i), visiting, depth + 1);
                }
                return;
            }
            throw new IllegalArgumentException(
                    "SharedMutex payload contains opaque host value of type "
                            + value.getClass().getName());
        } finally {
            visiting.remove(value);
        }
    }

    private void requireOwnedActorRefs(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorRef belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof ActorRuntime.Recipient<?> recipient) {
            if (!recipient.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "Recipient belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            requireOwnedActorRefs(shared.value(), visiting, depth + 1);
            return;
        }
        if (value instanceof SyncCell<?>) return;
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            requireOwnedActorRefs(sharedMutex.transportValue(), visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireOwnedActorRefs(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireOwnedActorRefs(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireOwnedActorRefs(entry.getKey(), visiting, depth + 1);
                    requireOwnedActorRefs(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireOwnedActorRefs(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void requireOwnedSharedHandles(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?> ref) {
            if (!ref.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "ActorRef belongs to a different ActorRuntime; cross-runtime actor channels require an explicit bridge");
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            if (!shared.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "Shared value belongs to a different ActorRuntime; copy/freeze it into the destination runtime");
            }
            shared.value();
            return;
        }
        if (value instanceof SyncCell<?> cell) {
            if (!cell.ownedBy(this)) {
                throw new IllegalArgumentException(
                        "SyncCell belongs to a different ActorRuntime and cannot cross shared-memory domains");
            }
            if (cell.closed()) throw new IllegalArgumentException("SyncCell is closed");
            return;
        }
        if (value instanceof OresMutex.Shared<?>) {
            // Runtime affinity is reserved atomically immediately before mailbox admission.
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross shared-memory domains");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) requireOwnedSharedHandles(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) requireOwnedSharedHandles(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    requireOwnedSharedHandles(entry.getKey(), visiting, depth + 1);
                    requireOwnedSharedHandles(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    requireOwnedSharedHandles(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private List<OresMutex.Shared<?>> reserveSharedMutexBindings(Object value) {
        Set<OresMutex.Shared<?>> unique = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        collectSharedMutexes(value, unique, new IdentityHashMap<>(), 0);

        List<OresMutex.Shared<?>> reserved = new ArrayList<>(unique.size());
        try {
            for (OresMutex.Shared<?> mutex : unique) {
                if (!mutex.reserveRuntimePublication(this)) {
                    throw new IllegalArgumentException(
                            "SharedMutex may cross actor mailboxes only within its owning ActorRuntime");
                }
                reserved.add(mutex);
            }
            return List.copyOf(reserved);
        } catch (RuntimeException | Error failure) {
            abortSharedMutexBindings(reserved);
            throw failure;
        }
    }

    private static void collectSharedMutexes(
            Object value,
            Set<OresMutex.Shared<?>> out,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof SyncCell<?>) return;
        if (value instanceof OresMutex.Shared<?> sharedMutex) {
            if (!out.add(sharedMutex)) return;
            if (visiting.put(value, Boolean.TRUE) != null) return;
            try {
                collectSharedMutexes(
                        sharedMutex.transportValue(),
                        out,
                        visiting,
                        depth + 1);
            } finally {
                visiting.remove(value);
            }
            return;
        }
        if (value instanceof Shared<?> shared) {
            collectSharedMutexes(shared.value(), out, visiting, depth + 1);
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) return;
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) collectSharedMutexes(item, out, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) collectSharedMutexes(item, out, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    collectSharedMutexes(entry.getKey(), out, visiting, depth + 1);
                    collectSharedMutexes(entry.getValue(), out, visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    collectSharedMutexes(Array.get(value, i), out, visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void commitSharedMutexBindings(List<OresMutex.Shared<?>> reservations) {
        for (OresMutex.Shared<?> mutex : reservations) {
            mutex.commitRuntimePublication(this);
        }
    }

    private void abortSharedMutexBindings(List<OresMutex.Shared<?>> reservations) {
        for (int i = reservations.size() - 1; i >= 0; i--) {
            reservations.get(i).abortRuntimePublication(this);
        }
    }

    private static void rejectSharedMutableHandles(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.Recipient<?>) return;
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("SyncCell is mutable shared state and cannot be wrapped as Shared");
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException("SharedMutex is mutable shared state and cannot be wrapped as Shared");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot be wrapped as Shared");
        }
        if (value instanceof Shared<?> shared) {
            shared.value();
            return;
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot be shared read-only");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) rejectSharedMutableHandles(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) rejectSharedMutableHandles(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectSharedMutableHandles(entry.getKey(), visiting, depth + 1);
                    rejectSharedMutableHandles(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectSharedMutableHandles(Array.get(value, i), visiting, depth + 1);
                }
            } else {
                throw new IllegalArgumentException("value of type " + value.getClass().getName()
                        + " is not a runtime-owned immutable actor value");
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void validateMessageGraph(Object value) {
        validateMessageGraph(value, new IdentityHashMap<>(), 0, new long[]{0L});
    }

    private static void validateMessageGraph(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long[] nodes) {
        requireGraphDepth(depth);
        if (++nodes[0] > MAX_MESSAGE_GRAPH_NODES) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum node count " + MAX_MESSAGE_GRAPH_NODES);
        }
        if (value == null || isScalar(value)
                || value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.Recipient<?>
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Shared<?>) {
            return;
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            return; // transport-specific validation produces the semantic error.
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                requireGraphNodeCapacity(nodes[0], list.size());
                for (Object item : list) validateMessageGraph(item, visiting, depth + 1, nodes);
            } else if (value instanceof Set<?> set) {
                requireGraphNodeCapacity(nodes[0], set.size());
                for (Object item : set) validateMessageGraph(item, visiting, depth + 1, nodes);
            } else if (value instanceof Map<?, ?> map) {
                requireGraphNodeCapacity(nodes[0], Math.multiplyExact((long) map.size(), 2L));
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    validateMessageGraph(entry.getKey(), visiting, depth + 1, nodes);
                    validateMessageGraph(entry.getValue(), visiting, depth + 1, nodes);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                requireGraphNodeCapacity(nodes[0], length);
                for (int i = 0; i < length; i++) {
                    validateMessageGraph(Array.get(value, i), visiting, depth + 1, nodes);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static void requireGraphNodeCapacity(long alreadyVisited, long additionalNodes) {
        if (additionalNodes < 0
                || additionalNodes > (long) MAX_MESSAGE_GRAPH_NODES - alreadyVisited) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum node count " + MAX_MESSAGE_GRAPH_NODES);
        }
    }

    private static void requireGraphDepth(int depth) {
        if (depth > MAX_MESSAGE_GRAPH_DEPTH) {
            throw new IllegalArgumentException(
                    "actor message graph exceeds maximum nesting depth " + MAX_MESSAGE_GRAPH_DEPTH);
        }
    }

    /**
     * Converts supported values into a deeply immutable/sendable graph.
     * Unknown host objects are rejected instead of being passed by reference.
     */
    public static Object freeze(Object value) {
        validateMessageGraph(value);
        rejectDataFreezeCapabilities(value, new IdentityHashMap<>(), 0);
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    private static Object freezeForTransport(Object value) {
        validateMessageGraph(value);
        return freeze(value, new IdentityHashMap<>(), 0);
    }

    private static void rejectDataFreezeCapabilities(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (value == null || isScalar(value)) return;
        if (value instanceof ActorRuntime.ActorRef<?>
                || value instanceof ActorRuntime.Recipient<?>
                || value instanceof Shared<?>
                || value instanceof SyncCell<?>
                || value instanceof OresMutex.Lock<?>
                || value instanceof OresMutex.Guard<?>
                || value instanceof ActorRuntime.HttpRequestCapability
                || value instanceof ActorRuntime.HttpResponseCapability
                || value instanceof HttpTransportLease) {
            throw new IllegalArgumentException(
                    "freeze() accepts data values only; live actor/shared capabilities require explicit actor transport");
        }
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot be frozen");
        }
        try {
            if (value instanceof List<?> list) {
                for (Object item : list) rejectDataFreezeCapabilities(item, visiting, depth + 1);
            } else if (value instanceof Set<?> set) {
                for (Object item : set) rejectDataFreezeCapabilities(item, visiting, depth + 1);
            } else if (value instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    rejectDataFreezeCapabilities(entry.getKey(), visiting, depth + 1);
                    rejectDataFreezeCapabilities(entry.getValue(), visiting, depth + 1);
                }
            } else if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                for (int i = 0; i < length; i++) {
                    rejectDataFreezeCapabilities(Array.get(value, i), visiting, depth + 1);
                }
            }
        } finally {
            visiting.remove(value);
        }
    }

    private static Object freeze(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (isScalar(value)) return value;
        if (value instanceof Shared<?> shared) {
            shared.value();
            return shared;
        }
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof ActorRuntime.Recipient<?> recipient) return recipient;
        if (value instanceof ActorRuntime.SyncCell<?> cell) return cell;
        if (value instanceof OresMutex.Shared<?> sharedMutex) return sharedMutex;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> frozen = new ArrayList<>(list.size());
                for (Object item : list) frozen.add(freeze(item, visiting, depth + 1));
                return List.copyOf(frozen);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> frozen = new LinkedHashSet<>();
                for (Object item : set) frozen.add(freeze(item, visiting, depth + 1));
                return Collections.unmodifiableSet(frozen);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> frozen = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    frozen.put(freeze(entry.getKey(), visiting, depth + 1), freeze(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(frozen);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> frozen = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    frozen.add(freeze(Array.get(value, i), visiting, depth + 1));
                }
                return List.copyOf(frozen);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    /**
     * Private transport never retains a shared mutable reference. Immutable
     * shared wrappers are unwrapped and copied into the private message graph.
     */
    private static Object isolateCopy(Object value) {
        return isolateCopy(value, new IdentityHashMap<>(), 0);
    }

    private static Object isolateCopy(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth) {
        requireGraphDepth(depth);
        if (isScalar(value)) return value;
        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof OresMutex.Shared<?>) {
            throw new IllegalArgumentException("private actors cannot receive SharedMutex<T>");
        }
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof Shared<?> shared) return isolateCopy(shared.value(), visiting, depth + 1);
        if (value instanceof ActorRuntime.ActorRef<?> ref) return ref;
        if (value instanceof ActorRuntime.Recipient<?> recipient) return recipient;

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>(list.size());
                for (Object item : list) copy.add(isolateCopy(item, visiting, depth + 1));
                return Collections.unmodifiableList(copy);
            }
            if (value instanceof Set<?> set) {
                LinkedHashSet<Object> copy = new LinkedHashSet<>();
                for (Object item : set) copy.add(isolateCopy(item, visiting, depth + 1));
                return Collections.unmodifiableSet(copy);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    copy.put(isolateCopy(entry.getKey(), visiting, depth + 1), isolateCopy(entry.getValue(), visiting, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                List<Object> copy = new ArrayList<>(length);
                for (int i = 0; i < length; i++) {
                    copy.add(isolateCopy(Array.get(value, i), visiting, depth + 1));
                }
                return Collections.unmodifiableList(copy);
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long estimateSharedTransportBytes(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long limit) {
        requireGraphDepth(depth);
        if (limit < 0) throw new IllegalStateException("message exceeds remaining actor memory");

        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return requireWithinLimit(scalar, limit);
        if (value instanceof Shared<?>) return requireWithinLimit(48L, limit);
        if (value instanceof ActorRuntime.SyncCell<?>) return requireWithinLimit(64L, limit);
        if (value instanceof OresMutex.Shared<?>) return requireWithinLimit(64L, limit);
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return requireWithinLimit(48L, limit);
        if (value instanceof ActorRuntime.Recipient<?>) return requireWithinLimit(48L, limit);

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                long total = requireWithinLimit(containerBase(24L, 8L, list.size()), limit);
                for (Object item : list) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = requireWithinLimit(containerBase(24L, 16L, set.size()), limit);
                for (Object item : set) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = requireWithinLimit(containerBase(24L, 32L, map.size()), limit);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(entry.getKey(), visiting, depth + 1, limit - total),
                            limit);
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(entry.getValue(), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                long total = requireWithinLimit(containerBase(24L, 8L, length), limit);
                for (int i = 0; i < length; i++) {
                    total = addWithinLimit(total,
                            estimateSharedTransportBytes(
                                    Array.get(value, i), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long estimateSharedMailboxBytes(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {
        requireGraphDepth(depth);
        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return scalar;
        if (value instanceof Shared<?>) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof OresMutex.Shared<?>) return 64L;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot cross actor boundaries");
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (value instanceof ActorRuntime.Recipient<?>) return 48L;
        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(item, seen, depth + 1));
            }
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(item, seen, depth + 1));
            }
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(entry.getKey(), seen, depth + 1));
                bytes = Math.addExact(bytes, estimateSharedMailboxBytes(entry.getValue(), seen, depth + 1));
            }
            return bytes;
        }
        return 64L;
    }

    /**
     * Validates and estimates a private-actor message before allocating its
     * isolation copy. The walk short-circuits as soon as the destination or
     * parent-runtime budget cannot admit the logical graph.
     */
    private static long estimatePrivateTransportBytes(
            Object value,
            IdentityHashMap<Object, Boolean> visiting,
            int depth,
            long limit) {
        requireGraphDepth(depth);
        if (limit < 0) throw new IllegalStateException("message exceeds remaining actor memory");

        long scalar = scalarLogicalBytes(value);
        if (scalar >= 0) return requireWithinLimit(scalar, limit);

        if (value instanceof ActorRuntime.SyncCell<?>) {
            throw new IllegalArgumentException("private actors cannot receive shared SyncCell values");
        }
        if (value instanceof Shared<?> shared) {
            return estimatePrivateTransportBytes(shared.value(), visiting, depth + 1, limit);
        }
        if (value instanceof ActorRuntime.ActorRef<?>) return requireWithinLimit(48L, limit);
        if (value instanceof ActorRuntime.Recipient<?>) return requireWithinLimit(48L, limit);

        if (visiting.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("cyclic values cannot cross private actor boundaries");
        }
        try {
            if (value instanceof List<?> list) {
                long total = requireWithinLimit(containerBase(24L, 8L, list.size()), limit);
                for (Object item : list) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Set<?> set) {
                long total = requireWithinLimit(containerBase(24L, 16L, set.size()), limit);
                for (Object item : set) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(item, visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value instanceof Map<?, ?> map) {
                long total = requireWithinLimit(containerBase(24L, 32L, map.size()), limit);
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(entry.getKey(), visiting, depth + 1, limit - total),
                            limit);
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(entry.getValue(), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                long total = requireWithinLimit(containerBase(24L, 8L, length), limit);
                for (int i = 0; i < length; i++) {
                    total = addWithinLimit(total,
                            estimatePrivateTransportBytes(
                                    Array.get(value, i), visiting, depth + 1, limit - total),
                            limit);
                }
                return total;
            }
            throw new IllegalArgumentException("value of type " + value.getClass().getName()
                    + " is not Sendable; mutable host objects cannot cross actor boundaries");
        } finally {
            visiting.remove(value);
        }
    }

    private static long scalarLogicalBytes(Object value) {
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        return -1L;
    }

    private static long containerBase(long header, long perEntry, int count) {
        try {
            return Math.addExact(header, Math.multiplyExact(perEntry, (long) count));
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("actor message size accounting overflow");
        }
    }

    private static long requireWithinLimit(long bytes, long limit) {
        if (bytes > limit) {
            throw new IllegalStateException(
                    "message requires at least " + bytes + " bytes but only " + limit + " remain");
        }
        return bytes;
    }

    private static long addWithinLimit(long left, long right, long limit) {
        long total;
        try {
            total = Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new IllegalStateException("actor message size accounting overflow");
        }
        return requireWithinLimit(total, limit);
    }

    /**
     * Conservative language-level footprint estimate. This is a quota metric,
     * not a promise about HotSpot/Graal object layout.
     */
    private static long estimateFrozenBytes(Object value) {
        return estimateFrozenBytes(value, new IdentityHashMap<>(), 0);
    }

    private static long estimateFrozenBytes(
            Object value,
            IdentityHashMap<Object, Boolean> seen,
            int depth) {
        requireGraphDepth(depth);
        if (value == null) return 8L;
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Character || value instanceof Integer || value instanceof Float) return 16L;
        if (value instanceof Long || value instanceof Double) return 24L;
        if (value instanceof BigInteger integer) return 32L + integer.toByteArray().length;
        if (value instanceof BigDecimal decimal) return 48L + decimal.unscaledValue().toByteArray().length;
        if (value instanceof String string) return 40L + (long) string.length() * 2L;
        if (value instanceof UUID || value instanceof ActorId) return 40L;
        if (value instanceof Enum<?>) return 24L;
        if (value instanceof ActorRuntime.ActorRef<?>) return 48L;
        if (value instanceof ActorRuntime.Recipient<?>) return 48L;
        if (value instanceof ActorRuntime.SyncCell<?>) return 64L;
        if (value instanceof OresMutex.Shared<?>) return 64L;
        if (value instanceof OresMutex.Local<?> || value instanceof OresMutex.Guard<?>) {
            throw new IllegalArgumentException("actor-local mutex state cannot be frozen");
        }
        if (value instanceof Shared<?> shared) return estimateFrozenBytes(shared.value(), seen, depth + 1);

        if (seen.put(value, Boolean.TRUE) != null) return 0L;

        long bytes = 24L;
        if (value instanceof List<?> list) {
            bytes = Math.addExact(bytes, 8L * list.size());
            for (Object item : list) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen, depth + 1));
            return bytes;
        }
        if (value instanceof Set<?> set) {
            bytes = Math.addExact(bytes, 16L * set.size());
            for (Object item : set) bytes = Math.addExact(bytes, estimateFrozenBytes(item, seen, depth + 1));
            return bytes;
        }
        if (value instanceof Map<?, ?> map) {
            bytes = Math.addExact(bytes, 32L * map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getKey(), seen, depth + 1));
                bytes = Math.addExact(bytes, estimateFrozenBytes(entry.getValue(), seen, depth + 1));
            }
            return bytes;
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            bytes = Math.addExact(bytes, 8L * length);
            for (int i = 0; i < length; i++) {
                bytes = Math.addExact(bytes, estimateFrozenBytes(Array.get(value, i), seen, depth + 1));
            }
            return bytes;
        }
        return 64L;
    }

    private static boolean isScalar(Object value) {
        return value == null || value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ActorId;
    }

    @Override
    public void close() {
        requireSupervisorContext("close an ActorRuntime");

        final boolean firstClose;
        final List<ActorCell<?>> snapshot;
        synchronized (runtimeLifecycleLock) {
            firstClose = closed.compareAndSet(false, true);
            snapshot = List.copyOf(actors.values());
        }
        for (ActorCell<?> cell : snapshot) cell.stopFromRuntimeClose();
        for (RootTask<?> rootTask : List.copyOf(rootTasks)) {
            rootTask.cancelFromRuntimeClose();
        }

        if (firstClose && ownsDispatcherGroup) {
            // Dedicated/test runtimes own their physical carriers. Production
            // OresContext runtimes share PROCESS_DISPATCHERS and must never
            // shut down carriers that service another live generation.
            dispatcherGroup.shutdownNow();
        }

        long deadline = System.nanoTime() + CLOSE_WAIT_NANOS;
        boolean interrupted = false;
        List<ActorId> stillRunning = new ArrayList<>();
        for (ActorCell<?> cell : snapshot) {
            long remaining = deadline - System.nanoTime();
            if (remaining > 0) {
                try {
                    cell.awaitFinalized(remaining);
                } catch (InterruptedException waitInterrupted) {
                    interrupted = true;
                    break;
                }
            }
            if (!cell.finalized()) stillRunning.add(cell.ref.id());
        }

        boolean rootStillRunning = activeRootTasks.get() != 0;
        if (!interrupted && rootStillRunning) {
            synchronized (runtimeLifecycleLock) {
                while (activeRootTasks.get() != 0) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    try {
                        long millis = Math.max(
                                1L,
                                TimeUnit.NANOSECONDS.toMillis(remaining));
                        runtimeLifecycleLock.wait(millis);
                    } catch (InterruptedException waitInterrupted) {
                        interrupted = true;
                        break;
                    }
                }
                rootStillRunning = activeRootTasks.get() != 0;
            }
        }

        if (interrupted) Thread.currentThread().interrupt();
        if (!stillRunning.isEmpty() || rootStillRunning || interrupted) {
            if (interrupted) {
                for (ActorCell<?> cell : snapshot) {
                    if (!cell.finalized() && !stillRunning.contains(cell.ref.id())) {
                        stillRunning.add(cell.ref.id());
                    }
                }
            }
            // Do not tear shared state out from under guest code that failed to
            // quiesce. A later close() may retry after the offending turn exits.
            throw new IllegalStateException(
                    "ActorRuntime close did not observe full guest termination: "
                            + stillRunning.size() + " actor(s), "
                            + activeRootTasks.get() + " root task(s) still running");
        }

        for (SyncCell<?> cell : List.copyOf(syncCells)) cell.invalidateFromRuntime();
        syncCells.clear();
        for (Shared<?> shared : List.copyOf(sharedValues)) shared.closeFromRuntime();
        sharedValues.clear();
        if (sharedMemoryBytes.get() != 0L) {
            throw new IllegalStateException(
                    "ActorRuntime close leaked shared actor-memory reservations: "
                            + sharedMemoryBytes.get());
        }

        actors.clear();
        actorCount.set(0);
        privateActorCount.set(0);
        sharedActorCount.set(0);
        untrustedActorCount.set(0);
    }

    private NativeCarrierExecutor dispatcherFor(ActorKind kind) {
        return switch (kind) {
            case PRIVATE -> privateDispatcher;
            case SHARED -> sharedDispatcher;
            case UNTRUSTED -> untrustedDispatcher;
        };
    }

    private AtomicInteger compensationCounter(ActorKind kind) {
        return switch (kind) {
            case PRIVATE -> privateCompensatingThreads;
            case SHARED -> sharedCompensatingThreads;
            case UNTRUSTED -> untrustedCompensatingThreads;
        };
    }

    private AtomicLong overrunCounter(ActorKind kind) {
        return switch (kind) {
            case PRIVATE -> privateOverrunTurns;
            case SHARED -> sharedOverrunTurns;
            case UNTRUSTED -> untrustedOverrunTurns;
        };
    }

    private AtomicLong rejectionCounter(ActorKind kind) {
        return switch (kind) {
            case PRIVATE -> privateRejectedTurns;
            case SHARED -> sharedRejectedTurns;
            case UNTRUSTED -> untrustedRejectedTurns;
        };
    }

    private boolean claimCompensatingThread(ActorKind kind) {
        int limit = dispatcherConfig.maxCompensatingThreads();
        if (limit == 0) return false;
        AtomicInteger counter = compensationCounter(kind);
        while (true) {
            int current = counter.get();
            if (current >= limit) return false;
            if (!counter.compareAndSet(current, current + 1)) continue;

            NativeCarrierExecutor executor = dispatcherFor(kind);
            synchronized (executor) {
                int base = dispatcherConfig.parallelismFor(kind);
                int target = Math.min(
                        executor.getMaximumPoolSize(),
                        Math.max(base, executor.getCorePoolSize()) + 1);
                executor.setCorePoolSize(target);
                executor.prestartCoreThread();
            }
            return true;
        }
    }

    private void releaseCompensatingThread(ActorKind kind) {
        AtomicInteger counter = compensationCounter(kind);
        int remaining = counter.decrementAndGet();
        if (remaining < 0) {
            counter.incrementAndGet();
            throw new IllegalStateException("dispatcher compensation accounting underflow for " + kind);
        }
        NativeCarrierExecutor executor = dispatcherFor(kind);
        synchronized (executor) {
            int base = dispatcherConfig.parallelismFor(kind);
            int target = Math.max(base, base + remaining);
            if (executor.getCorePoolSize() > target) executor.setCorePoolSize(target);
        }
    }

    private static ScheduledThreadPoolExecutor newUntrustedWatchdog(ThreadFactory threadFactory) {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private static NativeCarrierExecutor newDispatcher(
            int parallelism,
            int readyQueueCapacity,
            int maxCompensatingThreads,
            String threadPrefix) {
        int maxThreads;
        try {
            maxThreads = Math.addExact(parallelism, maxCompensatingThreads);
        } catch (ArithmeticException overflow) {
            maxThreads = Integer.MAX_VALUE;
        }
        return new NativeCarrierExecutor(
                parallelism,
                maxThreads,
                readyQueueCapacity,
                threadPrefix);
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger next = new AtomicInteger();
        return task -> {
            Thread thread = new Thread(task, prefix + next.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    private final class ActorCell<M> {
        private final ActorRef<M> ref;
        private final ActorKind kind;
        private final IsolatePolicy policy;
        private final BehaviorFactory<M> behaviorFactory;
        private final boolean trustedFactory;
        private final BlockingQueue<MessageEnvelope> mailbox;
        private final ActorMemorySlice memorySlice;
        private final UntrustedActorLimits untrustedLimits;
        private final HttpTransportLimits httpLimits;
        private final HttpRequestCapability httpRequest;
        private final HttpResponseCapability httpResponse;
        private final HttpTransportLease httpTransportLease;
        private final AtomicLong fuelRemaining = new AtomicLong(Long.MAX_VALUE);
        private final long createdNanos;
        private final Duration hardLifetime;
        private final long deadlineNanos;
        private volatile Thread activeCarrier;
        private volatile int activeCarrierSlot = -1;
        private volatile long activeCarrierCpuStartNanos;
        private volatile ScheduledFuture<?> lifetimeFuture;
        private volatile ScheduledFuture<?> messageDeadlineFuture;
        private final AtomicLong messageEpoch = new AtomicLong();
        private volatile long activeMessageEpoch;
        private final AtomicBoolean compensationClaimed = new AtomicBoolean();
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicInteger queuedMessages = new AtomicInteger();
        private final AtomicLong sharedMailboxBytes = new AtomicLong();
        private final Object lifecycleLock = new Object();
        private final Object executionDomain = new Object();
        private int activeTurns;
        private boolean finalized;
        private Behavior<M> behavior;

        private ActorCell(
                ActorRef<M> ref,
                ActorKind kind,
                IsolatePolicy policy,
                BehaviorFactory<M> behaviorFactory,
                boolean trustedFactory,
                UntrustedActorLimits untrustedLimits,
                HttpTransportLimits httpLimits,
                HttpRequestTransport requestTransport,
                HttpResponseTransport responseTransport,
                HttpTransportLease httpTransportLease) {
            this.ref = ref;
            this.kind = kind;
            this.policy = policy;
            this.behaviorFactory = behaviorFactory;
            this.trustedFactory = trustedFactory;
            this.mailbox = new LinkedBlockingQueue<>(policy.maxMailboxMessages());
            this.memorySlice = kind.memoryIsolated()
                    ? new ActorMemorySlice(ref.id(), policy.maxHeapBytes())
                    : null;
            this.untrustedLimits = untrustedLimits;
            this.httpLimits = httpLimits;
            this.createdNanos = System.nanoTime();
            if (kind == ActorKind.UNTRUSTED) {
                if (untrustedLimits == null) {
                    throw new IllegalArgumentException("UNTRUSTED actor requires limits");
                }
                this.hardLifetime = policy.maxWallTime().compareTo(untrustedLimits.maxLifetime()) <= 0
                        ? policy.maxWallTime()
                        : untrustedLimits.maxLifetime();
                long lifetimeNanos = hardLifetime.toNanos();
                this.deadlineNanos = lifetimeNanos >= Long.MAX_VALUE - createdNanos
                        ? Long.MAX_VALUE
                        : createdNanos + lifetimeNanos;
                this.fuelRemaining.set(untrustedLimits.fuelPerTurn());
            } else {
                this.hardLifetime = policy.maxWallTime();
                this.deadlineNanos = Long.MAX_VALUE;
            }

            if (requestTransport != null || responseTransport != null) {
                if (!kind.memoryIsolated()) {
                    throw new IllegalArgumentException(
                            "HTTP transport capabilities require a memory-isolated actor");
                }
                HttpTransportLimits limits = Objects.requireNonNull(httpLimits);
                this.httpTransportLease = Objects.requireNonNull(httpTransportLease);
                this.httpRequest = requestTransport == null
                        ? null
                        : new HttpRequestCapability(
                                ref.id(),
                                requestTransport,
                                this.httpTransportLease,
                                limits.maxRequestBytes());
                this.httpResponse = responseTransport == null
                        ? null
                        : new HttpResponseCapability(
                                ref.id(),
                                responseTransport,
                                this.httpTransportLease,
                                limits.maxResponseBytes());
            } else {
                this.httpRequest = null;
                this.httpResponse = null;
                this.httpTransportLease = null;
            }
        }

        private void armLifetimeLimit() {
            if (kind != ActorKind.UNTRUSTED) return;
            long delay = Math.max(1L, deadlineNanos - System.nanoTime());
            lifetimeFuture = untrustedWatchdog.schedule(
                    this::expireUntrusted,
                    delay,
                    TimeUnit.NANOSECONDS);
        }

        private void expireUntrusted() {
            Thread carrier;
            synchronized (lifecycleLock) {
                if (finalized || stopped.get()) return;
                ActorLifetimeExceededException failure = new ActorLifetimeExceededException(
                        "untrusted actor exceeded hard lifetime of "
                                + hardLifetime.toSeconds() + " seconds");
                ref.terminationCause.compareAndSet(null, failure);
                stopped.set(true);
                drainMailboxReservations();
                carrier = activeCarrier;
                finalizeStopLocked();
            }
            if (carrier != null) carrier.interrupt();
        }

        private void beginMessageBudget() {
            if (kind != ActorKind.UNTRUSTED) return;
            fuelRemaining.set(untrustedLimits.fuelPerTurn());
            checkUntrustedBudget(0);
        }

        private void checkUntrustedBudget(long cost) {
            if (kind != ActorKind.UNTRUSTED) return;
            if (cost < 0) throw new IllegalArgumentException("budget cost cannot be negative");
            if (System.nanoTime() - deadlineNanos >= 0) {
                throw new ActorLifetimeExceededException(
                        "untrusted actor exceeded hard lifetime of "
                                + untrustedLimits.maxLifetime().toSeconds() + " seconds");
            }
            long remaining = cost == 0 ? fuelRemaining.get() : fuelRemaining.addAndGet(-cost);
            if (remaining < 0) {
                throw new ActorBudgetExceededException(
                        "untrusted actor exhausted per-turn execution fuel "
                                + untrustedLimits.fuelPerTurn());
            }
        }

        private void throwIfControlStopped() {
            if (!stopped.get()) return;
            Throwable cause = ref.terminationCause.get();
            if (cause instanceof ActorBudgetExceededException budget) throw budget;
            if (cause instanceof ActorLifetimeExceededException lifetime) throw lifetime;
            if (cause instanceof ActorTurnExceededException turn) throw turn;
            throw new CancellationException("actor execution stopped");
        }

        private long beginCpuAccounting() {
            int slot = NativeCarrierExecutor.currentCarrierSlot();
            long started = NativeCarrierExecutor.currentCarrierCpuTimeNanos();
            activeCarrierSlot = slot;
            activeCarrierCpuStartNanos = started;
            return started;
        }

        private void endCpuAccounting(long started) {
            long finished = NativeCarrierExecutor.currentCarrierCpuTimeNanos();
            if (started > 0L && finished >= started) {
                ref.cpuTimeNanos.addAndGet(finished - started);
            }
            activeCarrierCpuStartNanos = 0L;
            activeCarrierSlot = -1;
        }

        private long activeCpuNanos() {
            int slot = activeCarrierSlot;
            long started = activeCarrierCpuStartNanos;
            if (slot < 0 || started <= 0L) return 0L;
            long current = dispatcherFor(kind).carrierCpuTimeNanos(slot);
            return current >= started ? current - started : 0L;
        }

        private void checkCpuBudget() {
            long limit = dispatcherConfig.maxMessageNanos();
            if (limit == Long.MAX_VALUE) return;
            long consumed = activeCpuNanos();
            if (consumed >= limit) {
                throw new ActorTurnExceededException(
                        ref.id(),
                        kind,
                        "actor " + ref.id() + " (" + kind
                                + ") exceeded native carrier CPU budget of "
                                + TimeUnit.NANOSECONDS.toMillis(limit) + " ms");
            }
        }

        private long armMessageDeadline(String phase) {
            long maxNanos = dispatcherConfig.maxMessageNanos();
            if (maxNanos == Long.MAX_VALUE) return 0L;
            long epoch = messageEpoch.incrementAndGet();
            Thread carrier = Thread.currentThread();
            synchronized (lifecycleLock) {
                if (stopped.get() || finalized) return 0L;
                activeMessageEpoch = epoch;
                messageDeadlineFuture = messageWatchdog.schedule(
                        () -> expireMessageTurn(epoch, carrier, phase),
                        maxNanos,
                        TimeUnit.NANOSECONDS);
            }
            return epoch;
        }

        private void expireMessageTurn(long epoch, Thread carrier, String phase) {
            boolean interrupt = false;
            synchronized (lifecycleLock) {
                if (finalized
                        || stopped.get()
                        || activeMessageEpoch != epoch
                        || activeCarrier != carrier) {
                    return;
                }
                long cpuNanos = activeCpuNanos();
                ActorTurnExceededException failure = new ActorTurnExceededException(
                        ref.id(),
                        kind,
                        "actor " + ref.id() + " (" + kind + ") exceeded hard "
                                + phase + " quantum of "
                                + TimeUnit.NANOSECONDS.toMillis(dispatcherConfig.maxMessageNanos())
                                + " ms; native_cpu_ms="
                                + TimeUnit.NANOSECONDS.toMillis(cpuNanos));
                ref.terminationCause.compareAndSet(null, failure);
                stopped.set(true);
                drainMailboxReservations();
                overrunCounter(kind).incrementAndGet();
                if (compensationClaimed.compareAndSet(false, true)
                        && !claimCompensatingThread(kind)) {
                    compensationClaimed.set(false);
                }
                interrupt = true;
            }
            if (interrupt) carrier.interrupt();
        }

        private void disarmMessageDeadline(long epoch) {
            if (epoch == 0L) return;
            ScheduledFuture<?> deadline = null;
            synchronized (lifecycleLock) {
                if (activeMessageEpoch == epoch) {
                    activeMessageEpoch = 0L;
                    deadline = messageDeadlineFuture;
                    messageDeadlineFuture = null;
                }
            }
            if (deadline != null) deadline.cancel(false);
            if (compensationClaimed.compareAndSet(true, false)) {
                releaseCompensatingThread(kind);
            }
        }

        private Duration remainingLifetime() {
            if (kind != ActorKind.UNTRUSTED) return policy.maxWallTime();
            long remaining = Math.max(0L, deadlineNanos - System.nanoTime());
            return Duration.ofNanos(remaining);
        }

        private boolean reserveMailboxSlot() {
            while (true) {
                int current = queuedMessages.get();
                if (current >= policy.maxMailboxMessages()) return false;
                if (queuedMessages.compareAndSet(current, current + 1)) return true;
            }
        }

        private void releaseMailboxSlot() {
            int remaining = queuedMessages.decrementAndGet();
            if (remaining < 0) {
                queuedMessages.incrementAndGet();
                throw new IllegalStateException(
                        "actor mailbox accounting underflow for " + ref.id());
            }
        }

        private boolean beginTurn() {
            synchronized (lifecycleLock) {
                if (stopped.get() || finalized) return false;
                activeTurns++;
                return true;
            }
        }

        private void endTurn() {
            synchronized (lifecycleLock) {
                if (activeTurns <= 0) {
                    throw new IllegalStateException("actor active-turn accounting underflow for " + ref.id());
                }
                activeTurns--;
                if (stopped.get() && activeTurns == 0) finalizeStopLocked();
                lifecycleLock.notifyAll();
            }
        }

        private void finalizeStopLocked() {
            if (finalized || activeTurns != 0) return;
            finalized = true;
            ScheduledFuture<?> timer = lifetimeFuture;
            lifetimeFuture = null;
            if (timer != null) timer.cancel(false);
            ScheduledFuture<?> messageTimer = messageDeadlineFuture;
            messageDeadlineFuture = null;
            activeMessageEpoch = 0L;
            if (messageTimer != null) messageTimer.cancel(false);
            drainMailboxReservations();
            behavior = null;
            if (httpRequest != null) {
                httpRequest.cancelFromRuntime(ref.terminationCause.get());
            }
            if (httpResponse != null) {
                httpResponse.abortFromRuntime(ref.terminationCause.get());
            }
            if (httpTransportLease != null) {
                httpTransportLease.revokeFromActor(ref.id());
            }
            if (memorySlice != null) {
                // ActorMemorySlice.close() zeroes owned direct-memory blocks,
                // releases every reservation, and makes leaked handles fail.
                memorySlice.close();
            }
            try {
                actorExitHook.accept(executionDomain);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                throw fatal;
            } catch (Throwable ignored) {
                // Actor termination must still complete. Runtime cleanup hooks
                // are best-effort and retryable by the process collector.
            }
            unregisterActor(this);
            lifecycleLock.notifyAll();
        }

        private boolean finalized() {
            synchronized (lifecycleLock) {
                return finalized;
            }
        }

        private void awaitFinalized(long remainingNanos) throws InterruptedException {
            long deadline = System.nanoTime() + Math.max(0L, remainingNanos);
            synchronized (lifecycleLock) {
                while (!finalized) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) return;
                    long millis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remaining));
                    lifecycleLock.wait(millis);
                }
            }
        }

        private void reserveSharedMailbox(long bytes) {
            if (bytes < 0) throw new IllegalArgumentException("shared mailbox reservation cannot be negative");
            synchronized (lifecycleLock) {
                if (closed.get()) throw new IllegalStateException("actor runtime is closed");
                if (stopped.get()) throw terminated(ref);
                long current = sharedMailboxBytes.get();
                long next;
                try {
                    next = Math.addExact(current, bytes);
                } catch (ArithmeticException overflow) {
                    throw new IllegalStateException("shared actor mailbox memory accounting overflow");
                }
                if (next > policy.maxHeapBytes()) {
                    throw new IllegalStateException("shared actor mailbox memory limit exceeded for " + ref.id()
                            + ": requested=" + bytes + " used=" + current + " limit=" + policy.maxHeapBytes());
                }
                reserveSharedRuntimeBytes(bytes, "shared actor mailbox");
                sharedMailboxBytes.set(next);
            }
        }

        private void releaseSharedMailbox(long bytes) {
            if (bytes == 0) return;
            synchronized (lifecycleLock) {
                long current = sharedMailboxBytes.get();
                long next = Math.max(0L, current - bytes);
                sharedMailboxBytes.set(next);
                releaseSharedRuntimeBytes(bytes);
            }
        }

        private void schedule() {
            if (stopped.get() || closed.get()) return;
            if (!scheduled.compareAndSet(false, true)) return;
            try {
                dispatcherFor(kind).execute(this::runBatch);
            } catch (RejectedExecutionException rejected) {
                rejectionCounter(kind).incrementAndGet();
                scheduled.set(false);
                stop();
                if (!closed.get()) throw rejected;
            }
        }

        private void runBatch() {
            ACTOR_CARRIER.set(Boolean.TRUE);
            boolean turnActive = beginTurn();
            long entryDeadline = 0L;
            try {
                if (!turnActive) return;

                // Cover the entire runtime/Truffle admission path, not only the
                // guest callback. In adversarial contexts TurnExecutor may wait
                // for a context-entry lock; that wait must still be bounded or
                // one hostile turn can strand every untrusted carrier behind it.
                activeCarrier = Thread.currentThread();
                entryDeadline = armMessageDeadline("runtime context entry");
                final long armedEntryDeadline = entryDeadline;
                turnExecutor.execute(() -> {
                    disarmMessageDeadline(armedEntryDeadline);
                    throwIfControlStopped();
                    runBatchEntered();
                });
            } catch (Throwable failure) {
                fail(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                disarmMessageDeadline(entryDeadline);
                activeCarrier = null;
                if (turnActive) endTurn();
                scheduled.set(false);
                if (!stopped.get() && !closed.get() && !mailbox.isEmpty()) {
                    schedule();
                }
                ACTOR_CARRIER.remove();
            }
        }

        @SuppressWarnings("unchecked")
        private void runBatchEntered() {
            currentActor.set(this);
            CURRENT_ACTOR_EXECUTION.set(new ActorExecutionContext(
                    ActorRuntime.this, ref.id(), kind, policy, executionDomain));
            try {
                ActorContext<M> context = new ActorContext<>() {
                    @Override public ActorRef<M> self() { return ref; }
                    @Override public ActorRuntime runtime() { return ActorRuntime.this; }
                    @Override public IsolatePolicy policy() { return policy; }
                    @Override public ActorKind kind() { return kind; }
                    @Override public Optional<ActorMemorySlice> privateMemory() {
                        return Optional.ofNullable(memorySlice);
                    }
                    @Override public Optional<HttpRequestCapability> httpRequest() {
                        return Optional.ofNullable(httpRequest);
                    }
                    @Override public Optional<HttpResponseCapability> httpResponse() {
                        return Optional.ofNullable(httpResponse);
                    }
                    @Override public void checkpoint() {
                        ActorRuntime.this.schedulerSafepoint();
                    }
                    @Override public long fuelRemaining() {
                        return kind == ActorKind.UNTRUSTED
                                ? ActorCell.this.fuelRemaining.get()
                                : Long.MAX_VALUE;
                    }
                    @Override public long cpuTimeNanos() {
                        long completed = ref.cpuTimeNanos.get();
                        long active = ActorCell.this.activeCpuNanos();
                        try {
                            return Math.addExact(completed, active);
                        } catch (ArithmeticException overflow) {
                            return Long.MAX_VALUE;
                        }
                    }
                    @Override public Duration remainingLifetime() {
                        return ActorCell.this.remainingLifetime();
                    }
                };

                if (behavior == null) {
                    beginMessageBudget();
                    long factoryCpuStart = beginCpuAccounting();
                    long factoryDeadline = armMessageDeadline("behavior initialization");
                    try {
                        Behavior<M> created = Objects.requireNonNull(
                                behaviorFactory.create(context),
                                "actor behaviorFactory returned null");
                        if (kind.memoryIsolated() && !trustedFactory) {
                            validatePrivateBehaviorState(ref.id(), created);
                        }
                        behavior = created;
                    } finally {
                        disarmMessageDeadline(factoryDeadline);
                        endCpuAccounting(factoryCpuStart);
                    }
                }

                int processed = 0;
                int turnThroughput = dispatcherConfig.throughputFor(kind);
                long batchStartedNanos = System.nanoTime();
                while (processed < turnThroughput && !stopped.get()) {
                    MessageEnvelope envelope = mailbox.poll();
                    if (envelope == null) break;
                    releaseMailboxSlot();
                    try (envelope) {
                        beginMessageBudget();
                        long messageCpuStart = beginCpuAccounting();
                        long messageDeadline = armMessageDeadline("mailbox message");
                        try {
                            behavior.onMessage((M) envelope.value(), context);
                            if (kind.memoryIsolated() && !trustedFactory) {
                                validatePrivateBehaviorState(ref.id(), behavior);
                            }
                        } finally {
                            disarmMessageDeadline(messageDeadline);
                            endCpuAccounting(messageCpuStart);
                        }
                    }
                    processed++;

                    // This is deliberately checked between complete mailbox
                    // messages. We never asynchronously tear through an
                    // arbitrary trusted Java stack. If the actor remains hot,
                    // schedule() below places it at the tail of the FIFO ready
                    // queue so peer actors get a turn.
                    if (kind != ActorKind.UNTRUSTED
                            && System.nanoTime() - batchStartedNanos >= dispatcherConfig.maxBatchNanos()) {
                        break;
                    }
                }
            } catch (Throwable failure) {
                // Fail-stop supervision for ordinary actor failures. Fatal VM
                // errors are cleaned up and then rethrown rather than swallowed.
                fail(failure);
                if (failure instanceof VirtualMachineError fatal) throw fatal;
                if (failure instanceof ThreadDeath fatal) throw fatal;
                if (failure instanceof LinkageError fatal) throw fatal;
            } finally {
                CURRENT_ACTOR_EXECUTION.remove();
                currentActor.remove();
                // Runtime watchdog interrupts are control-plane signals and
                // must never leak into the next actor using this pooled carrier.
                Thread.interrupted();
            }
        }

        private void drainMailboxReservations() {
            MessageEnvelope envelope;
            while ((envelope = mailbox.poll()) != null) {
                releaseMailboxSlot();
                envelope.close();
            }
        }

        private void fail(Throwable failure) {
            synchronized (lifecycleLock) {
                ref.terminationCause.compareAndSet(null, failure);
                stopped.set(true);
                drainMailboxReservations();
                finalizeStopLocked();
            }
        }

        private void stop() {
            synchronized (lifecycleLock) {
                stopped.set(true);
                drainMailboxReservations();
                finalizeStopLocked();
            }
        }

        private void stopFromRuntimeClose() {
            Thread carrier;
            synchronized (lifecycleLock) {
                stopped.set(true);
                drainMailboxReservations();
                carrier = activeCarrier;
                finalizeStopLocked();
            }
            // Runtime/context teardown owns this actor and may interrupt its
            // current carrier. The shared process executor itself remains alive.
            if (carrier != null) carrier.interrupt();
        }
    }
}
