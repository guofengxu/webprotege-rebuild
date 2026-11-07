package wprebuild.migrator.export;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntologyID;

/**
 * Assigns {@code o1}, {@code o2}, … in the order ontologies first appear.
 *
 * <p>Named ontologies match on {@code (ontologyIri, versionIri)}. Anonymous ids are not equal to
 * each other: BinaryOWL stores only an anonymous marker, so each deserialized run gets a fresh
 * {@link OWLOntologyID} and therefore its own key.
 */
public final class OntologyKeyAssigner {

    private final Map<OWLOntologyID, String> keys = new LinkedHashMap<>();

    public String keyFor(OWLOntologyID ontologyId) {
        Objects.requireNonNull(ontologyId, "ontologyId");
        String existing = keys.get(ontologyId);
        if (existing != null) {
            return existing;
        }
        String key = "o" + (keys.size() + 1);
        keys.put(ontologyId, key);
        return key;
    }

    public List<Slot> ontologies() {
        return keys.entrySet().stream()
                .map(entry -> slot(entry.getKey(), entry.getValue()))
                .toList();
    }

    private static Slot slot(OWLOntologyID ontologyId, String key) {
        return new Slot(
                key,
                text(ontologyId.getOntologyIRI().orNull()),
                text(ontologyId.getVersionIRI().orNull()));
    }

    private static String text(IRI iri) {
        return iri == null ? null : iri.toString();
    }

    /** One row of {@code manifest.json}'s {@code ontologies} array. Absent IRIs are null. */
    public record Slot(String key, String ontologyIri, String versionIri) {

        public Slot {
            Objects.requireNonNull(key, "key");
        }
    }
}
