package wprebuild.kernel.generator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyID;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Turns already parsed ontologies into the first revision of an empty project (BS-03).
 *
 * <p>Keys start at {@code o1} and follow the input order. Each ontology contributes one
 * {@link DeclareOntology}, then every axiom, ontology annotation, and import declaration. A project
 * that already contains an ontology is rejected. Import declarations are recorded and not loaded.
 */
public final class InitialImportGenerator implements ChangeListGenerator<List<OntologyKey>> {

    private final List<OWLOntology> ontologies;

    public InitialImportGenerator(List<OWLOntology> ontologies) {
        this.ontologies = List.copyOf(Objects.requireNonNull(ontologies, "ontologies"));
    }

    @Override
    public GeneratedChanges<List<OntologyKey>> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        if (!context.ontologies().keys().isEmpty()) {
            throw new IllegalStateException("project is not empty");
        }
        requireDistinctDocumentIris(ontologies);
        List<OntologyChange> changes = new ArrayList<>();
        List<OntologyKey> keys = new ArrayList<>(ontologies.size());
        int index = 1;
        for (OWLOntology ontology : ontologies) {
            OntologyKey key = OntologyKey.of(index);
            keys.add(key);
            changes.add(declare(key, ontology));
            addAxioms(key, ontology, changes);
            addAnnotations(key, ontology, changes);
            addImports(key, ontology, changes);
            index++;
        }
        return new GeneratedChanges<>(changes, List.copyOf(keys));
    }

    @Override
    public String describe(GeneratedChanges<List<OntologyKey>> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        return "Imported " + ontologies.size() + " ontologies";
    }

    /**
     * OWL API uses the version IRI, otherwise the ontology IRI, as the document IRI. A duplicate is
     * only reported while applying an already committed revision, which marks the runtime corrupted.
     * Anonymous ontologies have no document IRI and may repeat.
     */
    private static void requireDistinctDocumentIris(List<OWLOntology> ontologies) {
        Set<IRI> documents = new HashSet<>();
        for (OWLOntology ontology : ontologies) {
            Optional<IRI> document = ontology.getOntologyID().getDefaultDocumentIRI();
            if (document.isPresent() && !documents.add(document.get())) {
                throw new IllegalArgumentException("duplicate ontology document IRI: " + document.get());
            }
        }
    }

    private static DeclareOntology declare(OntologyKey key, OWLOntology ontology) {
        OWLOntologyID id = Objects.requireNonNull(ontology, "ontology").getOntologyID();
        return new DeclareOntology(key, id.getOntologyIRI(), id.getVersionIRI());
    }

    private static void addAxioms(OntologyKey key, OWLOntology ontology, List<OntologyChange> changes) {
        for (OWLAxiom axiom : ontology.getAxioms()) {
            changes.add(new AddAxiom(key, axiom));
        }
    }

    private static void addAnnotations(OntologyKey key, OWLOntology ontology, List<OntologyChange> changes) {
        for (OWLAnnotation annotation : ontology.getAnnotations()) {
            changes.add(new AddOntologyAnnotation(key, annotation));
        }
    }

    private static void addImports(OntologyKey key, OWLOntology ontology, List<OntologyChange> changes) {
        for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
            changes.add(new AddImport(key, declaration.getIRI()));
        }
    }
}
