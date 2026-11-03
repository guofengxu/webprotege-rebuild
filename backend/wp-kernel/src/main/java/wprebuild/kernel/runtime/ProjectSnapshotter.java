package wprebuild.kernel.runtime;

import java.util.Objects;
import wprebuild.kernel.codec.SnapshotCodec;
import wprebuild.kernel.revision.SnapshotStore;

/** Saves the runtime's ontologies under its current head. When to call this is decided later. */
public final class ProjectSnapshotter {

    private ProjectSnapshotter() {}

    public static void snapshot(ProjectRuntime runtime, SnapshotStore store, SnapshotCodec codec) {
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(store, "snapshot store");
        Objects.requireNonNull(codec, "snapshot codec");
        String codecId = Objects.requireNonNull(codec.id(), "codec id");
        byte[] payload = Objects.requireNonNull(codec.encode(runtime.ontologies()), "snapshot payload");
        store.save(runtime.projectId(), runtime.head(), codecId, payload);
    }
}
