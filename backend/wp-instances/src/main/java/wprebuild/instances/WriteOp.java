package wprebuild.instances;

import java.util.Objects;

/** One mutation applied inside {@link InstanceStore#applyBatch}. */
public sealed interface WriteOp {

    record Upsert(InstanceRecord record) implements WriteOp {
        public Upsert {
            Objects.requireNonNull(record, "record");
        }
    }

    record Retire(String iri) implements WriteOp {
        public Retire {
            Objects.requireNonNull(iri, "iri");
            if (iri.isBlank()) {
                throw new IllegalArgumentException("iri must not be blank");
            }
        }
    }

    record Link(LinkRecord link) implements WriteOp {
        public Link {
            Objects.requireNonNull(link, "link");
        }
    }

    record Unlink(LinkRecord link) implements WriteOp {
        public Unlink {
            Objects.requireNonNull(link, "link");
        }
    }
}
