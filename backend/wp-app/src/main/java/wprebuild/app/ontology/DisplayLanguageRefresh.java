package wprebuild.app.ontology;

import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import wprebuild.app.project.ProjectSettingKeys;
import wprebuild.app.project.ProjectSettingsChanged;
import wprebuild.kernel.id.ProjectId;

/**
 * Rebuilds a loaded project's display-name view after {@code display-names} changes, then asks the
 * events module to send {@code resync} once the write has committed (BS-09, BS-11).
 */
@Component
final class DisplayLanguageRefresh {

    private final ProjectRuntimeRegistry registry;

    private final ApplicationEventPublisher events;

    DisplayLanguageRefresh(ProjectRuntimeRegistry registry, ApplicationEventPublisher events) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.events = Objects.requireNonNull(events, "events");
    }

    @EventListener
    public void onSettings(ProjectSettingsChanged event) {
        if (!ProjectSettingKeys.DISPLAY_NAMES.equals(event.key())) {
            return;
        }
        ProjectId projectId = event.projectId();
        // After commit, every connection sees the new row. A load during the write is replaced here.
        afterCommit(() -> {
            if (registry.peek(projectId).isPresent()) {
                registry.evict(projectId);
                registry.get(projectId);
            }
            events.publishEvent(new ProjectResyncRequested(projectId));
        });
    }

    private static void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
