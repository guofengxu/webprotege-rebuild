package wprebuild.migrator.export;

import java.util.Objects;
import java.util.regex.Pattern;
import org.semanticweb.owlapi.change.AddAxiomData;
import org.semanticweb.owlapi.change.AddImportData;
import org.semanticweb.owlapi.change.AddOntologyAnnotationData;
import org.semanticweb.owlapi.change.OWLOntologyChangeData;
import org.semanticweb.owlapi.change.OWLOntologyChangeRecord;
import org.semanticweb.owlapi.change.RemoveAxiomData;
import org.semanticweb.owlapi.change.RemoveImportData;
import org.semanticweb.owlapi.change.RemoveOntologyAnnotationData;
import org.semanticweb.owlapi.model.IRI;

/** Renders one legacy change as {@code <op> TAB <kind> TAB <ontologyKey> TAB <ofn>} (ADR-011). */
public final class OfnLineRenderer {

    private static final Pattern KEY = Pattern.compile("o[1-9][0-9]*");

    private final FunctionalOfn syntax = new FunctionalOfn();

    public String render(OWLOntologyChangeRecord record, String ontologyKey) {
        Objects.requireNonNull(record, "record");
        requireKey(ontologyKey);
        OWLOntologyChangeData data = record.getData();
        if (data == null) {
            throw new IllegalArgumentException("change data is missing");
        }
        return op(data) + "\t" + kind(data) + "\t" + ontologyKey + "\t" + ofn(data);
    }

    static String collapseFormattingNewlines(String rendered) {
        return FunctionalOfn.collapseFormattingNewlines(rendered);
    }

    private String ofn(OWLOntologyChangeData data) {
        if (data instanceof AddAxiomData add) {
            return syntax.renderAxiom(add.getAxiom());
        }
        if (data instanceof RemoveAxiomData remove) {
            return syntax.renderAxiom(remove.getAxiom());
        }
        if (data instanceof AddOntologyAnnotationData add) {
            return syntax.renderAnnotation(add.getAnnotation());
        }
        if (data instanceof RemoveOntologyAnnotationData remove) {
            return syntax.renderAnnotation(remove.getAnnotation());
        }
        if (data instanceof AddImportData add) {
            return quoted(add.getDeclaration().getIRI());
        }
        if (data instanceof RemoveImportData remove) {
            return quoted(remove.getDeclaration().getIRI());
        }
        throw unsupported(data);
    }

    private static String op(OWLOntologyChangeData data) {
        if (data instanceof AddAxiomData
                || data instanceof AddOntologyAnnotationData
                || data instanceof AddImportData) {
            return "+";
        }
        if (data instanceof RemoveAxiomData
                || data instanceof RemoveOntologyAnnotationData
                || data instanceof RemoveImportData) {
            return "-";
        }
        throw unsupported(data);
    }

    private static String kind(OWLOntologyChangeData data) {
        if (data instanceof AddAxiomData || data instanceof RemoveAxiomData) {
            return "AXIOM";
        }
        if (data instanceof AddOntologyAnnotationData || data instanceof RemoveOntologyAnnotationData) {
            return "ANNOTATION";
        }
        if (data instanceof AddImportData || data instanceof RemoveImportData) {
            return "IMPORT";
        }
        throw unsupported(data);
    }

    private static String quoted(IRI iri) {
        Objects.requireNonNull(iri, "iri");
        String text = iri.toString();
        if (text.isBlank()
                || text.indexOf('<') >= 0
                || text.indexOf('>') >= 0
                || text.indexOf(' ') >= 0
                || text.indexOf('\t') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("import IRI cannot be written in angle brackets: " + text);
        }
        return "<" + text + ">";
    }

    private static void requireKey(String ontologyKey) {
        Objects.requireNonNull(ontologyKey, "ontologyKey");
        if (!KEY.matcher(ontologyKey).matches()) {
            throw new IllegalArgumentException("ontology key must match o[1-9][0-9]*: " + ontologyKey);
        }
    }

    private static IllegalArgumentException unsupported(OWLOntologyChangeData data) {
        return new IllegalArgumentException(
                "unsupported change: " + data.getClass().getSimpleName());
    }
}
