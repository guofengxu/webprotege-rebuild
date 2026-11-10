package wprebuild.app.search;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;

/** Query tokens such as {@code class} and {@code object-property}, and the stored type codes. */
public final class SearchTypes {

    private static final List<Kind> KINDS = List.of(
            new Kind("class", "CLASS", EntityType.CLASS),
            new Kind("object-property", "OBJECT_PROPERTY", EntityType.OBJECT_PROPERTY),
            new Kind("data-property", "DATA_PROPERTY", EntityType.DATA_PROPERTY),
            new Kind("annotation-property", "ANNOTATION_PROPERTY", EntityType.ANNOTATION_PROPERTY),
            new Kind("named-individual", "NAMED_INDIVIDUAL", EntityType.NAMED_INDIVIDUAL),
            new Kind("datatype", "DATATYPE", EntityType.DATATYPE));

    private SearchTypes() {}

    /** Empty or blank input means every entity type. */
    public static Set<EntityType<?>> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of();
        }
        Set<EntityType<?>> types = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            types.add(parseOne(token));
        }
        return Set.copyOf(types);
    }

    public static String code(EntityType<?> type) {
        return kind(type).code;
    }

    static OWLEntity entity(OWLDataFactory data, String code, String iri) {
        Kind kind = kind(code);
        return data.getOWLEntity(cast(kind.type), IRI.create(iri));
    }

    private static EntityType<?> parseOne(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("entity type is required");
        }
        String name = token.strip().toLowerCase(Locale.ROOT);
        for (Kind kind : KINDS) {
            if (kind.query.equals(name)) {
                return kind.type;
            }
        }
        throw new IllegalArgumentException(
                "entity type must be class, object-property, data-property, annotation-property,"
                        + " named-individual, or datatype");
    }

    private static Kind kind(EntityType<?> type) {
        for (Kind kind : KINDS) {
            if (kind.type.equals(type)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("unsupported entity type: " + type);
    }

    private static Kind kind(String code) {
        for (Kind kind : KINDS) {
            if (kind.code.equals(code)) {
                return kind;
            }
        }
        throw new IllegalStateException("search index stored an unknown entity type: " + code);
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> cast(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }

    private record Kind(String query, String code, EntityType<?> type) {}
}
