package wprebuild.app.ontology.form;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * First-slice form selector matcher (BS-17): {@code EntityTypeIsOneOf}, {@code SubClassOf} (any
 * ancestor by default), and {@code CompositeCriteria} ALL/ANY. Any unsupported node fails the whole
 * selector.
 *
 * <p>GateGuard facts: called by {@code GetEntityFormsHandler}; Glob found none; no data files; user
 * asked {@code 完成下一个任务卡}.
 */
@Component
public final class FormSelectorMatcher {

    private final JsonMapper json;

    public FormSelectorMatcher(JsonMapper json) {
        this.json = Objects.requireNonNull(json, "json");
    }

    public boolean matches(ProjectRuntime runtime, OWLEntity entity, String criteriaJson) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(criteriaJson, "criteria json");
        final JsonNode root;
        try {
            root = json.readTree(criteriaJson);
        } catch (RuntimeException ex) {
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        // BS-17: any unsupported node fails the whole selector (no partial evaluation under ANY).
        if (!fullySupported(root)) {
            return false;
        }
        return evaluate(runtime, entity, root);
    }

    private static boolean fullySupported(JsonNode node) {
        if (node == null || !node.isObject()) {
            return false;
        }
        String type = criteriaType(node);
        if (type == null) {
            return false;
        }
        return switch (type) {
            case "EntityTypeIsOneOf", "SubClassOf" -> true;
            case "CompositeCriteria" -> {
                JsonNode children = node.get("criteria");
                if (children == null || !children.isArray()) {
                    yield false;
                }
                for (JsonNode child : children) {
                    if (!fullySupported(child)) {
                        yield false;
                    }
                }
                yield true;
            }
            default -> false;
        };
    }

    private boolean evaluate(ProjectRuntime runtime, OWLEntity entity, JsonNode node) {
        String type = criteriaType(node);
        return switch (type) {
            case "CompositeCriteria" -> composite(runtime, entity, node);
            case "EntityTypeIsOneOf" -> entityTypeIsOneOf(entity, node);
            case "SubClassOf" -> subClassOf(runtime, entity, node);
            default -> false;
        };
    }

    private boolean composite(ProjectRuntime runtime, OWLEntity entity, JsonNode node) {
        JsonNode children = node.get("criteria");
        if (children == null || !children.isArray()) {
            return false;
        }
        String matchType = text(node.get("matchType"));
        if (matchType == null) {
            return false;
        }
        boolean all = "ALL".equalsIgnoreCase(matchType);
        boolean any = "ANY".equalsIgnoreCase(matchType);
        if (!all && !any) {
            return false;
        }
        if (children.isEmpty()) {
            return all;
        }
        for (JsonNode child : children) {
            if (child == null || !child.isObject()) {
                return false;
            }
            boolean hit = evaluate(runtime, entity, child);
            if (all && !hit) {
                return false;
            }
            if (any && hit) {
                return true;
            }
        }
        return all;
    }

    private static boolean entityTypeIsOneOf(OWLEntity entity, JsonNode node) {
        JsonNode types = node.get("types");
        if (types == null || !types.isArray() || types.isEmpty()) {
            return false;
        }
        String actual = canonicalType(entity.getEntityType());
        for (JsonNode typeNode : types) {
            String raw = text(typeNode);
            if (raw == null) {
                return false;
            }
            if (actual.equals(canonicalTypeName(raw))) {
                return true;
            }
        }
        return false;
    }

    private static boolean subClassOf(ProjectRuntime runtime, OWLEntity entity, JsonNode node) {
        if (!entity.isOWLClass()) {
            return false;
        }
        String targetIri = targetIri(node.get("target"));
        if (targetIri == null) {
            return false;
        }
        OWLDataFactory data = runtime.ontologies().dataFactory();
        OWLClass subject = entity.asOWLClass();
        OWLClass target = data.getOWLClass(IRI.create(targetIri));
        if (target.isOWLThing() || OWLRDFVocabulary.OWL_THING.getIRI().equals(target.getIRI())) {
            return true;
        }
        if (subject.equals(target)) {
            return false;
        }
        ClassHierarchyView hierarchy = runtime.view(ClassHierarchyView.class);
        String filter = text(node.get("filterType"));
        boolean direct = filter != null && "DIRECT".equalsIgnoreCase(filter);
        if (direct) {
            return hierarchy.parents(subject).contains(target);
        }
        return isAncestor(hierarchy, subject, target);
    }

    private static boolean isAncestor(ClassHierarchyView hierarchy, OWLClass subject, OWLClass target) {
        ArrayDeque<OWLClass> queue = new ArrayDeque<>(hierarchy.parents(subject));
        Set<OWLClass> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            OWLClass parent = queue.removeFirst();
            if (!seen.add(parent)) {
                continue;
            }
            if (parent.equals(target)) {
                return true;
            }
            queue.addAll(hierarchy.parents(parent));
        }
        return false;
    }

    private static String criteriaType(JsonNode node) {
        String type = text(node.get("type"));
        if (type != null) {
            return type;
        }
        return text(node.get("match"));
    }

    private static String targetIri(JsonNode target) {
        if (target == null || target.isNull() || target.isMissingNode()) {
            return null;
        }
        if (target.isString()) {
            String value = target.asString();
            return value.isBlank() ? null : value;
        }
        if (target.isObject()) {
            return text(target.get("iri"));
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode() || !node.isString()) {
            return null;
        }
        String value = node.asString();
        return value.isBlank() ? null : value;
    }

    private static String canonicalType(EntityType<?> type) {
        if (type.equals(EntityType.CLASS)) {
            return "CLASS";
        }
        if (type.equals(EntityType.OBJECT_PROPERTY)) {
            return "OBJECT_PROPERTY";
        }
        if (type.equals(EntityType.DATA_PROPERTY)) {
            return "DATA_PROPERTY";
        }
        if (type.equals(EntityType.ANNOTATION_PROPERTY)) {
            return "ANNOTATION_PROPERTY";
        }
        if (type.equals(EntityType.NAMED_INDIVIDUAL)) {
            return "NAMED_INDIVIDUAL";
        }
        if (type.equals(EntityType.DATATYPE)) {
            return "DATATYPE";
        }
        return type.getPrefixedName().toUpperCase(Locale.ROOT);
    }

    private static String canonicalTypeName(String raw) {
        String value = raw.strip();
        return switch (value) {
            case "owl:Class", "CLASS", "Class" -> "CLASS";
            case "owl:ObjectProperty", "OBJECT_PROPERTY", "ObjectProperty" -> "OBJECT_PROPERTY";
            case "owl:DataProperty", "DATA_PROPERTY", "DataProperty" -> "DATA_PROPERTY";
            case "owl:AnnotationProperty", "ANNOTATION_PROPERTY", "AnnotationProperty" -> "ANNOTATION_PROPERTY";
            case "owl:NamedIndividual", "NAMED_INDIVIDUAL", "NamedIndividual" -> "NAMED_INDIVIDUAL";
            case "rdfs:Datatype", "DATATYPE", "Datatype" -> "DATATYPE";
            default -> value.toUpperCase(Locale.ROOT).replace(':', '_');
        };
    }
}
