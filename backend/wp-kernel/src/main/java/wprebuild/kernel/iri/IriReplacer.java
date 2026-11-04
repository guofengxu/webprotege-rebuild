package wprebuild.kernel.iri;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.util.OWLObjectDuplicator;
import org.semanticweb.owlapi.util.RemappingIndividualProvider;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;

/** Rewrites IRIs inside ontology changes (BS-05). An empty map returns the changes unchanged. */
public final class IriReplacer {

    private IriReplacer() {}

    public static List<OntologyChange> replace(List<OntologyChange> changes, Map<IRI, IRI> replacements) {
        Objects.requireNonNull(changes, "changes");
        Map<IRI, IRI> map = Map.copyOf(Objects.requireNonNull(replacements, "replacements"));
        if (map.isEmpty()) {
            return List.copyOf(changes);
        }
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory dataFactory = manager.getOWLDataFactory();
        // The default duplicator remints blank-node ids, so a removal would miss the original axiom.
        RemappingIndividualProvider individuals = new RemappingIndividualProvider(false, dataFactory);
        OWLObjectDuplicator duplicator = new OWLObjectDuplicator(manager, map, individuals);
        List<OntologyChange> replaced = new ArrayList<>(changes.size());
        for (OntologyChange change : changes) {
            replaced.add(replaceOne(Objects.requireNonNull(change, "change"), duplicator, map));
        }
        return List.copyOf(replaced);
    }

    private static OntologyChange replaceOne(OntologyChange change, OWLObjectDuplicator duplicator, Map<IRI, IRI> map) {
        return switch (change) {
            case AddAxiom add -> new AddAxiom(add.ontology(), duplicator.duplicateObject(add.owlAxiom()));
            case RemoveAxiom remove ->
                new RemoveAxiom(remove.ontology(), duplicator.duplicateObject(remove.owlAxiom()));
            case AddOntologyAnnotation add ->
                new AddOntologyAnnotation(add.ontology(), duplicator.duplicateObject(add.annotation()));
            case RemoveOntologyAnnotation remove ->
                new RemoveOntologyAnnotation(remove.ontology(), duplicator.duplicateObject(remove.annotation()));
            case AddImport add -> new AddImport(add.ontology(), mapped(map, add.iri()));
            case RemoveImport remove -> new RemoveImport(remove.ontology(), mapped(map, remove.iri()));
            case DeclareOntology declare ->
                new DeclareOntology(
                        declare.ontology(), mapped(map, declare.ontologyIri()), mapped(map, declare.versionIri()));
        };
    }

    private static IRI mapped(Map<IRI, IRI> map, IRI iri) {
        return map.getOrDefault(iri, iri);
    }

    private static Optional<IRI> mapped(Map<IRI, IRI> map, Optional<IRI> iri) {
        return iri.map(value -> mapped(map, value));
    }
}
