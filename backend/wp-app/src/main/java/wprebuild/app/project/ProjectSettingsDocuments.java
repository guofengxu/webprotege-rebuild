package wprebuild.app.project;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.semanticweb.owlapi.model.IRI;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.crud.KitSettings;
import wprebuild.kernel.crud.NewEntitySettings;
import wprebuild.kernel.crud.OboKitSettings;
import wprebuild.kernel.crud.PrefixedNameExpander;
import wprebuild.kernel.crud.SuppliedNameKitSettings;
import wprebuild.kernel.crud.UserIdRange;
import wprebuild.kernel.crud.UuidKitSettings;
import wprebuild.kernel.crud.WhiteSpaceTreatment;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.shortform.DisplayNameSetting;

/**
 * Jackson documents stored in {@code project_settings}.
 *
 * <p>The shape matches the migration converter: kit {@code type}, IRIs as strings, and ontology keys
 * as {@code o1}. It is not the default Jackson view of the kernel records.
 */
public final class ProjectSettingsDocuments {

    /** Same fallback the previous editor used when a project had no prefix yet. */
    public static final String DEFAULT_IRI_PREFIX = "http://www.example.org/";

    private static final JsonMapper JSON = new JsonMapper();

    private static final int MAX_PREFIXES = 256;

    private static final int MAX_PREFIX_NAME = 64;

    private static final int MAX_IRI = 2048;

    private static final int MAX_NAMES = 32;

    private static final int MAX_LANG = 64;

    private static final int MAX_RANGES = 256;

    private static final int MAX_USER_ID = 200;

    private static final OntologyKey DEFAULT_TARGET = OntologyKey.of(1);

    private ProjectSettingsDocuments() {}

    public static boolean same(String left, String right) {
        try {
            return tree(left).equals(tree(right));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    public static String canonical(String key, JsonNode document) {
        return switch (ProjectSettingKeys.check(key)) {
            case ProjectSettingKeys.PREFIXES -> writePrefixes(parsePrefixes(document));
            case ProjectSettingKeys.NEW_ENTITY -> writeNewEntity(parseNewEntity(document));
            case ProjectSettingKeys.DISPLAY_NAMES -> writeDisplayNames(parseDisplayNames(document));
            default -> throw new IllegalStateException("unknown project setting");
        };
    }

    public static JsonNode canonicalTree(String key, String stored) {
        return tree(
                switch (ProjectSettingKeys.check(key)) {
                    case ProjectSettingKeys.PREFIXES -> writePrefixes(readPrefixes(stored));
                    case ProjectSettingKeys.NEW_ENTITY -> writeNewEntity(readNewEntity(stored));
                    case ProjectSettingKeys.DISPLAY_NAMES -> writeDisplayNames(readDisplayNames(stored));
                    default -> throw new IllegalStateException("unknown project setting");
                });
    }

    public static JsonNode defaults(String key) {
        return tree(
                switch (ProjectSettingKeys.check(key)) {
                    case ProjectSettingKeys.PREFIXES -> writePrefixes(Map.of());
                    case ProjectSettingKeys.NEW_ENTITY -> writeNewEntity(defaultNewEntitySettings());
                    case ProjectSettingKeys.DISPLAY_NAMES -> writeDisplayNames(List.of());
                    default -> throw new IllegalStateException("unknown project setting");
                });
    }

    public static Map<String, String> readPrefixes(String json) {
        return parsePrefixes(tree(json));
    }

    public static List<DisplayNameSetting> readDisplayNames(String json) {
        return parseDisplayNames(tree(json));
    }

    public static NewEntitySettings readNewEntity(String json) {
        return parseNewEntity(tree(json));
    }

    public static NewEntitySettings defaultNewEntitySettings() {
        return NewEntitySettings.defaults(DEFAULT_IRI_PREFIX, DEFAULT_TARGET);
    }

    private static Map<String, String> parsePrefixes(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("prefixes must be a JSON object");
        }
        if (document.size() > MAX_PREFIXES) {
            throw new IllegalArgumentException("too many prefixes");
        }
        Map<String, String> prefixes = new TreeMap<>();
        document.forEachEntry((name, value) -> {
            if (value == null || !value.isString()) {
                throw new IllegalArgumentException("prefix IRI must be a string");
            }
            String prefixName = prefixName(name);
            String iri = absoluteIri(value.asString(), "prefix IRI").toString();
            if (prefixes.put(prefixName, iri) != null) {
                throw new IllegalArgumentException("duplicate prefix name");
            }
        });
        new PrefixedNameExpander(prefixes);
        return Map.copyOf(prefixes);
    }

    private static List<DisplayNameSetting> parseDisplayNames(JsonNode document) {
        if (document == null || !document.isArray()) {
            throw new IllegalArgumentException("display names must be a JSON array");
        }
        if (document.size() > MAX_NAMES) {
            throw new IllegalArgumentException("too many display names");
        }
        List<DisplayNameSetting> names = new ArrayList<>();
        for (JsonNode item : document) {
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException("display name must be an object");
            }
            JsonNode property = item.get("annotationProperty");
            if (property == null || !property.isString()) {
                throw new IllegalArgumentException("annotationProperty is required");
            }
            names.add(new DisplayNameSetting(
                    absoluteIri(property.asString(), "annotation property"), language(item.get("lang"))));
        }
        return List.copyOf(names);
    }

    private static NewEntitySettings parseNewEntity(JsonNode document) {
        if (document == null || !document.isObject()) {
            throw new IllegalArgumentException("new entity settings must be a JSON object");
        }
        String prefix = prefixIri(text(document, "iriPrefix", "iriPrefix"));
        KitSettings kit = kit(document.get("kit"));
        IRI label = absoluteIri(text(document, "labelProperty", "labelProperty"), "label property");
        String lang = language(document.get("defaultLang"));
        OntologyKey target = ontologyKey(text(document, "targetOntology", "targetOntology"));
        return new NewEntitySettings(prefix, kit, label, lang, target);
    }

    private static KitSettings kit(JsonNode kit) {
        if (kit == null || !kit.isObject()) {
            throw new IllegalArgumentException("kit is required");
        }
        String type = text(kit, "type", "kit type");
        return switch (type) {
            case "UUID" -> new UuidKitSettings();
            case "SUPPLIED_NAME" -> new SuppliedNameKitSettings(whiteSpace(kit.get("whiteSpace")));
            case "OBO" -> new OboKitSettings(totalDigits(kit.get("totalDigits")), userRanges(kit.get("userRanges")));
            default -> throw new IllegalArgumentException("kit type must be UUID, SUPPLIED_NAME, or OBO");
        };
    }

    private static WhiteSpaceTreatment whiteSpace(JsonNode node) {
        if (node == null || node.isNull()) {
            return WhiteSpaceTreatment.CAMEL_CASE;
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("whiteSpace must be a string");
        }
        return switch (node.asString()) {
            case "CAMEL_CASE" -> WhiteSpaceTreatment.CAMEL_CASE;
            case "UNDERSCORES" -> WhiteSpaceTreatment.UNDERSCORES;
            default -> throw new IllegalArgumentException("whiteSpace must be CAMEL_CASE or UNDERSCORES");
        };
    }

    private static int totalDigits(JsonNode node) {
        if (node == null || node.isNull()) {
            return OboKitSettings.DEFAULT_TOTAL_DIGITS;
        }
        if (!node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new IllegalArgumentException("totalDigits must be an integer");
        }
        return node.asInt();
    }

    private static List<UserIdRange> userRanges(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalArgumentException("userRanges must be an array");
        }
        if (node.size() > MAX_RANGES) {
            throw new IllegalArgumentException("too many user ranges");
        }
        List<UserIdRange> ranges = new ArrayList<>();
        for (JsonNode item : node) {
            if (item == null || !item.isObject()) {
                throw new IllegalArgumentException("user range must be an object");
            }
            String userId = text(item, "userId", "userId");
            if (userId.length() > MAX_USER_ID || hasControl(userId)) {
                throw new IllegalArgumentException("userId is invalid");
            }
            ranges.add(new UserIdRange(new UserId(userId), integral(item.get("start")), integral(item.get("end"))));
        }
        return List.copyOf(ranges);
    }

    private static long integral(JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException("range bound must be an integer");
        }
        return node.asLong();
    }

    private static String writePrefixes(Map<String, String> prefixes) {
        ObjectNode node = JSON.createObjectNode();
        new TreeMap<>(prefixes).forEach(node::put);
        return write(node);
    }

    private static String writeDisplayNames(List<DisplayNameSetting> names) {
        ArrayNode array = JSON.createArrayNode();
        for (DisplayNameSetting name : names) {
            ObjectNode item = array.addObject();
            item.put("annotationProperty", name.annotationProperty().toString());
            item.put("lang", name.lang());
        }
        return write(array);
    }

    private static String writeNewEntity(NewEntitySettings settings) {
        ObjectNode node = JSON.createObjectNode();
        node.put("iriPrefix", settings.iriPrefix());
        node.set("kit", writeKit(settings.kit()));
        node.put("labelProperty", settings.labelProperty().toString());
        node.put("defaultLang", settings.defaultLang());
        node.put("targetOntology", settings.targetOntology().value());
        return write(node);
    }

    private static ObjectNode writeKit(KitSettings kit) {
        ObjectNode node = JSON.createObjectNode();
        switch (kit) {
            case UuidKitSettings ignored -> node.put("type", "UUID");
            case SuppliedNameKitSettings supplied -> {
                node.put("type", "SUPPLIED_NAME");
                node.put("whiteSpace", supplied.whiteSpace().name());
            }
            case OboKitSettings obo -> {
                node.put("type", "OBO");
                node.put("totalDigits", obo.totalDigits());
                ArrayNode ranges = node.putArray("userRanges");
                for (UserIdRange range : obo.userRanges()) {
                    ObjectNode item = ranges.addObject();
                    item.put("userId", range.userId().value());
                    item.put("start", range.start());
                    item.put("end", range.end());
                }
            }
        }
        return node;
    }

    private static String prefixName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("prefix name must not be blank");
        }
        String name = raw.endsWith(":") ? raw.substring(0, raw.length() - 1) : raw;
        if (name.isBlank()
                || name.length() > MAX_PREFIX_NAME
                || name.indexOf(':') >= 0
                || hasControl(name)
                || hasWhitespace(name)) {
            throw new IllegalArgumentException("prefix name is invalid");
        }
        return name;
    }

    private static String prefixIri(String raw) {
        IRI iri = absoluteIri(raw, "iriPrefix");
        if (hasWhitespace(raw)) {
            throw new IllegalArgumentException("iriPrefix must not contain whitespace");
        }
        return iri.toString();
    }

    private static IRI absoluteIri(String raw, String label) {
        String text = requireBounded(raw, label);
        if (hasWhitespace(text) || hasControl(text)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        final IRI iri;
        try {
            iri = IRI.create(text);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        if (!iri.isAbsolute() || (!text.contains("://") && !text.regionMatches(true, 0, "urn:", 0, 4))) {
            throw new IllegalArgumentException(label + " must be an absolute IRI");
        }
        return iri;
    }

    private static OntologyKey ontologyKey(String raw) {
        try {
            return new OntologyKey(requireBounded(raw, "targetOntology"));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("targetOntology must be an ontology key");
        }
    }

    private static String language(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (!node.isString()) {
            throw new IllegalArgumentException("language must be a string");
        }
        String lang = node.asString();
        if (lang.length() > MAX_LANG || hasWhitespace(lang) || hasControl(lang)) {
            throw new IllegalArgumentException("language is invalid");
        }
        return lang.toLowerCase(Locale.ROOT);
    }

    private static String text(JsonNode parent, String field, String label) {
        if (parent == null) {
            throw new IllegalArgumentException(label + " is required");
        }
        JsonNode node = parent.get(field);
        if (node == null || node.isNull() || !node.isString()) {
            throw new IllegalArgumentException(label + " is required");
        }
        return requireBounded(node.asString(), label);
    }

    private static String requireBounded(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(label + " is required");
        }
        String text = raw.trim();
        if (text.length() > MAX_IRI || hasControl(text)) {
            throw new IllegalArgumentException(label + " is invalid");
        }
        return text;
    }

    private static boolean hasWhitespace(String value) {
        return value.chars().anyMatch(Character::isWhitespace);
    }

    private static boolean hasControl(String value) {
        return value.chars().anyMatch(Character::isISOControl);
    }

    private static JsonNode tree(String json) {
        try {
            return JSON.readTree(Objects.requireNonNull(json, "json"));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("project setting is not valid JSON");
        }
    }

    private static String write(JsonNode node) {
        return JSON.writeValueAsString(node);
    }
}
