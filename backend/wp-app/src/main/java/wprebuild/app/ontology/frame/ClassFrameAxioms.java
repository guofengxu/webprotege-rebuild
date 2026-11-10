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
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.app.ontology.dto.EntityNode;

/**
 * Inverse of {@link ClassFrameQuery} for the editable part of a class frame.
 *
 * <p>{@code otherAxioms} are Manchester text and are not converted.
 */
public final class ClassFrameAxioms {

    private static final String CLASS = "CLASS";

    private static final String OBJECT_PROPERTY = "OBJECT_PROPERTY";

    private static final String DATA_PROPERTY = "DATA_PROPERTY";

    private static final String ANNOTATION_PROPERTY = "ANNOTATION_PROPERTY";

    private static final String NAMED_INDIVIDUAL = "NAMED_INDIVIDUAL";

    private static final String DATATYPE = "DATATYPE";

    private ClassFrameAxioms() {}

    public static Set<OWLAxiom> toAxioms(ClassFrame frame) {
        Objects.requireNonNull(frame, "frame");
        requireType(frame.subject(), CLASS);
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        IRI subjectIri = iri(frame.subject().iri());
        OWLClass subject = data.getOWLClass(subjectIri);
        LinkedHashSet<OWLAxiom> axioms = new LinkedHashSet<>();
        for (AnnotationValue annotation : frame.annotations()) {
            axioms.add(annotation(data, subjectIri, annotation));
        }
        for (EntityNode parent : frame.parents()) {
            axioms.add(data.getOWLSubClassOfAxiom(subject, parentClass(data, parent)));
        }
        for (PropertyValue value : frame.propertyValues()) {
            axioms.add(data.getOWLSubClassOfAxiom(subject, restriction(data, value)));
        }
        return Set.copyOf(axioms);
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

    private static OWLClassExpression restriction(OWLDataFactory data, PropertyValue value) {
        return switch (value.kind()) {
            case SOME ->
                data.getOWLObjectSomeValuesFrom(
                        objectProperty(data, value.property()), classFiller(data, value.filler()));
            case HAS_VALUE -> hasValue(data, value);
            case DATA_SOME ->
                data.getOWLDataSomeValuesFrom(
                        dataProperty(data, value.property()), datatypeFiller(data, value.filler()));
        };
    }

    private static OWLClassExpression hasValue(OWLDataFactory data, PropertyValue value) {
        if (OBJECT_PROPERTY.equals(value.property().entityType())) {
            return data.getOWLObjectHasValue(
                    objectProperty(data, value.property()), individualFiller(data, value.filler()));
        }
        if (DATA_PROPERTY.equals(value.property().entityType())) {
            return data.getOWLDataHasValue(dataProperty(data, value.property()), literalFiller(data, value.filler()));
        }
        throw new IllegalArgumentException("HAS_VALUE property must be an object or data property");
    }

    private static OWLClass parentClass(OWLDataFactory data, EntityNode node) {
        requireType(node, CLASS);
        return data.getOWLClass(iri(node.iri()));
    }

    private static OWLClass classFiller(OWLDataFactory data, ValueNode filler) {
        EntityNode entity = iriEntity(filler, CLASS);
        return data.getOWLClass(iri(entity.iri()));
    }

    private static OWLNamedIndividual individualFiller(OWLDataFactory data, ValueNode filler) {
        EntityNode entity = iriEntity(filler, NAMED_INDIVIDUAL);
        return data.getOWLNamedIndividual(iri(entity.iri()));
    }

    private static OWLDatatype datatypeFiller(OWLDataFactory data, ValueNode filler) {
        EntityNode entity = iriEntity(filler, DATATYPE);
        return data.getOWLDatatype(iri(entity.iri()));
    }

    private static OWLLiteral literalFiller(OWLDataFactory data, ValueNode filler) {
        if (filler instanceof LiteralValue literal) {
            return literal(data, literal);
        }
        throw new IllegalArgumentException("data value must be a literal");
    }

    private static OWLLiteral literal(OWLDataFactory data, LiteralValue literal) {
        if (literal.lang().isPresent()) {
            return data.getOWLLiteral(literal.lexical(), literal.lang().get());
        }
        return data.getOWLLiteral(literal.lexical(), data.getOWLDatatype(iri(literal.datatype())));
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

    private static EntityNode iriEntity(ValueNode filler, String type) {
        if (filler instanceof IriValue iri) {
            requireType(iri.entity(), type);
            return iri.entity();
        }
        throw new IllegalArgumentException(type + " filler must be an IRI");
    }

    private static void requireType(EntityNode node, String type) {
        if (!type.equals(node.entityType())) {
            throw new IllegalArgumentException(type + " expected, got " + node.entityType());
        }
    }

    private static IRI iri(String raw) {
        IRI iri = IRI.create(raw);
        if (!iri.isAbsolute()) {
            throw new IllegalArgumentException("IRI must be absolute");
        }
        return iri;
    }
}
