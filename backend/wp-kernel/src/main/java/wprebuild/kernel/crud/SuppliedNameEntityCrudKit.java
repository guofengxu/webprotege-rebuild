package wprebuild.kernel.crud;

import java.util.Optional;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;

/** IRI suffix is the supplied name after whitespace treatment. The label is the local name (BS-06). */
public final class SuppliedNameEntityCrudKit implements EntityCrudKit {

    @Override
    public CreatedEntity create(
            EntityType<?> type,
            String suppliedName,
            Optional<String> lang,
            NewEntitySettings settings,
            PrefixedNameExpander expander) {
        CrudEntities.requireArguments(type, suppliedName, lang, settings, expander);
        if (!(settings.kit() instanceof SuppliedNameKitSettings supplied)) {
            throw new IllegalArgumentException("supplied-name kit requires SuppliedNameKitSettings");
        }
        Optional<IRI> direct = CrudEntities.directIri(suppliedName, expander);
        IRI iri = direct.orElseGet(
                () -> IRI.create(settings.iriPrefix() + supplied.whiteSpace().transform(suppliedName)));
        String label = CrudEntities.localName(iri);
        if (label.isEmpty()) {
            label = iri.toString();
        }
        return CrudEntities.created(type, iri, suppliedName, label, lang, settings);
    }
}
