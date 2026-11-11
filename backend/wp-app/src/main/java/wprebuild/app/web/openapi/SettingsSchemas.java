package wprebuild.app.web.openapi;

import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * Documentation-only shapes for project settings JSON.
 *
 * <p>Nothing serializes these records. {@code ProjectSettingsDocuments} writes the stored JSON, and
 * the property names here match that document.
 */
public final class SettingsSchemas {

    private SettingsSchemas() {}

    /** Prefix name to IRI. Values are absolute IRIs. */
    @Schema(name = "PrefixSettings", type = "object", additionalPropertiesSchema = String.class)
    public record PrefixSettings() {}

    @Schema(
            name = "NewEntitySettings",
            requiredProperties = {"iriPrefix", "kit", "labelProperty", "defaultLang", "targetOntology"})
    public record NewEntitySettings(
            String iriPrefix,
            EntityCrudKitSettings kit,
            String labelProperty,
            String defaultLang,
            String targetOntology) {}

    @Schema(
            name = "EntityCrudKitSettings",
            discriminatorProperty = "type",
            discriminatorMapping = {
                @DiscriminatorMapping(value = "UUID", schema = UuidEntityCrudKit.class),
                @DiscriminatorMapping(value = "SUPPLIED_NAME", schema = SuppliedNameEntityCrudKit.class),
                @DiscriminatorMapping(value = "OBO", schema = OboEntityCrudKit.class)
            },
            oneOf = {UuidEntityCrudKit.class, SuppliedNameEntityCrudKit.class, OboEntityCrudKit.class})
    public record EntityCrudKitSettings() {}

    @Schema(
            name = "UuidEntityCrudKit",
            requiredProperties = {"type"})
    public record UuidEntityCrudKit(
            @Schema(allowableValues = {"UUID"}) String type) {}

    @Schema(
            name = "SuppliedNameEntityCrudKit",
            requiredProperties = {"type", "whiteSpace"})
    public record SuppliedNameEntityCrudKit(
            @Schema(allowableValues = {"SUPPLIED_NAME"}) String type,

            @Schema(allowableValues = {"CAMEL_CASE", "UNDERSCORES"})
            String whiteSpace) {}

    @Schema(
            name = "OboEntityCrudKit",
            requiredProperties = {"type", "totalDigits", "userRanges"})
    public record OboEntityCrudKit(
            @Schema(allowableValues = {"OBO"}) String type, int totalDigits, List<OboUserRange> userRanges) {}

    @Schema(
            name = "OboUserRange",
            requiredProperties = {"userId", "start", "end"})
    public record OboUserRange(String userId, long start, long end) {}

    @Schema(
            name = "DisplayNameSetting",
            requiredProperties = {"annotationProperty", "lang"})
    public record DisplayNameSetting(String annotationProperty, String lang) {}
}
