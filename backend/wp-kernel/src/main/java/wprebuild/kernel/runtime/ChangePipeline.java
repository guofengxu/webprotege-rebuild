package wprebuild.kernel.runtime;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.EffectiveChanges;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.revision.RevisionLog;

/**
 * Single write path for a project (write-path.md).
 *
 * <p>The generation lock covers the whole call. The revision is appended before
 * {@link ProjectWriteGate#applyExclusive}, so a database commit does not block readers. Listeners
 * run after the write lock is released and before the generation lock is released.
 */
public final class ChangePipeline {

    private static final Logger log = LoggerFactory.getLogger(ChangePipeline.class);

    private final ProjectRuntime runtime;
    private final ProjectWriteGate gate;
    private final RevisionLog revisionLog;
    private final FreshIriMinter minter;
    private final List<PostCommitListener> listeners;
    private final Clock clock;

    public ChangePipeline(
            ProjectRuntime runtime,
            ProjectWriteGate gate,
            RevisionLog revisionLog,
            FreshIriMinter minter,
            List<PostCommitListener> listeners,
            Clock clock) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.gate = Objects.requireNonNull(gate, "write gate");
        this.revisionLog = Objects.requireNonNull(revisionLog, "revision log");
        this.minter = Objects.requireNonNull(minter, "fresh IRI minter");
        this.listeners = List.copyOf(Objects.requireNonNull(listeners, "listeners"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public <R> ChangeApplicationResult<R> apply(UserId user, ChangeListGenerator<R> generator) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(generator, "generator");
        return gate.generate(() -> applyWhileGenerating(user, generator));
    }

    private <R> ChangeApplicationResult<R> applyWhileGenerating(UserId user, ChangeListGenerator<R> generator) {
        if (runtime.isCorrupted()) {
            throw new ProjectRuntimeCorruptedException(runtime.projectId());
        }
        GeneratedChanges<R> generated = Objects.requireNonNull(
                generator.generate(new ChangeContext(user, runtime.ontologies())), "generated changes");
        FreshIriMinter.MintResult minted =
                Objects.requireNonNull(minter.mint(user, generated.changes(), runtime.ontologies()), "mint result");
        List<OntologyChange> effective = EffectiveChanges.compute(runtime.ontologies(), minted.changes());
        R result = Objects.requireNonNull(
                generator.renameResult(generated.result(), minted.renameMap()), "renamed result");
        if (effective.isEmpty()) {
            return new ChangeApplicationResult<>(result, effective, minted.renameMap(), Optional.empty());
        }
        Revision revision = commit(user, generator, generated, effective);
        notifyListeners(revision, generator.silent());
        return new ChangeApplicationResult<>(result, effective, minted.renameMap(), Optional.of(revision));
    }

    private <R> Revision commit(
            UserId user,
            ChangeListGenerator<R> generator,
            GeneratedChanges<R> generated,
            List<OntologyChange> effective) {
        String description = Objects.requireNonNull(generator.describe(generated, effective), "description");
        RevisionNumber expectedHead = runtime.head();
        Revision revision = new Revision(expectedHead.next(), user, clock.instant(), description, effective);
        // WP-3: persist first. A failed append leaves memory and the head untouched.
        revisionLog.append(runtime.projectId(), expectedHead, revision);
        gate.applyExclusive(() -> {
            try {
                runtime.applyCommitted(revision);
            } catch (RuntimeException failure) {
                // WP-8: set the flag before this write lock is released. A snapshot waiting on the
                // read lock must not persist a partial apply.
                runtime.markCorrupted();
                throw failure;
            }
        });
        return revision;
    }

    private void notifyListeners(Revision revision, boolean silent) {
        for (PostCommitListener listener : listeners) {
            try {
                listener.onCommitted(runtime, revision, silent);
            } catch (RuntimeException failure) {
                // WP-6: the revision stays committed, and later listeners still run.
                log.error(
                        "post-commit listener failed for revision {} of project {}",
                        revision.number().value(),
                        runtime.projectId(),
                        failure);
            }
        }
    }
}
