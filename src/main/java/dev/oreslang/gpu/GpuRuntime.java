package dev.oreslang.gpu;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public process-wide GPU backend SPI for Oreslang embeddings.
 *
 * The interpreter never evaluates a gpu callable on the CPU as a fallback.
 * A backend must explicitly accept and execute each dispatched invocation.
 */
public final class GpuRuntime {
    private static final AtomicReference<Backend> PROCESS_BACKEND = new AtomicReference<>();
    private static final int MAX_AFFINITY_KEY_LENGTH = 256;

    public enum CallableKind { FNC, ROUTINE }

    /** Logical execution origin. Physical GPU placement remains a backend mapping decision. */
    public enum ExecutionClass { HOST, ACTOR }

    /**
     * Regent-inspired logical placement request. This intentionally does not expose a
     * physical "GPU core" number: GPU schedulers own SM/CU/warp placement. A backend
     * may honor a device ordinal and/or a logical partition while preserving affinity.
     */
    public record Placement(Integer deviceOrdinal, Integer partitionOrdinal, String affinityKey) {
        public Placement {
            if (deviceOrdinal != null && deviceOrdinal < 0) throw new IllegalArgumentException("GPU device ordinal must be non-negative");
            if (partitionOrdinal != null && partitionOrdinal < 0) throw new IllegalArgumentException("GPU partition ordinal must be non-negative");
            if (affinityKey != null && affinityKey.isBlank()) throw new IllegalArgumentException("GPU affinity key cannot be blank");
            if (affinityKey != null && affinityKey.length() > MAX_AFFINITY_KEY_LENGTH) throw new IllegalArgumentException("GPU affinity key cannot exceed " + MAX_AFFINITY_KEY_LENGTH + " characters");
        }

        public static Placement any() { return new Placement(null, null, null); }
        public static Placement device(int ordinal) { return new Placement(ordinal, null, null); }
        public static Placement partition(int ordinal) { return new Placement(null, ordinal, null); }
        public Placement withAffinity(String key) { return new Placement(deviceOrdinal, partitionOrdinal, key); }
    }

    /** Metadata available to a backend mapper for one dispatch. */
    public record DispatchContext(
            ExecutionClass executionClass,
            UUID actorId,
            String actorKind,
            Placement placement) {
        public DispatchContext {
            Objects.requireNonNull(executionClass, "executionClass");
            placement = placement == null ? Placement.any() : placement;
            if (executionClass == ExecutionClass.HOST) {
                if (actorId != null || actorKind != null) throw new IllegalArgumentException("host GPU dispatch cannot carry actor identity");
            } else {
                Objects.requireNonNull(actorId, "actorId");
                if (actorKind == null || actorKind.isBlank()) throw new IllegalArgumentException("actor GPU dispatch requires actor kind");
            }
        }

        public static DispatchContext host() {
            return new DispatchContext(ExecutionClass.HOST, null, null, Placement.any());
        }

        public static DispatchContext actor(UUID actorId, String actorKind, Placement placement) {
            return new DispatchContext(ExecutionClass.ACTOR, actorId, actorKind, placement);
        }
    }

    /** Marker used by private guest-runtime values that have a safe GPU wire form. */
    public interface Transferable {
        Object freezeForGpu();
    }

    /** Stable public representation for Oreslang complex scalar values. */
    public record ComplexValue(double real, double imaginary) { }

    /** Stable public representation for Oreslang Option values. */
    public record OptionValue(boolean present, Object value) {
        public OptionValue {
            if (present && value == null) throw new IllegalArgumentException("Some cannot carry standalone null");
            if (!present && value != null) throw new IllegalArgumentException("None cannot carry a value");
        }
    }

    /** Backend result for collecting a stream into a concrete device array. */
    public record CollectedArray(Object backendToken, long length) {
        public CollectedArray {
            Objects.requireNonNull(backendToken, "backendToken");
            if (length < 0) throw new IllegalArgumentException("collected GpuArray length cannot be negative");
        }
    }

    public interface Backend {
        String name();

        default boolean supports(Invocation invocation) {
            return true;
        }

        Object invoke(Invocation invocation);

        default Object uploadArray(List<?> values) {
            throw new GpuCapabilityUnavailableException("GPU backend '" + name() + "' does not support GpuArray uploads");
        }

        default List<?> downloadArray(Object backendToken, long length) {
            throw new GpuCapabilityUnavailableException("GPU backend '" + name() + "' does not support GpuArray downloads");
        }

        default Object openStream(Object arrayBackendToken, long length) {
            return arrayBackendToken;
        }

        default CollectedArray collectStream(Object streamBackendToken, long lengthHint) {
            if (lengthHint < 0) {
                throw new GpuCapabilityUnavailableException("GPU backend '" + name()
                        + "' must report a concrete length when collecting an unknown-length GpuStream");
            }
            return new CollectedArray(streamBackendToken, lengthHint);
        }
    }

    /** Opaque GPU-resident array resource. Guest code cannot access backendToken directly. */
    public static final class ArrayHandle {
        private final Backend backend;
        private final Object backendToken;
        private final long length;

        private ArrayHandle(Backend backend, Object backendToken, long length) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.backendToken = Objects.requireNonNull(backendToken, "backendToken");
            if (length < 0) throw new IllegalArgumentException("GpuArray length cannot be negative");
            this.length = length;
        }

        public String backendName() { return backend.name(); }
        public long length() { return length; }

        /** Host/backend SPI only; Oreslang guest code has no reflective host access. */
        public Object backendToken() { return backendToken; }
    }

    /** Opaque GPU-resident sequential stream resource. */
    public static final class StreamHandle {
        private final Backend backend;
        private final Object backendToken;
        private final long lengthHint;

        private StreamHandle(Backend backend, Object backendToken, long lengthHint) {
            this.backend = Objects.requireNonNull(backend, "backend");
            this.backendToken = Objects.requireNonNull(backendToken, "backendToken");
            if (lengthHint < -1) throw new IllegalArgumentException("GpuStream length hint must be -1 or non-negative");
            this.lengthHint = lengthHint;
        }

        public String backendName() { return backend.name(); }
        public long lengthHint() { return lengthHint; }

        /** Host/backend SPI only; Oreslang guest code has no reflective host access. */
        public Object backendToken() { return backendToken; }
    }

    public record Invocation(
            String callable,
            CallableKind callableKind,
            List<?> arguments,
            DispatchContext dispatchContext) {
        public Invocation {
            Objects.requireNonNull(callable, "callable");
            if (callable.isBlank()) throw new IllegalArgumentException("GPU callable cannot be blank");
            Objects.requireNonNull(callableKind, "callableKind");
            Objects.requireNonNull(arguments, "arguments");
            dispatchContext = dispatchContext == null ? DispatchContext.host() : dispatchContext;
            arguments = freezeArguments(arguments);
        }

        public Invocation(String callable, CallableKind callableKind, List<?> arguments) {
            this(callable, callableKind, arguments, DispatchContext.host());
        }
    }

    public static void installBackend(Backend backend) {
        PROCESS_BACKEND.set(Objects.requireNonNull(backend, "backend"));
    }

    public static void clearBackend() {
        PROCESS_BACKEND.set(null);
    }

    public static ArrayHandle adoptArray(Backend backend, Object backendToken, long length) {
        return new ArrayHandle(backend, backendToken, length);
    }

    public static StreamHandle adoptStream(Backend backend, Object backendToken, long lengthHint) {
        return new StreamHandle(backend, backendToken, lengthHint);
    }

    public boolean available() {
        return PROCESS_BACKEND.get() != null;
    }

    public String backendName() {
        Backend backend = PROCESS_BACKEND.get();
        return backend == null ? "unavailable" : backend.name();
    }

    public Object dispatch(String callable, CallableKind callableKind, List<?> arguments) {
        return dispatch(callable, callableKind, arguments, DispatchContext.host());
    }

    public Object dispatchActor(
            String callable,
            CallableKind callableKind,
            List<?> arguments,
            UUID actorId,
            String actorKind,
            Placement placement) {
        return dispatch(callable, callableKind, arguments,
                DispatchContext.actor(actorId, actorKind, placement));
    }

    private Object dispatch(
            String callable,
            CallableKind callableKind,
            List<?> arguments,
            DispatchContext dispatchContext) {
        Invocation invocation = new Invocation(callable, callableKind, arguments, dispatchContext);
        Backend backend = PROCESS_BACKEND.get();
        if (backend == null) {
            throw new GpuUnavailableException("gpu callable '" + callable
                    + "' requires a GPU backend; none is installed and CPU fallback is forbidden");
        }
        validateDeviceOwnership(invocation.arguments(), backend);
        if (!backend.supports(invocation)) {
            throw new GpuCapabilityUnavailableException("GPU backend '" + backend.name()
                    + "' cannot execute gpu callable '" + callable + "'");
        }
        try {
            Object result = freezeValue(backend.invoke(invocation), true);
            validateDeviceOwnership(result, backend);
            return result;
        } catch (GpuException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new GpuExecutionException("GPU backend '" + backend.name()
                    + "' failed while executing '" + callable + "'", failure);
        }
    }

    public ArrayHandle uploadArray(List<?> values) {
        Backend backend = requireBackend("GpuArray.from_cpu()");
        List<?> frozen = freezeArguments(values);
        Object token = backend.uploadArray(frozen);
        if (token == null) throw new GpuExecutionException("GPU backend '" + backend.name() + "' returned null from uploadArray", null);
        return new ArrayHandle(backend, token, frozen.size());
    }

    public List<?> downloadArray(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        Object downloaded = array.backend.downloadArray(array.backendToken, array.length);
        Object frozen = freezeValue(downloaded, false);
        if (!(frozen instanceof List<?> list)) {
            throw new GpuTransferException("GPU backend '" + array.backend.name() + "' downloadArray must return a List");
        }
        return list;
    }

    public StreamHandle stream(ArrayHandle array) {
        Objects.requireNonNull(array, "array");
        Object token = array.backend.openStream(array.backendToken, array.length);
        if (token == null) throw new GpuExecutionException("GPU backend '" + array.backend.name() + "' returned null from openStream", null);
        return new StreamHandle(array.backend, token, array.length);
    }

    public ArrayHandle collect(StreamHandle stream) {
        Objects.requireNonNull(stream, "stream");
        CollectedArray collected = stream.backend.collectStream(stream.backendToken, stream.lengthHint);
        if (collected == null) throw new GpuExecutionException("GPU backend '" + stream.backend.name() + "' returned null from collectStream", null);
        return new ArrayHandle(stream.backend, collected.backendToken(), collected.length());
    }

    public List<?> downloadStream(StreamHandle stream) {
        return downloadArray(collect(stream));
    }

    private Backend requireBackend(String operation) {
        Backend backend = PROCESS_BACKEND.get();
        if (backend == null) {
            throw new GpuUnavailableException(operation + " requires a GPU backend; none is installed and CPU fallback is forbidden");
        }
        return backend;
    }

    private static void validateDeviceOwnership(Object value, Backend expected) {
        if (value == null) return;
        if (value instanceof ArrayHandle array) {
            if (array.backend != expected) {
                throw new GpuTransferException("GpuArray belongs to backend '" + array.backend.name()
                        + "' but callable is executing on backend '" + expected.name() + "'");
            }
            return;
        }
        if (value instanceof StreamHandle stream) {
            if (stream.backend != expected) {
                throw new GpuTransferException("GpuStream belongs to backend '" + stream.backend.name()
                        + "' but callable is executing on backend '" + expected.name() + "'");
            }
            return;
        }
        if (value instanceof OptionValue option) {
            if (option.present()) validateDeviceOwnership(option.value(), expected);
            return;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) validateDeviceOwnership(item, expected);
            return;
        }
        if (value instanceof Set<?> set) {
            for (Object item : set) validateDeviceOwnership(item, expected);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                validateDeviceOwnership(entry.getKey(), expected);
                validateDeviceOwnership(entry.getValue(), expected);
            }
        }
    }

    private static List<?> freezeArguments(List<?> arguments) {
        ArrayList<Object> frozen = new ArrayList<>(arguments.size());
        for (Object argument : arguments) frozen.add(freezeValue(argument, false));
        return List.copyOf(frozen);
    }

    private static Object freezeValue(Object value, boolean allowRootVoidNull) {
        if (value == null) {
            if (allowRootVoidNull) return null;
            throw new GpuTransferException("standalone null cannot cross the Oreslang GPU boundary; use Option");
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal
                || value instanceof Enum<?> || value instanceof UUID || value instanceof ComplexValue
                || value instanceof ArrayHandle || value instanceof StreamHandle) {
            return value;
        }
        if (value instanceof OptionValue option) {
            return option.present()
                    ? new OptionValue(true, freezeValue(option.value(), false))
                    : new OptionValue(false, null);
        }
        if (value instanceof Transferable transferable) {
            Object transferred = transferable.freezeForGpu();
            if (transferred == value) {
                throw new GpuTransferException("GPU transferable values must produce a distinct wire representation");
            }
            return freezeValue(transferred, false);
        }
        if (value instanceof List<?> list) {
            ArrayList<Object> frozen = new ArrayList<>(list.size());
            for (Object item : list) frozen.add(freezeValue(item, false));
            return List.copyOf(frozen);
        }
        if (value instanceof Set<?> set) {
            return set.stream().map(item -> freezeValue(item, false))
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
        if (value instanceof Map<?, ?> map) {
            LinkedHashMap<Object, Object> frozen = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                frozen.put(freezeValue(entry.getKey(), false), freezeValue(entry.getValue(), false));
            }
            return Map.copyOf(frozen);
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            ArrayList<Object> frozen = new ArrayList<>(length);
            for (int i = 0; i < length; i++) frozen.add(freezeValue(Array.get(value, i), false));
            return List.copyOf(frozen);
        }
        throw new GpuTransferException("value of type " + value.getClass().getName()
                + " cannot cross the Oreslang GPU boundary");
    }

    public static class GpuException extends RuntimeException {
        public GpuException(String message) { super(message); }
        public GpuException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuUnavailableException extends GpuException {
        public GpuUnavailableException(String message) { super(message); }
    }

    public static final class GpuCapabilityUnavailableException extends GpuException {
        public GpuCapabilityUnavailableException(String message) { super(message); }
    }

    public static final class GpuExecutionException extends GpuException {
        public GpuExecutionException(String message, Throwable cause) { super(message, cause); }
    }

    public static final class GpuTransferException extends GpuException {
        public GpuTransferException(String message) { super(message); }
    }
}
