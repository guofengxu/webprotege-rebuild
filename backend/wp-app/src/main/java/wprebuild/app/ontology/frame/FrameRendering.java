package wprebuild.app.ontology.frame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import wprebuild.app.ontology.HierarchyQuery;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameView;

/** Sorts and renders the values shared by property frames. */
final class FrameRendering {

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

    private final HierarchyQuery hierarchy;

    FrameRendering(HierarchyQuery hierarchy) {
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
    }

    List<AnnotationValue> annotations(ProjectRuntime runtime, Collection<OWLAnnotationAssertionAxiom> axioms) {
        List<AnnotationValue> rendered = new ArrayList<>();
        for (OWLAnnotationAssertionAxiom axiom : axioms) {
            rendered.add(annotation(runtime, axiom));
        }
        rendered.sort(BY_ANNOTATION);
        return List.copyOf(rendered);
    }

    List<EntityNode> entities(ProjectRuntime runtime, Collection<? extends OWLEntity> entities) {
        List<EntityNode> rendered = new ArrayList<>(hierarchy.nodes(runtime, List.copyOf(entities)));
        rendered.sort(BY_NAME);
        return List.copyOf(rendered);
    }

    EntityNode node(ProjectRuntime runtime, OWLEntity entity) {
        return hierarchy.nodes(runtime, List.of(entity)).get(0);
    }

    List<String> manchester(ProjectRuntime runtime, Collection<OWLAxiom> axioms) {
        if (axioms.isEmpty()) {
            return List.of();
        }
        DisplayNameView names = runtime.view(DisplayNameView.class);
        ManchesterOWLSyntaxOWLObjectRendererImpl renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
        renderer.setShortFormProvider(names::displayName);
        List<String> lines = new ArrayList<>();
        for (OWLAxiom axiom : axioms) {
            lines.add(renderer.render(axiom).strip());
        }
        lines.sort(Comparator.naturalOrder());
        return List.copyOf(lines);
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

    private static LiteralValue literal(OWLLiteral literal) {
        String lang = literal.getLang();
        Optional<String> language = lang == null || lang.isBlank() ? Optional.empty() : Optional.of(lang);
        return new LiteralValue(
                literal.getLiteral(), literal.getDatatype().getIRI().toString(), language);
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
}
