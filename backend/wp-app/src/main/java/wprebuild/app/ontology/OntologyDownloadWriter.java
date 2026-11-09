package wprebuild.app.ontology;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AddImport;
import org.semanticweb.owlapi.model.AddOntologyAnnotation;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDocumentFormat;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;

/** Renders a project revision as one document, or a zip when the project has several ontologies. */
final class OntologyDownloadWriter {

    static final String ZIP_MEDIA_TYPE = "application/zip";

    ProjectDownload write(
            ProjectOntologies ontologies, RevisionNumber revision, DownloadFormat format, String projectName) {
        List<OntologyKey> keys = sorted(ontologies.keys());
        boolean single = keys.size() == 1;
        byte[] body = single ? document(ontologies, keys.getFirst(), format) : zip(ontologies, keys, format);
        String extension = single ? format.extension() : "zip";
        String mediaType = single ? format.mediaType() : ZIP_MEDIA_TYPE;
        return new ProjectDownload(body, mediaType, DownloadFormat.fileName(projectName, revision.value(), extension));
    }

    private static byte[] zip(ProjectOntologies ontologies, List<OntologyKey> keys, DownloadFormat format) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (OntologyKey key : keys) {
                zip.putNextEntry(new ZipEntry(entryName(ontologies, key, format)));
                zip.write(document(ontologies, key, format));
                zip.closeEntry();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to zip ontology download", ex);
        }
        return bytes.toByteArray();
    }

    private static String entryName(ProjectOntologies ontologies, OntologyKey key, DownloadFormat format) {
        OntologyInfo info = requireInfo(ontologies, key);
        String iri = info.ontologyIri().map(IRI::toString).orElse("");
        return DownloadFormat.entryName(key.value(), iri, format.extension());
    }

    private static byte[] document(ProjectOntologies ontologies, OntologyKey key, DownloadFormat format) {
        OntologyInfo info = requireInfo(ontologies, key);
        OWLOntologyManager manager = manager();
        try {
            OWLOntology ontology = create(manager, info);
            try (var axioms = ontologies.axioms(key)) {
                manager.addAxioms(ontology, axioms.toList());
            }
            List<OWLOntologyChange> header = header(ontology, ontologies, key);
            if (!header.isEmpty()) {
                manager.applyChanges(header);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            OWLDocumentFormat syntax = format.documentFormat();
            ontology.saveOntology(syntax, out);
            return out.toByteArray();
        } catch (OWLOntologyCreationException | OWLOntologyStorageException ex) {
            throw new IllegalStateException("failed to write " + key.value(), ex);
        } finally {
            manager.clearOntologies();
        }
    }

    private static List<OWLOntologyChange> header(OWLOntology ontology, ProjectOntologies ontologies, OntologyKey key) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAnnotation annotation : ontologies.annotations(key)) {
            changes.add(new AddOntologyAnnotation(ontology, annotation));
        }
        OWLDataFactory data = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (IRI iri : ontologies.imports(key)) {
            changes.add(new AddImport(ontology, data.getOWLImportsDeclaration(iri)));
        }
        return changes;
    }

    private static OWLOntology create(OWLOntologyManager manager, OntologyInfo info)
            throws OWLOntologyCreationException {
        if (info.ontologyIri().isEmpty()) {
            return manager.createOntology();
        }
        return manager.createOntology(new OWLOntologyID(info.ontologyIri(), info.versionIri()));
    }

    private static OWLOntologyManager manager() {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        manager.getOntologyConfigurator()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .withSaveIdsForAllAnonymousIndividuals(true);
        return manager;
    }

    private static OntologyInfo requireInfo(ProjectOntologies ontologies, OntologyKey key) {
        return ontologies.info(key).orElseThrow(() -> new IllegalStateException("missing ontology " + key.value()));
    }

    private static List<OntologyKey> sorted(Set<OntologyKey> keys) {
        List<OntologyKey> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparingInt(OntologyDownloadWriter::index));
        return List.copyOf(ordered);
    }

    private static int index(OntologyKey key) {
        return Integer.parseInt(key.value().substring(1));
    }
}
