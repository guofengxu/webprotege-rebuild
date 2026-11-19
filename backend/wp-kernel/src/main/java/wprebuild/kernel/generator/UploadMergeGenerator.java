package wprebuild.kernel.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
import wprebuild.kernel.generator.UploadMergePlan.OntologyPatch;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Builds one revision that applies an upload merge plan recomputed under the generation lock (BS-14).
 */
public final class UploadMergeGenerator implements ChangeListGenerator<UploadMergePlan> {

    private final UploadMergeMode mode;

    private final List<OWLOntology> uploaded;

    private final Optional<OntologyKey> target;

    private final String description;

    /** Resolved in {@link #generate} so {@link #describe} can prefer the ontology IRI over the key. */
    private Optional<String> additionsTargetLabel = Optional.empty();

    public UploadMergeGenerator(
            UploadMergeMode mode, List<OWLOntology> uploaded, Optional<OntologyKey> target, String description) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.uploaded = copyUploads(uploaded);
        this.target = Objects.requireNonNull(target, "target");
        this.description = Objects.requireNonNull(description, "description");
        if (mode == UploadMergeMode.ADDITIONS && target.isEmpty()) {
            throw new IllegalArgumentException("ADDITIONS mode requires a target ontology");
        }
    }

    @Override
    public GeneratedChanges<UploadMergePlan> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        ProjectOntologies ontologies = context.ontologies();
        UploadMergePlan plan = switch (mode) {
            case NEW_VERSION -> UploadMergePlanner.newVersion(ontologies, uploaded);
            case ADDITIONS -> {
                OntologyKey key = target.orElseThrow();
                additionsTargetLabel = Optional.of(labelFor(ontologies, key));
                yield UploadMergePlanner.additions(ontologies, uploaded, key);
            }
        };
        return new GeneratedChanges<>(toChanges(plan), plan);
    }

    @Override
    public String describe(GeneratedChanges<UploadMergePlan> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        if (!description.isBlank()) {
            return description;
        }
        if (mode == UploadMergeMode.NEW_VERSION) {
            return "Merged uploaded version";
        }
        // Pipeline always calls generate before describe; fall back to the key if misused.
        String label = additionsTargetLabel.orElseGet(
                () -> target.map(OntologyKey::value).orElseThrow());
        return "Merged uploaded ontologies into " + label;
    }

    private static List<OntologyChange> toChanges(UploadMergePlan plan) {
        List<OntologyChange> changes = new ArrayList<>();
        for (OntologyPatch patch : plan.patches()) {
            OntologyKey key = patch.key();
            for (OWLAxiom axiom : patch.removedAxioms()) {
                changes.add(new RemoveAxiom(key, axiom));
            }
            for (OWLAnnotation annotation : patch.removedAnnotations()) {
                changes.add(new RemoveOntologyAnnotation(key, annotation));
            }
            for (OWLAxiom axiom : patch.addedAxioms()) {
                changes.add(new AddAxiom(key, axiom));
            }
            for (OWLAnnotation annotation : patch.addedAnnotations()) {
                changes.add(new AddOntologyAnnotation(key, annotation));
            }
        }
        return changes;
    }

    private static String labelFor(ProjectOntologies ontologies, OntologyKey key) {
        return ontologies
                .info(key)
                .flatMap(OntologyInfo::ontologyIri)
                .map(Object::toString)
                .orElse(key.value());
    }

    private static List<OWLOntology> copyUploads(List<OWLOntology> uploaded) {
        Objects.requireNonNull(uploaded, "uploaded");
        List<OWLOntology> copy = new ArrayList<>(uploaded.size());
        for (OWLOntology ontology : uploaded) {
            copy.add(Objects.requireNonNull(ontology, "uploaded ontology"));
        }
        return List.copyOf(copy);
    }
}
