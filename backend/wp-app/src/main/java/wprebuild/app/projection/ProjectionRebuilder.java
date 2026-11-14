package wprebuild.app.projection;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Rebuilds one project's projection tables from the loaded runtime. Takes the projection advisory
 * lock first, computes under the project read lock, then writes without holding the kernel lock
 * (P6-10). Joins an existing transaction when present (P6-12).
 */
@Component
public final class ProjectionRebuilder {

    private static final Logger log = LoggerFactory.getLogger(ProjectionRebuilder.class);

    private final ProjectionWriter writer;

    private final ProjectRuntimeRegistry registry;

    private final TransactionTemplate transactions;

    public ProjectionRebuilder(
            ProjectionWriter writer, ProjectRuntimeRegistry registry, PlatformTransactionManager transactionManager) {
        this.writer = Objects.requireNonNull(writer, "writer");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transaction manager"));
    }

    public void rebuild(ProjectId projectId) {
        ProjectId id = Objects.requireNonNull(projectId, "project id");
        transactions.executeWithoutResult(status -> {
            writer.lockProject(id);
            ProjectHandle handle = registry.get(id);
            Computed computed = handle.gate().read(() -> {
                ProjectRuntime runtime = handle.runtime();
                if (runtime.isCorrupted()) {
                    throw new IllegalStateException("project runtime is corrupted: " + id);
                }
                return new Computed(
                        ProjectionRows.compute(runtime), runtime.head().value());
            });
            // Under the advisory lock a false return means the stored rev_no is already ahead of
            // this head (forward-only); leave rows unchanged and surface it in the log.
            if (!writer.replaceProject(id, computed.snapshot(), computed.head())) {
                log.warn("projection replace skipped for {} at head {} (stored rev_no is newer)", id, computed.head());
            }
        });
    }

    private record Computed(ProjectionSnapshot snapshot, long head) {}
}
