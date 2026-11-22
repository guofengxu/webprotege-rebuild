package wprebuild.instances.rules;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.InstanceStore;
import wprebuild.instances.LinkRecord;

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
}
