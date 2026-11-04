package wprebuild.kernel.crud;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * IRI suffix is a zero-padded OBO id. The label is the name the user typed (BS-06).
 *
 * <p>The old handler also wrote {@code oboInOwl:created_by} and {@code oboInOwl:creation_date}.
 * Those annotations are not part of the shared BS-06 creation changes, so this kit does not emit
 * them. A quoted IRI or an expandable prefixed name is used as-is and does not consume an id.
 */
public final class OboIdEntityCrudKit implements EntityCrudKit {

    private final UserId user;

    private final ProjectOntologies ontologies;

    private final OboIdAllocator allocator;

    /** Uses a private cursor. Sharing an allocator keeps ids unique across users and calls. */
    public OboIdEntityCrudKit(UserId user, ProjectOntologies ontologies) {
        this(user, ontologies, new OboIdAllocator());
    }

    OboIdEntityCrudKit(UserId user, ProjectOntologies ontologies, OboIdAllocator allocator) {
        this.user = Objects.requireNonNull(user, "user");
        this.ontologies = Objects.requireNonNull(ontologies, "ontologies");
        this.allocator = Objects.requireNonNull(allocator, "allocator");
    }

    @Override
    public CreatedEntity create(
            EntityType<?> type,
            String suppliedName,
            Optional<String> lang,
            NewEntitySettings settings,
            PrefixedNameExpander expander) {
        CrudEntities.requireArguments(type, suppliedName, lang, settings, expander);
        if (!(settings.kit() instanceof OboKitSettings obo)) {
            throw new IllegalArgumentException("OBO kit requires OboKitSettings");
        }
        Optional<IRI> quoted = CrudEntities.quotedIri(suppliedName);
        Optional<IRI> direct = quoted.or(() -> expander.expand(suppliedName));
        if (direct.isPresent()) {
            IRI iri = direct.get();
            String label = quoted.isPresent() ? iri.toString() : suppliedName;
            return CrudEntities.created(type, iri, suppliedName, label, lang, settings);
        }
        Set<String> signature = ontologies
                .signature()
                .map(entity -> entity.getIRI().toString())
                .collect(Collectors.toUnmodifiableSet());
        long id = allocator.allocate(user, settings.iriPrefix(), obo, signature);
        IRI iri = IRI.create(OboIdAllocator.iri(settings.iriPrefix(), obo.totalDigits(), id));
        return CrudEntities.created(type, iri, suppliedName, suppliedName, lang, settings);
    }
}
