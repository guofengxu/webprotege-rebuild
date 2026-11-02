package wprebuild.kernel.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLObjectIntersectionOf;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.testsupport.SyntheticOntologyGenerator.Parameters;

class TestCorpusTest {

    /**
     * Axiom types that functional syntax cannot carry in this corpus, mapped to the reason. Empty:
     * OWL API 5.5.1 parses every {@link AxiomType#AXIOM_TYPES} member from {@code axioms-all-types.ofn}.
     * OWL 2 has no equivalent or disjoint annotation-property axioms, so those relations are not
     * axiom types and are not listed here.
     */
    static final Map<AxiomType<?>, String> UNCOVERABLE_AXIOM_TYPES = Map.of();

    private static final List<String> REQUIRED_FILES = List.of(
            "axioms-all-types.ofn",
            "labels-multilingual.ttl",
            "warehouse-sample.ttl",
            "imports/a.owl",
            "imports/b.owl",
            "imports/main.owl",
            "formats/warehouse-sample.owl",
            "formats/warehouse-sample.ttl",
            "formats/warehouse-sample.owx",
            "formats/warehouse-sample.ofn",
            "formats/warehouse-sample.omn");

    private static final List<String> WAREHOUSE_FORMATS = List.of(
            "formats/warehouse-sample.owl",
            "formats/warehouse-sample.ttl",
            "formats/warehouse-sample.owx",
            "formats/warehouse-sample.ofn",
            "formats/warehouse-sample.omn");

    @Test
    void parses_every_corpus_file() throws IOException {
        Path root = TestOntologies.dir();
        assertThat(root).isDirectory();
        for (String relative : REQUIRED_FILES) {
            assertThat(root.resolve(relative)).as(relative).isRegularFile();
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .sorted()
                    .toList();
            assertThat(files).isNotEmpty();
            for (Path file : files) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                assertThat(TestOntologies.load(relative).getOntologyID())
                        .as(relative)
                        .isNotNull();
            }
        }
    }

    @Test
    void axioms_all_types_covers_every_axiom_type() {
        UNCOVERABLE_AXIOM_TYPES.forEach((type, reason) -> {
            assertThat(reason).as(type.getName()).isNotBlank();
            assertThat(AxiomType.AXIOM_TYPES).contains(type);
        });

        OWLOntology ontology = TestOntologies.load("axioms-all-types.ofn");
        Set<String> present = new TreeSet<>();
        ontology.axioms().map(axiom -> axiom.getAxiomType().getName()).forEach(present::add);
        Set<String> uncoverable = new HashSet<>();
        UNCOVERABLE_AXIOM_TYPES.keySet().stream().map(AxiomType::getName).forEach(uncoverable::add);
        Set<String> missing = new TreeSet<>();
        AxiomType.AXIOM_TYPES.stream()
                .map(AxiomType::getName)
                .filter(name -> !present.contains(name) && !uncoverable.contains(name))
                .forEach(missing::add);
        assertThat(missing).isEmpty();
        if (!uncoverable.isEmpty()) {
            assertThat(present).doesNotContainAnyElementsOf(uncoverable);
        }

        Set<EntityType<?>> declared = new HashSet<>();
        ontology.axioms(AxiomType.DECLARATION)
                .map(axiom -> axiom.getEntity().getEntityType())
                .forEach(declared::add);
        assertThat(declared)
                .contains(
                        EntityType.CLASS,
                        EntityType.OBJECT_PROPERTY,
                        EntityType.DATA_PROPERTY,
                        EntityType.ANNOTATION_PROPERTY,
                        EntityType.NAMED_INDIVIDUAL,
                        EntityType.DATATYPE);
        assertThat(ontology.axioms(AxiomType.EQUIVALENT_CLASSES))
                .anyMatch(axiom ->
                        axiom.getClassExpressions().stream().anyMatch(OWLObjectIntersectionOf.class::isInstance));
        assertThat(ontology.axioms().flatMap(OWLAxiom::anonymousIndividuals)).isNotEmpty();
        assertThat(ontology.axioms(AxiomType.SWRL_RULE)).hasSize(1);
        assertThat(literalValues(ontology)).anyMatch(text -> text.contains("人"));
        assertThat(languages(ontology)).contains("zh", "en");
    }

    @Test
    void labels_multilingual_mixes_zh_and_en_on_twenty_classes() {
        OWLOntology ontology = TestOntologies.load("labels-multilingual.ttl");
        assertThat(ontology.getClassesInSignature()).hasSize(20);
        IRI label = OWLRDFVocabulary.RDFS_LABEL.getIRI();
        IRI prefLabel = SKOSVocabulary.PREFLABEL.getIRI();
        Set<IRI> bothLanguages = new HashSet<>();
        Set<IRI> labelOnlyOneLanguage = new HashSet<>();
        Set<IRI> missingLabel = new HashSet<>();
        Set<IRI> missingPrefLabel = new HashSet<>();
        Set<String> labelLanguages = new HashSet<>();
        Set<String> prefLanguages = new HashSet<>();
        for (OWLClass cls : ontology.getClassesInSignature()) {
            Set<String> labels = languages(ontology, cls.getIRI(), label);
            Set<String> prefs = languages(ontology, cls.getIRI(), prefLabel);
            labelLanguages.addAll(labels);
            prefLanguages.addAll(prefs);
            if (labels.isEmpty()) {
                missingLabel.add(cls.getIRI());
            } else if (labels.size() == 1) {
                labelOnlyOneLanguage.add(cls.getIRI());
            } else {
                bothLanguages.add(cls.getIRI());
            }
            if (prefs.isEmpty()) {
                missingPrefLabel.add(cls.getIRI());
            }
        }
        assertThat(labelLanguages).contains("zh", "en");
        assertThat(prefLanguages).contains("zh", "en");
        assertThat(bothLanguages).isNotEmpty();
        assertThat(labelOnlyOneLanguage).isNotEmpty();
        assertThat(missingLabel).isNotEmpty();
        assertThat(missingPrefLabel).isNotEmpty();
    }

    @Test
    void imports_resolve_to_the_local_documents() {
        OWLOntology main = TestOntologies.load("imports/main.owl");
        assertThat(main.importsDeclarations())
                .extracting(declaration -> declaration.getIRI().toString())
                .containsExactlyInAnyOrder(
                        "http://example.org/wp-test/imports/a", "http://example.org/wp-test/imports/b");
        assertThat(main.imports()).hasSize(2);
        assertThat(main.classesInSignature(Imports.INCLUDED))
                .extracting(cls -> cls.getIRI().toString())
                .contains(
                        "http://example.org/wp-test/imports/a#ComponentA",
                        "http://example.org/wp-test/imports/b#ComponentB",
                        "http://example.org/wp-test/imports/main#MainConcept");
    }

    @Test
    void warehouse_sample_round_trips_across_the_five_formats() {
        OWLOntology source = TestOntologies.load("warehouse-sample.ttl");
        assertThat(source.getClassesInSignature()).hasSize(30);
        assertThat(languages(source)).contains("zh", "en");
        assertThat(literalValues(source)).contains("托盘", "货位", "巷道", "堆垛机", "自动导引车");
        Set<OWLAxiom> axioms = comparableAxioms(source);
        for (String relative : WAREHOUSE_FORMATS) {
            assertThat(comparableAxioms(TestOntologies.load(relative)))
                    .as(relative)
                    .isEqualTo(axioms);
        }
    }

    @Test
    void generator_repeats_the_same_axiom_set_for_the_same_seed() {
        Parameters parameters = new Parameters(12, 3, 2, 1, 5, 42L);
        OWLOntology first = SyntheticOntologyGenerator.generate(parameters);
        OWLOntology second = SyntheticOntologyGenerator.generate(parameters);
        assertThat(second.getAxioms()).isEqualTo(first.getAxioms());
        assertThat(first.getClassesInSignature()).hasSize(12);
        assertThat(first.getIndividualsInSignature()).hasSize(5);
        assertThat(first.getAxioms(AxiomType.SUBCLASS_OF)).hasSize(11);
        assertThat(first.getAxioms(AxiomType.ANNOTATION_ASSERTION)).hasSize(12 * 2 + 12);

        Parameters otherSeed = new Parameters(12, 3, 2, 1, 5, 99L);
        assertThat(SyntheticOntologyGenerator.generate(otherSeed).getAxioms()).isNotEqualTo(first.getAxioms());

        Parameters flat = new Parameters(4, 0, 1, 0, 0, 7L);
        OWLOntology flatOntology = SyntheticOntologyGenerator.generate(flat);
        assertThat(flatOntology.getAxioms(AxiomType.SUBCLASS_OF)).isEmpty();
        assertThat(flatOntology.getClassesInSignature()).hasSize(4);
    }

    @Test
    void generator_rejects_negative_counts() {
        assertThatThrownBy(() -> new Parameters(-1, 0, 0, 0, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classCount");
        assertThatThrownBy(() -> new Parameters(0, -1, 0, 0, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("averageBranching");
        assertThatThrownBy(() -> new Parameters(0, 0, -1, 0, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("labelLanguagesPerClass");
        assertThatThrownBy(() -> new Parameters(0, 0, 0, -1, 0, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("annotationAssertionsPerClass");
        assertThatThrownBy(() -> new Parameters(0, 0, 0, 0, -1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("individualCount");
        assertThatThrownBy(() -> new Parameters(0, 0, 0, 0, 1, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("classCount");
        assertThatThrownBy(() -> SyntheticOntologyGenerator.generate(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("parameters");
    }

    @Test
    void dir_uses_the_system_property_or_the_module_relative_default() {
        String configured = System.getProperty(TestOntologies.DIRECTORY_PROPERTY);
        assertThat(configured).isNotBlank();
        assertThat(TestOntologies.dir()).isEqualTo(Path.of(configured.trim()));

        String previous = configured;
        try {
            System.clearProperty(TestOntologies.DIRECTORY_PROPERTY);
            assertThat(TestOntologies.dir()).isEqualTo(TestOntologies.DEFAULT_DIRECTORY);
            System.setProperty(TestOntologies.DIRECTORY_PROPERTY, "   ");
            assertThat(TestOntologies.dir()).isEqualTo(TestOntologies.DEFAULT_DIRECTORY);
        } finally {
            System.setProperty(TestOntologies.DIRECTORY_PROPERTY, previous);
        }
    }

    @Timeout(value = 2, unit = TimeUnit.SECONDS)
    @Test
    void load_refuses_unmapped_http_imports() throws IOException {
        Path temp = Files.createTempDirectory("wp-corpus");
        Files.writeString(temp.resolve("remote-import.owl"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <owl:Ontology rdf:about="http://example.org/wp-test/remote-root">
                        <owl:imports rdf:resource="http://invalid.invalid/missing"/>
                    </owl:Ontology>
                </rdf:RDF>
                """);
        withCorpusDirectory(
                temp,
                () -> assertThatThrownBy(() -> TestOntologies.load("remote-import.owl"))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasStackTraceContaining("refusing to fetch remote import")
                        .hasStackTraceContaining("http://invalid.invalid/missing"));
    }

    @Test
    void import_by_iri_uses_the_canonical_document_not_a_format_copy() throws IOException {
        Path temp = Files.createTempDirectory("wp-corpus");
        Files.createDirectories(temp.resolve("formats"));
        Files.writeString(temp.resolve("source.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix ex: <http://example.org/wp-test/canonical#> .
                <http://example.org/wp-test/canonical> a owl:Ontology .
                ex:SourceOnly a owl:Class .
                """);
        Files.writeString(temp.resolve("formats/copy.ttl"), """
                @prefix owl: <http://www.w3.org/2002/07/owl#> .
                @prefix ex: <http://example.org/wp-test/canonical#> .
                <http://example.org/wp-test/canonical> a owl:Ontology .
                ex:FormatOnly a owl:Class .
                """);
        Files.writeString(temp.resolve("root.owl"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <rdf:RDF xmlns:owl="http://www.w3.org/2002/07/owl#"
                     xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
                    <owl:Ontology rdf:about="http://example.org/wp-test/canonical-root">
                        <owl:imports rdf:resource="http://example.org/wp-test/canonical"/>
                    </owl:Ontology>
                </rdf:RDF>
                """);
        withCorpusDirectory(temp, () -> {
            OWLOntology ontology = TestOntologies.load("root.owl");
            assertThat(ontology.classesInSignature(Imports.INCLUDED))
                    .extracting(cls -> cls.getIRI().toString())
                    .contains("http://example.org/wp-test/canonical#SourceOnly")
                    .doesNotContain("http://example.org/wp-test/canonical#FormatOnly");
        });
    }

    private static void withCorpusDirectory(Path directory, IoAction action) throws IOException {
        String previous = System.getProperty(TestOntologies.DIRECTORY_PROPERTY);
        System.setProperty(TestOntologies.DIRECTORY_PROPERTY, directory.toString());
        try {
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(TestOntologies.DIRECTORY_PROPERTY);
            } else {
                System.setProperty(TestOntologies.DIRECTORY_PROPERTY, previous);
            }
            deleteRecursively(directory);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }

    private static Set<OWLAxiom> comparableAxioms(OWLOntology ontology) {
        Set<OWLAxiom> axioms = new HashSet<>();
        ontology.axioms().filter(axiom -> !isReservedDeclaration(axiom)).forEach(axioms::add);
        return axioms;
    }

    private static boolean isReservedDeclaration(OWLAxiom axiom) {
        if (!(axiom instanceof OWLDeclarationAxiom declaration)) {
            return false;
        }
        String iri = declaration.getEntity().getIRI().toString();
        return iri.startsWith("http://www.w3.org/1999/02/22-rdf-syntax-ns#")
                || iri.startsWith("http://www.w3.org/2000/01/rdf-schema#")
                || iri.startsWith("http://www.w3.org/2002/07/owl#")
                || iri.startsWith("http://www.w3.org/2001/XMLSchema#");
    }

    private static Set<String> languages(OWLOntology ontology) {
        Set<String> languages = new HashSet<>();
        ontology.axioms(AxiomType.ANNOTATION_ASSERTION)
                .map(OWLAnnotationAssertionAxiom::getValue)
                .filter(OWLLiteral.class::isInstance)
                .map(OWLLiteral.class::cast)
                .map(OWLLiteral::getLang)
                .filter(language -> !language.isEmpty())
                .forEach(languages::add);
        return languages;
    }

    private static Set<String> languages(OWLOntology ontology, IRI subject, IRI property) {
        Set<String> languages = new HashSet<>();
        ontology.axioms(AxiomType.ANNOTATION_ASSERTION)
                .filter(axiom -> property.equals(axiom.getProperty().getIRI()))
                .filter(axiom -> subject.equals(axiom.getSubject()))
                .map(OWLAnnotationAssertionAxiom::getValue)
                .filter(OWLLiteral.class::isInstance)
                .map(OWLLiteral.class::cast)
                .map(OWLLiteral::getLang)
                .forEach(languages::add);
        return languages;
    }

    private static Stream<String> literalValues(OWLOntology ontology) {
        return ontology.axioms(AxiomType.ANNOTATION_ASSERTION)
                .map(OWLAnnotationAssertionAxiom::getValue)
                .filter(OWLLiteral.class::isInstance)
                .map(OWLLiteral.class::cast)
                .map(OWLLiteral::getLiteral);
    }
}
