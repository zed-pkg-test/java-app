package dev.oreslang.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial host-only accounting regression. Reflection never crosses into
 * a guest actor, and no private accounting API is exposed to Oreslang.
 */
@Timeout(30)
final class ActorSharedRuntimeAccountingTest {
    private static Method accountingMethod(String name, Class<?>... args)
            throws ReflectiveOperationException {
        Method method = ActorRuntime.class.getDeclaredMethod(name, args);
        method.setAccessible(true);
        return method;
    }

    @Test
    void invalidSharedReleaseCannotModifyOutstandingRuntimeReservations() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = accountingMethod(
                    "reserveSharedRuntimeBytes", long.class, String.class);
            Method release = accountingMethod("releaseSharedRuntimeBytes", long.class);

            long initial = runtime.sharedMemoryBytes();
            reserve.invoke(runtime, 64L, "shared release underflow regression");
            assertEquals(initial + 64L, runtime.sharedMemoryBytes());

            InvocationTargetException oversized = assertThrows(
                    InvocationTargetException.class,
                    () -> release.invoke(runtime, 65L));
            assertInstanceOf(IllegalStateException.class, oversized.getCause());
            assertTrue(oversized.getCause().getMessage().contains("underflow"));
            assertEquals(initial + 64L, runtime.sharedMemoryBytes(),
                    "invalid release must not zero a different outstanding reservation");

            InvocationTargetException negative = assertThrows(
                    InvocationTargetException.class,
                    () -> release.invoke(runtime, -1L));
            assertInstanceOf(IllegalArgumentException.class, negative.getCause());
            assertEquals(initial + 64L, runtime.sharedMemoryBytes());

            release.invoke(runtime, 64L);
            assertEquals(initial, runtime.sharedMemoryBytes());

            InvocationTargetException duplicate = assertThrows(
                    InvocationTargetException.class,
                    () -> release.invoke(runtime, 64L));
            assertInstanceOf(IllegalStateException.class, duplicate.getCause());
            assertEquals(initial, runtime.sharedMemoryBytes());
        }
    }

    @Test
    void concurrentBalancedReservationsDoNotLoseRuntimeAccounting() throws Exception {
        try (ActorRuntime runtime = new ActorRuntime()) {
            Method reserve = accountingMethod(
                    "reserveSharedRuntimeBytes", long.class, String.class);
            Method release = accountingMethod("releaseSharedRuntimeBytes", long.class);
            var pool = Executors.newFixedThreadPool(6);
            try {
                ArrayList<Future<?>> tasks = new ArrayList<>();
                for (int carrier = 0; carrier < 6; carrier++) {
                    tasks.add(pool.submit(() -> {
                        try {
                            for (int i = 0; i < 500; i++) {
                                reserve.invoke(runtime, 1L, "concurrent accounting");
                                release.invoke(runtime, 1L);
                            }
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError("balanced reservation failed", e);
                        }
                    }));
                }
                for (Future<?> task : tasks) task.get(10, TimeUnit.SECONDS);
                assertEquals(0L, runtime.sharedMemoryBytes(),
                        "concurrent reserve/release pairs must fully balance");
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
