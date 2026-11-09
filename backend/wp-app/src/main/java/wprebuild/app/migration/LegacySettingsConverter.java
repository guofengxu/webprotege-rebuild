package wprebuild.app.migration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.crud.OboKitSettings;
import wprebuild.kernel.crud.PrefixedNameExpander;
import wprebuild.kernel.shortform.DisplayNameSetting;

/**
 * Turns legacy Mongo settings into the JSON stored in {@code project_settings}.
 *
 * <p>New-entity JSON uses {@code kit.type} of {@code UUID}, {@code SUPPLIED_NAME}, or {@code OBO}.
 * An unrecognized kit becomes the UUID kit and adds a warning. Display names are an ordered list of
 * annotation property and language; languages that are not annotation assertions are skipped.
 *
 * <p>The stored documents name the kit with {@code type} and write IRIs and ontology keys as strings.
 * That is the shape later settings code reads. It is not the default Jackson view of the kernel records,
 * which cannot depend on Jackson.
 */
@Component
public final class LegacySettingsConverter {

    /** Same fallback the previous editor used when a project had no prefix yet. */
    static final String DEFAULT_IRI_PREFIX = "http://www.example.org/";

    private static final JsonMapper JSON = new JsonMapper();

    private static final PrefixedNameExpander PREFIXES = new PrefixedNameExpander(Map.of());

    private static final int SHOWN_LIMIT = 80;

    public ConvertedSettings convert(
            JsonNode entityCrudKitSettings, JsonNode displayNameSettings, OntologyKey targetOntology) {
        Objects.requireNonNull(targetOntology, "target ontology");
        List<String> warnings = new ArrayList<>();
        List<DisplayNameSetting> names = displayNames(displayNameSettings, warnings);
        String label = names.isEmpty()
                ? NewEntitySettings.RDFS_LABEL.toString()
                : names.get(0).annotationProperty().toString();
        String lang = names.isEmpty() ? "" : names.get(0).lang();
        ObjectNode entity = newEntity(entityCrudKitSettings, targetOntology, label, lang, warnings);
        return new ConvertedSettings(write(entity), write(namesJson(names)), List.copyOf(warnings));
    }

    String prefixesJson(Map<String, String> prefixes) {
        ObjectNode node = JSON.createObjectNode();
        Objects.requireNonNull(prefixes, "prefixes").forEach(node::put);
        return write(node);
    }

    private static ObjectNode newEntity(
            JsonNode crud, OntologyKey target, String label, String lang, List<String> warnings) {
        ObjectNode entity = JSON.createObjectNode();
        if (crud == null || crud.isNull() || crud.isMissingNode() || !crud.isObject()) {
            warnings.add("unrecognized entity crud kit; using UUID defaults");
            entity.put("iriPrefix", DEFAULT_IRI_PREFIX);
            entity.set("kit", uuidKit());
        } else {
            JsonNode settings = settingsNode(crud);
            entity.put("iriPrefix", iriPrefix(settings, warnings));
            entity.set("kit", kit(settings, warnings));
        }
        entity.put("labelProperty", label);
        entity.put("defaultLang", lang);
        entity.put("targetOntology", target.value());
        return entity;
    }

    private static JsonNode settingsNode(JsonNode raw) {
        JsonNode nested = raw.get("settings");
        if (nested != null && nested.isObject()) {
            return nested;
        }
        return raw;
    }

    private static String iriPrefix(JsonNode settings, List<String> warnings) {
        JsonNode prefix = settings.get("prefixSettings");
        if (prefix == null || !prefix.isObject()) {
            return DEFAULT_IRI_PREFIX;
        }
        JsonNode conditional = prefix.get("conditionalIriPrefixes");
        if (conditional != null && conditional.isArray() && !conditional.isEmpty()) {
            warnings.add("conditional IRI prefixes are not imported");
        }
        JsonNode value = prefix.get("iriPrefix");
        if (value == null || value.isNull()) {
            return DEFAULT_IRI_PREFIX;
        }
        if (!value.isString() || value.asString().isBlank()) {
            warnings.add("entity crud kit IRI prefix is not usable; using the default");
            return DEFAULT_IRI_PREFIX;
        }
        return value.asString();
    }

    private static ObjectNode kit(JsonNode settings, List<String> warnings) {
        JsonNode suffix = settings.get("suffixSettings");
        if (suffix == null || suffix.isNull()) {
            if (!settings.has("prefixSettings")) {
                warnings.add("unrecognized entity crud kit; using UUID defaults");
            }
            return uuidKit();
        }
        if (!suffix.isObject()) {
            warnings.add("unrecognized entity crud kit; using UUID defaults");
            return uuidKit();
        }
        String rawType = rawKitType(suffix);
        return switch (kind(rawType)) {
            case UUID -> {
                warnUuidFormat(suffix, warnings);
                yield uuidKit();
            }
            case SUPPLIED_NAME -> suppliedKit(whiteSpace(suffix, warnings));
            case OBO -> oboKit(totalDigits(suffix, warnings), userRanges(suffix, warnings));
            case UNKNOWN -> {
                warnings.add(
                        rawType.isBlank()
                                ? "unrecognized entity crud kit; using UUID defaults"
                                : "unrecognized entity crud kit " + shown(rawType) + "; using UUID defaults");
                yield uuidKit();
            }
        };
    }

    private static void warnUuidFormat(JsonNode suffix, List<String> warnings) {
        String format = text(suffix, "uuidFormat");
        if (format != null && !format.isBlank() && !"BASE62".equals(format)) {
            warnings.add("UUID format " + shown(format) + " is imported as the base62 UUID kit");
        }
    }

    private static String whiteSpace(JsonNode suffix, List<String> warnings) {
        String value = text(suffix, "whiteSpaceTreatment");
        if (value == null || value.isBlank() || "TRANSFORM_TO_CAMEL_CASE".equals(value) || "CAMEL_CASE".equals(value)) {
            return "CAMEL_CASE";
        }
        if ("REPLACE_WITH_UNDERSCORES".equals(value) || "UNDERSCORES".equals(value)) {
            return "UNDERSCORES";
        }
        warnings.add("whitespace treatment " + shown(value) + " is imported as camel case");
        return "CAMEL_CASE";
    }

    private static int totalDigits(JsonNode suffix, List<String> warnings) {
        JsonNode raw = suffix.get("totalDigits");
        if (raw == null || raw.isNull()) {
            return OboKitSettings.DEFAULT_TOTAL_DIGITS;
        }
        Long digits = integral(raw);
        if (digits == null || digits < 1 || digits > OboKitSettings.MAX_TOTAL_DIGITS) {
            warnings.add("OBO totalDigits is not usable; using " + OboKitSettings.DEFAULT_TOTAL_DIGITS);
            return OboKitSettings.DEFAULT_TOTAL_DIGITS;
        }
        return digits.intValue();
    }

    private static ArrayNode userRanges(JsonNode suffix, List<String> warnings) {
        ArrayNode ranges = JSON.createArrayNode();
        JsonNode listed = suffix.get("userIdRanges");
        if (listed == null || listed.isNull()) {
            return ranges;
        }
        if (!listed.isArray()) {
            warnings.add("OBO user ranges are not usable; using none");
            return ranges;
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode range : listed) {
            addRange(ranges, seen, range, warnings);
        }
        return ranges;
    }

    private static void addRange(ArrayNode ranges, Set<String> seen, JsonNode range, List<String> warnings) {
        if (range == null || !range.isObject()) {
            warnings.add("skipped an OBO user range");
            return;
        }
        String userId = text(range, "userId");
        Long start = integral(range.get("start"));
        Long end = integral(range.get("end"));
        if (userId == null || userId.isBlank() || start == null || end == null || start < 0 || end < start) {
            warnings.add("skipped an OBO user range");
            return;
        }
        if (!seen.add(userId)) {
            warnings.add("duplicate OBO user range for " + shown(userId));
            return;
        }
        ObjectNode stored = JSON.createObjectNode();
        stored.put("userId", userId);
        stored.put("start", start);
        stored.put("end", end);
        ranges.add(stored);
    }

    private static String rawKitType(JsonNode suffix) {
        for (String field : List.of("_class", "@class", "kitId")) {
            String value = text(suffix, field);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static KitKind kind(String rawType) {
        String token = rawType;
        int dot = token.lastIndexOf('.');
        if (dot >= 0 && dot < token.length() - 1) {
            token = token.substring(dot + 1);
        }
        token = token.toLowerCase(Locale.ROOT);
        if (token.endsWith("suffixsettings")) {
            token = token.substring(0, token.length() - "suffixsettings".length());
        } else if (token.endsWith("suffix")) {
            token = token.substring(0, token.length() - "suffix".length());
        }
        return switch (token) {
            case "uuid" -> KitKind.UUID;
            case "suppliedname" -> KitKind.SUPPLIED_NAME;
            case "obo", "oboid" -> KitKind.OBO;
            default -> KitKind.UNKNOWN;
        };
    }

    private static List<DisplayNameSetting> displayNames(JsonNode raw, List<String> warnings) {
        if (raw == null || raw.isNull() || raw.isMissingNode() || raw.isEmpty()) {
            return List.of();
        }
        if (!raw.isObject()) {
            warnings.add("display name settings were not recognized; using none");
            return List.of();
        }
        List<DisplayNameSetting> names = new ArrayList<>();
        appendLanguages(raw, "primaryDisplayNameLanguages", names, warnings);
        appendLanguages(raw, "secondaryDisplayNameLanguages", names, warnings);
        return List.copyOf(names);
    }

    private static void appendLanguages(
            JsonNode raw, String field, List<DisplayNameSetting> names, List<String> warnings) {
        JsonNode listed = raw.get(field);
        if (listed == null || listed.isNull()) {
            return;
        }
        if (!listed.isArray()) {
            warnings.add(field + " must be an array; using none");
            return;
        }
        for (JsonNode node : listed) {
            DisplayNameSetting setting = language(node, warnings);
            if (setting != null) {
                names.add(setting);
            }
        }
    }

    private static DisplayNameSetting language(JsonNode node, List<String> warnings) {
        if (node == null || !node.isObject()) {
            warnings.add("skipped a display name language");
            return null;
        }
        String type = languageType(node);
        if (!"AnnotationAssertion".equals(type)) {
            warnings.add("skipped display name language " + shown(type));
            return null;
        }
        IRI property = propertyIri(text(node, "propertyIri"));
        if (property == null) {
            warnings.add("skipped a display name with an unusable annotation property");
            return null;
        }
        String lang = text(node, "lang");
        return new DisplayNameSetting(property, lang == null ? "" : lang);
    }

    private static String languageType(JsonNode node) {
        String type = text(node, "type");
        if (type == null || type.isBlank()) {
            return text(node, "propertyIri") == null ? "LocalName" : "AnnotationAssertion";
        }
        return type;
    }

    private static IRI propertyIri(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("<") && text.endsWith(">") && text.length() > 2) {
            text = text.substring(1, text.length() - 1);
        }
        Optional<IRI> expanded = PREFIXES.expand(text);
        if (expanded.isPresent()) {
            return expanded.get();
        }
        try {
            IRI iri = IRI.create(text);
            return iri.isAbsolute() ? iri : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ObjectNode uuidKit() {
        ObjectNode kit = JSON.createObjectNode();
        kit.put("type", "UUID");
        return kit;
    }

    private static ObjectNode suppliedKit(String whiteSpace) {
        ObjectNode kit = JSON.createObjectNode();
        kit.put("type", "SUPPLIED_NAME");
        kit.put("whiteSpace", whiteSpace);
        return kit;
    }

    private static ObjectNode oboKit(int digits, ArrayNode ranges) {
        ObjectNode kit = JSON.createObjectNode();
        kit.put("type", "OBO");
        kit.put("totalDigits", digits);
        kit.set("userRanges", ranges);
        return kit;
    }

    private static ArrayNode namesJson(List<DisplayNameSetting> names) {
        ArrayNode array = JSON.createArrayNode();
        for (DisplayNameSetting name : names) {
            ObjectNode node = JSON.createObjectNode();
            node.put("annotationProperty", name.annotationProperty().toString());
            node.put("lang", name.lang());
            array.add(node);
        }
        return array;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isString()) {
            return null;
        }
        return value.asString();
    }

    /** Accepts a JSON integer, a decimal string, and mongoexport {@code $numberInt} / {@code $numberLong}. */
    private static Long integral(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isIntegralNumber() && node.canConvertToLong()) {
            return node.longValue();
        }
        if (node.isString()) {
            return parseLong(node.asString());
        }
        if (!node.isObject()) {
            return null;
        }
        Long numberLong = extendedLong(node, "$numberLong");
        if (numberLong != null) {
            return numberLong;
        }
        return extendedLong(node, "$numberInt");
    }

    private static Long extendedLong(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isString()) {
            return parseLong(value.asString());
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return value.longValue();
        }
        return null;
    }

    private static Long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String shown(String value) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < value.length() && out.length() < SHOWN_LIMIT; index++) {
            char current = value.charAt(index);
            out.append(Character.isISOControl(current) ? ' ' : current);
        }
        if (value.length() > SHOWN_LIMIT) {
            out.append("...");
        }
        return out.toString();
    }

    private static String write(JsonNode node) {
        return JSON.writeValueAsString(node);
    }

    private enum KitKind {
        UUID,
        SUPPLIED_NAME,
        OBO,
        UNKNOWN
    }
}

/** JSON documents for {@code new-entity} and {@code display-names}, plus conversion warnings. */
record ConvertedSettings(String newEntityJson, String displayNamesJson, List<String> warnings) {

    ConvertedSettings {
        Objects.requireNonNull(newEntityJson, "new entity json");
        Objects.requireNonNull(displayNamesJson, "display names json");
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
    }
}
