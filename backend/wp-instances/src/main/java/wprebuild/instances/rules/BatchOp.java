package wprebuild.instances.rules;

import java.util.Objects;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.LinkRecord;

/**
 * One validated batch mutation for {@link ValidatingInstanceWriter#applyBatch}. Link ops carry the
 * subject/object class IRIs required by the closed-world link gate.
 */
public sealed interface BatchOp {

    record Upsert(InstanceRecord record) implements BatchOp {
        public Upsert {
            Objects.requireNonNull(record, "record");
        }
    }

    record Link(LinkRecord link, String subjectClassIri, String objectClassIri) implements BatchOp {
        public Link {
            Objects.requireNonNull(link, "link");
            Objects.requireNonNull(subjectClassIri, "subjectClassIri");
            Objects.requireNonNull(objectClassIri, "objectClassIri");
            if (subjectClassIri.isBlank()) {
                throw new IllegalArgumentException("subjectClassIri must not be blank");
            }
            if (objectClassIri.isBlank()) {
                throw new IllegalArgumentException("objectClassIri must not be blank");
            }
        }
    }

    record Unlink(LinkRecord link) implements BatchOp {
        public Unlink {
            Objects.requireNonNull(link, "link");
        }
    }

    record Retire(String iri) implements BatchOp {
        public Retire {
            Objects.requireNonNull(iri, "iri");
            if (iri.isBlank()) {
                throw new IllegalArgumentException("iri must not be blank");
            }
        }
    }
}
