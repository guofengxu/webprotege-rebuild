package wprebuild.kernel.crud;

import java.util.Objects;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.vocab.Namespaces;
import wprebuild.kernel.change.OntologyKey;

/** How a project mints a new entity IRI and its label (BS-06). */
public record NewEntitySettings(
        String iriPrefix, KitSettings kit, IRI labelProperty, String defaultLang, OntologyKey targetOntology) {

    /** {@code rdfs:label}. */
    public static final IRI RDFS_LABEL = IRI.create(Namespaces.RDFS.getPrefixIRI() + "label");

    public NewEntitySettings {
        if (Objects.requireNonNull(iriPrefix, "iri prefix").isBlank()) {
            throw new IllegalArgumentException("iri prefix must not be blank");
        }
        Objects.requireNonNull(kit, "kit");
        Objects.requireNonNull(labelProperty, "label property");
        Objects.requireNonNull(defaultLang, "default language");
        Objects.requireNonNull(targetOntology, "target ontology");
    }

    /** UUID kit, {@code rdfs:label}, and an empty default language. */
    public static NewEntitySettings defaults(String iriPrefix, OntologyKey targetOntology) {
        return new NewEntitySettings(iriPrefix, new UuidKitSettings(), RDFS_LABEL, "", targetOntology);
    }
}
