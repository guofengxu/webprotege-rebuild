package wprebuild.app.ontology;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.ManchesterSyntaxDocumentFormat;
import org.semanticweb.owlapi.formats.OWLXMLDocumentFormat;
import org.semanticweb.owlapi.formats.RDFXMLDocumentFormat;
import org.semanticweb.owlapi.formats.TurtleDocumentFormat;
import org.semanticweb.owlapi.model.OWLDocumentFormat;

/** BS-12 download formats. The query value is the file extension. */
public enum DownloadFormat {
    OWL("owl", "application/rdf+xml", RDFXMLDocumentFormat::new),
    TTL("ttl", "text/turtle", TurtleDocumentFormat::new),
    OWX("owx", "application/owl+xml", OWLXMLDocumentFormat::new),
    OMN("omn", "text/owl-manchester", ManchesterSyntaxDocumentFormat::new),
    OFN("ofn", "text/owl-functional", FunctionalSyntaxDocumentFormat::new);

    /** Keeps the suggested filename inside a response header. The ontology bytes are not truncated. */
    private static final int FILE_NAME_LIMIT = 150;

    private final String extension;

    private final String mediaType;

    private final Supplier<OWLDocumentFormat> formats;

    DownloadFormat(String extension, String mediaType, Supplier<OWLDocumentFormat> formats) {
        this.extension = extension;
        this.mediaType = mediaType;
        this.formats = formats;
    }

    public String extension() {
        return extension;
    }

    public String mediaType() {
        return mediaType;
    }

    /** A new format instance. Missing entity types are not invented on save. */
    public OWLDocumentFormat documentFormat() {
        OWLDocumentFormat format = formats.get();
        format.setAddMissingTypes(false);
        return format;
    }

    public static DownloadFormat parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("format is required");
        }
        String name = raw.strip().toLowerCase(Locale.ROOT);
        for (DownloadFormat format : values()) {
            if (format.extension.equals(name)) {
                return format;
            }
        }
        throw new IllegalArgumentException("format must be owl, ttl, owx, omn, or ofn");
    }

    static String fileName(String projectName, long revision, String extension) {
        return limit(sanitize(projectName, "project"))
                + "-r"
                + revision
                + "."
                + Objects.requireNonNull(extension, "extension");
    }

    private static String limit(String value) {
        return value.length() <= FILE_NAME_LIMIT ? value : value.substring(0, FILE_NAME_LIMIT);
    }

    static String entryName(String key, String ontologyIri, String extension) {
        return key + "-" + sanitize(lastSegment(ontologyIri), "ontology") + "." + extension;
    }

    /** Characters that are illegal in a file name, or that would break Content-Disposition. */
    private static String sanitize(String raw, String blank) {
        StringBuilder out = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (ch < 0x20 || ch == 0x7f || "\\/:*?\"<>|".indexOf(ch) >= 0) {
                out.append('_');
            } else {
                out.append(ch);
            }
        }
        String sanitized = out.toString();
        return sanitized.isBlank() ? blank : sanitized;
    }

    private static String lastSegment(String ontologyIri) {
        if (ontologyIri == null || ontologyIri.isBlank()) {
            return "";
        }
        int cut = Math.max(ontologyIri.lastIndexOf('/'), ontologyIri.lastIndexOf('#'));
        if (cut < 0 || cut == ontologyIri.length() - 1) {
            return "";
        }
        return ontologyIri.substring(cut + 1);
    }
}
