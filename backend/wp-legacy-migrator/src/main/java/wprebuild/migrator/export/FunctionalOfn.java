package wprebuild.migrator.export;

import java.io.StringWriter;
import java.util.List;
import java.util.Objects;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.renderer.FunctionalSyntaxObjectRenderer;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnonymousIndividual;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.util.DefaultPrefixManager;

/**
 * Renders one OWL object with OWL API 4 functional syntax and parses that text back.
 *
 * <p>Prefixes are cleared so every IRI is written in full (ADR-011). Newlines inside quoted
 * literals stay; every other newline is renderer formatting and becomes a space, same rule as
 * P1-06. Parsing never follows an import IRI onto the network.
 */
final class FunctionalOfn {

    private static final IRI BLOCKED_IMPORT = IRI.create("urn:wp:blocked-import");

    /** OWL API 4 writes this on every untyped literal. It is the same literal as {@code "..."}. */
    private static final String XSD_STRING_SUFFIX = "^^<http://www.w3.org/2001/XMLSchema#string>";

    private final OWLOntology renderOntology;
    private final DefaultPrefixManager prefixes;
    private final OWLOntologyManager parserManager;
    private final OWLOntologyLoaderConfiguration loaderConfiguration;

    FunctionalOfn() {
        try {
            this.renderOntology = OWLManager.createOWLOntologyManager().createOntology();
        } catch (OWLOntologyCreationException exception) {
            throw new IllegalStateException("cannot create a renderer ontology", exception);
        }
        this.prefixes = new DefaultPrefixManager();
        this.prefixes.clear();
        for (String name : List.copyOf(prefixes.getPrefixNames())) {
            prefixes.unregisterNamespace(name);
        }
        this.parserManager = OWLManager.createOWLOntologyManager();
        this.parserManager.addIRIMapper(iri -> BLOCKED_IMPORT);
        this.loaderConfiguration = new OWLOntologyLoaderConfiguration()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setConnectionTimeout(1);
    }

    String renderAxiom(OWLAxiom axiom) {
        String rendered = render(axiom);
        OWLAxiom parsed = parseAxiom(rendered);
        if (axiom.getAnonymousIndividuals().isEmpty()) {
            if (!axiom.equals(parsed)) {
                throw new IllegalArgumentException("functional syntax render is not lossless: " + preview(rendered));
            }
            return rendered;
        }
        // OWL API 4's functional parser remints every blank node as _:genidN, so equals() cannot
        // hold. Keep the source label in the text; OWL API 5 (P1-06) preserves those labels.
        if (!axiom.getAxiomType().equals(parsed.getAxiomType())
                || parsed.getAnonymousIndividuals().size()
                        != axiom.getAnonymousIndividuals().size()) {
            throw new IllegalArgumentException("functional syntax render is not lossless: " + preview(rendered));
        }
        for (OWLAnonymousIndividual individual : axiom.getAnonymousIndividuals()) {
            if (!rendered.contains(individual.getID().getID())) {
                throw new IllegalArgumentException("functional syntax dropped anonymous individual "
                        + individual.getID() + ": " + preview(rendered));
            }
        }
        return rendered;
    }

    String renderAnnotation(OWLAnnotation annotation) {
        String rendered = render(annotation);
        OWLAnnotation parsed = parseAnnotation(rendered);
        if (!annotation.equals(parsed)) {
            throw new IllegalArgumentException("functional syntax render is not lossless: " + preview(rendered));
        }
        return rendered;
    }

    OWLAxiom parseAxiom(String ofn) {
        return load(ofn, ontology -> {
            if (!ontology.getAnnotations().isEmpty()) {
                throw new IllegalArgumentException("axiom line contains an ontology annotation: " + preview(ofn));
            }
            if (ontology.getAxioms().size() != 1) {
                throw new IllegalArgumentException(
                        "expected one axiom but found " + ontology.getAxioms().size() + ": " + preview(ofn));
            }
            return ontology.getAxioms().iterator().next();
        });
    }

    OWLAnnotation parseAnnotation(String ofn) {
        return load(ofn, ontology -> {
            if (!ontology.getAxioms().isEmpty()) {
                throw new IllegalArgumentException("annotation line contains axioms: " + preview(ofn));
            }
            if (ontology.getAnnotations().size() != 1) {
                throw new IllegalArgumentException("expected one annotation but found "
                        + ontology.getAnnotations().size() + ": " + preview(ofn));
            }
            return ontology.getAnnotations().iterator().next();
        });
    }

    private String render(OWLObject object) {
        StringWriter writer = new StringWriter();
        FunctionalSyntaxObjectRenderer renderer = new FunctionalSyntaxObjectRenderer(renderOntology, writer);
        renderer.setPrefixManager(prefixes);
        renderer.setAddMissingDeclarations(false);
        object.accept(renderer);
        String rendered = omitRedundantStringDatatype(collapseFormattingNewlines(writer.toString()));
        if (rendered.isBlank()) {
            throw new IllegalArgumentException("functional syntax renderer produced no text");
        }
        return rendered;
    }

    private <T> T load(String ofn, Extractor<T> extractor) {
        OWLOntology ontology = parseDocument(ofn);
        try {
            if (!ontology.getImportsDeclarations().isEmpty()) {
                throw new IllegalArgumentException("change line must not import another ontology: " + preview(ofn));
            }
            return extractor.extract(ontology);
        } finally {
            parserManager.removeOntology(ontology);
        }
    }

    private OWLOntology parseDocument(String ofn) {
        try {
            return parserManager.loadOntologyFromOntologyDocument(
                    new StringDocumentSource("Ontology(" + ofn + ")"), loaderConfiguration);
        } catch (OWLOntologyCreationException exception) {
            throw new IllegalArgumentException("cannot parse functional syntax: " + preview(ofn), exception);
        }
    }

    /**
     * ADR-011: a renderer may break an annotated axiom across lines. Newlines inside quoted literals
     * belong to the literal; every other newline is formatting and becomes a space.
     */
    static String collapseFormattingNewlines(String rendered) {
        Objects.requireNonNull(rendered, "rendered");
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

    /** Drop {@code ^^xsd:string} immediately after a literal so the text matches the interchange examples. */
    static String omitRedundantStringDatatype(String rendered) {
        StringBuilder out = new StringBuilder(rendered.length());
        boolean inLiteral = false;
        for (int i = 0; i < rendered.length(); i++) {
            char current = rendered.charAt(i);
            out.append(current);
            if (inLiteral) {
                if (current == '\\' && i + 1 < rendered.length()) {
                    out.append(rendered.charAt(++i));
                } else if (current == '"') {
                    inLiteral = false;
                    if (rendered.startsWith(XSD_STRING_SUFFIX, i + 1)) {
                        i += XSD_STRING_SUFFIX.length();
                    }
                }
                continue;
            }
            if (current == '"') {
                inLiteral = true;
            }
        }
        return out.toString();
    }

    static String preview(String ofn) {
        String flat = ofn.replace('\n', ' ').replace('\r', ' ');
        if (flat.length() <= 160) {
            return flat;
        }
        return flat.substring(0, 160) + "...";
    }

    @FunctionalInterface
    private interface Extractor<T> {
        T extract(OWLOntology ontology);
    }
}
