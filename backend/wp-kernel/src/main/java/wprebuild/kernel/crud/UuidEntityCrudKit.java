package wprebuild.kernel.crud;

import java.util.Optional;
import java.util.UUID;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;

/** IRI suffix {@code R} plus a base62 UUID. The label is the name the user typed (BS-06). */
public final class UuidEntityCrudKit implements EntityCrudKit {

    /** Keeps the local name a valid NCName without encoding the entity type. */
    static final String NCNAME_PREFIX = "R";

    @Override
    public CreatedEntity create(
            EntityType<?> type,
            String suppliedName,
            Optional<String> lang,
            NewEntitySettings settings,
            PrefixedNameExpander expander) {
        CrudEntities.requireArguments(type, suppliedName, lang, settings, expander);
        Optional<IRI> quoted = CrudEntities.quotedIri(suppliedName);
        Optional<IRI> direct = quoted.or(() -> expander.expand(suppliedName));
        IRI iri = direct.orElseGet(() -> mint(settings));
        String label = quoted.isPresent() ? iri.toString() : suppliedName;
        return CrudEntities.created(type, iri, suppliedName, label, lang, settings);
    }

    private static IRI mint(NewEntitySettings settings) {
        return IRI.create(settings.iriPrefix() + NCNAME_PREFIX + Base62.encode(UUID.randomUUID()));
    }
}
