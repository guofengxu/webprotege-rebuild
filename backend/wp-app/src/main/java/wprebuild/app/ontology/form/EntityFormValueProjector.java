package wprebuild.app.ontology.form;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.ontology.frame.LiteralValue;
import wprebuild.app.ontology.frame.ValueNode;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Projects first-slice form field values (BS-17): TEXT/NUMBER with PROPERTY binding read annotation
 * or data-property literals; other controls/bindings return empty placeholders without failing.
 *
 * <p>GateGuard facts: called by {@code GetEntityFormsHandler}; Glob found none; no data files; user
 * asked {@code 完成下一个任务卡}.
 */
@Component
public final class EntityFormValueProjector {

    private static final Comparator<OWLLiteral> BY_LEXICAL = Comparator.comparing(OWLLiteral::getLiteral)
            .thenComparing(literal -> literal.getDatatype().getIRI().toString())
            .thenComparing(literal -> literal.getLang() == null ? "" : literal.getLang());

    private final JsonMapper json;

    public EntityFormValueProjector(JsonMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public List<ProjectedFieldValue> project(ProjectRuntime runtime, OWLEntity subject, String descriptorJson) {
        Objects.requireNonNull(descriptorJson, "descriptor json");
        final JsonNode descriptor;
        try {
            descriptor = json.readTree(descriptorJson);
        } catch (RuntimeException ex) {
            return List.of();
        }
        return project(runtime, subject, descriptor);
    }

    public List<ProjectedFieldValue> project(ProjectRuntime runtime, OWLEntity subject, JsonNode descriptor) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(subject, "subject");
        if (descriptor == null || !descriptor.isObject()) {
            return List.of();
        }
        JsonNode fields = descriptor.get("fields");
        if (fields == null || !fields.isArray()) {
            return List.of();
        }
        List<ProjectedFieldValue> values = new ArrayList<>();
        for (JsonNode field : fields) {
            if (field == null || !field.isObject()) {
                continue;
            }
            UUID fieldId = fieldId(field);
            if (fieldId == null) {
                continue;
            }
            values.add(new ProjectedFieldValue(fieldId, projectField(runtime, subject, field)));
        }
        return List.copyOf(values);
    }

    private Optional<ValueNode> projectField(ProjectRuntime runtime, OWLEntity subject, JsonNode field) {
        try {
            String control = controlType(field);
            if (!"TEXT".equals(control) && !"NUMBER".equals(control)) {
                return Optional.empty();
            }
            JsonNode binding = field.get("owlBinding");
            if (binding == null || !binding.isObject()) {
                return Optional.empty();
            }
            if (!"PROPERTY".equals(text(binding.get("type")))) {
                return Optional.empty();
            }
            String propertyIri = text(binding.get("property"));
            if (propertyIri == null) {
                return Optional.empty();
            }
            return firstLiteral(runtime.ontologies(), subject, IRI.create(propertyIri))
                    .map(this::literal);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static Optional<OWLLiteral> firstLiteral(ProjectOntologies ontologies, OWLEntity subject, IRI propertyIri) {
        List<OWLLiteral> literals = new ArrayList<>();
        ontologies.annotationAssertions(subject.getIRI()).forEach(axiom -> {
            if (axiom.getProperty().getIRI().equals(propertyIri) && axiom.getValue() instanceof OWLLiteral literal) {
                literals.add(literal);
            }
        });
        if (literals.isEmpty() && subject.isOWLNamedIndividual()) {
            OWLNamedIndividual individual = subject.asOWLNamedIndividual();
            OWLDataProperty dataProperty = ontologies.dataFactory().getOWLDataProperty(propertyIri);
            ontologies.axiomsOfType(AxiomType.DATA_PROPERTY_ASSERTION).forEach(axiom -> {
                if (axiom.getProperty().equals(dataProperty)
                        && axiom.getSubject().equals(individual)) {
                    literals.add(axiom.getObject());
                }
            });
        }
        if (literals.isEmpty()) {
            return Optional.empty();
        }
        literals.sort(BY_LEXICAL);
        return Optional.of(literals.getFirst());
    }

    private LiteralValue literal(OWLLiteral value) {
        String lang = value.hasLang() ? value.getLang() : null;
        return new LiteralValue(
                value.getLiteral(),
                value.getDatatype().getIRI().toString(),
                Optional.ofNullable(lang).filter(text -> !text.isBlank()));
    }

    private static UUID fieldId(JsonNode field) {
        String raw = text(field.get("id"));
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String controlType(JsonNode field) {
        JsonNode control = field.get("control");
        if (control == null || !control.isObject()) {
            return null;
        }
        return text(control.get("type"));
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isString()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }

    /** One projected field value; {@code value} empty means placeholder / unset. */
    public record ProjectedFieldValue(UUID fieldId, Optional<ValueNode> value) {

        public ProjectedFieldValue {
            Objects.requireNonNull(fieldId, "field id");
            Objects.requireNonNull(value, "value");
        }
    }
}
