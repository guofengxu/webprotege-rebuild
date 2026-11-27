package wprebuild.instances.app;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.time.Instant;
import java.util.List;

/**
 * Body for {@code POST /api/v1/projects/{projectId}/batch}. {@code at} is caller-supplied (never
 * server {@code now()}). {@code source} may be omitted when the authenticated API key has a name.
 */
record BatchWriteRequest(Instant at, String source, List<Op> ops) {

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "op")
    @JsonSubTypes({
        @JsonSubTypes.Type(value = UpsertOp.class, name = "upsert"),
        @JsonSubTypes.Type(value = LinkOp.class, name = "link"),
        @JsonSubTypes.Type(value = UnlinkOp.class, name = "unlink"),
        @JsonSubTypes.Type(value = RetireOp.class, name = "retire")
    })
    sealed interface Op permits UpsertOp, LinkOp, UnlinkOp, RetireOp {}

    record UpsertOp(String iri, String classIri, String attributesJson) implements Op {}

    record LinkOp(
            String subjectIri, String propertyIri, String objectIri, String subjectClassIri, String objectClassIri)
            implements Op {}

    record UnlinkOp(String subjectIri, String propertyIri, String objectIri) implements Op {}

    record RetireOp(String iri) implements Op {}
}
