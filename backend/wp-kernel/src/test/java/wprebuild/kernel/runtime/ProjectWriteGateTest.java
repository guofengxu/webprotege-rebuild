package wprebuild.kernel.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(10)
class ProjectWriteGateTest {

    @Test
    void wp1_at_most_one_generate_runs_at_a_time() throws Exception {
        var gate = new ProjectWriteGate();
        var inCritical = new AtomicInteger();
        var maxInCritical = new AtomicInteger();
        var firstInside = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var first = start(
                "generate-1",
                failure,
                () -> gate.generate(() -> {
                    enter(inCritical, maxInCritical);
                    try {
                        firstInside.countDown();
                        await(releaseFirst);
                        return null;
                    } finally {
                        leave(inCritical);
                    }
                }));

        try {
            await(firstInside);
            assertThat(inCritical).hasValue(1);

            var secondInside = new CountDownLatch(1);
            var secondPhase = new AtomicInteger();
            var second = start("generate-2", failure, () -> {
                secondPhase.set(1);
                gate.generate(() -> {
                    secondPhase.set(2);
                    enter(inCritical, maxInCritical);
                    try {
                        secondInside.countDown();
                        return null;
                    } finally {
                        leave(inCritical);
                    }
                });
            });

            awaitBlockedBeforeCritical(second, secondPhase);
            assertThat(inCritical).hasValue(1);
            assertThat(secondInside.getCount()).isEqualTo(1);

            releaseFirst.countDown();
            await(secondInside);
            join(first, failure);
            join(second, failure);
            assertThat(maxInCritical).hasValue(1);
        } finally {
            releaseFirst.countDown();
        }
    }

    @Test
    void wp2_apply_exclusive_blocks_read_until_the_write_lock_is_released() throws Exception {
        var gate = new ProjectWriteGate();
        var writeEntered = new CountDownLatch(1);
        var releaseWrite = new CountDownLatch(1);
        var readEntered = new CountDownLatch(1);
        var readPhase = new AtomicInteger();
        var failure = new AtomicReference<Throwable>();

        var writer = start(
                "writer",
                failure,
                () -> gate.generate(() -> {
                    gate.applyExclusive(() -> {
                        writeEntered.countDown();
                        await(releaseWrite);
                    });
                    await(readEntered);
                    return null;
                }));

        try {
            await(writeEntered);

            var reader = start("reader", failure, () -> {
                readPhase.set(1);
                gate.read(() -> {
                    readPhase.set(2);
                    readEntered.countDown();
                    return "read";
                });
            });

            awaitBlockedBeforeCritical(reader, readPhase);
            assertThat(readEntered.getCount()).isEqualTo(1);

            releaseWrite.countDown();
            await(readEntered);
            join(writer, failure);
            join(reader, failure);
        } finally {
            releaseWrite.countDown();
        }
    }

    @Test
    void wp2_two_reads_stay_in_the_critical_section_together() throws Exception {
        var gate = new ProjectWriteGate();
        var bothInside = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();

        var first = start(
                "read-1",
                failure,
                () -> gate.read(() -> {
                    bothInside.countDown();
                    await(bothInside);
                    return null;
                }));
        var second = start(
                "read-2",
                failure,
                () -> gate.read(() -> {
                    bothInside.countDown();
                    await(bothInside);
                    return null;
                }));

        await(bothInside);
        join(first, failure);
        join(second, failure);
    }

    @Test
    void read_runs_while_another_thread_holds_the_generation_lock() throws Exception {
        var gate = new ProjectWriteGate();
        var holdingGenerate = new CountDownLatch(1);
        var releaseGenerate = new CountDownLatch(1);
        var readFinished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var writer = start(
                "generate",
                failure,
                () -> gate.generate(() -> {
                    holdingGenerate.countDown();
                    await(releaseGenerate);
                    return null;
                }));

        try {
            await(holdingGenerate);
            var reader = start(
                    "read",
                    failure,
                    () -> gate.read(() -> {
                        readFinished.countDown();
                        return "during-generate";
                    }));
            await(readFinished);
            releaseGenerate.countDown();
            join(writer, failure);
            join(reader, failure);
        } finally {
            releaseGenerate.countDown();
        }
    }

    @Test
    void generate_is_reentrant_and_keeps_the_outer_hold() throws Exception {
        var gate = new ProjectWriteGate();
        var depth = new AtomicInteger();
        var innerHolding = new CountDownLatch(1);
        var releaseInner = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var outer = start("outer-generate", failure, () -> {
            String value = gate.generate(() -> {
                assertThat(depth.incrementAndGet()).isEqualTo(1);
                String inner = gate.generate(() -> {
                    assertThat(depth.incrementAndGet()).isEqualTo(2);
                    gate.applyExclusive(() -> assertThat(depth).hasValue(2));
                    innerHolding.countDown();
                    await(releaseInner);
                    return "inner";
                });
                assertThat(depth).hasValue(2);
                return inner;
            });
            assertThat(value).isEqualTo("inner");
        });

        try {
            await(innerHolding);
            var phase = new AtomicInteger();
            var secondInside = new CountDownLatch(1);
            var second = start("blocked-generate", failure, () -> {
                phase.set(1);
                gate.generate(() -> {
                    phase.set(2);
                    secondInside.countDown();
                    return null;
                });
            });
            awaitBlockedBeforeCritical(second, phase);
            releaseInner.countDown();
            await(secondInside);
            join(outer, failure);
            join(second, failure);
        } finally {
            releaseInner.countDown();
        }
    }

    @Test
    void apply_exclusive_without_the_generation_lock_throws_and_does_not_take_the_write_lock() throws Exception {
        var gate = new ProjectWriteGate();
        var ran = new AtomicBoolean();
        var reading = new CountDownLatch(1);
        var releaseRead = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        assertThatThrownBy(() -> gate.applyExclusive(() -> ran.set(true))).isInstanceOf(IllegalStateException.class);
        assertThat(ran).isFalse();

        var reader = start(
                "reader",
                failure,
                () -> gate.read(() -> {
                    reading.countDown();
                    await(releaseRead);
                    return null;
                }));
        try {
            await(reading);
            var finished = new CountDownLatch(1);
            var error = new AtomicReference<Throwable>();
            var other = start("apply-without-generate", failure, () -> {
                try {
                    gate.applyExclusive(() -> ran.set(true));
                } catch (RuntimeException ex) {
                    error.set(ex);
                }
                finished.countDown();
            });
            await(finished);
            assertThat(error.get()).isInstanceOf(IllegalStateException.class);
            assertThat(ran).isFalse();
            join(other, failure);
        } finally {
            releaseRead.countDown();
        }
        join(reader, failure);
    }

    @Test
    void apply_exclusive_from_a_thread_that_does_not_hold_the_generation_lock_throws() throws Exception {
        var gate = new ProjectWriteGate();
        var error = new AtomicReference<Throwable>();
        var finished = new CountDownLatch(1);
        var failure = new AtomicReference<Throwable>();

        var holder = start(
                "holder",
                failure,
                () -> gate.generate(() -> {
                    var other = start("other", failure, () -> {
                        try {
                            gate.applyExclusive(() -> {});
                        } catch (RuntimeException ex) {
                            error.set(ex);
                        }
                        finished.countDown();
                    });
                    await(finished);
                    join(other, failure);
                    return null;
                }));

        join(holder, failure);
        assertThat(error.get()).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void locks_are_released_when_an_action_throws() throws Exception {
        var gate = new ProjectWriteGate();
        var failure = new AtomicReference<Throwable>();
        var readFailed = new IllegalArgumentException("read failed");
        var applyFailed = new IllegalArgumentException("apply failed");
        var applyBoom = new AssertionError("apply boom");

        assertThatThrownBy(() -> gate.read(() -> {
                    throw readFailed;
                }))
                .isSameAs(readFailed);
        assertWriteLockAvailable(gate, failure);

        assertThatThrownBy(() -> gate.generate(() -> {
                    gate.applyExclusive(() -> {
                        throw applyFailed;
                    });
                    return null;
                }))
                .isSameAs(applyFailed);
        assertWriteLockAvailable(gate, failure);

        assertThatThrownBy(() -> gate.generate(() -> {
                    gate.applyExclusive(() -> {
                        throw applyBoom;
                    });
                    return null;
                }))
                .isSameAs(applyBoom);
        assertWriteLockAvailable(gate, failure);
    }

    @Test
    void read_and_generate_return_the_supplier_value() {
        var gate = new ProjectWriteGate();

        assertThat(gate.read(() -> 7)).isEqualTo(7);
        assertThat(gate.generate(() -> "generated")).isEqualTo("generated");
    }

    @Test
    void rejects_null_actions() {
        var gate = new ProjectWriteGate();

        assertThatThrownBy(() -> gate.read(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gate.generate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> gate.applyExclusive(null)).isInstanceOf(NullPointerException.class);
    }

    private static void assertWriteLockAvailable(ProjectWriteGate gate, AtomicReference<Throwable> failure) {
        var entered = new CountDownLatch(1);
        var writer = start(
                "write-available",
                failure,
                () -> gate.generate(() -> {
                    gate.applyExclusive(() -> entered.countDown());
                    return null;
                }));
        await(entered);
        join(writer, failure);
    }

    private static void enter(AtomicInteger inCritical, AtomicInteger maxInCritical) {
        int now = inCritical.incrementAndGet();
        maxInCritical.accumulateAndGet(now, Math::max);
    }

    private static void leave(AtomicInteger inCritical) {
        inCritical.decrementAndGet();
    }

    private static Thread start(String name, AtomicReference<Throwable> failure, Runnable body) {
        return Thread.ofVirtual()
                .name(name)
                .uncaughtExceptionHandler((thread, error) -> failure.set(error))
                .start(body);
    }

    private static void join(Thread thread, AtomicReference<Throwable> failure) {
        try {
            assertThat(thread.join(Duration.ofSeconds(5))).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while joining " + thread.getName(), ex);
        }
        if (failure.get() != null) {
            throw new AssertionError(thread.getName() + " failed", failure.get());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for latch");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting", ex);
        }
    }

    /**
     * Phase is 1 after the thread has started user code and 2 inside the critical section.
     * Waiting in phase 1 means the thread is queued on the gate lock.
     */
    private static void awaitBlockedBeforeCritical(Thread thread, AtomicInteger phase) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            int seen = phase.get();
            if (seen >= 2) {
                throw new AssertionError("thread entered the critical section while another holder was active");
            }
            if (seen == 1 && isQueued(thread)) {
                return;
            }
            if (!thread.isAlive() && seen < 2) {
                throw new AssertionError("thread finished before the critical section; phase=" + seen);
            }
            Thread.onSpinWait();
        }
        throw new AssertionError(
                "thread was not queued on the lock; state=" + thread.getState() + " phase=" + phase.get());
    }

    private static boolean isQueued(Thread thread) {
        var state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING || state == Thread.State.BLOCKED;
    }
}
