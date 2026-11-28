package wprebuild.app.ontology;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import wprebuild.app.ontology.frame.ValueNode;
import wprebuild.kernel.id.RevisionNumber;

/**
 * Applicable forms for one entity, with projected field values and the current project head.
 *
 * <p>GateGuard facts: returned by {@code GetEntityFormsHandler} / {@code EntityFormsController};
 * Glob found none; no data files; user asked {@code 完成下一个任务卡}.
 */
@Schema(
        name = "EntityFormsResult",
        requiredProperties = {"entity", "head", "forms"})
public record EntityFormsResult(String entity, long head, List<EntityFormData> forms) {

    public EntityFormsResult {
        Objects.requireNonNull(entity, "entity");
        if (entity.isBlank()) {
            throw new IllegalArgumentException("entity is blank");
        }
        forms = List.copyOf(Objects.requireNonNull(forms, "forms"));
    }

    public static EntityFormsResult of(String entity, RevisionNumber head, List<EntityFormData> forms) {
        Objects.requireNonNull(head, "head");
        return new EntityFormsResult(entity, head.value(), forms);
    }

    @Schema(
            name = "EntityFormData",
            requiredProperties = {"formId", "descriptor", "values"})
    public record EntityFormData(UUID formId, JsonNode descriptor, List<EntityFormFieldValue> values) {

        public EntityFormData {
            Objects.requireNonNull(formId, "form id");
            Objects.requireNonNull(descriptor, "descriptor");
            values = List.copyOf(Objects.requireNonNull(values, "values"));
        }
    }

    @Schema(
            name = "EntityFormFieldValue",
            requiredProperties = {"fieldId"})
    @JsonInclude(JsonInclude.Include.NON_ABSENT)
    public record EntityFormFieldValue(UUID fieldId, Optional<ValueNode> value) {

        public EntityFormFieldValue {
            Objects.requireNonNull(fieldId, "field id");
            value = value == null ? Optional.empty() : value;
        }
    }
}
