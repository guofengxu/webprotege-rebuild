package wprebuild.migrator.export;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** File names shared by the exporter and the verifier. */
final class PackageFiles {

    static final String MANIFEST = "manifest.json";
    static final String REVISIONS = "revisions.jsonl";
    static final String HEAD = "head.ofn-lines.gz";
    static final String CHECKSUMS = "SHA256SUMS";
    static final List<String> CHECKED = List.of(MANIFEST, REVISIONS, HEAD);

    private PackageFiles() {}

    static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (read > 0) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
