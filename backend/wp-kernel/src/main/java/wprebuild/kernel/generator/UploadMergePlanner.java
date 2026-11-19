package wprebuild.kernel.generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.parameters.Imports;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.generator.UploadMergePlan.OntologyPatch;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;

/** Pure functions that compare uploaded ontologies with the project (BS-14). */
public final class UploadMergePlanner {

    private UploadMergePlanner() {}

    /**
     * Mode one: pair by ontology IRI (both non-anonymous), or pair the only project ontology with
     * the only uploaded ontology when each side has exactly one. Import declarations are ignored.
     */
    public static UploadMergePlan newVersion(ProjectOntologies project, List<OWLOntology> uploaded) {
        Objects.requireNonNull(project, "project");
        List<OWLOntology> uploads = copyUploads(uploaded);
        List<OntologyKey> keys = sortedKeys(project.keys());
        if (keys.size() == 1 && uploads.size() == 1) {
            OntologyPatch patch = diff(project, keys.getFirst(), uploads.getFirst());
            List<OntologyPatch> patches = patch.isEmpty() ? List.of() : List.of(patch);
            return new UploadMergePlan(UploadMergeMode.NEW_VERSION, patches, List.of());
        }
        // First upload wins per ontology IRI; later duplicates stay unmatched.
        Map<IRI, OWLOntology> uploadsByIri = new LinkedHashMap<>();
        Set<OWLOntology> matchedUploads = new HashSet<>();
        for (OWLOntology upload : uploads) {
            Optional<IRI> ontologyIri = upload.getOntologyID().getOntologyIRI();
            if (ontologyIri.isPresent()) {
                uploadsByIri.putIfAbsent(ontologyIri.get(), upload);
            }
        }
        List<OntologyPatch> patches = new ArrayList<>();
        for (OntologyKey key : keys) {
            Optional<IRI> ontologyIri = project.info(key).flatMap(OntologyInfo::ontologyIri);
            if (ontologyIri.isEmpty()) {
                continue;
            }
            OWLOntology upload = uploadsByIri.get(ontologyIri.get());
            if (upload == null) {
                continue;
            }
            matchedUploads.add(upload);
            OntologyPatch patch = diff(project, key, upload);
            if (!patch.isEmpty()) {
                patches.add(patch);
            }
        }
        List<String> unmatched = new ArrayList<>();
        for (OWLOntology upload : uploads) {
            if (!matchedUploads.contains(upload)) {
                unmatched.add(uploadLabel(upload));
            }
        }
        return new UploadMergePlan(UploadMergeMode.NEW_VERSION, patches, unmatched);
    }

    /**
     * Mode two: append axioms and ontology annotations from every upload that the target does not
     * already contain. Produces no removals.
     *
     * @throws IllegalArgumentException if {@code target} is not in the project
     */
    public static UploadMergePlan additions(ProjectOntologies project, List<OWLOntology> uploaded, OntologyKey target) {
        Objects.requireNonNull(project, "project");
        Objects.requireNonNull(target, "target");
        List<OWLOntology> uploads = copyUploads(uploaded);
        if (project.info(target).isEmpty()) {
            throw new IllegalArgumentException("target ontology does not exist: " + target.value());
        }
        Set<OWLAxiom> addedAxioms = new LinkedHashSet<>();
        Set<OWLAnnotation> addedAnnotations = new LinkedHashSet<>();
        for (OWLOntology upload : uploads) {
            for (OWLAxiom axiom : upload.getAxioms(Imports.EXCLUDED)) {
                if (!project.containsAxiom(target, axiom)) {
                    addedAxioms.add(axiom);
                }
            }
            for (OWLAnnotation annotation : upload.getAnnotations()) {
                if (!project.containsAnnotation(target, annotation)) {
                    addedAnnotations.add(annotation);
                }
            }
        }
        OntologyPatch patch =
                new OntologyPatch(target, sorted(addedAxioms), List.of(), sorted(addedAnnotations), List.of());
        List<OntologyPatch> patches = patch.isEmpty() ? List.of() : List.of(patch);
        return new UploadMergePlan(UploadMergeMode.ADDITIONS, patches, List.of());
    }

    private static OntologyPatch diff(ProjectOntologies project, OntologyKey key, OWLOntology upload) {
        Set<OWLAxiom> projectAxioms = new HashSet<>(project.axioms(key).toList());
        Set<OWLAxiom> uploadAxioms = new HashSet<>(upload.getAxioms(Imports.EXCLUDED));
        Set<OWLAnnotation> projectAnnotations = new HashSet<>(project.annotations(key));
        Set<OWLAnnotation> uploadAnnotations = new HashSet<>(upload.getAnnotations());

        Set<OWLAxiom> addedAxioms = new LinkedHashSet<>(uploadAxioms);
        addedAxioms.removeAll(projectAxioms);
        Set<OWLAxiom> removedAxioms = new LinkedHashSet<>(projectAxioms);
        removedAxioms.removeAll(uploadAxioms);

        Set<OWLAnnotation> addedAnnotations = new LinkedHashSet<>(uploadAnnotations);
        addedAnnotations.removeAll(projectAnnotations);
        Set<OWLAnnotation> removedAnnotations = new LinkedHashSet<>(projectAnnotations);
        removedAnnotations.removeAll(uploadAnnotations);

        return new OntologyPatch(
                key, sorted(addedAxioms), sorted(removedAxioms), sorted(addedAnnotations), sorted(removedAnnotations));
    }

    private static List<OWLOntology> copyUploads(List<OWLOntology> uploaded) {
        Objects.requireNonNull(uploaded, "uploaded");
        List<OWLOntology> copy = new ArrayList<>(uploaded.size());
        for (OWLOntology ontology : uploaded) {
            copy.add(Objects.requireNonNull(ontology, "uploaded ontology"));
        }
        return List.copyOf(copy);
    }

    private static List<OntologyKey> sortedKeys(Set<OntologyKey> keys) {
        List<OntologyKey> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparingInt(key -> Integer.parseInt(key.value().substring(1))));
        return ordered;
    }

    private static <T extends Comparable<? super T>> List<T> sorted(Set<T> values) {
        List<T> ordered = new ArrayList<>(values);
        ordered.sort(Comparator.naturalOrder());
        return ordered;
    }

    private static String uploadLabel(OWLOntology upload) {
        return upload.getOntologyID().getOntologyIRI().map(IRI::toString).orElse("");
    }
}
