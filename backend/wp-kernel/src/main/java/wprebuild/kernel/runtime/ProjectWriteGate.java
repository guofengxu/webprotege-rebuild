package wprebuild.kernel.runtime;

import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Supplier;

/**
 * Per-project gate for the write path (ADR-006).
 *
 * <p>Lock order is the generation lock, then the write lock. Readers take only the read lock, so a
 * read can proceed during generation and blocks only while changes are applied to memory.
 */
public final class ProjectWriteGate {

    private final ReentrantLock generationLock = new ReentrantLock();
    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();

    /** Runs {@code action} while holding the shared read lock. */
    public <T> T read(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        var readLock = readWriteLock.readLock();
        readLock.lock();
        try {
            return action.get();
        } finally {
            readLock.unlock();
        }
    }

    /** Runs {@code action} while holding the reentrant generation lock. */
    public <T> T generate(Supplier<T> action) {
        Objects.requireNonNull(action, "action");
        generationLock.lock();
        try {
            return action.get();
        } finally {
            generationLock.unlock();
        }
    }

    /**
     * Runs {@code action} while holding the write lock.
     *
     * @throws IllegalStateException if the calling thread does not already hold the generation lock
     */
    public void applyExclusive(Runnable action) {
        Objects.requireNonNull(action, "action");
        if (!generationLock.isHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "applyExclusive requires the calling thread to hold the generation lock (ADR-006)");
        }
        var writeLock = readWriteLock.writeLock();
        writeLock.lock();
        try {
            action.run();
        } finally {
            writeLock.unlock();
        }
    }
}
