package wprebuild.app.ontology.frame;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationValue;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLIndividual;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.HierarchyQuery;
import wprebuild.app.ontology.IndividualPage;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Reads one named-individual frame, and the direct individuals of a class, under the project read
 * lock.
 *
 * <p>Named class assertions, annotation assertions, property assertions, and binary same individuals
 * are structured. Complex types, negative assertions, different individuals, and larger same-individual
 * axioms stay as Manchester text. Inferred types are not read.
 */
@Component
public final class IndividualFrameQuery {

    private static final Comparator<ObjectPropertyAssertion> BY_OBJECT = Comparator.comparing(
                    (ObjectPropertyAssertion assertion) -> assertion.property().iri())
            .thenComparing(assertion -> assertion.individual().iri());

    private static final Comparator<DataPropertyAssertion> BY_DATA = Comparator.comparing(
                    (DataPropertyAssertion assertion) -> assertion.property().iri())
            .thenComparing(assertion -> assertion.value().lexical())
            .thenComparing(assertion -> assertion.value().datatype())
            .thenComparing(assertion -> assertion.value().lang().orElse(""));

    private final ProjectRuntimeRegistry registry;

    private final FrameRendering rendering;

    IndividualFrameQuery(ProjectRuntimeRegistry registry, HierarchyQuery hierarchy) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.rendering = new FrameRendering(Objects.requireNonNull(hierarchy, "hierarchy"));
    }

    public NamedIndividualFrame frame(ProjectId projectId, IRI iri) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(iri, "iri");
        return locked(projectId, runtime -> frame(runtime, iri));
    }

    /** The caller already holds the project read lock or the generation lock. */
    NamedIndividualFrame frame(ProjectRuntime runtime, IRI iri) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(iri, "iri");
        ProjectOntologies ontologies = runtime.ontologies();
        OWLNamedIndividual subject = ontologies.dataFactory().getOWLNamedIndividual(iri);
        if (!ontologies.containsEntity(subject)) {
            throw new NotFoundException("named individual not found");
        }
        Parts parts = new Parts();
        readAnnotations(ontologies, iri, parts.annotations, parts.others);
        readTypes(ontologies, subject, parts);
        readObjectAssertions(ontologies, subject, parts);
        readDataAssertions(ontologies, subject, parts);
        readSame(ontologies, subject, parts);
        readDifferent(ontologies, subject, parts.others);
        readNegative(ontologies, subject, parts.others);
        return assemble(runtime, subject, parts);
    }

    public IndividualPage individuals(ProjectId projectId, IRI type, int page, int size) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(type, "class");
        return locked(projectId, runtime -> individuals(runtime, type, page, size));
    }

    private IndividualPage individuals(ProjectRuntime runtime, IRI type, int page, int size) {
        ProjectOntologies ontologies = runtime.ontologies();
        OWLClass cls = ontologies.dataFactory().getOWLClass(type);
        if (!ontologies.containsEntity(cls)) {
            throw new NotFoundException("class not found");
        }
        LinkedHashSet<OWLNamedIndividual> individuals = new LinkedHashSet<>();
        ontologies.axiomsOfType(AxiomType.CLASS_ASSERTION).forEach(axiom -> {
            if (cls.equals(axiom.getClassExpression()) && axiom.getIndividual() instanceof OWLNamedIndividual named) {
                individuals.add(named);
            }
        });
        List<EntityNode> nodes = rendering.entities(runtime, individuals);
        long start = (long) page * size;
        if (start >= nodes.size()) {
            return new IndividualPage(List.of(), page, size, nodes.size());
        }
        int from = (int) start;
        int to = (int) Math.min(start + size, nodes.size());
        return new IndividualPage(nodes.subList(from, to), page, size, nodes.size());
    }

    private NamedIndividualFrame assemble(ProjectRuntime runtime, OWLNamedIndividual subject, Parts parts) {
        List<ObjectPropertyAssertion> objects = new ArrayList<>();
        for (RawObject raw : parts.objects) {
            objects.add(new ObjectPropertyAssertion(
                    rendering.node(runtime, raw.property()), rendering.node(runtime, raw.individual())));
        }
        objects.sort(BY_OBJECT);
        List<DataPropertyAssertion> dataAssertions = new ArrayList<>();
        for (RawData raw : parts.data) {
            dataAssertions.add(
                    new DataPropertyAssertion(rendering.node(runtime, raw.property()), literal(raw.value())));
        }
        dataAssertions.sort(BY_DATA);
        return new NamedIndividualFrame(
                rendering.node(runtime, subject),
                rendering.annotations(runtime, parts.annotations),
                rendering.entities(runtime, parts.types),
                objects,
                dataAssertions,
                rendering.entities(runtime, parts.same),
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

    private static void readTypes(ProjectOntologies ontologies, OWLNamedIndividual subject, Parts parts) {
        ontologies.axiomsOfType(AxiomType.CLASS_ASSERTION).forEach(axiom -> {
            if (!subject.equals(axiom.getIndividual())) {
                return;
            }
            if (axiom.getClassExpression() instanceof OWLClass cls) {
                parts.types.add(cls);
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readObjectAssertions(ProjectOntologies ontologies, OWLNamedIndividual subject, Parts parts) {
        ontologies.axiomsOfType(AxiomType.OBJECT_PROPERTY_ASSERTION).forEach(axiom -> {
            if (!subject.equals(axiom.getSubject())) {
                return;
            }
            if (axiom.getProperty() instanceof OWLObjectProperty property
                    && axiom.getObject() instanceof OWLNamedIndividual object) {
                parts.objects.add(new RawObject(property, object));
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readDataAssertions(ProjectOntologies ontologies, OWLNamedIndividual subject, Parts parts) {
        ontologies.axiomsOfType(AxiomType.DATA_PROPERTY_ASSERTION).forEach(axiom -> {
            if (!subject.equals(axiom.getSubject())) {
                return;
            }
            if (axiom.getProperty() instanceof OWLDataProperty property) {
                parts.data.add(new RawData(property, axiom.getObject()));
            } else {
                parts.others.add(axiom);
            }
        });
    }

    private static void readSame(ProjectOntologies ontologies, OWLNamedIndividual subject, Parts parts) {
        ontologies.axiomsOfType(AxiomType.SAME_INDIVIDUAL).forEach(axiom -> {
            if (!axiom.getIndividuals().contains(subject)) {
                return;
            }
            // Only a binary pair round-trips. A larger axiom is not equal to the binaries we write.
            if (axiom.getIndividuals().size() == 2
                    && axiom.getIndividuals().stream().allMatch(OWLIndividual::isNamed)) {
                for (OWLIndividual individual : axiom.getIndividuals()) {
                    if (individual instanceof OWLNamedIndividual named && !named.equals(subject)) {
                        parts.same.add(named);
                    }
                }
                return;
            }
            parts.others.add(axiom);
        });
    }

    private static void readDifferent(
            ProjectOntologies ontologies, OWLNamedIndividual subject, Collection<OWLAxiom> others) {
        ontologies.axiomsOfType(AxiomType.DIFFERENT_INDIVIDUALS).forEach(axiom -> {
            if (axiom.getIndividuals().contains(subject)) {
                others.add(axiom);
            }
        });
    }

    private static void readNegative(
            ProjectOntologies ontologies, OWLNamedIndividual subject, Collection<OWLAxiom> others) {
        ontologies.axiomsOfType(AxiomType.NEGATIVE_OBJECT_PROPERTY_ASSERTION).forEach(axiom -> {
            if (subject.equals(axiom.getSubject())) {
                others.add(axiom);
            }
        });
        ontologies.axiomsOfType(AxiomType.NEGATIVE_DATA_PROPERTY_ASSERTION).forEach(axiom -> {
            if (subject.equals(axiom.getSubject())) {
                others.add(axiom);
            }
        });
    }

    private static LiteralValue literal(OWLLiteral literal) {
        String lang = literal.getLang();
        Optional<String> language = lang == null || lang.isBlank() ? Optional.empty() : Optional.of(lang);
        return new LiteralValue(
                literal.getLiteral(), literal.getDatatype().getIRI().toString(), language);
    }

    private static final class Parts {
        private final Set<OWLAnnotationAssertionAxiom> annotations = new LinkedHashSet<>();
        private final Set<OWLClass> types = new LinkedHashSet<>();
        private final Set<RawObject> objects = new LinkedHashSet<>();
        private final Set<RawData> data = new LinkedHashSet<>();
        private final Set<OWLNamedIndividual> same = new LinkedHashSet<>();
        private final Set<OWLAxiom> others = new LinkedHashSet<>();
    }

    private record RawObject(OWLObjectProperty property, OWLNamedIndividual individual) {}

    private record RawData(OWLDataProperty property, OWLLiteral value) {}
}
