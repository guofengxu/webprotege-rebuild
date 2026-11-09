package wprebuild.app.ontology;

import java.util.Objects;

/** Bytes returned for {@link GetProjectDownload}. The web layer turns {@link #fileName()} into Content-Disposition. */
public record ProjectDownload(byte[] body, String mediaType, String fileName) {

    public ProjectDownload {
        Objects.requireNonNull(body, "body");
        body = body.clone();
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("media type must not be blank");
        }
        if (fileName == null || fileName.isBlank() || fileName.indexOf('"') >= 0 || fileName.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("file name is not safe for Content-Disposition");
        }
    }

    @Override
    public byte[] body() {
        return body.clone();
    }
}
