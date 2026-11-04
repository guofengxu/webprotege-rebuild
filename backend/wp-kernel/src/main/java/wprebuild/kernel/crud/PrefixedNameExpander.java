package wprebuild.kernel.crud;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.vocab.Namespaces;

/**
 * Expands {@code prefix:local} names. Built-in prefixes are always present; the constructor map
 * overrides them (BS-06).
 */
public final class PrefixedNameExpander {

    private static final List<Namespaces> BUILT_IN = List.of(
            Namespaces.OWL,
            Namespaces.RDF,
            Namespaces.RDFS,
            Namespaces.XSD,
            Namespaces.SKOS,
            Namespaces.DC,
            Namespaces.DCTERMS);

    private final Map<String, String> prefixes;

    public PrefixedNameExpander(Map<String, String> prefixes) {
        Objects.requireNonNull(prefixes, "prefixes");
        Map<String, String> merged = new LinkedHashMap<>();
        for (Namespaces namespace : BUILT_IN) {
            merged.put(namespace.getPrefixName() + ":", namespace.getPrefixIRI());
        }
        prefixes.forEach((name, iri) -> merged.put(canonical(name), prefixIri(iri)));
        this.prefixes = Map.copyOf(merged);
    }

    public Optional<IRI> expand(String suppliedName) {
        Objects.requireNonNull(suppliedName, "supplied name");
        int colon = suppliedName.indexOf(':');
        if (colon <= 0 || colon == suppliedName.length() - 1) {
            return Optional.empty();
        }
        String prefix = prefixes.get(suppliedName.substring(0, colon + 1));
        if (prefix == null) {
            return Optional.empty();
        }
        return Optional.of(IRI.create(prefix + suppliedName.substring(colon + 1)));
    }

    private static String canonical(String name) {
        String withColon = Objects.requireNonNull(name, "prefix name");
        if (!withColon.endsWith(":")) {
            withColon = withColon + ":";
        }
        int colon = withColon.indexOf(':');
        if (colon <= 0 || colon != withColon.length() - 1) {
            throw new IllegalArgumentException("prefix name must contain one trailing colon: " + name);
        }
        return withColon;
    }

    private static String prefixIri(String iri) {
        if (Objects.requireNonNull(iri, "prefix IRI").isBlank()) {
            throw new IllegalArgumentException("prefix IRI must not be blank");
        }
        return iri;
    }
}
