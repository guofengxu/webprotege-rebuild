package wprebuild.kernel.runtime;

import wprebuild.kernel.revision.Revision;

/**
 * Runs after a revision is stored and applied, while the generation lock is still held.
 * A failure must not roll back the revision (WP-6). {@code silent} is the generator's flag.
 */
@FunctionalInterface
public interface PostCommitListener {

    void onCommitted(ProjectRuntime runtime, Revision revision, boolean silent);
}
