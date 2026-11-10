package wprebuild.app.ontology;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import org.semanticweb.owlapi.model.EntityType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AccessService;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.crud.KitEntityCreator;
import wprebuild.kernel.crud.PrefixedNameExpander;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.DefaultFreshIriMinter;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.revision.SnapshotStore;
import wprebuild.kernel.runtime.ChangePipeline;
import wprebuild.kernel.runtime.DerivedView;
import wprebuild.kernel.runtime.FreshIriMinter;
import wprebuild.kernel.runtime.PostCommitListener;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.runtime.ProjectWriteGate;
import wprebuild.kernel.shortform.DisplayNameView;

/** Builds one project's runtime, write gate, and change pipeline. */
@Component
public final class ProjectRuntimeFactory {

    private static final Clock CLOCK = Clock.systemUTC();

    private final RevisionLog revisionLog;

    private final SnapshotStore snapshotStore;

    private final SnapshotCodec snapshotCodec;

    private final ProjectSettingsProvider settings;

    private final AccessService access;

    private final ApplicationEventPublisher events;

    private final Executor snapshotExecutor;

    private final RuntimeProperties properties;

    public ProjectRuntimeFactory(
            RevisionLog revisionLog,
            SnapshotStore snapshotStore,
            SnapshotCodec snapshotCodec,
            ProjectSettingsProvider settings,
            AccessService access,
            ApplicationEventPublisher events,
            @Qualifier("projectSnapshotExecutor") Executor snapshotExecutor,
            RuntimeProperties properties) {
        this.revisionLog = Objects.requireNonNull(revisionLog, "revision log");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshot store");
        this.snapshotCodec = Objects.requireNonNull(snapshotCodec, "snapshot codec");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.access = Objects.requireNonNull(access, "access");
        this.events = Objects.requireNonNull(events, "events");
        this.snapshotExecutor = Objects.requireNonNull(snapshotExecutor, "snapshot executor");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public ProjectHandle create(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        ProjectRuntime runtime = runtimeLoader(projectId).load(projectId);
        ProjectWriteGate gate = new ProjectWriteGate();
        ChangePipeline pipeline =
                new ChangePipeline(runtime, gate, revisionLog, minter(projectId), listeners(projectId, gate), CLOCK);
        return new ProjectHandle(runtime, gate, pipeline);
    }

    private ProjectRuntimeLoader runtimeLoader(ProjectId projectId) {
        return new ProjectRuntimeLoader(revisionLog, snapshotStore, snapshotCodec, views(projectId));
    }

    private List<Supplier<DerivedView>> views(ProjectId projectId) {
        return List.of(
                () -> new ClassHierarchyView(),
                () -> new ObjectPropertyHierarchyView(),
                () -> new DataPropertyHierarchyView(),
                () -> new AnnotationPropertyHierarchyView(),
                () -> new DisplayNameView(settings.displayNames(projectId)));
    }

    private FreshIriMinter minter(ProjectId projectId) {
        // Suppliers read the current rows, so a settings change applies to the next minted IRI (BS-06).
        return new DefaultFreshIriMinter(
                new KitEntityCreator(
                        () -> settings.newEntitySettings(projectId),
                        () -> new PrefixedNameExpander(settings.prefixes(projectId))),
                (user, type) -> canCreate(user, projectId, type));
    }

    private boolean canCreate(UserId user, ProjectId projectId, EntityType<?> type) {
        try {
            return access.can(user, projectId, EntityActions.create(type));
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private List<PostCommitListener> listeners(ProjectId projectId, ProjectWriteGate gate) {
        return List.of(
                new RevisionCommittedPublisher(projectId, events),
                new SnapshotOnCommit(
                        projectId, gate, snapshotStore, snapshotCodec, snapshotExecutor, properties.snapshotEvery()));
    }
}
