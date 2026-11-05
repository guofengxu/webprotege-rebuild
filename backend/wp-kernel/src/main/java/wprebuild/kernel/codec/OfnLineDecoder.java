package wprebuild.kernel.codec;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParser;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.io.StringDocumentSource;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.RemoveImport;
import org.semanticweb.owlapi.model.RemoveOntologyAnnotation;
import uk.ac.manchester.cs.owl.owlapi.OWLOntologyManagerImpl;
import wprebuild.kernel.change.OntologyChange;

/**
 * One functional-syntax parse context for a batch of {@code ofn-lines/1} lines.
 *
 * <p>Not thread-safe. Create one instance for a single {@code encode} or {@code decode} call and
 * discard it afterwards. {@code OWLOntologyManagerImpl} locks itself, and the staging ontology
 * keeps parsed axioms until the next line.
 */
public final class OfnLineDecoder {

    /** Document IRI used only as a wrapper so one expression can be parsed. It is not stored. */
    private static final String CODEC_ONTOLOGY = "urn:wp:codec";

    private final OWLOntologyManager manager;

    private final OWLOntology staging;

    private final OWLFunctionalSyntaxOWLParser parser = new OWLFunctionalSyntaxOWLParser();

    private final OWLOntologyLoaderConfiguration loader =
            new OWLOntologyLoaderConfiguration().setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT);

    public OfnLineDecoder(OWLDataFactory dataFactory) {
        this.manager = manager(Objects.requireNonNull(dataFactory, "dataFactory"));
        this.staging = stagingOntology(manager);
    }

    /** Same input, output, and exceptions as {@link OfnLines#decodeLine(String, OWLDataFactory)}. */
    public OntologyChange decode(String line) {
        Objects.requireNonNull(line, "line");
        return OfnLines.decodeWith(line, this);
    }

    /**
     * Parses one expression into the reused staging ontology.
     *
     * <p>The ontology is emptied first. A failed parse can leave axioms, annotations, or import
     * declarations behind, and those must not become part of the next line.
     */
    OWLOntology parse(String ofn) {
        Objects.requireNonNull(ofn, "ofn");
        clear();
        String document = "Ontology(<" + CODEC_ONTOLOGY + "> " + ofn + ")";
        try {
            OWLDocumentFormat format = parser.parse(new StringDocumentSource(document), staging, loader);
            if (!(format instanceof FunctionalSyntaxDocumentFormat)) {
                throw new IllegalArgumentException("expected functional syntax: " + OfnLines.preview(ofn));
            }
            return staging;
        } catch (OWLRuntimeException ex) {
            throw new IllegalArgumentException("cannot parse functional syntax: " + OfnLines.preview(ofn), ex);
        }
    }

    private void clear() {
        if (staging.getAxiomCount() > 0) {
            staging.removeAxioms(staging.axioms().toList());
        }
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAnnotation annotation : staging.annotations().toList()) {
            changes.add(new RemoveOntologyAnnotation(staging, annotation));
        }
        for (OWLImportsDeclaration declaration : staging.importsDeclarations().toList()) {
            changes.add(new RemoveImport(staging, declaration));
        }
        if (!changes.isEmpty()) {
            manager.applyChanges(changes);
        }
    }

    private static OWLOntology stagingOntology(OWLOntologyManager manager) {
        try {
            return manager.createOntology(IRI.create(CODEC_ONTOLOGY));
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalStateException("failed to create ofn line staging ontology", ex);
        }
    }

    /**
     * The stock manager's ontology factory is copied onto a manager that uses the caller's data
     * factory. Only the functional-syntax parser is installed. Remapping is off so {@code _:b1}
     * keeps its node id (ADR-011).
     */
    private static OWLOntologyManager manager(OWLDataFactory dataFactory) {
        OWLOntologyManager template = OWLManager.createOWLOntologyManager();
        OWLOntologyManager manager = new OWLOntologyManagerImpl(dataFactory, new ReentrantReadWriteLock());
        // Only the functional parser. The stock set includes OBO, which accepts arbitrary text.
        Set<OWLParserFactory> parsers = new LinkedHashSet<>();
        parsers.add(new OWLFunctionalSyntaxOWLParserFactory());
        manager.setOntologyParsers(parsers);
        manager.setOntologyFactories(copy(template.getOntologyFactories()));
        manager.getOntologyConfigurator().withRemapAllAnonymousIndividualsIds(false);
        manager.getIRIMappers().add(OfnLineDecoder::blockDocumentFetch);
        return manager;
    }

    private static <T> Set<T> copy(Iterable<T> items) {
        Set<T> copy = new LinkedHashSet<>();
        items.forEach(copy::add);
        return copy;
    }

    /** The wrapper document is supplied as a string. Every other document IRI is not loaded. */
    private static IRI blockDocumentFetch(IRI iri) {
        if (CODEC_ONTOLOGY.equals(iri.toString())) {
            return null;
        }
        return IRI.create("urn:wp:codec:blocked-import");
    }
}
