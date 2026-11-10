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
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Editable axioms of a named-individual frame.
 *
 * <p>{@code otherAxioms} are Manchester text and are not converted. Each same individual becomes one
 * binary {@code SameIndividual} axiom. Axioms with three or more individuals are not edited here.
 */
public final class IndividualFrameAxioms {

    private static final String CLASS = "CLASS";

    private static final String OBJECT_PROPERTY = "OBJECT_PROPERTY";

    private static final String DATA_PROPERTY = "DATA_PROPERTY";

    private static final String ANNOTATION_PROPERTY = "ANNOTATION_PROPERTY";

    private static final String NAMED_INDIVIDUAL = "NAMED_INDIVIDUAL";

    private IndividualFrameAxioms() {}

    public static Set<OWLAxiom> toAxioms(NamedIndividualFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireType(frame.subject(), NAMED_INDIVIDUAL);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        OWLNamedIndividual subject =
                data.getOWLNamedIndividual(iri(frame.subject().iri()));
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        addAnnotations(data, subject.getIRI(), frame.annotations(), axioms);
        for (EntityNode type : frame.types()) {
            axioms.add(data.getOWLClassAssertionAxiom(namedClass(data, type), subject));
        }
        for (ObjectPropertyAssertion assertion : frame.objectPropertyAssertions()) {
            axioms.add(data.getOWLObjectPropertyAssertionAxiom(
                    objectProperty(data, assertion.property()),
                    subject,
                    namedIndividual(data, assertion.individual())));
        }
        for (DataPropertyAssertion assertion : frame.dataPropertyAssertions()) {
            axioms.add(data.getOWLDataPropertyAssertionAxiom(
                    dataProperty(data, assertion.property()), subject, literal(data, assertion.value())));
        }
        for (EntityNode other : frame.sameIndividuals()) {
            if (frame.subject().iri().equals(other.iri())) {
                throw new IllegalArgumentException("same individual must differ from the subject");
            }
            axioms.add(data.getOWLSameIndividualAxiom(subject, namedIndividual(data, other)));
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

    private static OWLAnnotationProperty annotationProperty(OWLDataFactory data, EntityNode node) {
        requireType(node, ANNOTATION_PROPERTY);
        return data.getOWLAnnotationProperty(iri(node.iri()));
    }

    private static OWLNamedIndividual namedIndividual(OWLDataFactory data, EntityNode node) {
        requireType(node, NAMED_INDIVIDUAL);
        return data.getOWLNamedIndividual(iri(node.iri()));
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
