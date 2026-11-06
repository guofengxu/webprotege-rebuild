package wprebuild.kernel.codec;

import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObject;
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

/**
 * Canonical change lines {@code <op> TAB <kind> TAB <ontologyKey> TAB <ofn>} (ADR-011).
 *
 * <p>Migration import (P3-10) calls {@link #decodeLine} on lines that JSON has already unescaped.
 */
public final class OfnLines {

    private static final String OP_ADD = "+";

    private static final String OP_REMOVE = "-";

    private static final String KIND_ONTOLOGY = "ONTOLOGY";

    private static final String KIND_AXIOM = "AXIOM";

    private static final String KIND_ANNOTATION = "ANNOTATION";

    private static final String KIND_IMPORT = "IMPORT";

    private static final String ANONYMOUS_ONTOLOGY = "-";

    private OfnLines() {}

    public static String encodeLine(OntologyChange change) {
        Objects.requireNonNull(change, "change");
        return encodeLine(change, new OfnLineDecoder(OWLManager.getOWLDataFactory()));
    }

    static String encodeLine(OntologyChange change, OfnLineDecoder checker) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(checker, "checker");
        return op(change) + "\t" + kind(change) + "\t" + change.ontology().value() + "\t" + ofn(change, checker);
    }

    public static OntologyChange decodeLine(String line, OWLDataFactory dataFactory) {
        Objects.requireNonNull(line, "line");
        Objects.requireNonNull(dataFactory, "dataFactory");
        return new OfnLineDecoder(dataFactory).decode(line);
    }

    static OntologyChange decodeWith(String line, OfnLineDecoder decoder) {
        ParsedLine parsed = split(line);
        return switch (parsed.kind()) {
            case KIND_AXIOM -> axiom(parsed, decoder);
            case KIND_ANNOTATION -> annotation(parsed, decoder);
            case KIND_IMPORT -> importChange(parsed);
            case KIND_ONTOLOGY -> ontology(parsed);
            default -> throw new IllegalArgumentException("unknown change kind: " + parsed.kind());
        };
    }

    /** ADR-011 line escape: {@code \} → {@code \\}, LF → {@code \n}, CR → {@code \r}. */
    public static String escapeLine(String line) {
        Objects.requireNonNull(line, "line");
        StringBuilder escaped = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }

    public static String unescapeLine(String line) {
        Objects.requireNonNull(line, "line");
        StringBuilder raw = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (current != '\\') {
                raw.append(current);
                continue;
            }
            if (i + 1 >= line.length()) {
                throw new IllegalArgumentException("truncated line escape");
            }
            char next = line.charAt(++i);
            switch (next) {
                case '\\' -> raw.append('\\');
                case 'n' -> raw.append('\n');
                case 'r' -> raw.append('\r');
                default -> throw new IllegalArgumentException("unknown line escape: \\" + next);
            }
        }
        return raw.toString();
    }

    /**
     * ADR-011: a renderer may break an annotated axiom across lines. Newlines inside quoted literals
     * are part of the literal; every other newline is formatting and becomes a space.
     */
    static String collapseFormattingNewlines(String rendered) {
        StringBuilder collapsed = new StringBuilder(rendered.length());
        boolean inLiteral = false;
        for (int i = 0; i < rendered.length(); i++) {
            char current = rendered.charAt(i);
            if (inLiteral) {
                collapsed.append(current);
                if (current == '\\' && i + 1 < rendered.length()) {
                    collapsed.append(rendered.charAt(++i));
                } else if (current == '"') {
                    inLiteral = false;
                }
                continue;
            }
            if (current == '"') {
                inLiteral = true;
                collapsed.append(current);
            } else if (current == '\r' || current == '\n') {
                if (current == '\r' && i + 1 < rendered.length() && rendered.charAt(i + 1) == '\n') {
                    i++;
                }
                collapsed.append(' ');
            } else {
                collapsed.append(current);
            }
        }
        return collapsed.toString();
    }

    private static String op(OntologyChange change) {
        return switch (change) {
            case AddAxiom ignored -> OP_ADD;
            case RemoveAxiom ignored -> OP_REMOVE;
            case AddOntologyAnnotation ignored -> OP_ADD;
            case RemoveOntologyAnnotation ignored -> OP_REMOVE;
            case AddImport ignored -> OP_ADD;
            case RemoveImport ignored -> OP_REMOVE;
            case DeclareOntology ignored -> OP_ADD;
        };
    }

    private static String kind(OntologyChange change) {
        return switch (change) {
            case AddAxiom ignored -> KIND_AXIOM;
            case RemoveAxiom ignored -> KIND_AXIOM;
            case AddOntologyAnnotation ignored -> KIND_ANNOTATION;
            case RemoveOntologyAnnotation ignored -> KIND_ANNOTATION;
            case AddImport ignored -> KIND_IMPORT;
            case RemoveImport ignored -> KIND_IMPORT;
            case DeclareOntology ignored -> KIND_ONTOLOGY;
        };
    }

    private static String ofn(OntologyChange change, OfnLineDecoder checker) {
        return switch (change) {
            case AddAxiom add -> renderChecked(add.owlAxiom(), checker);
            case RemoveAxiom remove -> renderChecked(remove.owlAxiom(), checker);
            case AddOntologyAnnotation add -> renderChecked(add.annotation(), checker);
            case RemoveOntologyAnnotation remove -> renderChecked(remove.annotation(), checker);
            case AddImport add -> quoted(add.iri());
            case RemoveImport remove -> quoted(remove.iri());
            case DeclareOntology declared -> renderOntology(declared);
        };
    }

    private static String renderOntology(DeclareOntology declared) {
        if (declared.ontologyIri().isEmpty()) {
            return ANONYMOUS_ONTOLOGY;
        }
        String iri = quoted(declared.ontologyIri().orElseThrow());
        if (declared.versionIri().isEmpty()) {
            return iri;
        }
        return iri + " " + quoted(declared.versionIri().orElseThrow());
    }

    private static String quoted(IRI iri) {
        return "<" + iri + ">";
    }

    private static String renderFunctional(OWLObject object) {
        StringWriter writer = new StringWriter();
        // No ontology and no prefix map: every IRI is written in full (ADR-011).
        object.accept(new FunctionalSyntaxObjectRenderer(null, null, writer));
        String rendered = collapseFormattingNewlines(writer.toString());
        if (rendered.isBlank()) {
            throw new IllegalArgumentException("functional syntax renderer produced no text");
        }
        return rendered;
    }

    /**
     * OWL API omits the constructor when an n-ary expression has one operand, so the parsed object
     * would differ. Refuse that render instead of storing a different axiom.
     */
    private static String renderChecked(OWLObject object, OfnLineDecoder checker) {
        String rendered = renderFunctional(object);
        OWLObject parsed = object instanceof OWLAxiom ? checker.axiom(rendered) : checker.annotation(rendered);
        if (!object.equals(parsed)) {
            throw new IllegalArgumentException("functional syntax render is not lossless: " + preview(rendered));
        }
        return rendered;
    }

    private static OntologyChange axiom(ParsedLine parsed, OfnLineDecoder decoder) {
        OWLAxiom axiom = decoder.axiom(parsed.ofn());
        if (parsed.add()) {
            return new AddAxiom(parsed.ontology(), axiom);
        }
        return new RemoveAxiom(parsed.ontology(), axiom);
    }

    private static OntologyChange annotation(ParsedLine parsed, OfnLineDecoder decoder) {
        OWLAnnotation annotation = decoder.annotation(parsed.ofn());
        if (parsed.add()) {
            return new AddOntologyAnnotation(parsed.ontology(), annotation);
        }
        return new RemoveOntologyAnnotation(parsed.ontology(), annotation);
    }

    private static OntologyChange importChange(ParsedLine parsed) {
        IRI iri = iriToken(parsed.ofn());
        if (parsed.add()) {
            return new AddImport(parsed.ontology(), iri);
        }
        return new RemoveImport(parsed.ontology(), iri);
    }

    private static OntologyChange ontology(ParsedLine parsed) {
        if (!parsed.add()) {
            throw new IllegalArgumentException("ONTOLOGY changes cannot be removed (ADR-011)");
        }
        if (ANONYMOUS_ONTOLOGY.equals(parsed.ofn())) {
            return new DeclareOntology(parsed.ontology(), Optional.empty(), Optional.empty());
        }
        String[] tokens = parsed.ofn().split(" ", -1);
        if (tokens.length == 1) {
            return new DeclareOntology(parsed.ontology(), Optional.of(iriToken(tokens[0])), Optional.empty());
        }
        if (tokens.length == 2) {
            return new DeclareOntology(
                    parsed.ontology(), Optional.of(iriToken(tokens[0])), Optional.of(iriToken(tokens[1])));
        }
        throw new IllegalArgumentException(
                "ontology registration must be -, <iri>, or <iri> <versionIri>: " + parsed.ofn());
    }

    static OWLAxiom oneAxiom(OWLOntology ontology, String ofn) {
        rejectImports(ontology, ofn);
        if (!ontology.annotations().toList().isEmpty()) {
            throw new IllegalArgumentException("axiom line contains an ontology annotation: " + preview(ofn));
        }
        List<OWLAxiom> axioms = ontology.axioms().toList();
        if (axioms.size() != 1) {
            throw new IllegalArgumentException("expected one axiom but found " + axioms.size() + ": " + preview(ofn));
        }
        return axioms.get(0);
    }

    static OWLAnnotation oneAnnotation(OWLOntology ontology, String ofn) {
        rejectImports(ontology, ofn);
        if (ontology.getAxiomCount() != 0) {
            throw new IllegalArgumentException("annotation line contains axioms: " + preview(ofn));
        }
        List<OWLAnnotation> annotations = ontology.annotations().toList();
        if (annotations.size() != 1) {
            throw new IllegalArgumentException(
                    "expected one annotation but found " + annotations.size() + ": " + preview(ofn));
        }
        return annotations.get(0);
    }

    private static void rejectImports(OWLOntology ontology, String ofn) {
        if (ontology.importsDeclarations().findAny().isPresent()) {
            throw new IllegalArgumentException("change line must not import another ontology: " + preview(ofn));
        }
    }

    private static ParsedLine split(String line) {
        int first = line.indexOf('\t');
        int second = first < 0 ? -1 : line.indexOf('\t', first + 1);
        int third = second < 0 ? -1 : line.indexOf('\t', second + 1);
        if (first <= 0 || second <= first + 1 || third <= second + 1) {
            throw new IllegalArgumentException("change line must be <op> TAB <kind> TAB <ontologyKey> TAB <ofn>");
        }
        String operator = line.substring(0, first);
        if (!OP_ADD.equals(operator) && !OP_REMOVE.equals(operator)) {
            throw new IllegalArgumentException("change op must be + or -: " + operator);
        }
        String kind = line.substring(first + 1, second);
        OntologyKey ontology = new OntologyKey(line.substring(second + 1, third));
        String ofn = line.substring(third + 1);
        if (ofn.isEmpty()) {
            throw new IllegalArgumentException("change line is missing its payload");
        }
        return new ParsedLine(OP_ADD.equals(operator), kind, ontology, ofn);
    }

    private static IRI iriToken(String token) {
        if (token.length() < 3 || token.charAt(0) != '<' || token.charAt(token.length() - 1) != '>') {
            throw new IllegalArgumentException("expected an IRI in angle brackets: " + token);
        }
        String iri = token.substring(1, token.length() - 1);
        if (iri.isBlank() || iri.indexOf('<') >= 0 || iri.indexOf('>') >= 0 || iri.indexOf(' ') >= 0) {
            throw new IllegalArgumentException("expected an IRI in angle brackets: " + token);
        }
        return IRI.create(iri);
    }

    static String preview(String ofn) {
        String flat = ofn.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= 160) {
            return flat;
        }
        return flat.substring(0, 160) + "...";
    }

    private record ParsedLine(boolean add, String kind, OntologyKey ontology, String ofn) {}
}
