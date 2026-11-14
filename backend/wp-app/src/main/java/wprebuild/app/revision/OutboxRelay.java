package wprebuild.app.revision;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.kernel.id.ProjectId;

/**
 * In-process outbox relay: delivers {@code outbox} rows to each {@link OutboxConsumer} in global
 * {@code id} order, tracking progress in {@code outbox_consumers}.
 *
 * <p>{@code outbox.published_at} and the partial index {@code outbox_unpublished} are unused legacy
 * columns from the original schema; this relay never updates {@code published_at}. Cleaning old
 * outbox rows is out of scope.
 *
 * <p>Consumers are resolved via {@link ObjectProvider} on each poll (not constructor injection) so
 * projection consumers can depend on {@code RevisionLog} without a cycle through
 * {@link JdbcRevisionCommitOutbox}.
 */
@Component
public final class OutboxRelay implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Delay before retrying after {@link OutboxRetryLater}. */
    static final long RETRY_LATER_MILLIS = 200L;

    /** Initial backoff after a hard consumer failure. */
    private static final long BACKOFF_INITIAL_MILLIS = 1_000L;

    private static final long BACKOFF_MAX_MILLIS = 60_000L;

    private final OutboxProperties properties;

    private final JdbcClient jdbc;

    private final TransactionTemplate transactions;

    private final Supplier<List<OutboxConsumer>> consumers;

    private final ScheduledExecutorService scheduler;

    private final ConcurrentHashMap<String, Long> backoffUntilMillis = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Long> backoffDelayMillis = new ConcurrentHashMap<>();

    private final AtomicBoolean wakeScheduled = new AtomicBoolean(false);

    private final AtomicBoolean wakeAgain = new AtomicBoolean(false);

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> periodic;

    @Autowired
    OutboxRelay(
            OutboxProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            ObjectProvider<OutboxConsumer> consumers) {
        this(
                properties,
                jdbc,
                transactionManager,
                () -> consumers.orderedStream().toList(),
                true);
    }

    /** Test constructor: optional auto-start of the poller. */
    OutboxRelay(
            OutboxProperties properties,
            JdbcClient jdbc,
            PlatformTransactionManager transactionManager,
            Supplier<List<OutboxConsumer>> consumers,
            boolean start) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transaction manager"));
        this.consumers = Objects.requireNonNull(consumers, "consumers");
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("wp-outbox-relay").factory());
        if (start && properties.enabled()) {
            long period = properties.pollInterval().toMillis();
            periodic = scheduler.scheduleWithFixedDelay(this::safePoll, period, period, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Requests an immediate poll. Non-blocking; concurrent calls coalesce into one run. No-op when
     * the relay is disabled or destroyed.
     */
    public void wake() {
        if (!properties.enabled() || stopped.get()) {
            return;
        }
        if (wakeScheduled.compareAndSet(false, true)) {
            try {
                scheduler.execute(this::runWake);
            } catch (RuntimeException ex) {
                wakeScheduled.set(false);
                log.warn("could not schedule outbox wake", ex);
            }
        } else {
            wakeAgain.set(true);
        }
    }

    @Override
    public void destroy() {
        stopped.set(true);
        ScheduledFuture<?> tick = periodic;
        if (tick != null) {
            tick.cancel(false);
        }
        scheduler.shutdownNow();
        try {
            scheduler.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void runWake() {
        try {
            safePoll();
        } finally {
            wakeScheduled.set(false);
            if (wakeAgain.compareAndSet(true, false) && !stopped.get()) {
                wake();
            }
        }
    }

    private void safePoll() {
        if (stopped.get() || !properties.enabled()) {
            return;
        }
        List<OutboxConsumer> list;
        try {
            list = consumers.get();
        } catch (RuntimeException ex) {
            log.error("failed to resolve outbox consumers", ex);
            return;
        }
        if (list == null || list.isEmpty()) {
            return;
        }
        // Never let a single consumer abort scheduleWithFixedDelay (JDK drops later ticks).
        for (OutboxConsumer consumer : list) {
            if (stopped.get()) {
                return;
            }
            try {
                drain(consumer);
            } catch (RuntimeException ex) {
                log.error("outbox consumer {} poll failed", consumerName(consumer), ex);
            }
        }
    }

    private void drain(OutboxConsumer consumer) {
        String name = consumer.name();
        Long until = backoffUntilMillis.get(name);
        if (until != null && System.currentTimeMillis() < until) {
            return;
        }
        // Registration commits on its own so a later accept failure does not erase the cursor row.
        try {
            transactions.executeWithoutResult(status -> registerIfAbsent(consumer));
        } catch (RuntimeException ex) {
            log.error("outbox consumer {} registration failed", name, ex);
            applyBackoff(name);
            scheduleBackoffWake(name);
            return;
        }
        while (!stopped.get()) {
            PollOutcome outcome;
            try {
                outcome = transactions.execute(status -> pollOnce(status, consumer));
            } catch (OutboxRetryLater ex) {
                scheduleRetryLater();
                return;
            } catch (RuntimeException ex) {
                if (stopped.get()) {
                    return;
                }
                long firstId = peekFirstIdSafe(name);
                log.error("outbox consumer {} failed at id {}", name, firstId, ex);
                applyBackoff(name);
                scheduleBackoffWake(name);
                return;
            }
            if (outcome == null || outcome == PollOutcome.IDLE) {
                clearBackoff(name);
                return;
            }
            clearBackoff(name);
            if (outcome == PollOutcome.DONE) {
                return;
            }
            // MORE: fetch the next batch immediately (full batch or partial ack).
        }
    }

    private PollOutcome pollOnce(TransactionStatus status, OutboxConsumer consumer) {
        String name = consumer.name();
        OptionalLong locked = lockLastId(name);
        if (locked.isEmpty()) {
            return PollOutcome.IDLE;
        }
        long lastId = locked.getAsLong();
        List<OutboxMessage> batch = fetchBatch(lastId);
        if (batch.isEmpty()) {
            return PollOutcome.DONE;
        }
        long accepted;
        try {
            accepted = consumer.accept(batch);
        } catch (OutboxRetryLater ex) {
            status.setRollbackOnly();
            throw ex;
        } catch (RuntimeException ex) {
            status.setRollbackOnly();
            throw ex;
        }
        long firstId = batch.getFirst().id();
        long lastBatchId = batch.getLast().id();
        if (accepted < firstId || accepted > lastBatchId) {
            status.setRollbackOnly();
            throw new IllegalStateException("outbox consumer %s returned illegal ack %s for batch [%s, %s]"
                    .formatted(name, accepted, firstId, lastBatchId));
        }
        updateLastId(name, accepted);
        if (batch.size() == properties.batchSize() || accepted < lastBatchId) {
            return PollOutcome.MORE;
        }
        return PollOutcome.DONE;
    }

    private void registerIfAbsent(OutboxConsumer consumer) {
        long initial = switch (consumer.startFrom()) {
            case EARLIEST -> 0L;
            case LATEST -> maxOutboxId();
        };
        jdbc.sql("""
                        insert into outbox_consumers (consumer, last_id)
                        values (?, ?)
                        on conflict (consumer) do nothing
                        """).param(consumer.name()).param(initial).update();
    }

    private long maxOutboxId() {
        return jdbc.sql("select coalesce(max(id), 0) as max_id from outbox")
                .query((rs, rowNum) -> rs.getLong("max_id"))
                .single();
    }

    private OptionalLong lockLastId(String consumer) {
        return jdbc.sql("""
                        select last_id
                        from outbox_consumers
                        where consumer = ?
                        for update skip locked
                        """)
                .param(consumer)
                .query((rs, rowNum) -> rs.getLong("last_id"))
                .optional()
                .map(OptionalLong::of)
                .orElseGet(OptionalLong::empty);
    }

    private List<OutboxMessage> fetchBatch(long afterId) {
        return jdbc.sql("""
                        select id, project_id, rev_no, type, payload::text as payload_json, created_at
                        from outbox
                        where id > ?
                        order by id
                        limit ?
                        """)
                .param(afterId)
                .param(properties.batchSize())
                .query((rs, rowNum) -> new OutboxMessage(
                        rs.getLong("id"),
                        new ProjectId(rs.getObject("project_id", java.util.UUID.class)),
                        rs.getLong("rev_no"),
                        rs.getString("type"),
                        rs.getString("payload_json"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private void updateLastId(String consumer, long lastId) {
        jdbc.sql("""
                        update outbox_consumers
                        set last_id = ?, updated_at = now()
                        where consumer = ?
                        """).param(lastId).param(consumer).update();
    }

    private long peekFirstIdSafe(String consumer) {
        if (stopped.get()) {
            return -1L;
        }
        try {
            Long lastId = jdbc.sql("select last_id from outbox_consumers where consumer = ?")
                    .param(consumer)
                    .query(Long.class)
                    .optional()
                    .orElse(0L);
            return jdbc.sql("select min(id) as first_id from outbox where id > ?")
                    .param(lastId)
                    .query((rs, rowNum) -> {
                        long value = rs.getLong("first_id");
                        return rs.wasNull() ? lastId + 1 : value;
                    })
                    .optional()
                    .orElse(lastId + 1);
        } catch (RuntimeException ex) {
            log.warn("could not peek first outbox id for {}", consumer, ex);
            return -1L;
        }
    }

    private void applyBackoff(String consumer) {
        long previous = backoffDelayMillis.getOrDefault(consumer, BACKOFF_INITIAL_MILLIS / 2);
        long next = Math.min(Math.max(previous * 2, BACKOFF_INITIAL_MILLIS), BACKOFF_MAX_MILLIS);
        backoffDelayMillis.put(consumer, next);
        backoffUntilMillis.put(consumer, System.currentTimeMillis() + next);
    }

    private void scheduleBackoffWake(String consumer) {
        if (stopped.get()) {
            return;
        }
        Long until = backoffUntilMillis.get(consumer);
        long delay = until == null ? BACKOFF_INITIAL_MILLIS : Math.max(1L, until - System.currentTimeMillis());
        try {
            scheduler.schedule(this::wake, delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            log.warn("could not schedule outbox backoff wake for {}", consumer, ex);
        }
    }

    private static String consumerName(OutboxConsumer consumer) {
        try {
            return consumer.name();
        } catch (RuntimeException ex) {
            return "<unnamed>";
        }
    }

    private void clearBackoff(String consumer) {
        backoffUntilMillis.remove(consumer);
        backoffDelayMillis.remove(consumer);
    }

    private void scheduleRetryLater() {
        if (stopped.get()) {
            return;
        }
        try {
            scheduler.schedule(this::wake, RETRY_LATER_MILLIS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            log.warn("could not schedule outbox retry-later wake", ex);
        }
    }

    private enum PollOutcome {
        IDLE,
        DONE,
        MORE
    }
}
