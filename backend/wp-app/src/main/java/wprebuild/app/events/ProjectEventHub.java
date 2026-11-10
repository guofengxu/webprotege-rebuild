package wprebuild.app.events;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.kernel.id.ProjectId;

/**
 * Per-project subscribers and a ring of the last N revisions (BS-11, default N = 200).
 *
 * <p>Catch-up takes the project generation lock and then this hub's lock, so it cannot observe a
 * head that the translator has not recorded yet. {@link #publish} runs on the writing thread, which
 * already holds the generation lock, and only takes this hub's lock. Notifying subscribers happens
 * after that lock is released (P3-21).
 */
@Component
public final class ProjectEventHub implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ProjectEventHub.class);

    private final ProjectRuntimeRegistry registry;

    private final int capacity;

    private final Object lock = new Object();

    private final Map<ProjectId, Window> windows = new HashMap<>();

    private final List<Live> lives = new ArrayList<>();

    private final List<Waiter> waiters = new ArrayList<>();

    private final ExecutorService delivery;

    private final ScheduledExecutorService heartbeats;

    public ProjectEventHub(ProjectRuntimeRegistry registry, EventsProperties properties) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(properties, "properties");
        this.capacity = properties.bufferSize();
        // One worker keeps each project's batches in publish order. A thread per task can
        // deliver revision N+1 before N, and a reconnect then skips N.
        this.delivery = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().daemon(true).name("wp-events-delivery").factory());
        this.heartbeats = Executors.newSingleThreadScheduledExecutor(
                Thread.ofVirtual().name("wp-events-heartbeat-", 0).factory());
        long period = properties.heartbeat().toMillis();
        this.heartbeats.scheduleAtFixedRate(this::beat, period, period, TimeUnit.MILLISECONDS);
    }

    /** Records events and wakes matching subscribers. Safe to call while holding the generation lock. */
    public void publish(ProjectId projectId, List<ProjectEvent> events) {
        Objects.requireNonNull(projectId, "project id");
        List<ProjectEvent> copied = List.copyOf(Objects.requireNonNull(events, "events"));
        if (copied.isEmpty()) {
            return;
        }
        List<LiveSink> sinks = new ArrayList<>();
        List<Waiter> ready = new ArrayList<>();
        synchronized (lock) {
            remember(projectId, copied);
            for (Live live : lives) {
                if (live.projectId.equals(projectId)) {
                    sinks.add(live.sink);
                }
            }
            ready.addAll(takeWaiters(projectId, copied));
        }
        dispatch(projectId, sinks, ready, new Delivery(false, copied));
    }

    /**
     * Registers {@code sink} and returns the backlog for {@code after}. An empty cursor subscribes
     * to later events only.
     */
    public Subscription subscribe(ProjectId projectId, OptionalLong after, LiveSink sink) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(sink, "sink");
        ProjectHandle handle = registry.get(projectId);
        return handle.gate().generate(() -> {
            synchronized (lock) {
                long head = handle.runtime().head().value();
                Delivery backlog =
                        after.isPresent() ? catchupLocked(projectId, after.getAsLong(), head) : Delivery.empty();
                lives.add(new Live(projectId, sink));
                return new Subscription(backlog, () -> remove(sink));
            }
        });
    }

    /**
     * Returns events newer than {@code after}, or parks {@code consumer} until some arrive.
     *
     * @return a ticket while the poll is parked; {@code null} when {@code consumer} was already called
     */
    public PollTicket park(ProjectId projectId, long after, Consumer<Delivery> consumer) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(consumer, "consumer");
        ProjectHandle handle = registry.get(projectId);
        Parked parked = handle.gate().generate(() -> {
            synchronized (lock) {
                Delivery catchup =
                        catchupLocked(projectId, after, handle.runtime().head().value());
                if (catchup.immediate()) {
                    return new Parked(catchup, null);
                }
                Waiter waiter = new Waiter(projectId, after, consumer);
                waiters.add(waiter);
                return new Parked(null, waiter);
            }
        });
        if (parked.immediate != null) {
            consumer.accept(parked.immediate);
            return null;
        }
        return new PollTicket(parked.waiter);
    }

    /** Events strictly after {@code after}, or a resync when the ring no longer covers that range. */
    public Delivery catchup(ProjectId projectId, long after) {
        Objects.requireNonNull(projectId, "project id");
        ProjectHandle handle = registry.get(projectId);
        return handle.gate().generate(() -> {
            synchronized (lock) {
                return catchupLocked(projectId, after, handle.runtime().head().value());
            }
        });
    }

    public void cancel(PollTicket ticket) {
        if (ticket == null) {
            return;
        }
        synchronized (lock) {
            waiters.remove(ticket.waiter);
        }
    }

    /**
     * Drops the retained revisions and tells current subscribers to reload. Used when a revision
     * could not be translated. Does not take the generation lock.
     */
    public void resync(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        List<LiveSink> sinks = new ArrayList<>();
        List<Waiter> ready = new ArrayList<>();
        synchronized (lock) {
            windows.remove(projectId);
            for (Live live : lives) {
                if (live.projectId.equals(projectId)) {
                    sinks.add(live.sink);
                }
            }
            Iterator<Waiter> iterator = waiters.iterator();
            while (iterator.hasNext()) {
                Waiter waiter = iterator.next();
                if (waiter.projectId.equals(projectId)) {
                    ready.add(waiter);
                    iterator.remove();
                }
            }
        }
        dispatch(projectId, sinks, ready, Delivery.reload());
    }

    /** How many long-polls are waiting on {@code projectId}. */
    public int parkedPolls(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        synchronized (lock) {
            int count = 0;
            for (Waiter waiter : waiters) {
                if (waiter.projectId.equals(projectId)) {
                    count++;
                }
            }
            return count;
        }
    }

    @Override
    public void destroy() {
        List<LiveSink> sinks;
        synchronized (lock) {
            sinks = new ArrayList<>(lives.size());
            for (Live live : lives) {
                sinks.add(live.sink);
            }
            lives.clear();
            waiters.clear();
        }
        for (LiveSink sink : sinks) {
            safely(sink::onShutdown);
        }
        heartbeats.shutdownNow();
        delivery.shutdownNow();
    }

    private void remember(ProjectId projectId, List<ProjectEvent> events) {
        Window window = windows.computeIfAbsent(projectId, ignored -> new Window());
        List<ProjectEvent> batch = new ArrayList<>();
        long revNo = events.get(0).revNo();
        for (ProjectEvent event : events) {
            if (event.revNo() != revNo) {
                window.add(revNo, batch, capacity);
                batch = new ArrayList<>();
                revNo = event.revNo();
            }
            batch.add(event);
        }
        window.add(revNo, batch, capacity);
    }

    private Delivery catchupLocked(ProjectId projectId, long after, long head) {
        if (after < 0) {
            throw new IllegalArgumentException("after must not be negative");
        }
        if (after >= head) {
            return Delivery.empty();
        }
        Window window = windows.get(projectId);
        if (window == null || !window.covers(after + 1, head)) {
            return Delivery.reload();
        }
        return new Delivery(false, window.after(after));
    }

    private List<Waiter> takeWaiters(ProjectId projectId, List<ProjectEvent> events) {
        long min = Long.MAX_VALUE;
        for (ProjectEvent event : events) {
            min = Math.min(min, event.revNo());
        }
        List<Waiter> ready = new ArrayList<>();
        Iterator<Waiter> iterator = waiters.iterator();
        while (iterator.hasNext()) {
            Waiter waiter = iterator.next();
            if (!waiter.projectId.equals(projectId)) {
                continue;
            }
            boolean newer = false;
            boolean permissions = false;
            for (ProjectEvent event : events) {
                if (event.revNo() > waiter.after) {
                    newer = true;
                }
                if (event instanceof PermissionsChanged && event.revNo() >= waiter.after) {
                    permissions = true;
                }
            }
            boolean gap = min > waiter.after + 1;
            if (!newer && !permissions && !gap) {
                continue;
            }
            // A role change shares the current head, so it is not a missing revision.
            waiter.gap = gap && !permissions;
            ready.add(waiter);
            iterator.remove();
        }
        return ready;
    }

    private void remove(LiveSink sink) {
        synchronized (lock) {
            lives.removeIf(live -> live.sink == sink);
        }
    }

    private void dispatch(ProjectId projectId, List<LiveSink> sinks, List<Waiter> ready, Delivery live) {
        try {
            delivery.execute(() -> {
                for (Waiter waiter : ready) {
                    safely(() -> waiter.consumer.accept(waiter.gap ? Delivery.reload() : live));
                }
                for (LiveSink sink : sinks) {
                    safely(() -> sink.onEvents(live));
                }
            });
        } catch (RuntimeException ex) {
            log.warn("could not deliver events for {}", projectId, ex);
        }
    }

    private void beat() {
        List<LiveSink> sinks;
        synchronized (lock) {
            sinks = new ArrayList<>(lives.size());
            for (Live live : lives) {
                sinks.add(live.sink);
            }
        }
        for (LiveSink sink : sinks) {
            try {
                delivery.execute(() -> safely(sink::onHeartbeat));
            } catch (RuntimeException ex) {
                log.warn("could not schedule event heartbeat", ex);
            }
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException ex) {
            log.warn("event delivery failed", ex);
        }
    }

    /** Receives live events and the heartbeat comment. */
    public interface LiveSink {

        void onEvents(Delivery delivery);

        void onHeartbeat();

        /** Completes the client connection when the application stops. */
        void onShutdown();
    }

    /** Backlog captured atomically with registration, plus a way to leave the hub. */
    public static final class Subscription implements AutoCloseable {

        private final Delivery backlog;

        private final Runnable unsubscribe;

        private boolean closed;

        private Subscription(Delivery backlog, Runnable unsubscribe) {
            this.backlog = backlog;
            this.unsubscribe = unsubscribe;
        }

        public Delivery backlog() {
            return backlog;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            unsubscribe.run();
        }
    }

    /** Handle for a parked long poll. */
    public static final class PollTicket {

        private final Waiter waiter;

        private PollTicket(Waiter waiter) {
            this.waiter = waiter;
        }
    }

    /** A catch-up result. {@code resync} means the client should reload the project. */
    public record Delivery(boolean resync, List<ProjectEvent> events) {

        public Delivery {
            events = List.copyOf(Objects.requireNonNull(events, "events"));
        }

        public static Delivery empty() {
            return new Delivery(false, List.of());
        }

        public static Delivery reload() {
            return new Delivery(true, List.of());
        }

        public boolean immediate() {
            return resync || !events.isEmpty();
        }
    }

    private record Live(ProjectId projectId, LiveSink sink) {}

    private static final class Waiter {

        private final ProjectId projectId;

        private final long after;

        private final Consumer<Delivery> consumer;

        private boolean gap;

        private Waiter(ProjectId projectId, long after, Consumer<Delivery> consumer) {
            this.projectId = projectId;
            this.after = after;
            this.consumer = consumer;
        }
    }

    private record Parked(Delivery immediate, Waiter waiter) {}

    /** Recorded revisions, trimmed to the last {@code capacity} revision numbers. */
    private static final class Window {

        private final Deque<Slot> slots = new ArrayDeque<>();

        /** Role changes at the current head, folded into the next recorded revision. */
        private final List<ProjectEvent> carried = new ArrayList<>();

        private void add(long revNo, List<ProjectEvent> batch, int capacity) {
            boolean projectChanged = false;
            for (ProjectEvent event : batch) {
                if (event instanceof ProjectChanged) {
                    projectChanged = true;
                    break;
                }
            }
            if (!projectChanged) {
                carried.addAll(batch);
                return;
            }
            for (Slot slot : slots) {
                if (slot.revNo == revNo) {
                    slot.events.addAll(batch);
                    slot.complete = true;
                    return;
                }
            }
            if (!slots.isEmpty() && revNo < slots.peekLast().revNo) {
                return;
            }
            List<ProjectEvent> stored = new ArrayList<>(carried);
            stored.addAll(batch);
            carried.clear();
            slots.addLast(new Slot(revNo, stored));
            while (slots.size() > capacity) {
                slots.removeFirst();
            }
        }

        private boolean covers(long from, long to) {
            for (long revNo = from; revNo <= to; revNo++) {
                Slot slot = slot(revNo);
                if (slot == null || !slot.complete) {
                    return false;
                }
            }
            return true;
        }

        private List<ProjectEvent> after(long after) {
            List<ProjectEvent> out = new ArrayList<>();
            for (Slot slot : slots) {
                if (slot.revNo > after) {
                    out.addAll(slot.events);
                }
            }
            for (ProjectEvent event : carried) {
                if (event.revNo() > after) {
                    out.add(event);
                }
            }
            return List.copyOf(out);
        }

        private Slot slot(long revNo) {
            for (Slot slot : slots) {
                if (slot.revNo == revNo) {
                    return slot;
                }
            }
            return null;
        }
    }

    private static final class Slot {

        private final long revNo;

        private final List<ProjectEvent> events;

        private boolean complete;

        private Slot(long revNo, List<ProjectEvent> events) {
            this.revNo = revNo;
            this.events = events;
            this.complete = true;
        }
    }
}
