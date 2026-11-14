package wprebuild.app.projection;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLDatatype;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.codec.OfnZipSnapshotCodec;
import wprebuild.kernel.hierarchy.AnnotationPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ClassHierarchyView;
import wprebuild.kernel.hierarchy.DataPropertyHierarchyView;
import wprebuild.kernel.hierarchy.ObjectPropertyHierarchyView;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.revision.InMemoryRevisionLog;
import wprebuild.kernel.revision.InMemorySnapshotStore;
import wprebuild.kernel.runtime.DerivedView;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.runtime.ProjectRuntimeLoader;
import wprebuild.kernel.shortform.DisplayNameSetting;
import wprebuild.kernel.shortform.DisplayNameView;
import wprebuild.kernel.testsupport.SyntheticOntologyGenerator;

/**
 * Builds in-memory {@link ProjectRuntime} corpora for projection benchmarks (P6-11).
 *
 * <p>Class-tree shape matches {@code KernelBenchmark}: branching factor 4, one {@code rdfs:label}
 * per class, seed 20. Adds 1,000 object properties and 1,000 data properties, each with domain and
 * range. Loaded via kernel memory {@code RevisionLog} / {@code SnapshotStore} and {@code
 * ProjectRuntimeLoader} — no application upload path.
 */
final class ProjectionCorpus {

    private static final Logger log = LoggerFactory.getLogger(ProjectionCorpus.class);

    static final OntologyKey ROOT = OntologyKey.of(1);

    static final int BRANCHING = 4;

    static final int LABEL_LANGUAGES = 1;

    static final long SEED = 20L;

    static final int OBJECT_PROPERTY_COUNT = 1_000;

    static final int DATA_PROPERTY_COUNT = 1_000;

    static final int SMALL_CLASS_COUNT = 33_333;

    static final int LARGE_CLASS_COUNT = 166_667;

    static final String NAMESPACE = "http://example.org/wp-test/synthetic#";

    private ProjectionCorpus() {}

    static Loaded small() {
        return load("约 10 万", SMALL_CLASS_COUNT);
    }

    static Loaded large() {
        return load("约 50 万", LARGE_CLASS_COUNT);
    }

    private static Loaded load(String target, int classCount) {
        log.info("generate {} classes for {}", classCount, target);
        SyntheticOntologyGenerator.Parameters parameters =
                new SyntheticOntologyGenerator.Parameters(classCount, BRANCHING, LABEL_LANGUAGES, 0, 0, SEED);
        MutableProjectOntologies project = importGenerated(SyntheticOntologyGenerator.generate(parameters));
        addProperties(project, classCount);
        long axiomCount = project.axiomCount();
        log.info("{} axiom count after properties: {}", target, axiomCount);

        ProjectId projectId = ProjectId.random();
        OfnZipSnapshotCodec codec = new OfnZipSnapshotCodec();
        byte[] payload = codec.encode(project);
        project = null;
        System.gc();

        InMemoryRevisionLog revisionLog = new InMemoryRevisionLog();
        InMemorySnapshotStore store = new InMemorySnapshotStore();
        store.save(projectId, RevisionNumber.ZERO, codec.id(), payload);
        payload = null;

        ProjectRuntime runtime = new ProjectRuntimeLoader(revisionLog, store, codec, viewSuppliers()).load(projectId);
        requireViews(runtime);

        String depth3ClassIri = findDepth3ClassIri(runtime);
        String sampleObjectPropertyIri = NAMESPACE + "OP0";
        String labelPrefix = "class-12";
        log.info(
                "{} ready: axioms={}, depth3={}, property={}",
                target,
                axiomCount,
                depth3ClassIri,
                sampleObjectPropertyIri);
        return new Loaded(
                target,
                projectId,
                runtime,
                axiomCount,
                classCount,
                depth3ClassIri,
                sampleObjectPropertyIri,
                labelPrefix);
    }

    private static MutableProjectOntologies importGenerated(OWLOntology ontology) {
        MutableProjectOntologies project = OwlApiProjectOntologies.empty();
        project.apply(List.of(new DeclareOntology(
                ROOT,
                ontology.getOntologyID().getOntologyIRI(),
                ontology.getOntologyID().getVersionIRI())));
        addAxioms(project, ontology);
        ontology.getOWLOntologyManager().removeOntology(ontology);
        return project;
    }

    private static void addAxioms(MutableProjectOntologies project, OWLOntology ontology) {
        List<OntologyChange> batch = new ArrayList<>(10_000);
        ontology.axioms().forEach(axiom -> {
            batch.add(new AddAxiom(ROOT, axiom));
            if (batch.size() == 10_000) {
                project.apply(batch);
                batch.clear();
            }
        });
        if (!batch.isEmpty()) {
            project.apply(batch);
        }
    }

    /** Adds object/data property declarations with named domain and range axioms. */
    private static void addProperties(MutableProjectOntologies project, int classCount) {
        OWLDataFactory data = project.dataFactory();
        OWLClass domainClass = data.getOWLClass(IRI.create(NAMESPACE + "C0"));
        OWLClass rangeClass = data.getOWLClass(IRI.create(NAMESPACE + "C" + Math.min(1, classCount - 1)));
        OWLDatatype stringType = data.getOWLDatatype(IRI.create("http://www.w3.org/2001/XMLSchema#string"));

        List<OntologyChange> batch = new ArrayList<>(10_000);
        for (int index = 0; index < OBJECT_PROPERTY_COUNT; index++) {
            OWLObjectProperty property = data.getOWLObjectProperty(IRI.create(NAMESPACE + "OP" + index));
            batch.add(new AddAxiom(ROOT, data.getOWLDeclarationAxiom(property)));
            batch.add(new AddAxiom(ROOT, data.getOWLObjectPropertyDomainAxiom(property, domainClass)));
            batch.add(new AddAxiom(ROOT, data.getOWLObjectPropertyRangeAxiom(property, rangeClass)));
            flush(project, batch, 10_000);
        }
        for (int index = 0; index < DATA_PROPERTY_COUNT; index++) {
            OWLDataProperty property = data.getOWLDataProperty(IRI.create(NAMESPACE + "DP" + index));
            batch.add(new AddAxiom(ROOT, data.getOWLDeclarationAxiom(property)));
            batch.add(new AddAxiom(ROOT, data.getOWLDataPropertyDomainAxiom(property, domainClass)));
            batch.add(new AddAxiom(ROOT, data.getOWLDataPropertyRangeAxiom(property, stringType)));
            flush(project, batch, 10_000);
        }
        if (!batch.isEmpty()) {
            project.apply(batch);
        }
    }

    private static void flush(MutableProjectOntologies project, List<OntologyChange> batch, int limit) {
        if (batch.size() >= limit) {
            project.apply(List.copyOf(batch));
            batch.clear();
        }
    }

    /**
     * Picks a named class whose shortest path from {@code owl:Thing} has length 4 (Thing plus three
     * named classes = depth 3), preferring one that still has children so Q1 returns descendants.
     */
    private static String findDepth3ClassIri(ProjectRuntime runtime) {
        ClassHierarchyView classes = runtime.view(ClassHierarchyView.class);
        OWLClass thing = runtime.ontologies().dataFactory().getOWLThing();
        String withChildren = null;
        String any = null;
        for (var entity : runtime.ontologies().signature().toList()) {
            if (!(entity instanceof OWLClass cls) || cls.equals(thing)) {
                continue;
            }
            for (List<OWLClass> path : classes.pathsToRoot(cls)) {
                if (path.size() != 4) {
                    continue;
                }
                String iri = cls.getIRI().toString();
                if (any == null) {
                    any = iri;
                }
                if (classes.hasChildren(cls)) {
                    withChildren = iri;
                    break;
                }
            }
            if (withChildren != null) {
                break;
            }
        }
        String chosen = withChildren != null ? withChildren : any;
        if (chosen == null) {
            throw new IllegalStateException("no class at depth 3 in synthetic hierarchy");
        }
        return chosen;
    }

    private static List<Supplier<DerivedView>> viewSuppliers() {
        IRI label = OWLManager.getOWLDataFactory().getRDFSLabel().getIRI();
        DisplayNameSetting englishLabel = new DisplayNameSetting(label, "en");
        return List.of(
                ClassHierarchyView::new,
                ObjectPropertyHierarchyView::new,
                DataPropertyHierarchyView::new,
                AnnotationPropertyHierarchyView::new,
                () -> new DisplayNameView(List.of(englishLabel)));
    }

    private static void requireViews(ProjectRuntime runtime) {
        runtime.view(ClassHierarchyView.class);
        runtime.view(ObjectPropertyHierarchyView.class);
        runtime.view(DataPropertyHierarchyView.class);
        runtime.view(AnnotationPropertyHierarchyView.class);
        runtime.view(DisplayNameView.class);
    }

    record Loaded(
            String target,
            ProjectId projectId,
            ProjectRuntime runtime,
            long axiomCount,
            int classCount,
            String depth3ClassIri,
            String sampleObjectPropertyIri,
            String labelPrefix) {

        Loaded {
            Objects.requireNonNull(target, "target");
            Objects.requireNonNull(projectId, "projectId");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(depth3ClassIri, "depth3ClassIri");
            Objects.requireNonNull(sampleObjectPropertyIri, "sampleObjectPropertyIri");
            Objects.requireNonNull(labelPrefix, "labelPrefix");
        }
    }
}
