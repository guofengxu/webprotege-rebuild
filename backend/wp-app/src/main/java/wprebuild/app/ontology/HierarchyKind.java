package wprebuild.app.ontology;

import java.util.Locale;

/** The four asserted hierarchies (BS-08). Path values are lowercase and hyphenated. */
public enum HierarchyKind {
    CLASS("class"),
    OBJECT_PROPERTY("object-property"),
    DATA_PROPERTY("data-property"),
    ANNOTATION_PROPERTY("annotation-property");

    private final String path;

    HierarchyKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static HierarchyKind parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("hierarchy kind is required");
        }
        String name = raw.strip().toLowerCase(Locale.ROOT);
        for (HierarchyKind kind : values()) {
            if (kind.path.equals(name)) {
                return kind;
            }
        }
        throw new IllegalArgumentException(
                "hierarchy kind must be class, object-property, data-property, or annotation-property");
    }
}
