package wprebuild.kernel.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.testsupport.SyntheticOntologyGenerator;
import wprebuild.kernel.testsupport.TestOntologies;

/** ofn-zip/1 round trip: registry identity, corpus axioms, and imports that are not fetched. */
class OfnZipSnapshotCodecTest {

    private static final OntologyKey CORPUS = OntologyKey.of(1);

    private static final OntologyKey ANONYMOUS = OntologyKey.of(2);

    private static final OntologyKey SYNTHETIC = OntologyKey.of(10);

    private static final IRI UNRESOLVED = IRI.create("http://invalid.invalid/x");

    private static final IRI IMPORTED = IRI.create("http://invalid.invalid/imported");

    private final SnapshotCodec codec = new OfnZipSnapshotCodec();

    @Test
    void id_is_ofn_zip_v1() {
        assertThat(codec.id()).isEqualTo("ofn-zip/1");
    }

    @Test
    void round_trips_corpus_synthetic_and_anonymous_ontologies() {
        OWLOntology corpus = TestOntologies.load("axioms-all-types.ofn");
        OWLOntology synthetic =
                SyntheticOntologyGenerator.generate(new SyntheticOntologyGenerator.Parameters(10_000, 0, 0, 0, 0, 1L));
        assertThat(synthetic.getAxiomCount()).isEqualTo(10_000);

        MutableProjectOntologies source = OwlApiProjectOntologies.empty();
        OWLDataFactory data = source.dataFactory();
        OWLAnnotation comment = data.getOWLAnnotation(data.getRDFSComment(), data.getOWLLiteral("快照", "zh"));
        OWLAxiom anonymousAxiom = data.getOWLDeclarationAxiom(data.getOWLClass(IRI.create("http://example.org/Anon")));

        source.apply(List.of(
                declare(CORPUS, corpus),
                declare(SYNTHETIC, synthetic),
                new DeclareOntology(ANONYMOUS, Optional.empty(), Optional.empty())));
        source.apply(axiomsOf(CORPUS, corpus));
        source.apply(axiomsOf(SYNTHETIC, synthetic));
        source.apply(List.of(
                new AddAxiom(ANONYMOUS, anonymousAxiom),
                new AddOntologyAnnotation(CORPUS, comment),
                new AddOntologyAnnotation(ANONYMOUS, comment),
                new AddImport(CORPUS, IMPORTED),
                new AddImport(ANONYMOUS, UNRESOLVED)));

        MutableProjectOntologies decoded = codec.decode(codec.encode(source));

        assertSameStructure(source, decoded);
        assertThat(decoded.info(ANONYMOUS)).contains(new OntologyInfo(ANONYMOUS, Optional.empty(), Optional.empty()));
        assertThat(decoded.axiomCount()).isEqualTo(source.axiomCount());
    }

    @Test
    void writes_registry_rows_in_key_number_order_and_copies_those_iris_into_the_header() throws IOException {
        IRI first = IRI.create("http://example.org/a");
        IRI tenth = IRI.create("http://example.org/c");
        IRI tenthVersion = IRI.create("http://example.org/c/1");
        MutableProjectOntologies source = OwlApiProjectOntologies.empty();
        source.apply(List.of(
                new DeclareOntology(SYNTHETIC, Optional.of(tenth), Optional.of(tenthVersion)),
                new DeclareOntology(ANONYMOUS, Optional.empty(), Optional.empty()),
                new DeclareOntology(CORPUS, Optional.of(first), Optional.empty())));

        Map<String, byte[]> entries = unzip(codec.encode(source));
        String registry = text(entries.get("registry.tsv"));
        String named = text(entries.get("o1.ofn"));
        String anonymous = text(entries.get("o2.ofn"));
        String versioned = text(entries.get("o10.ofn"));

        assertThat(registry)
                .isEqualTo("o1\thttp://example.org/a\t-\n"
                        + "o2\t-\t-\n"
                        + "o10\thttp://example.org/c\thttp://example.org/c/1\n");
        assertThat(named).contains("Ontology(<http://example.org/a>");
        assertThat(anonymous).contains("Ontology(").doesNotContain("Ontology(<");
        assertThat(versioned).contains("<http://example.org/c>").contains("<http://example.org/c/1>");
    }

    @Test
    void recovers_key_and_iris_from_the_registry_when_the_ofn_header_differs() {
        byte[] payload = zip(
                "registry.tsv",
                "o7\t-\t-\n" + "o8\thttp://example.org/registry\thttp://example.org/registry/9\n",
                "o7.ofn",
                """
                Ontology(<http://example.org/written-by-owlapi>
                Declaration(Class(<http://example.org/A>))
                )
                """,
                "o8.ofn",
                """
                Ontology(
                Declaration(Class(<http://example.org/B>))
                )
                """);

        MutableProjectOntologies decoded = codec.decode(payload);
        OWLDataFactory data = decoded.dataFactory();

        assertThat(decoded.keys()).containsExactlyInAnyOrder(OntologyKey.of(7), OntologyKey.of(8));
        assertThat(decoded.info(OntologyKey.of(7)))
                .contains(new OntologyInfo(OntologyKey.of(7), Optional.empty(), Optional.empty()));
        assertThat(decoded.info(OntologyKey.of(8)))
                .contains(new OntologyInfo(
                        OntologyKey.of(8),
                        Optional.of(IRI.create("http://example.org/registry")),
                        Optional.of(IRI.create("http://example.org/registry/9"))));
        assertThat(decoded.axioms(OntologyKey.of(7)))
                .containsExactly(data.getOWLDeclarationAxiom(data.getOWLClass(IRI.create("http://example.org/A"))));
        assertThat(decoded.axioms(OntologyKey.of(8)))
                .containsExactly(data.getOWLDeclarationAxiom(data.getOWLClass(IRI.create("http://example.org/B"))));
    }

    @Test
    void decodes_an_unresolved_import_without_loading_the_document() {
        MutableProjectOntologies source = OwlApiProjectOntologies.empty();
        source.apply(List.of(
                new DeclareOntology(CORPUS, Optional.empty(), Optional.empty()), new AddImport(CORPUS, UNRESOLVED)));
        byte[] payload = codec.encode(source);

        long started = System.nanoTime();
        MutableProjectOntologies decoded = codec.decode(payload);
        long elapsed = System.nanoTime() - started;

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1).toNanos());
        assertThat(decoded.containsImport(CORPUS, UNRESOLVED)).isTrue();
        assertThat(decoded.imports(CORPUS)).containsExactly(UNRESOLVED);
        assertThat(decoded.axiomCount()).isZero();
        assertThat(decoded.keys()).containsExactly(CORPUS);
    }

    @Test
    void import_iris_lists_only_import_declarations() {
        byte[] document = """
                Ontology(<http://example.org/ont> <http://example.org/ont/1>
                    Declaration(Class(<http://example.org/Entity>))
                    AnnotationAssertion(<http://www.w3.org/2000/01/rdf-schema#comment> <http://example.org/Entity> "<http://example.org/in-literal>")
                    Import(<http://example.org/first>)
                    Import(
                        <http://example.org/second>
                    )
                    Import(<>)
                    Import(<http://example.org/first>)
                )
                """.getBytes(StandardCharsets.UTF_8);

        Set<IRI> iris = OfnZipSnapshotCodec.importIris(document);

        assertThat(iris)
                .containsExactly(IRI.create("http://example.org/first"), IRI.create("http://example.org/second"));
        assertThatThrownBy(() -> iris.add(IRI.create("http://example.org/extra")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void round_trips_sixty_thousand_axioms_within_fifteen_seconds() {
        OWLOntology synthetic =
                SyntheticOntologyGenerator.generate(new SyntheticOntologyGenerator.Parameters(20_000, 4, 1, 0, 0, 20L));
        assertThat(synthetic.getAxiomCount()).isBetween(50_000, 70_000);

        MutableProjectOntologies source = OwlApiProjectOntologies.empty();
        source.apply(List.of(declare(SYNTHETIC, synthetic)));
        source.apply(axiomsOf(SYNTHETIC, synthetic));

        MutableProjectOntologies decoded = codec.decode(codec.encode(source));

        assertSameStructure(source, decoded);
    }

    @Test
    void rejects_a_render_that_would_drop_a_one_operand_constructor() {
        MutableProjectOntologies source = OwlApiProjectOntologies.empty();
        OWLDataFactory data = source.dataFactory();
        OWLClass clazz = data.getOWLClass(IRI.create("http://example.org/A"));
        OWLClass parent = data.getOWLClass(IRI.create("http://example.org/B"));
        OWLAxiom collapsed = data.getOWLSubClassOfAxiom(data.getOWLObjectIntersectionOf(Set.of(clazz)), parent);
        source.apply(List.of(
                new DeclareOntology(CORPUS, Optional.empty(), Optional.empty()), new AddAxiom(CORPUS, collapsed)));

        assertThatThrownBy(() -> codec.encode(source))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not lossless");
    }

    @Test
    void rejects_an_ofn_entry_that_is_not_functional_syntax() {
        byte[] turtle = zip("registry.tsv", "o1\t-\t-\n", "o1.ofn", """
                @prefix ex: <http://example.org/> .
                ex:A a owl:Class .
                """);
        byte[] blank = zip("registry.tsv", "o1\t-\t-\n", "o1.ofn", "   \n");

        assertThatThrownBy(() -> codec.decode(turtle)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(blank)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_an_import_iri_that_functional_syntax_cannot_quote() {
        IRI broken = IRI.create("http://example.org/a>)\nImport(<http://evil.example/b");
        MutableProjectOntologies source = OwlApiProjectOntologies.empty();
        source.apply(List.of(
                new DeclareOntology(CORPUS, Optional.empty(), Optional.empty()), new AddImport(CORPUS, broken)));

        assertThatThrownBy(() -> codec.encode(source)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_and_a_payload_that_is_not_a_zip() {
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.decode(new byte[] {1, 2, 3})).isInstanceOf(IllegalArgumentException.class);
    }

    private static void assertSameStructure(ProjectOntologies expected, ProjectOntologies actual) {
        assertThat(actual.keys()).containsExactlyInAnyOrderElementsOf(expected.keys());
        for (OntologyKey key : expected.keys()) {
            assertThat(actual.info(key)).as("info %s", key.value()).isEqualTo(expected.info(key));
            // Set equality stays linear. AssertJ's any-order comparison is quadratic in the
            // axiom count and would exceed the 60k-axiom timeout even when the codec is fast.
            assertThat(axiomSet(actual.axioms(key)))
                    .as("axioms %s", key.value())
                    .isEqualTo(axiomSet(expected.axioms(key)));
            assertThat(actual.annotations(key))
                    .as("annotations %s", key.value())
                    .isEqualTo(expected.annotations(key));
            assertThat(actual.imports(key)).as("imports %s", key.value()).isEqualTo(expected.imports(key));
        }
    }

    private static Set<OWLAxiom> axiomSet(Stream<OWLAxiom> axioms) {
        return axioms.collect(Collectors.toSet());
    }

    private static DeclareOntology declare(OntologyKey key, OWLOntology ontology) {
        return new DeclareOntology(
                key,
                ontology.getOntologyID().getOntologyIRI(),
                ontology.getOntologyID().getVersionIRI());
    }

    private static List<OntologyChange> axiomsOf(OntologyKey key, OWLOntology ontology) {
        return ontology.getAxioms().stream()
                .map(axiom -> (OntologyChange) new AddAxiom(key, axiom))
                .toList();
    }

    private static Map<String, byte[]> unzip(byte[] payload) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] zip(String... nameThenText) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (int i = 0; i < nameThenText.length; i += 2) {
                zip.putNextEntry(new ZipEntry(nameThenText[i]));
                zip.write(nameThenText[i + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        return bytes.toByteArray();
    }
}
