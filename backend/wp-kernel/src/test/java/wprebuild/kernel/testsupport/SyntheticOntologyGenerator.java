package wprebuild.kernel.testsupport;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Builds a deterministic class tree for scale tests.
 *
 * <p>The same parameters and seed always yield the same axiom set. {@code averageBranching} is the
 * tree's branch factor: every class except the root has one parent at index {@code (index - 1) /
 * averageBranching} after a seed-driven shuffle. A branch factor of {@code 0} adds no subclass
 * axioms. Random draws happen in a fixed order: shuffle the class list, then pick each
 * individual's type.
 */
public final class SyntheticOntologyGenerator {

    private static final IRI ONTOLOGY_IRI = IRI.create("http://example.org/wp-test/synthetic");

    private static final String NAMESPACE = "http://example.org/wp-test/synthetic#";

    private static final List<String> LABEL_LANGUAGES = List.of("en", "zh", "de", "fr", "es", "ja");

    private final Parameters parameters;

    private final Random random;

    private final OWLDataFactory dataFactory;

    private SyntheticOntologyGenerator(Parameters parameters) {
        this.parameters = parameters;
        this.random = new Random(parameters.seed());
        this.dataFactory = OWLManager.getOWLDataFactory();
    }

    public static OWLOntology generate(Parameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        return new SyntheticOntologyGenerator(parameters).build();
    }

    private OWLOntology build() {
        List<OWLClass> classes = createClasses();
        List<OWLAxiom> axioms = new ArrayList<>();
        axioms.addAll(declarations(classes));
        axioms.addAll(hierarchy(classes));
        axioms.addAll(labels(classes));
        axioms.addAll(notes(classes));
        axioms.addAll(individuals(classes));
        return ontologyWith(axioms);
    }

    private List<OWLClass> createClasses() {
        List<OWLClass> classes = new ArrayList<>(parameters.classCount());
        for (int index = 0; index < parameters.classCount(); index++) {
            classes.add(dataFactory.getOWLClass(iri("C" + index)));
        }
        return List.copyOf(classes);
    }

    private List<OWLAxiom> declarations(List<OWLClass> classes) {
        List<OWLAxiom> axioms = new ArrayList<>();
        classes.stream().map(dataFactory::getOWLDeclarationAxiom).forEach(axioms::add);
        return List.copyOf(axioms);
    }

    private List<OWLAxiom> hierarchy(List<OWLClass> classes) {
        if (parameters.averageBranching() == 0 || classes.size() < 2) {
            return List.of();
        }
        List<OWLClass> order = new ArrayList<>(classes);
        Collections.shuffle(order, random);
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int index = 1; index < order.size(); index++) {
            OWLClass parent = order.get((index - 1) / parameters.averageBranching());
            axioms.add(dataFactory.getOWLSubClassOfAxiom(order.get(index), parent));
        }
        return List.copyOf(axioms);
    }

    private List<OWLAxiom> labels(List<OWLClass> classes) {
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int classIndex = 0; classIndex < classes.size(); classIndex++) {
            for (int labelIndex = 0; labelIndex < parameters.labelLanguagesPerClass(); labelIndex++) {
                String language = LABEL_LANGUAGES.get(labelIndex % LABEL_LANGUAGES.size());
                String text = "class-" + classIndex + "-label-" + labelIndex;
                axioms.add(dataFactory.getOWLAnnotationAssertionAxiom(
                        dataFactory.getRDFSLabel(),
                        classes.get(classIndex).getIRI(),
                        dataFactory.getOWLLiteral(text, language)));
            }
        }
        return List.copyOf(axioms);
    }

    private List<OWLAxiom> notes(List<OWLClass> classes) {
        if (parameters.annotationAssertionsPerClass() == 0 || classes.isEmpty()) {
            return List.of();
        }
        OWLAnnotationProperty note = dataFactory.getOWLAnnotationProperty(iri("note"));
        List<OWLAxiom> axioms = new ArrayList<>();
        axioms.add(dataFactory.getOWLDeclarationAxiom(note));
        for (int classIndex = 0; classIndex < classes.size(); classIndex++) {
            for (int noteIndex = 0; noteIndex < parameters.annotationAssertionsPerClass(); noteIndex++) {
                String text = parameters.seed() + ":" + classIndex + ":" + noteIndex;
                axioms.add(dataFactory.getOWLAnnotationAssertionAxiom(
                        note, classes.get(classIndex).getIRI(), dataFactory.getOWLLiteral(text)));
            }
        }
        return List.copyOf(axioms);
    }

    private List<OWLAxiom> individuals(List<OWLClass> classes) {
        if (parameters.individualCount() == 0) {
            return List.of();
        }
        List<OWLAxiom> axioms = new ArrayList<>();
        for (int index = 0; index < parameters.individualCount(); index++) {
            OWLNamedIndividual individual = dataFactory.getOWLNamedIndividual(iri("I" + index));
            OWLClass type = classes.get(random.nextInt(classes.size()));
            axioms.add(dataFactory.getOWLDeclarationAxiom(individual));
            axioms.add(dataFactory.getOWLClassAssertionAxiom(type, individual));
        }
        return List.copyOf(axioms);
    }

    private static OWLOntology ontologyWith(Collection<OWLAxiom> axioms) {
        try {
            OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
            OWLOntology ontology = manager.createOntology(ONTOLOGY_IRI);
            manager.addAxioms(ontology, axioms);
            return ontology;
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException("failed to build synthetic ontology", ex);
        }
    }

    private static IRI iri(String localName) {
        return IRI.create(NAMESPACE + localName);
    }

    /**
     * @param classCount classes in the signature
     * @param averageBranching subclass branch factor; {@code 0} builds a flat list
     * @param labelLanguagesPerClass {@code rdfs:label} literals per class, cycling en/zh/de/fr/es/ja
     * @param annotationAssertionsPerClass extra annotation assertions per class
     * @param individualCount named individuals, each asserted to one generated class
     * @param seed source of the class shuffle and individual types
     */
    public record Parameters(
            int classCount,
            int averageBranching,
            int labelLanguagesPerClass,
            int annotationAssertionsPerClass,
            int individualCount,
            long seed) {

        public Parameters {
            requireNonNegative(classCount, "classCount");
            requireNonNegative(averageBranching, "averageBranching");
            requireNonNegative(labelLanguagesPerClass, "labelLanguagesPerClass");
            requireNonNegative(annotationAssertionsPerClass, "annotationAssertionsPerClass");
            requireNonNegative(individualCount, "individualCount");
            if (individualCount > 0 && classCount == 0) {
                throw new IllegalArgumentException("individualCount requires classCount > 0");
            }
        }

        private static void requireNonNegative(int value, String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be >= 0");
            }
        }
    }
}
