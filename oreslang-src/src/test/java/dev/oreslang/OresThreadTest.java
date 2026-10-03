package dev.oreslang;

import dev.oreslang.compiler.OresCompiler;
import dev.oreslang.runtime.ActorRuntime;
import dev.oreslang.runtime.IsolatePolicy;
import dev.oreslang.runtime.OresThread;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

final class OresThreadTest {

    @Test
    void nativeThreadStartsJoinsAndHasDedicatedPthreadIdentity() throws Exception {
        AtomicLong nativeId = new AtomicLong();
        AtomicReference<OresThread> current = new AtomicReference<>();
        AtomicBoolean actorCarrier = new AtomicBoolean(true);

        OresThread thread = new OresThread(() -> {
            current.set(OresThread.currentThread());
            nativeId.set(OresThread.currentThread().nativeThreadId());
            actorCarrier.set(ActorRuntime.isActorCarrierThread());
            threadCpuBurn();
            assertTrue(OresThread.currentThread().cpuTimeNanos() > 0L);
            OresThread.currentThread().setName("ores-test-renamed");
            OresThread.yield();
        }, "ores-test-platform-thread");

        assertFalse(thread.isAlive());
        assertEquals(OresThread.State.NEW, thread.getState());
        thread.start();
        thread.join();

        assertSame(thread, current.get());
        assertTrue(nativeId.get() != 0L);
        assertFalse(actorCarrier.get(), "explicit Thread must not masquerade as an actor carrier");
        assertEquals(OresThread.State.TERMINATED, thread.getState());
        assertFalse(thread.isVirtual());
        assertEquals("ores-test-renamed", thread.getName());
    }

    @Test
    void interruptWakesNativeSleepWithoutUsingJvmThreadInterrupt() throws Exception {
        CountDownLatch sleeping = new CountDownLatch(1);
        AtomicBoolean observed = new AtomicBoolean();
        AtomicBoolean jvmInterruptFlag = new AtomicBoolean(true);

        OresThread thread = new OresThread(() -> {
            sleeping.countDown();
            try {
                OresThread.sleep(5_000);
            } catch (InterruptedException expected) {
                observed.set(true);
                jvmInterruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });

        thread.start();
        assertTrue(sleeping.await(2, TimeUnit.SECONDS));
        thread.interrupt();
        thread.join();

        assertTrue(observed.get());
        assertFalse(jvmInterruptFlag.get(),
                "OresThread interruption must use the native control block, not java.lang.Thread.interrupt()");
        assertFalse(thread.isInterrupted(),
                "InterruptedException consumes the explicit Thread interrupt status like java.lang.Thread");
    }

    @Test
    void nativeCpuTimeIsVisibleAndRetainedAfterTermination() throws Exception {
        AtomicLong duringRun = new AtomicLong();

        OresThread thread = new OresThread(() -> {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(20);
            long value = 0;
            while (System.nanoTime() - deadline < 0) {
                value ^= System.nanoTime();
            }
            assertNotEquals(Long.MIN_VALUE, value);
            duringRun.set(OresThread.currentThread().cpuTimeNanos());
        }, "ores-cpu-accounting");

        thread.start();
        thread.join();

        assertTrue(duringRun.get() > 0L, "native pthread CPU clock should advance while target runs");
        assertTrue(thread.cpuTimeNanos() >= duringRun.get(),
                "completed OresThread must retain its final native CPU accounting");
    }

    @Test
    void logicalRenameDoesNotRequireJavaThreadSetName() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        OresThread thread = new OresThread(() -> {
            entered.countDown();
            try {
                assertTrue(release.await(2, TimeUnit.SECONDS));
            } catch (InterruptedException failure) {
                fail(failure);
            }
        }, "before");

        thread.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        thread.setName("after");
        assertEquals("after", thread.getName());
        release.countDown();
        thread.join();
    }

    @Test
    void actorCannotEscapeDispatcherByStartingDedicatedThread() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 1, Long.MAX_VALUE, 64);
        IsolatePolicy policy = IsolatePolicy.developer();
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(policy, config)) {
            var ref = runtime.<Integer>spawnSharedTrusted(factoryContext -> (message, context) -> {
                try {
                    new OresThread(() -> { }).start();
                } catch (Throwable expected) {
                    failure.set(expected);
                } finally {
                    done.countDown();
                }
            });
            ref.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }

        assertInstanceOf(SecurityException.class, failure.get());
    }

    @Test
    void privateActorCannotEscapeDispatcherByStartingDedicatedThread() throws Exception {
        var config = new ActorRuntime.DispatcherConfig(
                1, 1, 1, 1, Long.MAX_VALUE, 64);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        try (ActorRuntime runtime = new ActorRuntime(IsolatePolicy.developer(), config)) {
            var ref = runtime.<Integer>spawnPrivateTrusted(factoryContext -> (message, context) -> {
                try {
                    new OresThread(() -> { }).start();
                } catch (Throwable expected) {
                    failure.set(expected);
                } finally {
                    done.countDown();
                }
            });
            ref.send(1);
            assertTrue(done.await(5, TimeUnit.SECONDS));
        }

        assertInstanceOf(SecurityException.class, failure.get());
    }

    private static void threadCpuBurn() {
        long value = 0L;
        for (int i = 0; i < 1_000_000; i++) value += i;
        if (value == Long.MIN_VALUE) throw new AssertionError("unreachable");
    }

    @Test
    void typeCheckerAcceptsJavaShapedThreadSurface() {
        OresCompiler.parseAndTypeCheck("""
                pub routine main() => void {
                  val Thread worker = new Thread(nlex () -> {
                    return;
                  }, "worker");
                  worker.start();
                  worker.interrupt();
                  val bool alive = worker.isAlive();
                  val bool interrupted = worker.isInterrupted();
                  val int id = worker.threadId();
                  val int cpu_nanos = worker.cpuTimeNanos();
                  val string name = worker.getName();
                  worker.setName("renamed");
                  worker.join();
                  return;
                }
                """);
    }

    @Test
    void typeCheckerRejectsLexicallyCapturedThreadTarget() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        pub routine main() => void {
                          val int captured = 7;
                          val Thread worker = new Thread(() -> {
                            stdio.stdout.write(captured);
                            return;
                          });
                          worker.start();
                          return;
                        }
                        """));
        assertTrue(error.getMessage().contains("nlex"));
    }

    @Test
    void typeCheckerRejectsThreadCreationInsideActor() {
        IllegalArgumentException error = assertThrows(
                IllegalArgumentException.class,
                () -> OresCompiler.parseAndTypeCheck("""
                        shared actor Worker {
                          pub fnc run() => void {
                            val Thread worker = new Thread(nlex () -> { return; });
                            worker.start();
                            return;
                          }
                        }
                        """));
        assertTrue(error.getMessage().contains("Thread")
                || error.getMessage().contains("thread"));
    }
}
