package wprebuild.kernel.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLClassExpression;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.testsupport.TestOntologies;

/** ADR-011. ofn-lines/1 round trip, line escaping, and the frozen uncompressed text. */
class OfnLineChangeCodecTest {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private static final OntologyKey KEY = OntologyKey.of(1);

    private static final IRI ONTOLOGY_IRI = IRI.create("http://example.org/wp-test/ont");

    private static final IRI VERSION_IRI = IRI.create("http://example.org/wp-test/ont/1");

    private static final IRI IMPORTED = IRI.create("http://example.org/other");

    private static final OWLClass CLASS_A = DATA.getOWLClass(IRI.create("http://example.org/A"));

    private static final OWLClass CLASS_B = DATA.getOWLClass(IRI.create("http://example.org/B"));

    private static final OWLClass PALLET = DATA.getOWLClass(IRI.create("http://example.org/wh#Pallet"));

    private static final OWLDataProperty NOTE = DATA.getOWLDataProperty(IRI.create("http://example.org/note"));

    private static final OWLNamedIndividual ENTITY = DATA.getOWLNamedIndividual(IRI.create("http://example.org/e"));

    private static final OWLObjectProperty KNOWS = DATA.getOWLObjectProperty(IRI.create("http://example.org/knows"));

    private final ChangeCodec codec = new OfnLineChangeCodec(DATA);

    @Test
    void id_is_ofn_lines_v1() {
        assertThat(codec.id()).isEqualTo("ofn-lines/1");
        assertThat(new OfnLineChangeCodec().id()).isEqualTo("ofn-lines/1");
    }

    @Test
    void round_trips_every_corpus_axiom_as_add_and_remove() {
        OWLOntology ontology = TestOntologies.load("axioms-all-types.ofn");

        assertThat(ontology.getAxioms()).isNotEmpty();
        for (OWLAxiom axiom : ontology.getAxioms()) {
            roundTrip(new AddAxiom(KEY, axiom));
            roundTrip(new RemoveAxiom(KEY, axiom));
        }
    }

    @Test
    void round_trips_ontology_annotations_and_imports() {
        OWLAnnotation comment = DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("demo"));
        OWLAnnotation label = DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("人", "zh"));

        roundTrip(new AddOntologyAnnotation(KEY, comment));
        roundTrip(new RemoveOntologyAnnotation(KEY, label));
        roundTrip(new AddImport(KEY, IMPORTED));
        roundTrip(new RemoveImport(OntologyKey.of(2), IMPORTED));
    }

    @Test
    void round_trips_named_versioned_and_anonymous_ontology_declarations() {
        roundTrip(new DeclareOntology(KEY, Optional.empty(), Optional.empty()));
        roundTrip(new DeclareOntology(OntologyKey.of(2), Optional.of(ONTOLOGY_IRI), Optional.empty()));
        roundTrip(new DeclareOntology(OntologyKey.of(3), Optional.of(ONTOLOGY_IRI), Optional.of(VERSION_IRI)));
    }

    @Test
    void round_trips_literals_containing_newline_tab_quote_backslash_and_chinese() {
        String lexical = "a\nb\t\"c\\d中";
        OWLAxiom axiom = DATA.getOWLDataPropertyAssertionAxiom(NOTE, ENTITY, DATA.getOWLLiteral(lexical));
        AddAxiom change = new AddAxiom(KEY, axiom);

        String physical = OfnLines.escapeLine(OfnLines.encodeLine(change));

        assertThat(physical)
                .doesNotContain("\n")
                .doesNotContain("\r")
                .contains("\\n")
                .contains("中");
        assertThat(OfnLines.encodeLine(change).split("\t", 4)[3]).contains("\t");
        roundTrip(change);
    }

    @Test
    void replaces_formatting_newlines_outside_literals_with_spaces() {
        String rendered =
                "SubClassOf(Annotation(<http://example.org/p> \"keep\nme\")\r\n<http://example.org/A> <http://example.org/B>)";

        assertThat(OfnLines.collapseFormattingNewlines(rendered))
                .isEqualTo(
                        "SubClassOf(Annotation(<http://example.org/p> \"keep\nme\") <http://example.org/A> <http://example.org/B>)");

        OWLAnnotation annotation = DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("keep\nme"));
        OWLAxiom axiom = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_B, Set.of(annotation));
        String ofn = OfnLines.encodeLine(new AddAxiom(KEY, axiom)).split("\t", 4)[3];

        assertThat(ofn).contains("\"keep\nme\"");
        assertThat(newlinesOutsideLiterals(ofn)).isZero();
        assertThat(OfnLines.escapeLine(OfnLines.encodeLine(new AddAxiom(KEY, axiom))))
                .doesNotContain("\n");
        roundTrip(new AddAxiom(KEY, axiom));
    }

    @Test
    void round_trips_an_axiom_that_mentions_anonymous_individual_b1() {
        OWLAnonymousIndividual anon = DATA.getOWLAnonymousIndividual("b1");
        OWLAxiom member = DATA.getOWLClassAssertionAxiom(CLASS_A, anon);
        OWLAxiom link = DATA.getOWLObjectPropertyAssertionAxiom(KNOWS, anon, anon);
        List<OntologyChange> changes = List.of(new AddAxiom(KEY, member), new AddAxiom(OntologyKey.of(2), link));

        List<OntologyChange> decoded = codec.decode(codec.encode(changes));

        assertThat(decoded).isEqualTo(changes);
        assertThat(anonymousIndividuals(decoded.get(0))).containsExactly(anon);
        assertThat(anonymousIndividuals(decoded.get(1))).containsExactly(anon);
        assertThat(OfnLines.encodeLine(changes.get(0))).contains("_:b1");
    }

    @Test
    void round_trips_two_distinct_anonymous_individuals() {
        OWLAnonymousIndividual first = DATA.getOWLAnonymousIndividual("b1");
        OWLAnonymousIndividual second = DATA.getOWLAnonymousIndividual("b2");
        OWLAxiom axiom = DATA.getOWLObjectPropertyAssertionAxiom(KNOWS, first, second);

        roundTrip(new AddAxiom(KEY, axiom));
        assertThat(OfnLines.encodeLine(new AddAxiom(KEY, axiom)))
                .contains("_:b1")
                .contains("_:b2");
    }

    @Test
    void writes_built_in_datatype_iris_in_full() {
        OWLAxiom axiom = DATA.getOWLDataPropertyAssertionAxiom(NOTE, ENTITY, DATA.getOWLLiteral(30));

        assertThat(OfnLines.encodeLine(new AddAxiom(KEY, axiom)))
                .contains("^^<http://www.w3.org/2001/XMLSchema#integer>")
                .doesNotContain("xsd:");
    }

    @Test
    void decode_line_reads_the_interchange_examples() {
        assertThat(OfnLines.encodeLine(new AddAxiom(KEY, DATA.getOWLDeclarationAxiom(PALLET))))
                .isEqualTo("+\tAXIOM\to1\tDeclaration(Class(<http://example.org/wh#Pallet>))");
        assertThat(OfnLines.decodeLine("+\tAXIOM\to1\tDeclaration(Class(<http://example.org/wh#Pallet>))", DATA))
                .isEqualTo(new AddAxiom(KEY, DATA.getOWLDeclarationAxiom(PALLET)));

        assertThat(OfnLines.encodeLine(new AddOntologyAnnotation(
                        KEY, DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("demo")))))
                .isEqualTo("+\tANNOTATION\to1\tAnnotation(<http://www.w3.org/2000/01/rdf-schema#comment> \"demo\")");
        assertThat(OfnLines.decodeLine(
                        "+\tANNOTATION\to1\tAnnotation(<http://www.w3.org/2000/01/rdf-schema#comment> \"demo\")", DATA))
                .isEqualTo(new AddOntologyAnnotation(
                        KEY, DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("demo"))));

        assertThat(OfnLines.encodeLine(new AddImport(KEY, IMPORTED)))
                .isEqualTo("+\tIMPORT\to1\t<http://example.org/other>");
        assertThat(OfnLines.decodeLine("-\tIMPORT\to2\t<http://example.org/other>", DATA))
                .isEqualTo(new RemoveImport(OntologyKey.of(2), IMPORTED));

        assertThat(OfnLines.encodeLine(new DeclareOntology(KEY, Optional.of(ONTOLOGY_IRI), Optional.of(VERSION_IRI))))
                .isEqualTo("+\tONTOLOGY\to1\t<http://example.org/wp-test/ont> <http://example.org/wp-test/ont/1>");
        assertThat(OfnLines.encodeLine(new DeclareOntology(OntologyKey.of(2), Optional.empty(), Optional.empty())))
                .isEqualTo("+\tONTOLOGY\to2\t-");
        assertThat(OfnLines.decodeLine("+\tONTOLOGY\to2\t-", DATA))
                .isEqualTo(new DeclareOntology(OntologyKey.of(2), Optional.empty(), Optional.empty()));
    }

    @Test
    void escape_line_round_trips_backslash_newline_and_carriage_return() {
        String raw = "a\\b\nc\r";

        assertThat(OfnLines.escapeLine(raw)).isEqualTo("a\\\\b\\nc\\r");
        assertThat(OfnLines.unescapeLine(OfnLines.escapeLine(raw))).isEqualTo(raw);
        assertThatThrownBy(() -> OfnLines.unescapeLine("\\t"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("\\t");
        assertThatThrownBy(() -> OfnLines.unescapeLine("trailing\\")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void uncompressed_text_matches_the_golden_file() throws IOException {
        byte[] expected = resource("/codec/ofn-lines-v1-golden.txt");
        byte[] actual = gunzip(codec.encode(goldenChanges())).getBytes(StandardCharsets.UTF_8);

        assertThat(actual).containsExactly(expected);
    }

    @Test
    void encode_writes_gzip_and_decode_rejects_a_truncated_payload() {
        byte[] payload = codec.encode(List.of());

        assertThat(payload).startsWith(0x1f, (byte) 0x8b);
        assertThat(codec.decode(payload)).isEmpty();
        assertThatThrownBy(() -> codec.decode(new byte[] {1, 2, 3})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfnLines.decodeLine("+\tONTOLOGY\to1\t-", null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> codec.encode(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new OfnLineChangeCodec(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> OfnLines.decodeLine("-\tONTOLOGY\to1\t-", DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ONTOLOGY");
    }

    @Test
    void rejects_a_line_that_is_not_one_functional_syntax_expression() {
        assertThatThrownBy(() -> OfnLines.decodeLine("+\tAXIOM\to1\tHello", DATA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OfnLines.decodeLine(
                        "+\tAXIOM\to1\tAnnotation(<http://www.w3.org/2000/01/rdf-schema#comment> \"hidden\") "
                                + "SubClassOf(<http://example.org/A> <http://example.org/B>)",
                        DATA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("annotation");
        assertThatThrownBy(() -> OfnLines.decodeLine("+\tAXIOM\to1\tImport(<file:///tmp/wp-missing.ofn>)", DATA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_damaged_gzip_utf8_and_a_raw_carriage_return() throws IOException {
        byte[] payload = codec.encode(List.of(new DeclareOntology(KEY, Optional.empty(), Optional.empty())));
        byte[] extra = Arrays.copyOf(payload, payload.length + 1);
        extra[extra.length - 1] = 0x1f;
        byte[] concatenated = Arrays.copyOf(payload, payload.length * 2);
        System.arraycopy(payload, 0, concatenated, payload.length, payload.length);

        assertThatThrownBy(() -> codec.decode(extra)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(concatenated)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(gzip(new byte[] {'+', '\t', (byte) 0xff})))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(gzip("+\tONTOLOGY\to1\t-\r\n".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("carriage return");
    }

    @Test
    void round_trips_a_carriage_return_inside_a_literal() {
        roundTrip(new AddAxiom(KEY, DATA.getOWLDataPropertyAssertionAxiom(NOTE, ENTITY, DATA.getOWLLiteral("a\rb"))));
    }

    @Test
    void rejects_a_single_operand_intersection_the_renderer_would_unwrap() {
        OWLClassExpression intersection = DATA.getOWLObjectIntersectionOf(CLASS_A);
        OWLAxiom axiom = DATA.getOWLSubClassOfAxiom(intersection, CLASS_B);

        assertThat(intersection).isNotEqualTo(CLASS_A);
        assertThatThrownBy(() -> OfnLines.encodeLine(new AddAxiom(KEY, axiom)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("lossless");
    }

    private void roundTrip(OntologyChange change) {
        assertThat(codec.decode(codec.encode(List.of(change))))
                .as(change.toString())
                .containsExactly(change);
    }

    private static List<OWLAnonymousIndividual> anonymousIndividuals(OntologyChange change) {
        return change.axiom().orElseThrow().anonymousIndividuals().toList();
    }

    private static int newlinesOutsideLiterals(String ofn) {
        int count = 0;
        boolean inLiteral = false;
        for (int i = 0; i < ofn.length(); i++) {
            char current = ofn.charAt(i);
            if (inLiteral) {
                if (current == '\\' && i + 1 < ofn.length()) {
                    i++;
                    continue;
                }
                if (current == '"') {
                    inLiteral = false;
                }
                continue;
            }
            if (current == '"') {
                inLiteral = true;
            } else if (current == '\n') {
                count++;
            }
        }
        return count;
    }

    private static List<OntologyChange> goldenChanges() {
        OWLAnnotation comment = DATA.getOWLAnnotation(DATA.getRDFSComment(), DATA.getOWLLiteral("demo"));
        OWLAnnotation label = DATA.getOWLAnnotation(DATA.getRDFSLabel(), DATA.getOWLLiteral("人", "zh"));
        OWLAxiom declared = DATA.getOWLDeclarationAxiom(PALLET);
        OWLAxiom subclass = DATA.getOWLSubClassOfAxiom(CLASS_A, CLASS_B);
        OWLAxiom noted = DATA.getOWLDataPropertyAssertionAxiom(NOTE, ENTITY, DATA.getOWLLiteral("a\nb\t\"c\\d中"));
        return List.of(
                new DeclareOntology(KEY, Optional.of(ONTOLOGY_IRI), Optional.of(VERSION_IRI)),
                new DeclareOntology(OntologyKey.of(2), Optional.empty(), Optional.empty()),
                new DeclareOntology(OntologyKey.of(3), Optional.of(ONTOLOGY_IRI), Optional.empty()),
                new AddImport(KEY, IMPORTED),
                new RemoveImport(KEY, IMPORTED),
                new AddOntologyAnnotation(KEY, comment),
                new RemoveOntologyAnnotation(OntologyKey.of(2), label),
                new AddAxiom(KEY, declared),
                new RemoveAxiom(OntologyKey.of(2), subclass),
                new AddAxiom(KEY, noted));
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(raw);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        return bytes.toByteArray();
    }

    private static String gunzip(byte[] payload) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            return new String(gzip.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    private static byte[] resource(String name) throws IOException {
        InputStream in = OfnLineChangeCodecTest.class.getResourceAsStream(name);
        assertThat(in).as(name).isNotNull();
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }
}
