package wprebuild.instances.rules;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable closed-world rules derived from one project's ontology projection. Domains and ranges
 * are the named classes / datatypes declared on the property itself (no inheritance from parent
 * properties, no complex class expressions).
 */
public final class ValidationRules {

    private final long ontologyRev;
    private final Set<String> classes;
    private final Set<String> deprecatedClasses;
    private final Set<String> objectProperties;
    private final Set<String> dataProperties;
    private final Map<String, Set<String>> parentsByChild;
    private final Map<String, Set<String>> domainsByProperty;
    private final Map<String, List<PropertyRange>> rangesByProperty;

    ValidationRules(
            long ontologyRev,
            Set<String> classes,
            Set<String> deprecatedClasses,
            Set<String> objectProperties,
            Set<String> dataProperties,
            Map<String, Set<String>> parentsByChild,
            Map<String, Set<String>> domainsByProperty,
            Map<String, List<PropertyRange>> rangesByProperty) {
        this.ontologyRev = ontologyRev;
        this.classes = Set.copyOf(classes);
        this.deprecatedClasses = Set.copyOf(deprecatedClasses);
        this.objectProperties = Set.copyOf(objectProperties);
        this.dataProperties = Set.copyOf(dataProperties);
        this.parentsByChild = copySetMap(parentsByChild);
        this.domainsByProperty = copySetMap(domainsByProperty);
        Map<String, List<PropertyRange>> ranges = new HashMap<>();
        rangesByProperty.forEach((k, v) -> ranges.put(k, List.copyOf(v)));
        this.rangesByProperty = Map.copyOf(ranges);
    }

    public long ontologyRev() {
        return ontologyRev;
    }

    public boolean classExists(String iri) {
        return classes.contains(Objects.requireNonNull(iri, "iri"));
    }

    public boolean isDeprecatedClass(String iri) {
        return deprecatedClasses.contains(Objects.requireNonNull(iri, "iri"));
    }

    public boolean objectPropertyExists(String iri) {
        return objectProperties.contains(Objects.requireNonNull(iri, "iri"));
    }

    public boolean dataPropertyExists(String iri) {
        return dataProperties.contains(Objects.requireNonNull(iri, "iri"));
    }

    public boolean propertyExists(String iri) {
        return objectPropertyExists(iri) || dataPropertyExists(iri);
    }

    /**
     * Reflexive-transitive ancestor closure over {@code hierarchy = 'CLASS'} edges: the class
     * itself plus every parent reached by walking upward.
     */
    public Set<String> ancestorClosure(String classIri) {
        Objects.requireNonNull(classIri, "classIri");
        Set<String> closure = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(classIri);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!closure.add(current)) {
                continue;
            }
            pending.addAll(parentsByChild.getOrDefault(current, Set.of()));
        }
        return Collections.unmodifiableSet(closure);
    }

    public Set<String> domains(String propertyIri) {
        return domainsByProperty.getOrDefault(Objects.requireNonNull(propertyIri, "propertyIri"), Set.of());
    }

    public List<PropertyRange> ranges(String propertyIri) {
        return rangesByProperty.getOrDefault(Objects.requireNonNull(propertyIri, "propertyIri"), List.of());
    }

    private static Map<String, Set<String>> copySetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new HashMap<>();
        source.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
        return Map.copyOf(copy);
    }
}
