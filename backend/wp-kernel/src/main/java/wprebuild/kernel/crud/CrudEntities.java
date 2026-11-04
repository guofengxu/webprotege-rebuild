package wprebuild.kernel.crud;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.iri.EntityCreator.CreatedEntity;

/** Shared IRI parsing and the declaration plus label changes (BS-06). */
final class CrudEntities {

    private static final OWLDataFactory DATA = OWLManager.getOWLDataFactory();

    private CrudEntities() {}

    static void requireArguments(
            EntityType<?> type,
            String suppliedName,
            Optional<String> lang,
            NewEntitySettings settings,
            PrefixedNameExpander expander) {
        Objects.requireNonNull(type, "entity type");
        Objects.requireNonNull(suppliedName, "supplied name");
        Objects.requireNonNull(lang, "lang");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(expander, "prefixed name expander");
    }

    static Optional<IRI> quotedIri(String suppliedName) {
        if (suppliedName.length() < 3 || suppliedName.charAt(0) != '<' || !suppliedName.endsWith(">")) {
            return Optional.empty();
        }
        String content = suppliedName.substring(1, suppliedName.length() - 1);
        final URI uri;
        try {
            uri = new URI(content);
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
        return Optional.of(IRI.create(uri.toString()));
    }

    static Optional<IRI> directIri(String suppliedName, PrefixedNameExpander expander) {
        return quotedIri(suppliedName).or(() -> expander.expand(suppliedName));
    }

    /** Fragment after {@code #}, otherwise the last path segment. Empty when the IRI has neither. */
    static String localName(IRI iri) {
        String text = iri.toString();
        int hash = text.lastIndexOf('#');
        if (hash >= 0 && hash < text.length() - 1) {
            return text.substring(hash + 1);
        }
        int slash = text.lastIndexOf('/');
        if (slash >= 0 && slash < text.length() - 1) {
            return text.substring(slash + 1);
        }
        return "";
    }

    static CreatedEntity created(
            EntityType<?> type,
            IRI iri,
            String suppliedName,
            String label,
            Optional<String> lang,
            NewEntitySettings settings) {
        OWLEntity entity = entity(type, iri);
        List<OntologyChange> changes = new ArrayList<>(2);
        changes.add(new AddAxiom(settings.targetOntology(), DATA.getOWLDeclarationAxiom(entity)));
        if (!suppliedName.isBlank()) {
            changes.add(new AddAxiom(
                    settings.targetOntology(),
                    DATA.getOWLAnnotationAssertionAxiom(
                            DATA.getOWLAnnotationProperty(settings.labelProperty()),
                            iri,
                            literal(label, language(lang, settings.defaultLang())))));
        }
        return new CreatedEntity(entity, changes);
    }

    private static String language(Optional<String> lang, String defaultLang) {
        return lang.filter(value -> !value.isBlank()).orElse(defaultLang);
    }

    private static OWLLiteral literal(String text, String lang) {
        if (lang.isBlank()) {
            return DATA.getOWLLiteral(text);
        }
        return DATA.getOWLLiteral(text, lang);
    }

    private static OWLEntity entity(EntityType<?> type, IRI iri) {
        if (EntityType.CLASS.equals(type)) {
            return DATA.getOWLClass(iri);
        }
        if (EntityType.OBJECT_PROPERTY.equals(type)) {
            return DATA.getOWLObjectProperty(iri);
        }
        if (EntityType.DATA_PROPERTY.equals(type)) {
            return DATA.getOWLDataProperty(iri);
        }
        if (EntityType.ANNOTATION_PROPERTY.equals(type)) {
            return DATA.getOWLAnnotationProperty(iri);
        }
        if (EntityType.NAMED_INDIVIDUAL.equals(type)) {
            return DATA.getOWLNamedIndividual(iri);
        }
        if (EntityType.DATATYPE.equals(type)) {
            return DATA.getOWLDatatype(iri);
        }
        throw new IllegalArgumentException("unsupported entity type: " + type);
    }
}
