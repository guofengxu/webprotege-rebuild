package wprebuild.kernel.iri;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLObject;
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
        OWLObjectDuplicator duplicator = duplicator(map);
        List<OntologyChange> replaced = new ArrayList<>(changes.size());
        for (OntologyChange change : changes) {
            replaced.add(replaceOne(Objects.requireNonNull(change, "change"), duplicator, map));
        }
        return List.copyOf(replaced);
    }

    /**
     * Rewrites IRIs inside one OWL object with the same duplicator used for change lists (BS-05,
     * BS-13). An empty map returns {@code object} unchanged.
     */
    public static <T extends OWLObject> T replaceObject(T object, Map<IRI, IRI> replacements) {
        Objects.requireNonNull(object, "object");
        return replaceObjects(List.of(object), replacements).getFirst();
    }

    /**
     * Rewrites IRIs inside many OWL objects with a single duplicator (BS-13 merge usage rewrite).
     * An empty map returns copies of the input list unchanged.
     */
    public static <T extends OWLObject> List<T> replaceObjects(List<T> objects, Map<IRI, IRI> replacements) {
        Objects.requireNonNull(objects, "objects");
        Map<IRI, IRI> map = Map.copyOf(Objects.requireNonNull(replacements, "replacements"));
        if (map.isEmpty() || objects.isEmpty()) {
            return List.copyOf(objects);
        }
        OWLObjectDuplicator duplicator = duplicator(map);
        List<T> replaced = new ArrayList<>(objects.size());
        for (T object : objects) {
            replaced.add(duplicator.duplicateObject(Objects.requireNonNull(object, "object")));
        }
        return List.copyOf(replaced);
    }

    private static OWLObjectDuplicator duplicator(Map<IRI, IRI> map) {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        OWLDataFactory dataFactory = manager.getOWLDataFactory();
        // The default duplicator remints blank-node ids, so a removal would miss the original axiom.
        RemappingIndividualProvider individuals = new RemappingIndividualProvider(false, dataFactory);
        return new OWLObjectDuplicator(manager, map, individuals);
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
