package wprebuild.app.revision;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.support.TestData;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

class OutboxRelayTest {

    private static final Instant TIMESTAMP = Instant.parse("2026-01-15T12:00:00Z");

    @Nested
    @DatabaseTest
    @TestPropertySource(
            properties = {"wp.outbox.enabled=false", "wp.outbox.poll-interval=1h", "wp.outbox.batch-size=200"})
    class ManualRelay {

        private final JdbcRevisionLog log;
        private final JdbcClient jdbc;
        private final PlatformTransactionManager transactions;
        private final RevisionCommitOutbox outbox;
        private final TestData data;
        private final List<OutboxRelay> relays = new ArrayList<>();

        ManualRelay(
                JdbcRevisionLog log,
                JdbcClient jdbc,
                PlatformTransactionManager transactions,
                RevisionCommitOutbox outbox) {
            this.log = log;
            this.jdbc = jdbc;
            this.transactions = transactions;
            this.outbox = outbox;
            this.data = new TestData(jdbc);
        }

        @AfterEach
        void stopRelays() {
            for (OutboxRelay relay : relays) {
                relay.destroy();
            }
            relays.clear();
            jdbc.sql("delete from outbox_consumers").update();
            jdbc.sql("delete from outbox").update();
        }

        @Test
        void delivers_five_revisions_in_id_order() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            RecordingConsumer consumer = new RecordingConsumer("ordered", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay relay = startRelay(200, consumer);

            for (int i = 1; i <= 5; i++) {
                log.append(projectId, RevisionNumber.of(i - 1), revision(i));
            }
            relay.wake();

            awaitUntil(() -> consumer.ids().size() == 5);
            assertThat(consumer.ids()).containsExactlyElementsOf(outboxIds(projectId));
            assertThat(lastId("ordered")).isEqualTo(consumer.ids().getLast());
        }

        @Test
        void global_lock_serialises_commit_order_across_projects() throws Exception {
            ProjectId projectA = ProjectId.random();
            ProjectId projectB = ProjectId.random();
            data.insertProject(projectA, UserId.SYSTEM);
            data.insertProject(projectB, UserId.SYSTEM);

            CountDownLatch aInserted = new CountDownLatch(1);
            CountDownLatch aMayCommit = new CountDownLatch(1);
            CountDownLatch bDone = new CountDownLatch(1);
            AtomicBoolean bFinishedWhileAHeld = new AtomicBoolean(false);

            Thread threadA = new Thread(
                    () -> {
                        TransactionTemplate tx = new TransactionTemplate(transactions);
                        tx.executeWithoutResult(status -> {
                            outbox.record(projectA, revision(1));
                            aInserted.countDown();
                            awaitLatch(aMayCommit);
                        });
                    },
                    "outbox-lock-a");
            Thread threadB = new Thread(
                    () -> {
                        awaitLatch(aInserted);
                        TransactionTemplate tx = new TransactionTemplate(transactions);
                        tx.executeWithoutResult(status -> outbox.record(projectB, revision(1)));
                        bFinishedWhileAHeld.set(aMayCommit.getCount() > 0);
                        bDone.countDown();
                    },
                    "outbox-lock-b");

            threadA.start();
            threadB.start();
            assertThat(aInserted.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(300);
            assertThat(bDone.getCount()).isEqualTo(1);

            aMayCommit.countDown();
            assertThat(bDone.await(5, TimeUnit.SECONDS)).isTrue();
            threadA.join(5_000);
            threadB.join(5_000);
            assertThat(bFinishedWhileAHeld).isFalse();

            RecordingConsumer consumer = new RecordingConsumer("lock-order", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay relay = startRelay(200, consumer);
            relay.wake();
            awaitUntil(() -> consumer.ids().size() == 2);
            assertThat(consumer.ids()).containsExactlyElementsOf(allOutboxIds());
            assertThat(consumer.ids().get(1) - consumer.ids().get(0)).isEqualTo(1);
        }

        @Test
        void hard_failure_keeps_last_id_and_retries_same_batch() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            log.append(projectId, RevisionNumber.ZERO, revision(1));
            log.append(projectId, RevisionNumber.of(1), revision(2));

            AtomicInteger attempts = new AtomicInteger();
            OutboxConsumer consumer = new OutboxConsumer() {
                @Override
                public String name() {
                    return "flaky";
                }

                @Override
                public StartPosition startFrom() {
                    return StartPosition.EARLIEST;
                }

                @Override
                public long accept(List<OutboxMessage> batch) {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("boom");
                    }
                    return batch.getLast().id();
                }
            };
            OutboxRelay relay = startRelay(200, consumer);
            relay.wake();
            awaitUntil(() -> attempts.get() >= 1);
            assertThat(consumerRegistered("flaky")).isTrue();
            assertThat(lastId("flaky")).isZero();

            relay.destroy();
            relays.remove(relay);
            OutboxRelay retry = startRelay(200, consumer);
            retry.wake();
            awaitUntil(() -> attempts.get() >= 2
                    && lastId("flaky") == outboxIds(projectId).getLast());
        }

        @Test
        void retry_later_skips_error_log_and_retries_quickly() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            log.append(projectId, RevisionNumber.ZERO, revision(1));

            AtomicInteger attempts = new AtomicInteger();
            OutboxConsumer consumer = new OutboxConsumer() {
                @Override
                public String name() {
                    return "retry-later";
                }

                @Override
                public StartPosition startFrom() {
                    return StartPosition.EARLIEST;
                }

                @Override
                public long accept(List<OutboxMessage> batch) {
                    if (attempts.getAndIncrement() == 0) {
                        throw new OutboxRetryLater("not ready");
                    }
                    return batch.getLast().id();
                }
            };

            Logger logger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
            ListAppender<ILoggingEvent> appender = new ListAppender<>();
            appender.start();
            logger.addAppender(appender);
            try {
                OutboxRelay relay = startRelay(200, consumer);
                long started = System.nanoTime();
                relay.wake();
                awaitUntil(() -> attempts.get() >= 2 && lastId("retry-later") > 0);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(appender.list.stream()
                                .filter(event -> event.getLevel().isGreaterOrEqual(Level.ERROR)))
                        .isEmpty();
            } finally {
                logger.detachAppender(appender);
            }
        }

        @Test
        void partial_ack_delivers_remainder_immediately() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            for (int i = 1; i <= 5; i++) {
                log.append(projectId, RevisionNumber.of(i - 1), revision(i));
            }
            AtomicInteger calls = new AtomicInteger();
            List<Long> seen = new CopyOnWriteArrayList<>();
            OutboxConsumer consumer = new OutboxConsumer() {
                @Override
                public String name() {
                    return "partial";
                }

                @Override
                public StartPosition startFrom() {
                    return StartPosition.EARLIEST;
                }

                @Override
                public long accept(List<OutboxMessage> batch) {
                    int call = calls.incrementAndGet();
                    if (call == 1) {
                        seen.add(batch.get(0).id());
                        seen.add(batch.get(1).id());
                        return batch.get(1).id();
                    }
                    for (OutboxMessage message : batch) {
                        seen.add(message.id());
                    }
                    return batch.getLast().id();
                }
            };
            OutboxRelay relay = startRelay(200, consumer);
            relay.wake();
            awaitUntil(() -> seen.size() == 5
                    && lastId("partial") == outboxIds(projectId).getLast());
            assertThat(seen).containsExactlyElementsOf(outboxIds(projectId));
            assertThat(calls.get()).isGreaterThanOrEqualTo(2);
        }

        @Test
        void failing_consumer_does_not_block_another() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            log.append(projectId, RevisionNumber.ZERO, revision(1));

            OutboxConsumer failing = new OutboxConsumer() {
                @Override
                public String name() {
                    return "always-fail";
                }

                @Override
                public StartPosition startFrom() {
                    return StartPosition.EARLIEST;
                }

                @Override
                public long accept(List<OutboxMessage> batch) {
                    throw new IllegalStateException("nope");
                }
            };
            RecordingConsumer ok = new RecordingConsumer("ok", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay relay = startRelay(200, failing, ok);
            relay.wake();
            awaitUntil(() -> ok.ids().size() == 1);
            assertThat(lastId("ok")).isEqualTo(ok.ids().getFirst());
            assertThat(lastId("always-fail")).isZero();
        }

        @Test
        void latest_skips_existing_rows_earliest_gets_history() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            log.append(projectId, RevisionNumber.ZERO, revision(1));
            log.append(projectId, RevisionNumber.of(1), revision(2));
            List<Long> existing = outboxIds(projectId);

            RecordingConsumer latest = new RecordingConsumer("latest", OutboxConsumer.StartPosition.LATEST);
            RecordingConsumer earliest = new RecordingConsumer("earliest", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay relay = startRelay(200, latest, earliest);
            relay.wake();
            awaitUntil(() -> earliest.ids().size() == 2 && lastId("latest") == existing.getLast());
            assertThat(earliest.ids()).containsExactlyElementsOf(existing);
            assertThat(latest.ids()).isEmpty();

            log.append(projectId, RevisionNumber.of(2), revision(3));
            relay.wake();
            awaitUntil(() -> latest.ids().size() == 1 && earliest.ids().size() == 3);
            assertThat(latest.ids()).containsExactly(outboxIds(projectId).getLast());
            assertThat(earliest.ids()).containsExactlyElementsOf(outboxIds(projectId));
        }

        @Test
        void drains_backlog_without_waiting_for_poll_interval() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            for (int i = 1; i <= 1000; i++) {
                log.append(projectId, RevisionNumber.of(i - 1), revision(i));
            }
            RecordingConsumer consumer = new RecordingConsumer("backlog", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay relay = startRelay(200, consumer);
            long started = System.nanoTime();
            relay.wake();
            awaitUntil(() -> consumer.ids().size() == 1000, Duration.ofSeconds(30));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        }

        @Test
        void two_relays_deliver_each_row_once_per_consumer() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            for (int i = 1; i <= 20; i++) {
                log.append(projectId, RevisionNumber.of(i - 1), revision(i));
            }
            RecordingConsumer shared = new RecordingConsumer("shared", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay first = startRelay(5, shared);
            OutboxRelay second = startRelay(5, shared);
            first.wake();
            second.wake();
            awaitUntil(() -> shared.ids().size() == 20, Duration.ofSeconds(10));
            assertThat(shared.ids()).doesNotHaveDuplicates();
            assertThat(shared.ids()).containsExactlyElementsOf(outboxIds(projectId));
        }

        @Test
        void no_consumers_leaves_outbox_consumers_untouched() throws Exception {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            log.append(projectId, RevisionNumber.ZERO, revision(1));
            OutboxRelay relay = startRelay(200);
            relay.wake();
            Thread.sleep(300);
            assertThat(consumerCount()).isZero();
            assertThat(allOutboxIds()).hasSize(1);
        }

        @Test
        void destroy_stops_polling() throws Exception {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            RecordingConsumer consumer = new RecordingConsumer("stopped", OutboxConsumer.StartPosition.EARLIEST);
            OutboxRelay relay = startRelay(200, consumer);
            relay.destroy();
            relays.remove(relay);
            log.append(projectId, RevisionNumber.ZERO, revision(1));
            relay.wake();
            Thread.sleep(400);
            assertThat(consumer.ids()).isEmpty();
            assertThat(consumerCount()).isZero();
        }

        private OutboxRelay startRelay(int batchSize, OutboxConsumer... consumers) {
            OutboxProperties properties = new OutboxProperties(true, Duration.ofHours(1), batchSize);
            OutboxRelay relay = new OutboxRelay(properties, jdbc, transactions, () -> List.of(consumers), true);
            relays.add(relay);
            return relay;
        }

        private List<Long> outboxIds(ProjectId projectId) {
            return jdbc.sql("select id from outbox where project_id = ? order by id")
                    .param(projectId.value())
                    .query(Long.class)
                    .list();
        }

        private List<Long> allOutboxIds() {
            return jdbc.sql("select id from outbox order by id")
                    .query(Long.class)
                    .list();
        }

        private long lastId(String consumer) {
            return jdbc.sql("select last_id from outbox_consumers where consumer = ?")
                    .param(consumer)
                    .query(Long.class)
                    .single();
        }

        private boolean consumerRegistered(String consumer) {
            return jdbc.sql("select count(*) from outbox_consumers where consumer = ?")
                            .param(consumer)
                            .query(Long.class)
                            .single()
                    > 0;
        }

        private long consumerCount() {
            return jdbc.sql("select count(*) from outbox_consumers")
                    .query(Long.class)
                    .single();
        }
    }

    @Nested
    @DatabaseTest
    @TestPropertySource(
            properties = {"wp.outbox.enabled=true", "wp.outbox.poll-interval=1h", "wp.outbox.batch-size=200"})
    @Import(SpringWiring.Config.class)
    class SpringWiring {

        private final JdbcRevisionLog log;
        private final JdbcClient jdbc;
        private final OutboxRelay relay;
        private final TestData data;
        private final DependentConsumer dependent;

        SpringWiring(JdbcRevisionLog log, JdbcClient jdbc, OutboxRelay relay, DependentConsumer dependent) {
            this.log = log;
            this.jdbc = jdbc;
            this.relay = relay;
            this.data = new TestData(jdbc);
            this.dependent = dependent;
        }

        @AfterEach
        void cleanup() {
            jdbc.sql("delete from outbox_consumers").update();
            jdbc.sql("delete from outbox").update();
            dependent.ids().clear();
        }

        @Test
        void context_starts_with_consumer_depending_on_revision_log_and_registry() {
            assertThat(relay).isNotNull();
            assertThat(dependent.revisionLog()).isNotNull();
            assertThat(dependent.registry()).isNotNull();
        }

        @Test
        void wake_after_commit_delivers_far_sooner_than_poll_interval() {
            ProjectId projectId = ProjectId.random();
            data.insertProject(projectId, UserId.SYSTEM);
            long started = System.nanoTime();
            log.append(projectId, RevisionNumber.ZERO, revision(1));
            awaitUntil(() -> !dependent.ids().isEmpty());
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }

        @TestConfiguration(proxyBeanMethods = false)
        static class Config {
            @Bean
            DependentConsumer dependentConsumer(RevisionLog revisionLog, ProjectRuntimeRegistry registry) {
                return new DependentConsumer(revisionLog, registry);
            }
        }
    }

    private static Revision revision(long number) {
        return new Revision(RevisionNumber.of(number), UserId.SYSTEM, TIMESTAMP, "r" + number, List.of());
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static void awaitUntil(BooleanSupplier condition) {
        awaitUntil(condition, Duration.ofSeconds(5));
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
        throw new AssertionError("condition not met within " + timeout);
    }

    private static final class RecordingConsumer implements OutboxConsumer {

        private final String name;
        private final StartPosition startFrom;
        private final List<Long> ids = new CopyOnWriteArrayList<>();

        private RecordingConsumer(String name, StartPosition startFrom) {
            this.name = name;
            this.startFrom = startFrom;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public StartPosition startFrom() {
            return startFrom;
        }

        @Override
        public long accept(List<OutboxMessage> batch) {
            for (OutboxMessage message : batch) {
                ids.add(message.id());
            }
            return batch.getLast().id();
        }

        private List<Long> ids() {
            return List.copyOf(ids);
        }
    }

    static final class DependentConsumer implements OutboxConsumer {

        private final RevisionLog revisionLog;
        private final ProjectRuntimeRegistry registry;
        private final List<Long> ids = new CopyOnWriteArrayList<>();

        DependentConsumer(RevisionLog revisionLog, ProjectRuntimeRegistry registry) {
            this.revisionLog = revisionLog;
            this.registry = registry;
        }

        RevisionLog revisionLog() {
            return revisionLog;
        }

        ProjectRuntimeRegistry registry() {
            return registry;
        }

        List<Long> ids() {
            return ids;
        }

        @Override
        public String name() {
            return "dependent";
        }

        @Override
        public StartPosition startFrom() {
            return StartPosition.EARLIEST;
        }

        @Override
        public long accept(List<OutboxMessage> batch) {
            for (OutboxMessage message : batch) {
                ids.add(message.id());
            }
            return batch.getLast().id();
        }
    }
}
