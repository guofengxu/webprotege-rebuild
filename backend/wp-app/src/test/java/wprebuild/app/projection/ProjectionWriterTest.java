package wprebuild.app.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.projection.ProjectionSnapshot.DomainRow;
import wprebuild.app.projection.ProjectionSnapshot.EntityRow;
import wprebuild.app.projection.ProjectionSnapshot.HierarchyEdgeRow;
import wprebuild.app.projection.ProjectionSnapshot.LabelRow;
import wprebuild.app.projection.ProjectionSnapshot.RangeRow;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

@DatabaseTest
class ProjectionWriterTest {

    private static final List<String> TABLES =
            List.of("project_state", "entities", "labels", "hierarchy_edges", "property_domains", "property_ranges");

    private final ProjectionWriter writer;
    private final JdbcClient jdbc;
    private final PlatformTransactionManager transactions;

    ProjectionWriterTest(ProjectionWriter writer, JdbcClient jdbc, PlatformTransactionManager transactions) {
        this.writer = writer;
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Test
    void projection_schema_has_all_six_tables() {
        List<String> tables = jdbc.sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'projection'
                          and table_type = 'BASE TABLE'
                        """).query(String.class).list();
        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    void replace_writes_snapshot_rows_and_replaces_on_second_write() {
        ProjectId projectId = ProjectId.random();
        ProjectionSnapshot first = snapshot(
                List.of(new EntityRow("http://ex/A", "CLASS", false)),
                List.of(new LabelRow("http://ex/A", "http://www.w3.org/2000/01/rdf-schema#label", "en", "Alpha")),
                List.of(new HierarchyEdgeRow("CLASS", "http://ex/A", "http://ex/Thing")),
                List.of(new DomainRow("http://ex/p", "http://ex/A")),
                List.of(new RangeRow("http://ex/p", "http://ex/B", "CLASS")));

        runInTx(() -> {
            writer.lockProject(projectId);
            assertThat(writer.replaceProject(projectId, first, 3L)).isTrue();
        });

        assertThat(entityIris(projectId)).containsExactly("http://ex/A");
        assertThat(labelValues(projectId)).containsExactly("Alpha");
        assertThat(hierarchyParents(projectId)).containsExactly("http://ex/Thing");
        assertThat(domainClasses(projectId)).containsExactly("http://ex/A");
        assertThat(rangeIris(projectId)).containsExactly("http://ex/B");
        assertThat(revNo(projectId)).isEqualTo(3L);

        ProjectionSnapshot second = snapshot(
                List.of(
                        new EntityRow("http://ex/B", "CLASS", true),
                        new EntityRow("http://ex/p", "OBJECT_PROPERTY", false)),
                List.of(new LabelRow("http://ex/B", "http://www.w3.org/2000/01/rdf-schema#label", "", "Beta")),
                List.of(),
                List.of(),
                List.of(new RangeRow("http://ex/p", "http://www.w3.org/2001/XMLSchema#string", "DATATYPE")));

        runInTx(() -> {
            writer.lockProject(projectId);
            assertThat(writer.replaceProject(projectId, second, 5L)).isTrue();
        });

        assertThat(entityIris(projectId)).containsExactlyInAnyOrder("http://ex/B", "http://ex/p");
        assertThat(labelValues(projectId)).containsExactly("Beta");
        assertThat(hierarchyParents(projectId)).isEmpty();
        assertThat(domainClasses(projectId)).isEmpty();
        assertThat(rangeIris(projectId)).containsExactly("http://www.w3.org/2001/XMLSchema#string");
        assertThat(revNo(projectId)).isEqualTo(5L);
    }

    @Test
    void projects_do_not_interfere_with_each_other() {
        ProjectId a = ProjectId.random();
        ProjectId b = ProjectId.random();
        ProjectionSnapshot snapA = snapshot(
                List.of(new EntityRow("http://ex/A", "CLASS", false)), List.of(), List.of(), List.of(), List.of());
        ProjectionSnapshot snapB = snapshot(
                List.of(new EntityRow("http://ex/B", "CLASS", false)), List.of(), List.of(), List.of(), List.of());

        runInTx(() -> {
            writer.lockProject(a);
            writer.replaceProject(a, snapA, 1L);
        });
        runInTx(() -> {
            writer.lockProject(b);
            writer.replaceProject(b, snapB, 1L);
        });

        assertThat(entityIris(a)).containsExactly("http://ex/A");
        assertThat(entityIris(b)).containsExactly("http://ex/B");
    }

    @Test
    void lower_rev_no_is_rejected_and_leaves_rows_unchanged() {
        ProjectId projectId = ProjectId.random();
        ProjectionSnapshot first = snapshot(
                List.of(new EntityRow("http://ex/A", "CLASS", false)), List.of(), List.of(), List.of(), List.of());
        ProjectionSnapshot stale = snapshot(
                List.of(new EntityRow("http://ex/Stale", "CLASS", false)), List.of(), List.of(), List.of(), List.of());

        runInTx(() -> {
            writer.lockProject(projectId);
            writer.replaceProject(projectId, first, 10L);
        });
        boolean accepted = runInTx(() -> {
            writer.lockProject(projectId);
            return writer.replaceProject(projectId, stale, 9L);
        });

        assertThat(accepted).isFalse();
        assertThat(entityIris(projectId)).containsExactly("http://ex/A");
        assertThat(revNo(projectId)).isEqualTo(10L);
    }

    @Test
    void lock_project_without_transaction_throws() {
        assertThatThrownBy(() -> writer.lockProject(ProjectId.random()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transaction");
    }

    @Test
    void replace_without_lock_throws() {
        ProjectId projectId = ProjectId.random();
        ProjectionSnapshot snap = snapshot(
                List.of(new EntityRow("http://ex/A", "CLASS", false)), List.of(), List.of(), List.of(), List.of());
        assertThatThrownBy(() -> runInTx(() -> writer.replaceProject(projectId, snap, 1L)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lockProject");
    }

    @Test
    void concurrent_writes_serialise_and_leave_one_complete_snapshot() throws Exception {
        ProjectId projectId = ProjectId.random();
        ProjectionSnapshot snapA = snapshot(
                List.of(new EntityRow("http://ex/A", "CLASS", false)),
                List.of(new LabelRow("http://ex/A", "http://ex/label", "en", "A")),
                List.of(),
                List.of(),
                List.of());
        ProjectionSnapshot snapB = snapshot(
                List.of(new EntityRow("http://ex/B", "CLASS", false)),
                List.of(new LabelRow("http://ex/B", "http://ex/label", "en", "B")),
                List.of(),
                List.of(),
                List.of());

        CountDownLatch aLocked = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        CountDownLatch bDone = new CountDownLatch(1);
        AtomicBoolean bFinishedWhileAHeld = new AtomicBoolean(false);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread threadA = new Thread(
                () -> {
                    try {
                        TransactionTemplate tx = new TransactionTemplate(transactions);
                        tx.executeWithoutResult(status -> {
                            writer.lockProject(projectId);
                            aLocked.countDown();
                            writer.replaceProject(projectId, snapA, 1L);
                            awaitLatch(aMayCommit);
                        });
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    }
                },
                "projection-a");
        Thread threadB = new Thread(
                () -> {
                    try {
                        awaitLatch(aLocked);
                        TransactionTemplate tx = new TransactionTemplate(transactions);
                        tx.executeWithoutResult(status -> {
                            writer.lockProject(projectId);
                            writer.replaceProject(projectId, snapB, 2L);
                        });
                        bFinishedWhileAHeld.set(aMayCommit.getCount() > 0);
                        bDone.countDown();
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                        bDone.countDown();
                    }
                },
                "projection-b");

        threadA.start();
        threadB.start();
        assertThat(aLocked.await(5, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(300);
        assertThat(bDone.getCount()).isEqualTo(1);

        aMayCommit.countDown();
        assertThat(bDone.await(5, TimeUnit.SECONDS)).isTrue();
        threadA.join(5_000);
        threadB.join(5_000);
        assertThat(failure.get()).isNull();
        assertThat(bFinishedWhileAHeld).isFalse();

        List<String> iris = entityIris(projectId);
        List<String> labels = labelValues(projectId);
        assertThat(iris).hasSize(1);
        assertThat(labels).hasSize(1);
        // B has the higher rev_no, so after both commit the surviving snapshot is B's.
        assertThat(iris).containsExactly("http://ex/B");
        assertThat(labels).containsExactly("B");
        assertThat(revNo(projectId)).isEqualTo(2L);
    }

    private void runInTx(Runnable action) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> action.run());
    }

    private <T> T runInTx(java.util.function.Supplier<T> action) {
        return new TransactionTemplate(transactions).execute(status -> action.get());
    }

    private static ProjectionSnapshot snapshot(
            List<EntityRow> entities,
            List<LabelRow> labels,
            List<HierarchyEdgeRow> edges,
            List<DomainRow> domains,
            List<RangeRow> ranges) {
        return new ProjectionSnapshot(entities, labels, edges, domains, ranges);
    }

    private long revNo(ProjectId projectId) {
        return jdbc.sql("select rev_no from projection.project_state where project_id = ?")
                .param(projectId.value())
                .query(Long.class)
                .single();
    }

    private List<String> entityIris(ProjectId projectId) {
        return jdbc.sql("select iri from projection.entities where project_id = ? order by iri")
                .param(projectId.value())
                .query(String.class)
                .list();
    }

    private List<String> labelValues(ProjectId projectId) {
        return jdbc.sql("select value from projection.labels where project_id = ? order by value")
                .param(projectId.value())
                .query(String.class)
                .list();
    }

    private List<String> hierarchyParents(ProjectId projectId) {
        return jdbc.sql("select parent_iri from projection.hierarchy_edges where project_id = ? order by parent_iri")
                .param(projectId.value())
                .query(String.class)
                .list();
    }

    private List<String> domainClasses(ProjectId projectId) {
        return jdbc.sql("select class_iri from projection.property_domains where project_id = ? order by class_iri")
                .param(projectId.value())
                .query(String.class)
                .list();
    }

    private List<String> rangeIris(ProjectId projectId) {
        return jdbc.sql("select range_iri from projection.property_ranges where project_id = ? order by range_iri")
                .param(projectId.value())
                .query(String.class)
                .list();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
