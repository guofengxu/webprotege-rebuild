package wprebuild.instances.app;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import wprebuild.instances.InstanceRecord;
import wprebuild.instances.InstanceStore;
import wprebuild.instances.LinkRecord;
import wprebuild.instances.rules.BatchOp;
import wprebuild.instances.rules.CachedRuleSource;
import wprebuild.instances.rules.ValidatingInstanceWriter;
import wprebuild.instances.rules.ValidationRules;

/**
 * Applies authenticated batch writes through {@link CachedRuleSource} and {@link
 * ValidatingInstanceWriter}. Does not use server time as the business {@code at}.
 */
@Service
class InstancesBatchWriteService {

    private final InstancesProperties properties;
    private final InstancesApiKeyService apiKeys;
    private final InstanceStore store;
    private final CachedRuleSource rules;
    private final Clock clock;

    @Autowired
    InstancesBatchWriteService(
            InstancesProperties properties,
            InstancesApiKeyService apiKeys,
            InstanceStore store,
            CachedRuleSource rules) {
        this(properties, apiKeys, store, rules, Clock.systemUTC());
    }

    /** Package-visible for tests that inject a fixed clock for the future-at window. */
    InstancesBatchWriteService(
            InstancesProperties properties,
            InstancesApiKeyService apiKeys,
            InstanceStore store,
            CachedRuleSource rules,
            Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.apiKeys = Objects.requireNonNull(apiKeys, "apiKeys");
        this.store = Objects.requireNonNull(store, "store");
        this.rules = Objects.requireNonNull(rules, "rules");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    BatchWriteResponse apply(UUID projectId, UUID apiKeyId, BatchWriteRequest request) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(apiKeyId, "apiKeyId");
        Objects.requireNonNull(request, "request");
        if (!properties.projectId().equals(projectId)) {
            throw new ProjectForbiddenException(projectId);
        }
        if (request.at() == null) {
            throw new IllegalArgumentException("at is required");
        }
        if (request.ops() == null) {
            throw new IllegalArgumentException("ops is required");
        }
        if (request.ops().size() > properties.maxBatchOps()) {
            throw new PayloadTooLargeException(
                    "ops count " + request.ops().size() + " exceeds max " + properties.maxBatchOps());
        }
        rejectFarFuture(request.at());
        String source = resolveSource(apiKeyId, request.source());
        ValidationRules validationRules = rules.rulesFor(projectId);
        List<BatchOp> batchOps = toBatchOps(request.ops(), source);
        new ValidatingInstanceWriter(store, validationRules).applyBatch(batchOps, request.at());
        return new BatchWriteResponse(batchOps.size(), request.at(), validationRules.ontologyRev(), source);
    }

    private void rejectFarFuture(Instant at) {
        Instant limit = clock.instant().plus(properties.maxFutureSkew());
        if (at.isAfter(limit)) {
            throw new IllegalArgumentException(
                    "at (" + at + ") is more than " + properties.maxFutureSkew() + " ahead of server time");
        }
    }

    private String resolveSource(UUID apiKeyId, String bodySource) {
        if (bodySource != null && !bodySource.isBlank()) {
            return bodySource.strip();
        }
        return apiKeys.activeKeyName(apiKeyId)
                .filter(name -> !name.isBlank())
                .orElseThrow(() -> new IllegalArgumentException("source is required"));
    }

    private static List<BatchOp> toBatchOps(List<BatchWriteRequest.Op> ops, String source) {
        List<BatchOp> result = new ArrayList<>(ops.size());
        for (BatchWriteRequest.Op op : ops) {
            result.add(
                    switch (op) {
                        case BatchWriteRequest.UpsertOp upsert ->
                            new BatchOp.Upsert(new InstanceRecord(
                                    requireText(upsert.iri(), "iri"),
                                    requireText(upsert.classIri(), "classIri"),
                                    requireText(upsert.attributesJson(), "attributesJson"),
                                    source,
                                    0L));
                        case BatchWriteRequest.LinkOp link ->
                            new BatchOp.Link(
                                    new LinkRecord(
                                            requireText(link.subjectIri(), "subjectIri"),
                                            requireText(link.propertyIri(), "propertyIri"),
                                            requireText(link.objectIri(), "objectIri")),
                                    requireText(link.subjectClassIri(), "subjectClassIri"),
                                    requireText(link.objectClassIri(), "objectClassIri"));
                        case BatchWriteRequest.UnlinkOp unlink ->
                            new BatchOp.Unlink(new LinkRecord(
                                    requireText(unlink.subjectIri(), "subjectIri"),
                                    requireText(unlink.propertyIri(), "propertyIri"),
                                    requireText(unlink.objectIri(), "objectIri")));
                        case BatchWriteRequest.RetireOp retire -> new BatchOp.Retire(requireText(retire.iri(), "iri"));
                        case null -> throw new IllegalArgumentException("ops entry must not be null");
                    });
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
