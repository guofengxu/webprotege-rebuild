package wprebuild.app.ontology;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.generator.DeleteEntitiesGenerator;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;

/**
 * Removes every mention of the requested IRIs (BS-07).
 *
 * <p>The bus requires {@link Action#VIEW_PROJECT}. Each entity type in the snapshot that is actually
 * deleted also requires its {@code DELETE_*} action (BS-10). Both the lookup and the permission
 * check run under the generation lock, so a commit cannot change the set after it was authorized
 * (ADR-006). An IRI that is not a class, property, or individual is skipped.
 */
@Service
public final class DeleteEntitiesHandler implements CommandHandler<DeleteEntities, RevisionNumber> {

    private static final Logger log = LoggerFactory.getLogger(DeleteEntitiesHandler.class);

    private final ProjectRuntimeRegistry registry;

    private final AccessService access;

    public DeleteEntitiesHandler(ProjectRuntimeRegistry registry, AccessService access) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.access = Objects.requireNonNull(access, "access");
    }

    @Override
    public Class<DeleteEntities> commandType() {
        return DeleteEntities.class;
    }

    @Override
    public Optional<ProjectId> project(DeleteEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(DeleteEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public RevisionNumber handle(DeleteEntities command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectHandle handle = registry.get(command.projectId());
        // Reentrant with ChangePipeline.apply. No other commit can land until this returns.
        return handle.gate().generate(() -> delete(handle, command, context));
    }

    private RevisionNumber delete(ProjectHandle handle, DeleteEntities command, CommandContext context) {
        if (handle.runtime().isCorrupted()) {
            throw new IllegalStateException("project runtime is corrupted: " + command.projectId());
        }
        Set<OWLEntity> found = match(handle.runtime().ontologies(), command.iris());
        for (Action action : EntityActions.deleteAll(found)) {
            access.require(context.user(), command.projectId(), action);
        }
        if (found.isEmpty()) {
            return handle.runtime().head();
        }
        ChangeApplicationResult<Set<OWLEntity>> applied =
                handle.pipeline().apply(context.user(), new DeleteEntitiesGenerator(found));
        RevisionNumber revision = applied.revision()
                .map(Revision::number)
                .orElseGet(() -> handle.runtime().head());
        log.info(
                "deleted {} entities in project {} at revision {}",
                found.size(),
                command.projectId(),
                revision.value());
        return revision;
    }

    private static Set<OWLEntity> match(ProjectOntologies ontologies, Set<IRI> iris) {
        List<OWLEntity> found = new ArrayList<>();
        ontologies
                .signature()
                .filter(entity -> iris.contains(entity.getIRI()) && deletable(entity.getEntityType()))
                .forEach(found::add);
        return Set.copyOf(found);
    }

    private static boolean deletable(EntityType<?> type) {
        return EntityType.CLASS.equals(type)
                || EntityType.OBJECT_PROPERTY.equals(type)
                || EntityType.DATA_PROPERTY.equals(type)
                || EntityType.ANNOTATION_PROPERTY.equals(type)
                || EntityType.NAMED_INDIVIDUAL.equals(type);
    }
}
