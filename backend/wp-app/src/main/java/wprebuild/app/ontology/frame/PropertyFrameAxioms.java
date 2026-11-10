package wprebuild.app.ontology.frame;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Editable axioms of a property frame.
 *
 * <p>{@code otherAxioms} are Manchester text and are not converted.
 */
public final class PropertyFrameAxioms {

    private static final String CLASS = "CLASS";

    private static final String OBJECT_PROPERTY = "OBJECT_PROPERTY";

    private static final String DATA_PROPERTY = "DATA_PROPERTY";

    private static final String ANNOTATION_PROPERTY = "ANNOTATION_PROPERTY";

    private static final String DATATYPE = "DATATYPE";

    private PropertyFrameAxioms() {}

    public static Set<OWLAxiom> toAxioms(ObjectPropertyFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireType(frame.subject(), OBJECT_PROPERTY);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI subjectIri = iri(frame.subject().iri());
        OWLObjectProperty subject = data.getOWLObjectProperty(subjectIri);
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        addAnnotations(data, subjectIri, frame.annotations(), axioms);
        for (EntityNode parent : frame.parents()) {
            axioms.add(data.getOWLSubObjectPropertyOfAxiom(subject, objectProperty(data, parent)));
        }
        for (EntityNode domain : frame.domains()) {
            axioms.add(data.getOWLObjectPropertyDomainAxiom(subject, namedClass(data, domain)));
        }
        for (EntityNode range : frame.ranges()) {
            axioms.add(data.getOWLObjectPropertyRangeAxiom(subject, namedClass(data, range)));
        }
        for (ObjectPropertyCharacteristic characteristic : frame.characteristics()) {
            axioms.add(characteristic.axiom(data, subject));
        }
        for (EntityNode inverse : frame.inverses()) {
            axioms.add(data.getOWLInverseObjectPropertiesAxiom(subject, objectProperty(data, inverse)));
        }
        return Set.copyOf(axioms);
    }

    public static Set<OWLAxiom> toAxioms(DataPropertyFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireType(frame.subject(), DATA_PROPERTY);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI subjectIri = iri(frame.subject().iri());
        OWLDataProperty subject = data.getOWLDataProperty(subjectIri);
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        addAnnotations(data, subjectIri, frame.annotations(), axioms);
        for (EntityNode parent : frame.parents()) {
            axioms.add(data.getOWLSubDataPropertyOfAxiom(subject, dataProperty(data, parent)));
        }
        for (EntityNode domain : frame.domains()) {
            axioms.add(data.getOWLDataPropertyDomainAxiom(subject, namedClass(data, domain)));
        }
        for (EntityNode range : frame.ranges()) {
            axioms.add(data.getOWLDataPropertyRangeAxiom(subject, datatype(data, range)));
        }
        if (frame.functional()) {
            axioms.add(data.getOWLFunctionalDataPropertyAxiom(subject));
        }
        return Set.copyOf(axioms);
    }

    public static Set<OWLAxiom> toAxioms(AnnotationPropertyFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireType(frame.subject(), ANNOTATION_PROPERTY);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI subjectIri = iri(frame.subject().iri());
        OWLAnnotationProperty subject = data.getOWLAnnotationProperty(subjectIri);
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        addAnnotations(data, subjectIri, frame.annotations(), axioms);
        for (EntityNode parent : frame.parents()) {
            axioms.add(data.getOWLSubAnnotationPropertyOfAxiom(subject, annotationProperty(data, parent)));
        }
        for (String domain : frame.domains()) {
            axioms.add(data.getOWLAnnotationPropertyDomainAxiom(subject, iri(domain)));
        }
        for (String range : frame.ranges()) {
            axioms.add(data.getOWLAnnotationPropertyRangeAxiom(subject, iri(range)));
        }
        return Set.copyOf(axioms);
    }

    private static void addAnnotations(
            OWLDataFactory data, IRI subject, Iterable<AnnotationValue> annotations, Set<OWLAxiom> axioms) {
        for (AnnotationValue annotation : annotations) {
            axioms.add(annotation(data, subject, annotation));
        }
    }

    private static OWLAnnotationAssertionAxiom annotation(
            OWLDataFactory data, IRI subject, AnnotationValue annotation) {
        return data.getOWLAnnotationAssertionAxiom(
                annotationProperty(data, annotation.property()), subject, annotationValue(data, annotation.value()));
    }

    private static OWLAnnotationValue annotationValue(OWLDataFactory data, ValueNode value) {
        return switch (value) {
            case LiteralValue literal -> literal(data, literal);
            case IriValue iri -> iri(iri.entity().iri());
        };
    }

    private static OWLLiteral literal(OWLDataFactory data, LiteralValue literal) {
        if (literal.lang().isPresent()) {
            return data.getOWLLiteral(literal.lexical(), literal.lang().get());
        }
        return data.getOWLLiteral(literal.lexical(), data.getOWLDatatype(iri(literal.datatype())));
    }

    private static OWLClass namedClass(OWLDataFactory data, EntityNode node) {
        requireType(node, CLASS);
        return data.getOWLClass(iri(node.iri()));
    }

    private static OWLObjectProperty objectProperty(OWLDataFactory data, EntityNode node) {
        requireType(node, OBJECT_PROPERTY);
        return data.getOWLObjectProperty(iri(node.iri()));
    }

    private static OWLDataProperty dataProperty(OWLDataFactory data, EntityNode node) {
        requireType(node, DATA_PROPERTY);
        return data.getOWLDataProperty(iri(node.iri()));
    }

    private static OWLDatatype datatype(OWLDataFactory data, EntityNode node) {
        requireType(node, DATATYPE);
        return data.getOWLDatatype(iri(node.iri()));
    }

    private static OWLAnnotationProperty annotationProperty(OWLDataFactory data, EntityNode node) {
        requireType(node, ANNOTATION_PROPERTY);
        return data.getOWLAnnotationProperty(iri(node.iri()));
    }

    private static void requireType(EntityNode node, String type) {
        if (!type.equals(node.entityType())) {
            throw new IllegalArgumentException(type + " expected, got " + node.entityType());
        }
    }

    private static IRI iri(String raw) {
        IRI parsed = IRI.create(raw);
        if (!parsed.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        return parsed;
    }
}
