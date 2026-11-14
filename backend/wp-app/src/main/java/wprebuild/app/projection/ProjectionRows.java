package wprebuild.app.projection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.app.projection.ProjectionSnapshot.DomainRow;
import wprebuild.app.projection.ProjectionSnapshot.EntityRow;
import wprebuild.app.projection.ProjectionSnapshot.HierarchyEdgeRow;
import wprebuild.app.projection.ProjectionSnapshot.LabelRow;
import wprebuild.app.projection.ProjectionSnapshot.RangeRow;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.HierarchyView;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Pure projection of one {@link ProjectRuntime} into {@link ProjectionSnapshot} rows. Does not touch
 * the database (P6-10 / P6-11).
 */
public final class ProjectionRows {

    private static final Set<IRI> LABEL_PROPERTIES = Set.of(
            OWLRDFVocabulary.RDFS_LABEL.getIRI(), SKOSVocabulary.PREFLABEL.getIRI(), SKOSVocabulary.ALTLABEL.getIRI());

    private ProjectionRows() {}

    public static ProjectionSnapshot compute(ProjectRuntime runtime) {
        Objects.requireNonNull(runtime, "runtime");
        ProjectOntologies ontologies = runtime.ontologies();
        OWLDataFactory data = ontologies.dataFactory();

        List<EntityRow> entities = new ArrayList<>();
        List<LabelRow> labels = new ArrayList<>();
        for (OWLEntity entity : ontologies.signature().toList()) {
            entities.add(new EntityRow(entity.getIRI().toString(), entityType(entity), deprecated(ontologies, entity)));
            collectLabels(ontologies, entity.getIRI(), labels);
        }
        entities.sort(Comparator.comparing(EntityRow::iri).thenComparing(EntityRow::entityType));
        labels.sort(Comparator.comparing(LabelRow::iri)
                .thenComparing(LabelRow::propertyIri)
                .thenComparing(LabelRow::lang)
                .thenComparing(LabelRow::value));

        List<HierarchyEdgeRow> edges = hierarchyEdges(runtime, data);
        edges.sort(Comparator.comparing(HierarchyEdgeRow::hierarchy)
                .thenComparing(HierarchyEdgeRow::childIri)
                .thenComparing(HierarchyEdgeRow::parentIri));

        List<DomainRow> domains = new ArrayList<>();
        List<RangeRow> ranges = new ArrayList<>();
        collectDomainsAndRanges(ontologies, domains, ranges);
        domains.sort(Comparator.comparing(DomainRow::propertyIri).thenComparing(DomainRow::classIri));
        ranges.sort(Comparator.comparing(RangeRow::propertyIri)
                .thenComparing(RangeRow::rangeIri)
                .thenComparing(RangeRow::rangeKind));

        return new ProjectionSnapshot(entities, labels, edges, domains, ranges);
    }

    private static void collectLabels(ProjectOntologies ontologies, IRI subject, List<LabelRow> labels) {
        ontologies.annotationAssertions(subject).forEach(axiom -> {
            IRI property = axiom.getProperty().getIRI();
            if (!LABEL_PROPERTIES.contains(property)) {
                return;
            }
            if (!(axiom.getValue() instanceof OWLLiteral literal)) {
                return;
            }
            labels.add(new LabelRow(subject.toString(), property.toString(), literal.getLang(), literal.getLiteral()));
        });
    }

    private static List<HierarchyEdgeRow> hierarchyEdges(ProjectRuntime runtime, OWLDataFactory data) {
        Set<HierarchyEdgeRow> unique = new LinkedHashSet<>();
        ClassHierarchyView classes = runtime.view(ClassHierarchyView.class);
        OWLClass thing = data.getOWLThing();
        ObjectPropertyHierarchyView objectProperties = runtime.view(ObjectPropertyHierarchyView.class);
        OWLObjectProperty topObject = data.getOWLTopObjectProperty();
        DataPropertyHierarchyView dataProperties = runtime.view(DataPropertyHierarchyView.class);
        OWLDataProperty topData = data.getOWLTopDataProperty();
        AnnotationPropertyHierarchyView annotationProperties = runtime.view(AnnotationPropertyHierarchyView.class);

        for (OWLEntity entity : runtime.ontologies().signature().toList()) {
            switch (entity) {
                case OWLClass cls -> addParents("CLASS", classes, cls, thing, unique);
                case OWLObjectProperty property ->
                    addParents("OBJECT_PROPERTY", objectProperties, property, topObject, unique);
                case OWLDataProperty property -> addParents("DATA_PROPERTY", dataProperties, property, topData, unique);
                case OWLAnnotationProperty property ->
                    addParents("ANNOTATION_PROPERTY", annotationProperties, property, null, unique);
                default -> {
                    // Individuals and datatypes have no hierarchy edges.
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private static <E extends OWLEntity> void addParents(
            String hierarchy, HierarchyView<E> view, E child, E structuralRoot, Set<HierarchyEdgeRow> unique) {
        for (E parent : view.parents(child)) {
            if (structuralRoot != null && parent.equals(structuralRoot)) {
                continue;
            }
            unique.add(new HierarchyEdgeRow(
                    hierarchy, child.getIRI().toString(), parent.getIRI().toString()));
        }
    }

    private static void collectDomainsAndRanges(
            ProjectOntologies ontologies, List<DomainRow> domains, List<RangeRow> ranges) {
        Set<DomainRow> domainSet = new LinkedHashSet<>();
        Set<RangeRow> rangeSet = new LinkedHashSet<>();

        ontologies.axiomsOfType(AxiomType.OBJECT_PROPERTY_DOMAIN).forEach(axiom -> {
            if (axiom.getProperty().isAnonymous()) {
                return;
            }
            if (axiom.getDomain() instanceof OWLClass domain) {
                domainSet.add(new DomainRow(
                        axiom.getProperty().asOWLObjectProperty().getIRI().toString(),
                        domain.getIRI().toString()));
            }
        });
        ontologies.axiomsOfType(AxiomType.OBJECT_PROPERTY_RANGE).forEach(axiom -> {
            if (axiom.getProperty().isAnonymous()) {
                return;
            }
            if (axiom.getRange() instanceof OWLClass range) {
                rangeSet.add(new RangeRow(
                        axiom.getProperty().asOWLObjectProperty().getIRI().toString(),
                        range.getIRI().toString(),
                        "CLASS"));
            }
        });
        ontologies.axiomsOfType(AxiomType.DATA_PROPERTY_DOMAIN).forEach(axiom -> {
            if (axiom.getDomain() instanceof OWLClass domain) {
                domainSet.add(new DomainRow(
                        axiom.getProperty().asOWLDataProperty().getIRI().toString(),
                        domain.getIRI().toString()));
            }
        });
        ontologies.axiomsOfType(AxiomType.DATA_PROPERTY_RANGE).forEach(axiom -> {
            if (axiom.getRange() instanceof OWLDatatype range) {
                rangeSet.add(new RangeRow(
                        axiom.getProperty().asOWLDataProperty().getIRI().toString(),
                        range.getIRI().toString(),
                        "DATATYPE"));
            }
        });

        domains.addAll(domainSet);
        ranges.addAll(rangeSet);
    }

    private static String entityType(OWLEntity entity) {
        return switch (entity) {
            case OWLClass ignored -> "CLASS";
            case OWLObjectProperty ignored -> "OBJECT_PROPERTY";
            case OWLDataProperty ignored -> "DATA_PROPERTY";
            case OWLAnnotationProperty ignored -> "ANNOTATION_PROPERTY";
            case OWLNamedIndividual ignored -> "NAMED_INDIVIDUAL";
            case OWLDatatype ignored -> "DATATYPE";
            default -> throw new IllegalArgumentException("unsupported entity " + entity.getIRI());
        };
    }

    private static boolean deprecated(ProjectOntologies ontologies, OWLEntity entity) {
        return ontologies
                .annotationAssertions(entity.getIRI())
                .anyMatch(OWLAnnotationAssertionAxiom::isDeprecatedIRIAssertion);
    }
}
