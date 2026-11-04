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
 * <p>UUID and supplied-name minting ignore the user and the project ontologies. The OBO kit reads
 * both, and one allocator is kept for the life of this creator so ids minted in a single change
 * list stay distinct before they appear in the signature.
 */
public final class KitEntityCreator implements EntityCreator {

    private final Supplier<NewEntitySettings> settings;

    private final Supplier<PrefixedNameExpander> prefixes;

    private final UuidEntityCrudKit uuidKit = new UuidEntityCrudKit();

    private final SuppliedNameEntityCrudKit suppliedNameKit = new SuppliedNameEntityCrudKit();

    private final OboIdAllocator oboIds = new OboIdAllocator();

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
        EntityCrudKit kit = switch (current.kit()) {
            case UuidKitSettings ignored -> uuidKit;
            case SuppliedNameKitSettings ignored -> suppliedNameKit;
            case OboKitSettings ignored -> new OboIdEntityCrudKit(user, ontologies, oboIds);
        };
        return kit.create(type, fresh.suppliedName(), lang, current, expander);
    }
}
