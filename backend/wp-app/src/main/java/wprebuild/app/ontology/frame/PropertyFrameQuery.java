package wprebuild.app.ontology.frame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLObjectPropertyExpression;
import org.semanticweb.owlapi.model.OWLUnaryPropertyAxiom;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.HierarchyQuery;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Reads one property frame under the project read lock.
 *
 * <p>Named parents, domains, ranges, characteristics, and inverses are structured. Property chains,
 * disjoint properties, equivalent properties, and other subject axioms are Manchester text.
 */
@Component
public final class PropertyFrameQuery {

    private final ProjectRuntimeRegistry registry;

    private final FrameRendering rendering;

    PropertyFrameQuery(ProjectRuntimeRegistry registry, HierarchyQuery hierarchy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.rendering = new FrameRendering(Objects.requireNonNull(hierarchy, "hierarchy"));
    }

    public ObjectPropertyFrame objectFrame(ProjectId projectId, IRI iri) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        return locked(projectId, runtime -> objectFrame(runtime, iri));
    }

    public DataPropertyFrame dataFrame(ProjectId projectId, IRI iri) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        return locked(projectId, runtime -> dataFrame(runtime, iri));
    }

    public AnnotationPropertyFrame annotationFrame(ProjectId projectId, IRI iri) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        return locked(projectId, runtime -> annotationFrame(runtime, iri));
    }

    /** The caller already holds the project read lock or the generation lock. */
    ObjectPropertyFrame objectFrame(ProjectRuntime runtime, IRI iri) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(iri, "iri");
        ProjectOntologies ontologies = runtime.ontologies();
        OWLObjectProperty subject = ontologies.dataFactory().getOWLObjectProperty(iri);
        if (!ontologies.containsEntity(subject)) {
            throw new NotFoundException("object property not found");
        }
        ObjectParts parts = new ObjectParts();
        readAnnotations(ontologies, iri, parts.annotations, parts.others);
        readObjectParents(ontologies, subject, parts);
        readObjectDomains(ontologies, subject, parts);
        readObjectRanges(ontologies, subject, parts);
        readCharacteristics(ontologies, subject, parts.characteristics);
        readInverses(ontologies, subject, parts);
        keep(
                ontologies.axiomsOfType(AxiomType.SUB_PROPERTY_CHAIN_OF),
                parts.others,
                axiom -> subject.equals(axiom.getSuperProperty()));
        keep(
                ontologies.axiomsOfType(AxiomType.DISJOINT_OBJECT_PROPERTIES),
                parts.others,
                axiom -> axiom.properties().anyMatch(subject::equals));
        keep(
                ontologies.axiomsOfType(AxiomType.EQUIVALENT_OBJECT_PROPERTIES),
                parts.others,
                axiom -> axiom.properties().anyMatch(subject::equals));
        return new ObjectPropertyFrame(
                rendering.node(runtime, subject),
                rendering.annotations(runtime, parts.annotations),
                rendering.entities(runtime, parts.parents),
                rendering.entities(runtime, parts.domains),
                rendering.entities(runtime, parts.ranges),
                sorted(parts.characteristics),
                rendering.entities(runtime, parts.inverses),
                rendering.manchester(runtime, parts.others));
    }

    /** The caller already holds the project read lock or the generation lock. */
    DataPropertyFrame dataFrame(ProjectRuntime runtime, IRI iri) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(iri, "iri");
        ProjectOntologies ontologies = runtime.ontologies();
        OWLDataProperty subject = ontologies.dataFactory().getOWLDataProperty(iri);
        if (!ontologies.containsEntity(subject)) {
            throw new NotFoundException("data property not found");
        }
        DataParts parts = new DataParts();
        readAnnotations(ontologies, iri, parts.annotations, parts.others);
        readDataParents(ontologies, subject, parts);
        readDataDomains(ontologies, subject, parts);
        readDataRanges(ontologies, subject, parts);
        parts.functional = ontologies
                .axiomsOfType(AxiomType.FUNCTIONAL_DATA_PROPERTY)
                .anyMatch(axiom -> axiom.getProperty().equals(subject));
        keep(
                ontologies.axiomsOfType(AxiomType.DISJOINT_DATA_PROPERTIES),
                parts.others,
                axiom -> axiom.properties().anyMatch(subject::equals));
        keep(
                ontologies.axiomsOfType(AxiomType.EQUIVALENT_DATA_PROPERTIES),
                parts.others,
                axiom -> axiom.properties().anyMatch(subject::equals));
        return new DataPropertyFrame(
                rendering.node(runtime, subject),
                rendering.annotations(runtime, parts.annotations),
                rendering.entities(runtime, parts.parents),
                rendering.entities(runtime, parts.domains),
                rendering.entities(runtime, parts.ranges),
                parts.functional,
                rendering.manchester(runtime, parts.others));
    }

    /** The caller already holds the project read lock or the generation lock. */
    AnnotationPropertyFrame annotationFrame(ProjectRuntime runtime, IRI iri) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(iri, "iri");
        ProjectOntologies ontologies = runtime.ontologies();
        OWLAnnotationProperty subject = ontologies.dataFactory().getOWLAnnotationProperty(iri);
        if (!ontologies.containsEntity(subject)) {
            throw new NotFoundException("annotation property not found");
        }
        AnnotationParts parts = new AnnotationParts();
        readAnnotations(ontologies, iri, parts.annotations, parts.others);
        ontologies.axiomsOfType(AxiomType.SUB_ANNOTATION_PROPERTY_OF).forEach(axiom -> {
            if (axiom.getSubProperty().equals(subject)) {
                parts.parents.add(axiom.getSuperProperty());
            }
        });
        ontologies.axiomsOfType(AxiomType.ANNOTATION_PROPERTY_DOMAIN).forEach(axiom -> {
            if (axiom.getProperty().equals(subject)) {
                parts.domains.add(axiom.getDomain());
            }
        });
        ontologies.axiomsOfType(AxiomType.ANNOTATION_PROPERTY_RANGE).forEach(axiom -> {
            if (axiom.getProperty().equals(subject)) {
                parts.ranges.add(axiom.getRange());
            }
        });
        return new AnnotationPropertyFrame(
                rendering.node(runtime, subject),
                rendering.annotations(runtime, parts.annotations),
                rendering.entities(runtime, parts.parents),
                iris(parts.domains),
                iris(parts.ranges),
                rendering.manchester(runtime, parts.others));
    }

    private <T> T locked(ProjectId projectId, Function<ProjectRuntime, T> query) {
        ProjectHandle handle = registry.get(projectId);
        return handle.gate().read(() -> {
            if (handle.runtime().isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + projectId);
            }
            return query.apply(handle.runtime());
        });
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

    private static void readObjectParents(ProjectOntologies ontologies, OWLObjectProperty subject, ObjectParts parts) {
        ontologies.axiomsOfType(AxiomType.SUB_OBJECT_PROPERTY).forEach(axiom -> {
            if (!axiom.getSubProperty().equals(subject)) {
                return;
            }
            if (axiom.getSuperProperty() instanceof OWLObjectProperty parent) {
                parts.parents.add(parent);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readObjectDomains(ProjectOntologies ontologies, OWLObjectProperty subject, ObjectParts parts) {
        ontologies.axiomsOfType(AxiomType.OBJECT_PROPERTY_DOMAIN).forEach(axiom -> {
            if (!axiom.getProperty().equals(subject)) {
                return;
            }
            if (axiom.getDomain() instanceof OWLClass domain) {
                parts.domains.add(domain);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readObjectRanges(ProjectOntologies ontologies, OWLObjectProperty subject, ObjectParts parts) {
        ontologies.axiomsOfType(AxiomType.OBJECT_PROPERTY_RANGE).forEach(axiom -> {
            if (!axiom.getProperty().equals(subject)) {
                return;
            }
            if (axiom.getRange() instanceof OWLClass range) {
                parts.ranges.add(range);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readCharacteristics(
            ProjectOntologies ontologies,
            OWLObjectProperty subject,
            Set<ObjectPropertyCharacteristic> characteristics) {
        characteristic(
                ontologies,
                AxiomType.FUNCTIONAL_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.FUNCTIONAL,
                characteristics);
        characteristic(
                ontologies,
                AxiomType.INVERSE_FUNCTIONAL_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.INVERSE_FUNCTIONAL,
                characteristics);
        characteristic(
                ontologies,
                AxiomType.TRANSITIVE_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.TRANSITIVE,
                characteristics);
        characteristic(
                ontologies,
                AxiomType.SYMMETRIC_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.SYMMETRIC,
                characteristics);
        characteristic(
                ontologies,
                AxiomType.ASYMMETRIC_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.ASYMMETRIC,
                characteristics);
        characteristic(
                ontologies,
                AxiomType.REFLEXIVE_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.REFLEXIVE,
                characteristics);
        characteristic(
                ontologies,
                AxiomType.IRREFLEXIVE_OBJECT_PROPERTY,
                subject,
                ObjectPropertyCharacteristic.IRREFLEXIVE,
                characteristics);
    }

    private static void characteristic(
            ProjectOntologies ontologies,
            AxiomType<? extends OWLUnaryPropertyAxiom<?>> type,
            OWLObjectProperty subject,
            ObjectPropertyCharacteristic characteristic,
            Set<ObjectPropertyCharacteristic> characteristics) {
        ontologies.axiomsOfType(type).forEach(axiom -> {
            if (axiom.getProperty().equals(subject)) {
                characteristics.add(characteristic);
            }
        });
    }

    private static void readInverses(ProjectOntologies ontologies, OWLObjectProperty subject, ObjectParts parts) {
        ontologies.axiomsOfType(AxiomType.INVERSE_OBJECT_PROPERTIES).forEach(axiom -> {
            List<OWLObjectPropertyExpression> properties = axiom.properties().toList();
            if (properties.stream().noneMatch(subject::equals)) {
                return;
            }
            if (!properties.stream().allMatch(OWLObjectProperty.class::isInstance)) {
                parts.others.add(axiom);
                return;
            }
            for (OWLObjectPropertyExpression property : properties) {
                if (!property.equals(subject)) {
                    parts.inverses.add((OWLObjectProperty) property);
                }
            }
        });
    }

    private static void readDataParents(ProjectOntologies ontologies, OWLDataProperty subject, DataParts parts) {
        ontologies.axiomsOfType(AxiomType.SUB_DATA_PROPERTY).forEach(axiom -> {
            if (!axiom.getSubProperty().equals(subject)) {
                return;
            }
            if (axiom.getSuperProperty() instanceof OWLDataProperty parent) {
                parts.parents.add(parent);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readDataDomains(ProjectOntologies ontologies, OWLDataProperty subject, DataParts parts) {
        ontologies.axiomsOfType(AxiomType.DATA_PROPERTY_DOMAIN).forEach(axiom -> {
            if (!axiom.getProperty().equals(subject)) {
                return;
            }
            if (axiom.getDomain() instanceof OWLClass domain) {
                parts.domains.add(domain);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readDataRanges(ProjectOntologies ontologies, OWLDataProperty subject, DataParts parts) {
        ontologies.axiomsOfType(AxiomType.DATA_PROPERTY_RANGE).forEach(axiom -> {
            if (!axiom.getProperty().equals(subject)) {
                return;
            }
            if (axiom.getRange() instanceof OWLDatatype range) {
                parts.ranges.add(range);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static <T extends OWLAxiom> void keep(
            Stream<T> axioms, Collection<OWLAxiom> others, Predicate<T> involved) {
        axioms.forEach(axiom -> {
            if (involved.test(axiom)) {
                others.add(axiom);
            }
        });
    }

    private static List<ObjectPropertyCharacteristic> sorted(Collection<ObjectPropertyCharacteristic> values) {
        List<ObjectPropertyCharacteristic> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.comparing(ObjectPropertyCharacteristic::name));
        return List.copyOf(ordered);
    }

    private static List<String> iris(Collection<IRI> iris) {
        List<String> ordered = new ArrayList<>();
        for (IRI iri : iris) {
            ordered.add(iri.toString());
        }
        ordered.sort(Comparator.naturalOrder());
        return List.copyOf(ordered);
    }

    private static final class ObjectParts {
        private final Set<OWLAnnotationAssertionAxiom> annotations = new LinkedHashSet<>();
        private final Set<OWLObjectProperty> parents = new LinkedHashSet<>();
        private final Set<OWLClass> domains = new LinkedHashSet<>();
        private final Set<OWLClass> ranges = new LinkedHashSet<>();
        private final Set<ObjectPropertyCharacteristic> characteristics = new LinkedHashSet<>();
        private final Set<OWLObjectProperty> inverses = new LinkedHashSet<>();
        private final Set<OWLAxiom> others = new LinkedHashSet<>();
    }

    private static final class DataParts {
        private final Set<OWLAnnotationAssertionAxiom> annotations = new LinkedHashSet<>();
        private final Set<OWLDataProperty> parents = new LinkedHashSet<>();
        private final Set<OWLClass> domains = new LinkedHashSet<>();
        private final Set<OWLDatatype> ranges = new LinkedHashSet<>();
        private final Set<OWLAxiom> others = new LinkedHashSet<>();
        private boolean functional;
    }

    private static final class AnnotationParts {
        private final Set<OWLAnnotationAssertionAxiom> annotations = new LinkedHashSet<>();
        private final Set<OWLAnnotationProperty> parents = new LinkedHashSet<>();
        private final Set<IRI> domains = new LinkedHashSet<>();
        private final Set<IRI> ranges = new LinkedHashSet<>();
        private final Set<OWLAxiom> others = new LinkedHashSet<>();
    }
}
