package wprebuild.kernel.generator;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Builds the inverse of one stored revision so the pipeline can commit it as a new revision (BS-04).
 *
 * <p>The inverse list is the target revision's changes reversed, each replaced by its inverse. A
 * missing revision, or one that contains {@link DeclareOntology}, produces no changes.
 * {@link OntologyChange#inverse()} refuses {@link DeclareOntology}.
 */
public final class RevertRevisionGenerator implements ChangeListGenerator<RevertResult> {

    private final ProjectId projectId;

    private final RevisionNumber target;

    private final RevisionLog revisionLog;

    public RevertRevisionGenerator(ProjectId projectId, RevisionNumber target, RevisionLog revisionLog) {
        this.projectId = Objects.requireNonNull(projectId, "project id");
        this.target = Objects.requireNonNull(target, "target revision");
        this.revisionLog = Objects.requireNonNull(revisionLog, "revision log");
    }

    @Override
    public GeneratedChanges<RevertResult> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        Optional<Revision> stored = revisionLog.get(projectId, target);
        if (stored.isEmpty()) {
            return rejected("revision does not exist: " + target.value());
        }
        List<OntologyChange> original = stored.get().changes();
        if (containsDeclareOntology(original)) {
            return rejected("revision contains DeclareOntology: " + target.value());
        }
        return new GeneratedChanges<>(inverseReversed(original), new RevertResult(true, Optional.empty()));
    }

    @Override
    public String describe(GeneratedChanges<RevertResult> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        return "Reverted revision " + target.value();
    }

    private static boolean containsDeclareOntology(List<OntologyChange> changes) {
        for (OntologyChange change : changes) {
            if (change instanceof DeclareOntology) {
                return true;
            }
        }
        return false;
    }

    private static List<OntologyChange> inverseReversed(List<OntologyChange> changes) {
        List<OntologyChange> inverse = new ArrayList<>(changes.size());
        for (int index = changes.size() - 1; index >= 0; index--) {
            inverse.add(changes.get(index).inverse());
        }
        return inverse;
    }

    private static GeneratedChanges<RevertResult> rejected(String reason) {
        return new GeneratedChanges<>(List.of(), new RevertResult(false, Optional.of(reason)));
    }
}
