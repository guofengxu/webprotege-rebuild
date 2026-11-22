package wprebuild.instances.rules;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Minimal flat JSON-object reader for instance attribute maps (property IRI → value). */
final class AttributesJson {

    enum ValueKind {
        STRING,
        INTEGER,
        DECIMAL,
        BOOLEAN,
        NULL,
        OBJECT,
        ARRAY
    }

    private AttributesJson() {}

    static Map<String, ValueKind> parseObject(String json) {
        Objects.requireNonNull(json, "json");
        Parser parser = new Parser(json);
        parser.skipWs();
        if (!parser.tryConsume('{')) {
            throw new IllegalArgumentException("attributesJson must be a JSON object");
        }
        Map<String, ValueKind> entries = new LinkedHashMap<>();
        parser.skipWs();
        if (parser.tryConsume('}')) {
            return Map.of();
        }
        while (true) {
            parser.skipWs();
            String key = parser.readString();
            parser.skipWs();
            parser.expect(':');
            parser.skipWs();
            ValueKind kind = parser.readValueKind();
            entries.put(key, kind);
            parser.skipWs();
            if (parser.tryConsume('}')) {
                return Map.copyOf(entries);
            }
            parser.expect(',');
        }
    }

    static Map<String, ValueKind> parseObjectOrThrow(String json) {
        try {
            return parseObject(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("attributesJson must be a JSON object of property IRIs", e);
        }
    }

    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        void skipWs() {
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                    index++;
                } else {
                    return;
                }
            }
        }

        boolean tryConsume(char expected) {
            if (index < text.length() && text.charAt(index) == expected) {
                index++;
                return true;
            }
            return false;
        }

        void expect(char expected) {
            if (!tryConsume(expected)) {
                throw new IllegalArgumentException("expected '" + expected + "' at index " + index);
            }
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (index >= text.length()) {
                        throw new IllegalArgumentException("unterminated escape");
                    }
                    char esc = text.charAt(index++);
                    sb.append(
                            switch (esc) {
                                case '"', '\\', '/' -> esc;
                                case 'b' -> '\b';
                                case 'f' -> '\f';
                                case 'n' -> '\n';
                                case 'r' -> '\r';
                                case 't' -> '\t';
                                case 'u' -> readUnicode();
                                default -> throw new IllegalArgumentException("bad escape \\" + esc);
                            });
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("unterminated string");
        }

        private char readUnicode() {
            if (index + 4 > text.length()) {
                throw new IllegalArgumentException("bad unicode escape");
            }
            int value = Integer.parseInt(text.substring(index, index + 4), 16);
            index += 4;
            return (char) value;
        }

        ValueKind readValueKind() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("unexpected end of JSON");
            }
            char c = text.charAt(index);
            if (c == '"') {
                readString();
                return ValueKind.STRING;
            }
            if (c == '{') {
                skipNested('{', '}');
                return ValueKind.OBJECT;
            }
            if (c == '[') {
                skipNested('[', ']');
                return ValueKind.ARRAY;
            }
            if (c == 't') {
                expectLiteral("true");
                return ValueKind.BOOLEAN;
            }
            if (c == 'f') {
                expectLiteral("false");
                return ValueKind.BOOLEAN;
            }
            if (c == 'n') {
                expectLiteral("null");
                return ValueKind.NULL;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                return readNumberKind();
            }
            throw new IllegalArgumentException("unexpected JSON value at index " + index);
        }

        private ValueKind readNumberKind() {
            int start = index;
            if (text.charAt(index) == '-') {
                index++;
            }
            while (index < text.length() && Character.isDigit(text.charAt(index))) {
                index++;
            }
            boolean fractional = false;
            if (index < text.length() && text.charAt(index) == '.') {
                fractional = true;
                index++;
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
                fractional = true;
                index++;
                if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
                    index++;
                }
                while (index < text.length() && Character.isDigit(text.charAt(index))) {
                    index++;
                }
            }
            String number = text.substring(start, index);
            BigDecimal value = new BigDecimal(number);
            if (!fractional && value.scale() <= 0) {
                return ValueKind.INTEGER;
            }
            BigDecimal stripped = value.stripTrailingZeros();
            if (stripped.scale() <= 0) {
                return ValueKind.INTEGER;
            }
            return ValueKind.DECIMAL;
        }

        private void expectLiteral(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("expected " + literal);
            }
            index += literal.length();
        }

        private void skipNested(char open, char close) {
            expect(open);
            int depth = 1;
            boolean inString = false;
            while (index < text.length() && depth > 0) {
                char c = text.charAt(index++);
                if (inString) {
                    if (c == '\\') {
                        index++;
                    } else if (c == '"') {
                        inString = false;
                    }
                } else if (c == '"') {
                    inString = true;
                } else if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                }
            }
            if (depth != 0) {
                throw new IllegalArgumentException("unbalanced " + open + close);
            }
        }
    }
}
