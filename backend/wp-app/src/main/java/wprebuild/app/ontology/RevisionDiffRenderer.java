package wprebuild.app.ontology;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.semanticweb.owlapi.manchestersyntax.renderer.ManchesterOWLSyntaxOWLObjectRendererImpl;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObject;
import org.semanticweb.owlapi.util.AxiomSubjectProviderEx;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameView;

/**
 * Groups one revision by subject, then renders only the lines that will be returned.
 *
 * <p>{@link #prepare} does not touch the runtime. {@link #render} needs the project read lock and
 * builds Manchester text for the limited page only.
 */
final class RevisionDiffRenderer {

    private static final String ADD = "ADD";

    private static final String REMOVE = "REMOVE";

    private static final String UNSIGNED = "urn:webprotege:unsigned-change";

    private static final List<EntityType<?>> LOOKUP_TYPES = List.of(
            EntityType.CLASS,
            EntityType.OBJECT_PROPERTY,
            EntityType.DATA_PROPERTY,
            EntityType.ANNOTATION_PROPERTY,
            EntityType.NAMED_INDIVIDUAL,
            EntityType.DATATYPE);

    private RevisionDiffRenderer() {}

    static Prepared prepare(List<OntologyChange> changes) {
        List<OntologyChange> source = List.copyOf(Objects.requireNonNull(changes, "changes"));
        Map<String, List<Line>> groups = new LinkedHashMap<>();
        Map<String, OWLEntity> entities = new LinkedHashMap<>();
        for (OntologyChange change : source) {
            SubjectRef ref = subject(change);
            groups.computeIfAbsent(ref.key(), ignored -> new ArrayList<>()).add(new Line(change, ref));
            if (ref.entity() != null) {
                entities.putIfAbsent(ref.key(), ref.entity());
            }
        }
        List<Line> ordered = new ArrayList<>(source.size());
        for (List<Line> group : groups.values()) {
            ordered.addAll(group);
        }
        return new Prepared(List.copyOf(ordered), Map.copyOf(entities));
    }

    static List<RevisionChange> render(ProjectRuntime runtime, HierarchyQuery hierarchy, Prepared prepared, int limit) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(hierarchy, "hierarchy");
        Objects.requireNonNull(prepared, "prepared");
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1");
        }
        int end = Math.min(limit, prepared.lines().size());
        if (end == 0) {
            return List.of();
        }
        ManchesterOWLSyntaxOWLObjectRendererImpl manchester = manchester(runtime);
        Map<String, EntityNode> nodes = new LinkedHashMap<>();
        List<RevisionChange> page = new ArrayList<>(end);
        for (int index = 0; index < end; index++) {
            Line line = prepared.lines().get(index);
            EntityNode node = nodes.computeIfAbsent(
                    line.ref().key(), ignored -> node(runtime, hierarchy, line.ref(), prepared.entities()));
            page.add(new RevisionChange(operation(line.change()), node, text(manchester, line.change())));
        }
        return List.copyOf(page);
    }

    private static SubjectRef subject(OntologyChange change) {
        return switch (change) {
            case AddAxiom add -> axiomSubject(add.owlAxiom());
            case RemoveAxiom remove -> axiomSubject(remove.owlAxiom());
            case AddOntologyAnnotation add -> SubjectRef.ontology(add.ontology());
            case RemoveOntologyAnnotation remove -> SubjectRef.ontology(remove.ontology());
            case AddImport add -> SubjectRef.imported(add.iri());
            case RemoveImport remove -> SubjectRef.imported(remove.iri());
            case DeclareOntology declared -> SubjectRef.ontology(declared.ontology());
        };
    }

    private static SubjectRef axiomSubject(OWLAxiom axiom) {
        OWLObject subject = AxiomSubjectProviderEx.getSubject(axiom);
        if (subject instanceof OWLEntity entity) {
            return SubjectRef.entity(entity);
        }
        if (subject instanceof IRI iri) {
            return SubjectRef.bare(iri);
        }
        OWLEntity fallback = smallest(axiom);
        return fallback == null ? SubjectRef.unsigned() : SubjectRef.entity(fallback);
    }

    private static EntityNode node(
            ProjectRuntime runtime, HierarchyQuery hierarchy, SubjectRef ref, Map<String, OWLEntity> entities) {
        if (ref.ontology() != null) {
            return ontologyNode(runtime, ref.ontology());
        }
        if (ref.imported() != null) {
            return new EntityNode(
                    ref.imported().toString(),
                    "ONTOLOGY",
                    localName(ref.imported().toString()),
                    false,
                    false);
        }
        OWLEntity entity = entities.get(ref.key());
        if (entity == null) {
            entity = find(runtime, ref.bare());
        }
        if (entity != null) {
            return entityNode(runtime, hierarchy, entity);
        }
        String iri = ref.bare() == null ? UNSIGNED : ref.bare().toString();
        return new EntityNode(iri, "IRI", localName(iri), false, false);
    }

    private static EntityNode ontologyNode(ProjectRuntime runtime, OntologyKey key) {
        String shown = runtime.ontologies()
                .info(key)
                .flatMap(OntologyInfo::ontologyIri)
                .map(IRI::toString)
                .orElse(key.value());
        return new EntityNode(shown, "ONTOLOGY", localName(shown), false, false);
    }

    private static EntityNode entityNode(ProjectRuntime runtime, HierarchyQuery hierarchy, OWLEntity entity) {
        if (runtime.ontologies().containsEntity(entity)) {
            return hierarchy.nodes(runtime, List.of(entity)).getFirst();
        }
        return new EntityNode(
                entity.getIRI().toString(),
                typeName(entity),
                runtime.view(DisplayNameView.class).displayName(entity),
                false,
                false);
    }

    private static OWLEntity find(ProjectRuntime runtime, IRI iri) {
        if (iri == null) {
            return null;
        }
        OWLDataFactory data = runtime.ontologies().dataFactory();
        for (EntityType<?> type : LOOKUP_TYPES) {
            OWLEntity entity = data.getOWLEntity(cast(type), iri);
            if (runtime.ontologies().containsEntity(entity)) {
                return entity;
            }
        }
        return null;
    }

    private static String operation(OntologyChange change) {
        return switch (change) {
            case AddAxiom ignored -> ADD;
            case AddOntologyAnnotation ignored -> ADD;
            case AddImport ignored -> ADD;
            case DeclareOntology ignored -> ADD;
            case RemoveAxiom ignored -> REMOVE;
            case RemoveOntologyAnnotation ignored -> REMOVE;
            case RemoveImport ignored -> REMOVE;
        };
    }

    private static String text(ManchesterOWLSyntaxOWLObjectRendererImpl manchester, OntologyChange change) {
        String rendered = switch (change) {
            case AddAxiom add -> manchester.render(add.owlAxiom());
            case RemoveAxiom remove -> manchester.render(remove.owlAxiom());
            case AddOntologyAnnotation add -> manchester.render(add.annotation());
            case RemoveOntologyAnnotation remove -> manchester.render(remove.annotation());
            case AddImport add -> "Import(" + add.iri() + ")";
            case RemoveImport remove -> "Import(" + remove.iri() + ")";
            case DeclareOntology declared ->
                "Ontology("
                        + declared.ontologyIri()
                                .map(IRI::toString)
                                .orElse(declared.ontology().value())
                        + ")";
        };
        String renderedText =
                Objects.requireNonNull(rendered, "rendered change").strip();
        if (renderedText.isBlank()) {
            throw new IllegalStateException("Manchester rendering was blank");
        }
        return renderedText;
    }

    private static ManchesterOWLSyntaxOWLObjectRendererImpl manchester(ProjectRuntime runtime) {
        DisplayNameView names = runtime.view(DisplayNameView.class);
        ManchesterOWLSyntaxOWLObjectRendererImpl renderer = new ManchesterOWLSyntaxOWLObjectRendererImpl();
        renderer.setShortFormProvider(names::displayName);
        return renderer;
    }

    private static OWLEntity smallest(OWLAxiom axiom) {
        OWLEntity best = null;
        for (OWLEntity entity : axiom.getSignature()) {
            if (best == null || entity.getIRI().compareTo(best.getIRI()) < 0) {
                best = entity;
            }
        }
        return best;
    }

    private static String typeName(OWLEntity entity) {
        return switch (entity) {
            case org.semanticweb.owlapi.model.OWLClass ignored -> "CLASS";
            case org.semanticweb.owlapi.model.OWLObjectProperty ignored -> "OBJECT_PROPERTY";
            case OWLDataProperty ignored -> "DATA_PROPERTY";
            case OWLAnnotationProperty ignored -> "ANNOTATION_PROPERTY";
            case OWLNamedIndividual ignored -> "NAMED_INDIVIDUAL";
            case OWLDatatype ignored -> "DATATYPE";
            default -> throw new IllegalArgumentException("unsupported entity " + entity.getIRI());
        };
    }

    private static String localName(String iri) {
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash < iri.length() - 1) {
            return iri.substring(hash + 1);
        }
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash < iri.length() - 1) {
            return iri.substring(slash + 1);
        }
        return iri;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> cast(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }

    record Prepared(List<Line> lines, Map<String, OWLEntity> entities) {

        Prepared {
            lines = List.copyOf(Objects.requireNonNull(lines, "lines"));
            entities = Map.copyOf(Objects.requireNonNull(entities, "entities"));
        }

        int size() {
            return lines.size();
        }
    }

    private record Line(OntologyChange change, SubjectRef ref) {}

    private record SubjectRef(String key, OWLEntity entity, IRI bare, IRI imported, OntologyKey ontology) {

        static SubjectRef entity(OWLEntity entity) {
            String text = Objects.requireNonNull(entity, "entity").getIRI().toString();
            return new SubjectRef(text, entity, entity.getIRI(), null, null);
        }

        static SubjectRef bare(IRI iri) {
            String text = Objects.requireNonNull(iri, "iri").toString();
            return new SubjectRef(text, null, iri, null, null);
        }

        static SubjectRef imported(IRI iri) {
            return new SubjectRef("import:" + iri, null, null, Objects.requireNonNull(iri, "iri"), null);
        }

        static SubjectRef ontology(OntologyKey key) {
            return new SubjectRef("ontology:" + key.value(), null, null, null, key);
        }

        static SubjectRef unsigned() {
            return new SubjectRef("unsigned", null, null, null, null);
        }
    }
}
