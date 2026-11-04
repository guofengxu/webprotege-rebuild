package wprebuild.kernel.crud;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.semanticweb.owlapi.model.EntityType;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.EntityCreator;
import wprebuild.kernel.iri.FreshEntityIri;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * {@link EntityCreator} that follows the project's current settings (BS-06).
 *
 * <p>The user and the project ontologies are part of the port. UUID and supplied-name minting do
 * not read them; the OBO kit (P1-14) will.
 */
public final class KitEntityCreator implements EntityCreator {

    private final Supplier<NewEntitySettings> settings;

    private final Supplier<PrefixedNameExpander> prefixes;

    private final UuidEntityCrudKit uuidKit = new UuidEntityCrudKit();

    private final SuppliedNameEntityCrudKit suppliedNameKit = new SuppliedNameEntityCrudKit();

    public KitEntityCreator(Supplier<NewEntitySettings> settings, Supplier<PrefixedNameExpander> prefixes) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    @Override
    public CreatedEntity create(EntityType<?> type, FreshEntityIri fresh, UserId user, ProjectOntologies ontologies) {
        Objects.requireNonNull(type, "entity type");
        Objects.requireNonNull(fresh, "fresh entity");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(ontologies, "ontologies");
        NewEntitySettings current = Objects.requireNonNull(settings.get(), "settings");
        PrefixedNameExpander expander = Objects.requireNonNull(prefixes.get(), "prefixes");
        Optional<String> lang = fresh.langTag().isBlank() ? Optional.empty() : Optional.of(fresh.langTag());
        return select(current.kit()).create(type, fresh.suppliedName(), lang, current, expander);
    }

    private EntityCrudKit select(KitSettings kit) {
        return switch (kit) {
            case UuidKitSettings ignored -> uuidKit;
            case SuppliedNameKitSettings ignored -> suppliedNameKit;
            case OboKitSettings ignored -> throw new IllegalArgumentException("OBO entity IRIs are not available yet");
        };
    }
}
