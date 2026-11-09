package wprebuild.app.ontology;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.generator.CreateEntitiesGenerator;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;

/** Mints entities into the project's target ontology (BS-05, BS-06, BS-07). */
@Service
public final class CreateEntitiesHandler implements CommandHandler<CreateEntities, CreateEntities.Result> {

    private static final Logger log = LoggerFactory.getLogger(CreateEntitiesHandler.class);

    private final ProjectRuntimeRegistry registry;

    private final ProjectSettingsProvider settings;

    private final HierarchyQuery hierarchy;

    public CreateEntitiesHandler(
            ProjectRuntimeRegistry registry, ProjectSettingsProvider settings, HierarchyQuery hierarchy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    @Override
    public Class<CreateEntities> commandType() {
        return CreateEntities.class;
    }

    @Override
    public Optional<ProjectId> project(CreateEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(CreateEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(EntityActions.create(command.entityType()));
    }

    @Override
    public CreateEntities.Result handle(CreateEntities command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        OntologyKey target = settings.newEntitySettings(command.projectId()).targetOntology();
        ChangeApplicationResult<List<OWLEntity>> applied =
                registry.get(command.projectId()).pipeline().apply(context.user(), generator(command, target));
        RevisionNumber revision = applied.revision().map(Revision::number).orElseGet(() -> head(command.projectId()));
        CreateEntities.Result result =
                new CreateEntities.Result(hierarchy.describe(command.projectId(), applied.result()), revision);
        log.info(
                "created {} entities in project {} at revision {}",
                result.entities().size(),
                command.projectId(),
                revision.value());
        return result;
    }

    private RevisionNumber head(ProjectId projectId) {
        ProjectHandle handle = registry.get(projectId);
        return handle.gate().read(() -> handle.runtime().head());
    }

    private static CreateEntitiesGenerator<OWLEntity> generator(CreateEntities command, OntologyKey target) {
        return new CreateEntitiesGenerator<>(
                cast(command.entityType()),
                command.names(),
                command.lang(),
                parents(command.entityType(), command.parents()),
                target);
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> cast(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }

    private static Set<OWLEntity> parents(EntityType<?> type, Set<IRI> iris) {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        Set<OWLEntity> copied = new LinkedHashSet<>();
        for (IRI iri : iris) {
            copied.add(parent(data, type, iri));
        }
        return Set.copyOf(copied);
    }

    private static OWLEntity parent(OWLDataFactory data, EntityType<?> type, IRI iri) {
        if (EntityType.OBJECT_PROPERTY.equals(type)) {
            return data.getOWLObjectProperty(iri);
        }
        if (EntityType.DATA_PROPERTY.equals(type)) {
            return data.getOWLDataProperty(iri);
        }
        if (EntityType.ANNOTATION_PROPERTY.equals(type)) {
            return data.getOWLAnnotationProperty(iri);
        }
        if (EntityType.CLASS.equals(type) || EntityType.NAMED_INDIVIDUAL.equals(type)) {
            return data.getOWLClass(iri);
        }
        throw new IllegalArgumentException("unsupported entity type: " + type.getPrintName());
    }
}
