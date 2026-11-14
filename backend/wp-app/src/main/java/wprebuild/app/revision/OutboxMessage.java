package wprebuild.app.revision;

import java.time.Instant;
import java.util.Objects;
import wprebuild.kernel.id.ProjectId;

/** One row from {@code outbox}, delivered to {@link OutboxConsumer}s in id order. */
public record OutboxMessage(
        long id, ProjectId projectId, long revNo, String type, String payloadJson, Instant createdAt) {

    public OutboxMessage {
        if (id < 1) {
            throw new IllegalArgumentException("outbox id must be positive");
        }
        Objects.requireNonNull(projectId, "project id");
        if (revNo < 1) {
            throw new IllegalArgumentException("revision number must be positive");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(payloadJson, "payload json");
        Objects.requireNonNull(createdAt, "created at");
    }
}
