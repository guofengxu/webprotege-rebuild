package wprebuild.app.projection;

import java.util.List;
import java.util.Objects;

/**
 * Immutable rows for one project's derived ontology projection (ADR-013). True source remains the
 * revision log; these tables are rebuildable at any time.
 */
public record ProjectionSnapshot(
        List<EntityRow> entities,
        List<LabelRow> labels,
        List<HierarchyEdgeRow> hierarchyEdges,
        List<DomainRow> domains,
        List<RangeRow> ranges) {

    public ProjectionSnapshot {
        entities = List.copyOf(Objects.requireNonNull(entities, "entities"));
        labels = List.copyOf(Objects.requireNonNull(labels, "labels"));
        hierarchyEdges = List.copyOf(Objects.requireNonNull(hierarchyEdges, "hierarchyEdges"));
        domains = List.copyOf(Objects.requireNonNull(domains, "domains"));
        ranges = List.copyOf(Objects.requireNonNull(ranges, "ranges"));
    }

    public record EntityRow(String iri, String entityType, boolean deprecated) {
        public EntityRow {
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(entityType, "entityType");
        }
    }

    public record LabelRow(String iri, String propertyIri, String lang, String value) {
        public LabelRow {
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(propertyIri, "propertyIri");
            Objects.requireNonNull(lang, "lang");
            Objects.requireNonNull(value, "value");
        }
    }

    public record HierarchyEdgeRow(String hierarchy, String childIri, String parentIri) {
        public HierarchyEdgeRow {
            Objects.requireNonNull(hierarchy, "hierarchy");
            Objects.requireNonNull(childIri, "childIri");
            Objects.requireNonNull(parentIri, "parentIri");
        }
    }

    public record DomainRow(String propertyIri, String classIri) {
        public DomainRow {
            Objects.requireNonNull(propertyIri, "propertyIri");
            Objects.requireNonNull(classIri, "classIri");
        }
    }

    public record RangeRow(String propertyIri, String rangeIri, String rangeKind) {
        public RangeRow {
            Objects.requireNonNull(propertyIri, "propertyIri");
            Objects.requireNonNull(rangeIri, "rangeIri");
            Objects.requireNonNull(rangeKind, "rangeKind");
        }
    }
}
