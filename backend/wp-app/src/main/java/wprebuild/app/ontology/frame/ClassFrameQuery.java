package wprebuild.app.ontology.frame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataHasValue;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDataSomeValuesFrom;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectHasValue;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectSomeValuesFrom;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.HierarchyQuery;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameView;

/**
 * Reads one class frame under the project read lock.
 *
 * <p>Annotations, parents, property values, and Manchester lines are sorted so the same ontology
 * always produces the same frame. Named {@code SubClassOf} parents, annotation assertions, and the
 * three supported restrictions are structured. Every other axiom whose subject is the class is
 * rendered as text.
 */
@Component
public final class ClassFrameQuery {

    private static final List<EntityType<?>> IRI_TYPES = List.of(
            EntityType.CLASS,
            EntityType.OBJECT_PROPERTY,
            EntityType.DATA_PROPERTY,
            EntityType.ANNOTATION_PROPERTY,
            EntityType.NAMED_INDIVIDUAL,
            EntityType.DATATYPE);

    private static final Comparator<EntityNode> BY_NAME = Comparator.comparing(
                    EntityNode::displayName, String::compareToIgnoreCase)
            .thenComparing(EntityNode::iri);

    private static final Comparator<AnnotationValue> BY_ANNOTATION = Comparator.comparing(
                    (AnnotationValue value) -> value.property().iri())
            .thenComparing(value -> value.lang().orElse(""))
            .thenComparing(value -> valueKey(value.value()));

    private static final Comparator<PropertyValue> BY_VALUE = Comparator.comparing(PropertyValue::kind)
            .thenComparing(value -> value.property().iri())
            .thenComparing(value -> valueKey(value.filler()));

    private final ProjectRuntimeRegistry registry;

    private final HierarchyQuery hierarchy;

    ClassFrameQuery(ProjectRuntimeRegistry registry, HierarchyQuery hierarchy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    public ClassFrame frame(ProjectId projectId, IRI iri) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        ProjectHandle handle = registry.get(projectId);
        return handle.gate().read(() -> {
            if (handle.runtime().isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + projectId);
            }
            return frame(handle.runtime(), iri);
        });
    }

    /**
     * Reads one class frame. The caller already holds the project read lock or the generation lock.
     */
    ClassFrame frame(ProjectRuntime runtime, IRI iri) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(iri, "iri");
        return read(runtime, iri);
    }

    private ClassFrame read(ProjectRuntime runtime, IRI iri) {
        ProjectOntologies ontologies = runtime.ontologies();
        OWLClass subject = ontologies.dataFactory().getOWLClass(iri);
        if (!ontologies.containsEntity(subject)) {
            throw new NotFoundException("class not found");
        }
        LinkedHashSet<OWLAnnotationAssertionAxiom> annotations = new LinkedHashSet<>();
        LinkedHashSet<OWLClass> parents = new LinkedHashSet<>();
        LinkedHashSet<RawValue> values = new LinkedHashSet<>();
        LinkedHashSet<OWLAxiom> others = new LinkedHashSet<>();
        readAnnotations(ontologies, iri, annotations, others);
        readSubClasses(ontologies, subject, parents, values, others);
        others.addAll(otherSubjectAxioms(ontologies, subject));
        return assemble(runtime, subject, annotations, parents, values, others);
    }

    private static void readAnnotations(
            ProjectOntologies ontologies,
            IRI subject,
            Collection<OWLAnnotationAssertionAxiom> annotations,
            Collection<OWLAxiom> others) {
        ontologies.annotationAssertions(subject).forEach(axiom -> {
            OWLAnnotationValue value = axiom.getValue();
            if (value instanceof OWLLiteral || value instanceof IRI) {
                annotations.add(axiom);
            } else {
                others.add(axiom);
            }
        });
    }

    private static void readSubClasses(
            ProjectOntologies ontologies,
            OWLClass subject,
            Collection<OWLClass> parents,
            Collection<RawValue> values,
            Collection<OWLAxiom> others) {
        ontologies.axiomsOfType(AxiomType.SUBCLASS_OF).forEach(axiom -> {
            if (!(axiom.getSubClass() instanceof OWLClass subclass) || !subclass.equals(subject)) {
                return;
            }
            if (axiom.getSuperClass() instanceof OWLClass parent) {
                parents.add(parent);
                return;
            }
            restriction(axiom.getSuperClass()).ifPresentOrElse(values::add, () -> others.add(axiom));
        });
    }

    private static List<OWLAxiom> otherSubjectAxioms(ProjectOntologies ontologies, OWLClass subject) {
        List<OWLAxiom> axioms = new ArrayList<>();
        ontologies.axiomsOfType(AxiomType.EQUIVALENT_CLASSES).forEach(axiom -> {
            if (axiom.classExpressions().anyMatch(subject::equals)) {
                axioms.add(axiom);
            }
        });
        ontologies.axiomsOfType(AxiomType.DISJOINT_CLASSES).forEach(axiom -> {
            if (axiom.classExpressions().anyMatch(subject::equals)) {
                axioms.add(axiom);
            }
        });
        ontologies.axiomsOfType(AxiomType.DISJOINT_UNION).forEach(axiom -> {
            if (axiom.getOWLClass().equals(subject)) {
                axioms.add(axiom);
            }
        });
        ontologies.axiomsOfType(AxiomType.HAS_KEY).forEach(axiom -> {
            if (axiom.getClassExpression().equals(subject)) {
                axioms.add(axiom);
            }
        });
        return axioms;
    }

    private static Optional<RawValue> restriction(OWLClassExpression expression) {
        return switch (expression) {
            case OWLObjectSomeValuesFrom some
            when some.getProperty() instanceof OWLObjectProperty property
                    && some.getFiller() instanceof OWLClass filler ->
                Optional.of(new RawValue(property, PropertyValueKind.SOME, new EntityFiller(filler)));
            case OWLObjectHasValue has
            when has.getProperty() instanceof OWLObjectProperty property
                    && has.getFiller() instanceof OWLNamedIndividual filler ->
                Optional.of(new RawValue(property, PropertyValueKind.HAS_VALUE, new EntityFiller(filler)));
            case OWLDataHasValue has
            when has.getProperty() instanceof OWLDataProperty property ->
                Optional.of(new RawValue(property, PropertyValueKind.HAS_VALUE, new LiteralFiller(has.getFiller())));
            case OWLDataSomeValuesFrom some
            when some.getProperty() instanceof OWLDataProperty property
                    && some.getFiller() instanceof OWLDatatype filler ->
                Optional.of(new RawValue(property, PropertyValueKind.DATA_SOME, new EntityFiller(filler)));
            default -> Optional.empty();
        };
    }

    private ClassFrame assemble(
            ProjectRuntime runtime,
            OWLClass subject,
            Collection<OWLAnnotationAssertionAxiom> annotations,
            Collection<OWLClass> parents,
            Collection<RawValue> values,
            Collection<OWLAxiom> others) {
        DisplayNameView names = runtime.view(DisplayNameView.class);
        List<AnnotationValue> renderedAnnotations = new ArrayList<>();
        for (OWLAnnotationAssertionAxiom axiom : annotations) {
            renderedAnnotations.add(annotation(runtime, axiom));
        }
        renderedAnnotations.sort(BY_ANNOTATION);
        List<EntityNode> renderedParents = new ArrayList<>(hierarchy.nodes(runtime, List.copyOf(parents)));
        renderedParents.sort(BY_NAME);
        List<PropertyValue> renderedValues = new ArrayList<>();
        for (RawValue value : values) {
            renderedValues.add(propertyValue(runtime, value));
        }
        renderedValues.sort(BY_VALUE);
        return new ClassFrame(
                node(runtime, subject),
                renderedAnnotations,
                renderedParents,
                renderedValues,
                manchester(names, others));
    }

    private AnnotationValue annotation(ProjectRuntime runtime, OWLAnnotationAssertionAxiom axiom) {
        ValueNode value = annotationValue(runtime, axiom.getValue());
        Optional<String> lang = value instanceof LiteralValue literal ? literal.lang() : Optional.empty();
        return new AnnotationValue(node(runtime, axiom.getProperty()), value, lang);
    }

    private ValueNode annotationValue(ProjectRuntime runtime, OWLAnnotationValue value) {
        return switch (value) {
            case OWLLiteral literal -> literal(literal);
            case IRI iri -> new IriValue(iriNode(runtime, iri));
            default -> throw new IllegalStateException("unsupported annotation value");
        };
    }

    private PropertyValue propertyValue(ProjectRuntime runtime, RawValue value) {
        return new PropertyValue(node(runtime, value.property()), value.kind(), filler(runtime, value.filler()));
    }

    private ValueNode filler(ProjectRuntime runtime, RawFiller filler) {
        return switch (filler) {
            case EntityFiller entity -> new IriValue(node(runtime, entity.entity()));
            case LiteralFiller literal -> literal(literal.literal());
        };
    }

    private EntityNode iriNode(ProjectRuntime runtime, IRI iri) {
        OWLDataFactory data = runtime.ontologies().dataFactory();
        for (EntityType<?> type : IRI_TYPES) {
            OWLEntity entity = data.getOWLEntity(asEntityType(type), iri);
            if (runtime.ontologies().containsEntity(entity)) {
                return node(runtime, entity);
            }
        }
        return new EntityNode(iri.toString(), "IRI", localName(iri), false, false);
    }

    private EntityNode node(ProjectRuntime runtime, OWLEntity entity) {
        return hierarchy.nodes(runtime, List.of(entity)).get(0);
    }

    private static LiteralValue literal(OWLLiteral literal) {
        String lang = literal.getLang();
        Optional<String> language = lang == null || lang.isBlank() ? Optional.empty() : Optional.of(lang);
        return new LiteralValue(
                literal.getLiteral(), literal.getDatatype().getIRI().toString(), language);
    }

    private static List<String> manchester(DisplayNameView names, Collection<OWLAxiom> axioms) {
        if (axioms.isEmpty()) {
            return List.of();
        }
        ManchesterOWLSyntaxOWLObjectRendererImpl renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
        renderer.setShortFormProvider(names::displayName);
        List<String> lines = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            lines.add(renderer.render(axiom).strip());
        }
        lines.sort(Comparator.naturalOrder());
        return List.copyOf(lines);
    }

    private static String valueKey(ValueNode value) {
        return switch (value) {
            case IriValue iri -> iri.entity().iri();
            case LiteralValue literal ->
                literal.lexical() + "\0" + literal.datatype() + "\0"
                        + literal.lang().orElse("");
        };
    }

    private static String localName(IRI iri) {
        String text = iri.toString();
        int hash = text.lastIndexOf('#');
        if (hash >= 0 && hash < text.length() - 1) {
            return text.substring(hash + 1);
        }
        int slash = text.lastIndexOf('/');
        if (slash >= 0 && slash < text.length() - 1) {
            return text.substring(slash + 1);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> asEntityType(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }

    private record RawValue(OWLEntity property, PropertyValueKind kind, RawFiller filler) {}

    private sealed interface RawFiller permits EntityFiller, LiteralFiller {}

    private record EntityFiller(OWLEntity entity) implements RawFiller {}

    private record LiteralFiller(OWLLiteral literal) implements RawFiller {}
}
