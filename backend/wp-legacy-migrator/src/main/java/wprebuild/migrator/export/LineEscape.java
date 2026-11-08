package wprebuild.migrator.export;

import java.util.Objects;

/** ADR-011 line escape: {@code \} → {@code \\}, LF → {@code \n}, CR → {@code \r}. */
final class LineEscape {

    private LineEscape() {}

    static String escape(String line) {
        Objects.requireNonNull(line, "line");
        StringBuilder escaped = new StringBuilder(line.length());
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            switch (current) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(current);
            }
        }
        return escaped.toString();
    }
}
