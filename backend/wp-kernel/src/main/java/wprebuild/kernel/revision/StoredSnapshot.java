package wprebuild.kernel.revision;

import java.util.Objects;
import wprebuild.kernel.id.RevisionNumber;

/** One encoded project snapshot stored beside its codec id (ADR-011). */
public record StoredSnapshot(RevisionNumber revision, String codecId, byte[] payload) {

    public StoredSnapshot {
        Objects.requireNonNull(revision, "revision");
        Objects.requireNonNull(codecId, "codec id");
        Objects.requireNonNull(payload, "payload");
        payload = payload.clone();
    }

    @Override
    public byte[] payload() {
        return payload.clone();
    }

    @Override
    public String toString() {
        return "StoredSnapshot[revision=%s, codecId=%s, payload=%d bytes]".formatted(revision, codecId, payload.length);
    }
}
