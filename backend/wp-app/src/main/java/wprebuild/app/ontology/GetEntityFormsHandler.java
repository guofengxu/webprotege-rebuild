package wprebuild.app.ontology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.form.FormDescriptorRepository;
import wprebuild.app.form.FormDescriptorRow;
import wprebuild.app.form.FormSelectorRepository;
import wprebuild.app.form.FormSelectorRow;
import wprebuild.app.ontology.EntityFormsResult.EntityFormData;
import wprebuild.app.ontology.EntityFormsResult.EntityFormFieldValue;
import wprebuild.app.ontology.form.EntityFormValueProjector;
import wprebuild.app.ontology.form.EntityFormValueProjector.ProjectedFieldValue;
import wprebuild.app.ontology.form.FormSelectorMatcher;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Matches form selectors to an entity and projects TEXT/NUMBER PROPERTY values (BS-17 / P6-69).
 *
 * <p>GateGuard facts: called by CommandBus from {@code EntityFormsController}; Glob found none; no
 * data files; user asked {@code 完成下一个任务卡}.
 */
@Service
public final class GetEntityFormsHandler implements CommandHandler<GetEntityForms, EntityFormsResult> {

    private static final Logger log = LoggerFactory.getLogger(GetEntityFormsHandler.class);

    private static final List<EntityType<?>> LOOKUP_TYPES = List.of(
            EntityType.CLASS,
            EntityType.OBJECT_PROPERTY,
            EntityType.DATA_PROPERTY,
            EntityType.ANNOTATION_PROPERTY,
            EntityType.NAMED_INDIVIDUAL,
            EntityType.DATATYPE);

    private final ProjectRuntimeRegistry registry;
    private final FormDescriptorRepository descriptors;
    private final FormSelectorRepository selectors;
    private final FormSelectorMatcher matcher;
    private final EntityFormValueProjector projector;
    private final JsonMapper json;

    public GetEntityFormsHandler(
            ProjectRuntimeRegistry registry,
            FormDescriptorRepository descriptors,
            FormSelectorRepository selectors,
            FormSelectorMatcher matcher,
            EntityFormValueProjector projector,
            JsonMapper json) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors");
        this.selectors = Objects.requireNonNull(selectors, "selectors");
        this.matcher = Objects.requireNonNull(matcher, "matcher");
        this.projector = Objects.requireNonNull(projector, "projector");
        this.json = Objects.requireNonNull(json, "json");
    }

    @Override
    public Class<GetEntityForms> commandType() {
        return GetEntityForms.class;
    }

    @Override
    public Optional<ProjectId> project(GetEntityForms command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetEntityForms command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public EntityFormsResult handle(GetEntityForms command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectHandle handle = registry.get(command.projectId());
        return handle.gate().read(() -> {
            ProjectRuntime runtime = handle.runtime();
            if (runtime.isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + command.projectId());
            }
            OWLEntity entity =
                    find(runtime, command.entity()).orElseThrow(() -> new NotFoundException("entity not found"));
            Map<UUID, FormDescriptorRow> byId = new HashMap<>();
            for (FormDescriptorRow row : descriptors.listByProject(command.projectId())) {
                byId.put(row.formId(), row);
            }
            List<EntityFormData> forms = new ArrayList<>();
            for (FormSelectorRow selector : selectors.listByProject(command.projectId())) {
                if (!command.formIds().isEmpty() && !command.formIds().contains(selector.formId())) {
                    continue;
                }
                if (!matcher.matches(runtime, entity, selector.criteriaJson())) {
                    continue;
                }
                FormDescriptorRow descriptor = byId.get(selector.formId());
                if (descriptor == null) {
                    continue;
                }
                try {
                    forms.add(toFormData(runtime, entity, descriptor));
                } catch (RuntimeException ex) {
                    // BS-17: one bad descriptor must not fail the whole entity-forms response.
                    log.warn(
                            "skipping form {} for entity {} in project {}: {}",
                            descriptor.formId(),
                            command.entity(),
                            command.projectId(),
                            ex.toString());
                }
            }
            forms.sort((left, right) -> Integer.compare(
                    byId.get(left.formId()).ordinal(), byId.get(right.formId()).ordinal()));
            return EntityFormsResult.of(command.entity().toString(), runtime.head(), forms);
        });
    }

    private EntityFormData toFormData(ProjectRuntime runtime, OWLEntity entity, FormDescriptorRow descriptor) {
        JsonNode descriptorNode = readObject(descriptor.descriptorJson());
        List<ProjectedFieldValue> projected = projector.project(runtime, entity, descriptorNode);
        List<EntityFormFieldValue> values = projected.stream()
                .map(value -> new EntityFormFieldValue(value.fieldId(), value.value()))
                .toList();
        return new EntityFormData(descriptor.formId(), descriptorNode, values);
    }

    private JsonNode readObject(String raw) {
        JsonNode node = json.readTree(raw);
        if (node == null || !node.isObject()) {
            throw new IllegalStateException("descriptor is not a JSON object");
        }
        return node;
    }

    private static Optional<OWLEntity> find(ProjectRuntime runtime, IRI iri) {
        OWLDataFactory data = runtime.ontologies().dataFactory();
        for (EntityType<?> type : LOOKUP_TYPES) {
            OWLEntity entity = data.getOWLEntity(cast(type), iri);
            if (runtime.ontologies().containsEntity(entity)) {
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> cast(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }
}
