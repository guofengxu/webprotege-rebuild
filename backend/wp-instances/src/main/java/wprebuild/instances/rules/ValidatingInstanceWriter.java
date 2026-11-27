package wprebuild.instances.rules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.InstanceStore;
import wprebuild.instances.LinkRecord;
import wprebuild.instances.WriteOp;

/**
 * Write gate that validates against loaded {@link ValidationRules} before delegating to
 * {@link InstanceStore}. Successful writes stamp {@code ontology_rev} from the rules.
 */
public final class ValidatingInstanceWriter {

    private final InstanceStore store;
    private final ValidationRules rules;
    private final InstanceValidator validator;

    public ValidatingInstanceWriter(InstanceStore store, ValidationRules rules) {
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.validator = new InstanceValidator();
    }

    public void upsert(InstanceRecord record, Instant at) {
        Objects.requireNonNull(record, "record");
        Objects.requireNonNull(at, "at");
        List<Violation> violations = validator.validate(record, rules);
        if (!violations.isEmpty()) {
            throw new InstanceValidationException(violations);
        }
        InstanceRecord stamped = new InstanceRecord(
                record.iri(), record.classIri(), record.attributesJson(), record.source(), rules.ontologyRev());
        store.upsert(stamped, at);
    }

    public void link(LinkRecord link, String subjectClassIri, String objectClassIri, Instant at) {
        Objects.requireNonNull(link, "link");
        Objects.requireNonNull(subjectClassIri, "subjectClassIri");
        Objects.requireNonNull(objectClassIri, "objectClassIri");
        Objects.requireNonNull(at, "at");
        List<Violation> violations = validator.validateLink(link, subjectClassIri, objectClassIri, rules);
        if (!violations.isEmpty()) {
            throw new InstanceValidationException(violations);
        }
        store.link(link, at);
    }

    /**
     * Validate every op first; on any {@link Violation} throw and write nothing. Otherwise apply the
     * whole batch in one store transaction (stamping {@code ontology_rev} on upserts).
     */
    public void applyBatch(List<BatchOp> ops, Instant at) {
        Objects.requireNonNull(ops, "ops");
        Objects.requireNonNull(at, "at");
        List<Violation> violations = new ArrayList<>();
        List<WriteOp> writes = new ArrayList<>(ops.size());
        for (BatchOp op : ops) {
            switch (op) {
                case BatchOp.Upsert(InstanceRecord record) -> {
                    List<Violation> found = validator.validate(record, rules);
                    violations.addAll(found);
                    if (found.isEmpty()) {
                        writes.add(new WriteOp.Upsert(stamp(record)));
                    }
                }
                case BatchOp.Link(LinkRecord link, String subjectClassIri, String objectClassIri) -> {
                    List<Violation> found = validator.validateLink(link, subjectClassIri, objectClassIri, rules);
                    violations.addAll(found);
                    if (found.isEmpty()) {
                        writes.add(new WriteOp.Link(link));
                    }
                }
                case BatchOp.Unlink(LinkRecord link) -> writes.add(new WriteOp.Unlink(link));
                case BatchOp.Retire(String iri) -> writes.add(new WriteOp.Retire(iri));
            }
        }
        if (!violations.isEmpty()) {
            throw new InstanceValidationException(violations);
        }
        store.applyBatch(writes, at);
    }

    private InstanceRecord stamp(InstanceRecord record) {
        return new InstanceRecord(
                record.iri(), record.classIri(), record.attributesJson(), record.source(), rules.ontologyRev());
    }
}
