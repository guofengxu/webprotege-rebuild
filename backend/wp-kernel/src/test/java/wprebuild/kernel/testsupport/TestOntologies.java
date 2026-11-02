package wprebuild.kernel.testsupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyManager;

/**
 * Loads the shared ontology corpus under {@code testdata/ontologies}.
 *
 * <p>Import IRIs are resolved from files in that directory. Copies under {@code formats/} are
 * ignored so an ontology IRI maps to the canonical document. {@code http} and {@code https}
 * imports that are not in the corpus are refused instead of fetched.
 */
public final class TestOntologies {

    static final String DIRECTORY_PROPERTY = "wp.testdata.dir";

    static final Path DEFAULT_DIRECTORY = Path.of("../../testdata/ontologies");

    private static final Pattern TURTLE_ONTOLOGY = Pattern.compile("<(https?://[^>\\s]+)>\\s+a\\s+owl:Ontology\\b");

    private static final Pattern FUNCTIONAL_ONTOLOGY = Pattern.compile("Ontology\\(\\s*<([^>\\s]+)>");

    private static final Pattern MANCHESTER_ONTOLOGY = Pattern.compile("(?m)^Ontology:\\s*<([^>\\s]+)>");

    private static final Pattern RDF_ONTOLOGY =
            Pattern.compile("<(?:[\\w.-]+:)?Ontology\\b[^>]*rdf:about=\"([^\"]+)\"");

    private static final Pattern OWL_XML_ONTOLOGY =
            Pattern.compile("<(?:[\\w.-]+:)?Ontology\\b[^>]*ontologyIRI=\"([^\"]+)\"");

    private TestOntologies() {}

    public static Path dir() {
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_DIRECTORY;
        }
        return Path.of(configured.trim());
    }

    public static OWLOntology load(String relativePath) {
        Path file = resolve(relativePath);
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        manager.getIRIMappers().add(corpusMapper(dir()));
        try {
            return manager.loadOntologyFromOntologyDocument(file.toFile());
        } catch (OWLOntologyCreationException ex) {
            throw new IllegalArgumentException("failed to parse ontology: " + file, ex);
        }
    }

    private static Path resolve(String relativePath) {
        Objects.requireNonNull(relativePath, "relativePath");
        if (relativePath.isBlank()) {
            throw new IllegalArgumentException("relativePath must not be blank");
        }
        Path root = dir().toAbsolutePath().normalize();
        Path file = root.resolve(relativePath).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException("relativePath escapes the corpus directory: " + relativePath);
        }
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("ontology file does not exist: " + file);
        }
        return file;
    }

    private static OWLOntologyIRIMapper corpusMapper(Path root) {
        Map<String, Path> documents = indexCorpus(root);
        return ontologyIri -> {
            Path document = documents.get(ontologyIri.toString());
            if (document != null) {
                return IRI.create(document.toUri());
            }
            String scheme = ontologyIri.getScheme();
            if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalArgumentException("refusing to fetch remote import: " + ontologyIri);
            }
            return null;
        };
    }

    private static Map<String, Path> indexCorpus(Path root) {
        Path absoluteRoot = root.toAbsolutePath().normalize();
        if (!Files.isDirectory(absoluteRoot)) {
            return Map.of();
        }
        try (Stream<Path> walk = Files.walk(absoluteRoot)) {
            List<Path> files = walk.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().startsWith("."))
                    .filter(path -> !isFormatsCopy(absoluteRoot, path))
                    .toList();
            Map<String, Path> indexed = new HashMap<>();
            for (Path file : files) {
                String ontologyIri = readOntologyIri(file);
                if (ontologyIri == null) {
                    continue;
                }
                Path previous = indexed.putIfAbsent(ontologyIri, file);
                if (previous != null) {
                    throw new IllegalArgumentException(
                            "duplicate ontology IRI " + ontologyIri + " in " + previous + " and " + file);
                }
            }
            return Map.copyOf(indexed);
        } catch (IOException ex) {
            throw new IllegalArgumentException("failed to index corpus: " + absoluteRoot, ex);
        }
    }

    private static boolean isFormatsCopy(Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        return relative.getNameCount() > 0
                && "formats".equals(relative.getName(0).toString());
    }

    private static String readOntologyIri(Path file) throws IOException {
        String extension = extension(file.getFileName().toString());
        String text = Files.readString(file);
        return switch (extension) {
            case "ttl" -> firstGroup(TURTLE_ONTOLOGY, text);
            case "ofn" -> firstGroup(FUNCTIONAL_ONTOLOGY, text);
            case "omn" -> firstGroup(MANCHESTER_ONTOLOGY, text);
            case "owl", "rdf", "xml", "owx" -> xmlOntologyIri(text);
            default -> null;
        };
    }

    private static String xmlOntologyIri(String text) {
        String about = firstGroup(RDF_ONTOLOGY, text);
        if (about != null) {
            return about;
        }
        return firstGroup(OWL_XML_ONTOLOGY, text);
    }

    private static String extension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }
}
