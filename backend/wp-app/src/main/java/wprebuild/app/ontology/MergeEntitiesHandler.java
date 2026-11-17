package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.dto.EntityRef;
import wprebuild.kernel.generator.MergeEntitiesGenerator;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameView;

/** Applies {@link MergeEntitiesGenerator} on the project write pipeline (BS-13). */
@Service
public final class MergeEntitiesHandler implements CommandHandler<MergeEntities, MergeEntities.Result> {

    private static final Logger log = LoggerFactory.getLogger(MergeEntitiesHandler.class);

    private final ProjectRuntimeRegistry registry;

    public MergeEntitiesHandler(ProjectRuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Class<MergeEntities> commandType() {
        return MergeEntities.class;
    }

    @Override
    public Optional<ProjectId> project(MergeEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(MergeEntities command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.MERGE_ENTITIES);
    }

    @Override
    public MergeEntities.Result handle(MergeEntities command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectHandle handle = registry.get(command.projectId());
        String description = resolveDescription(handle, command);
        ChangeApplicationResult<OWLEntity> applied = handle.pipeline()
                .apply(
                        context.user(),
                        new MergeEntitiesGenerator(
                                command.sources(), command.target(), command.treatment(), description));
        OWLEntity target = applied.result();
        Long revision = applied.revision()
                .map(Revision::number)
                .map(number -> number.value())
                .orElse(null);
        MergeEntities.Result result = new MergeEntities.Result(ref(target), revision);
        log.info(
                "merged {} entities into {} in project {} (revision {})",
                command.sources().size(),
                target.getIRI(),
                command.projectId(),
                revision);
        return result;
    }

    /**
     * Blank user descriptions become {@code Merged <n> entities into <display name>} using the
     * project's display-name settings (BS-13).
     */
    private static String resolveDescription(ProjectHandle handle, MergeEntities command) {
        if (!command.description().isBlank()) {
            return command.description().strip();
        }
        return handle.gate().read(() -> {
            ProjectRuntime runtime = handle.runtime();
            OWLEntity target = runtime.ontologies()
                    .signature()
                    .filter(entity -> entity.getIRI().equals(command.target()))
                    .findFirst()
                    .orElseGet(() -> runtime.ontologies().dataFactory().getOWLClass(command.target()));
            String displayName = runtime.view(DisplayNameView.class).displayName(target);
            return "Merged " + command.sources().size() + " entities into " + displayName;
        });
    }

    private static EntityRef ref(OWLEntity entity) {
        return new EntityRef(entity.getIRI().toString(), entityType(entity));
    }

    private static String entityType(OWLEntity entity) {
        return switch (entity) {
            case OWLClass ignored -> "CLASS";
            case OWLObjectProperty ignored -> "OBJECT_PROPERTY";
            case OWLDataProperty ignored -> "DATA_PROPERTY";
            case OWLAnnotationProperty ignored -> "ANNOTATION_PROPERTY";
            case OWLNamedIndividual ignored -> "NAMED_INDIVIDUAL";
            case OWLDatatype ignored -> "DATATYPE";
            default -> throw new IllegalArgumentException("unsupported entity " + entity.getIRI());
        };
    }
}
