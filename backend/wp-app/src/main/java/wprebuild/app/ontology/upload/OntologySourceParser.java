package wprebuild.app.ontology.upload;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.io.FileDocumentSource;
import org.semanticweb.owlapi.io.OWLParserException;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.io.UnparsableOntologyException;
import org.semanticweb.owlapi.manchestersyntax.parser.ManchesterOWLSyntaxOntologyParserFactory;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLImportsDeclaration;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyIRIMapper;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.owlxml.parser.OWLXMLParserFactory;
import org.semanticweb.owlapi.rdf.rdfxml.parser.RDFXMLParserFactory;
import org.semanticweb.owlapi.rdf.turtle.parser.TurtleOntologyParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.OntologyParseException;
import wprebuild.app.ontology.UploadProperties;

/**
 * Reads one uploaded ontology document, or a zip of documents (BS-12).
 *
 * <p>Formats are the five OWL API parsers for RDF/XML, Turtle, OWL/XML, functional syntax, and
 * Manchester syntax. Import IRIs that are not files inside the zip resolve to a missing local file
 * and are ignored. Nothing is fetched over the network.
 */
@Component
public final class OntologySourceParser {

    private static final Logger log = LoggerFactory.getLogger(OntologySourceParser.class);

    /** Not a document. Opening it fails at once, so a missing import never becomes an HTTP request. */
    private static final IRI BLOCKED = IRI.create("file:///wp-upload-missing-import");

    private static final int MAX_ENTRIES = 4096;

    private static final int MESSAGE_LIMIT = 500;

    private static final Set<String> ONTOLOGY_EXTENSIONS = Set.of("owl", "rdf", "ttl", "owx", "omn", "ofn");

    /**
     * Remote imports are mapped to a missing local file, so the loader does not open their IRIs.
     * {@code retriesToAttempt(0)} skips the first read as well, which breaks local file imports.
     */
    private static final OWLOntologyLoaderConfiguration LOADER = new OWLOntologyLoaderConfiguration()
            .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
            .setFollowRedirects(false);

    private final long maxBytes;

    public OntologySourceParser(UploadProperties properties) {
        this.maxBytes =
                Objects.requireNonNull(properties, "properties").maxSize().toBytes();
    }

    public List<OWLOntology> parse(Path file) {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            throw new OntologyParseException("upload is not a file", null, null);
        }
        try {
            if (isZip(file)) {
                return parseZip(file);
            }
            return parseDocument(file);
        } catch (OntologyParseException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new OntologyParseException("cannot read the uploaded file", null, ex);
        }
    }

    private List<OWLOntology> parseDocument(Path file) {
        OWLOntologyManager manager = manager();
        manager.getIRIMappers().add(iri -> BLOCKED);
        OWLOntology ontology = load(manager, file, null);
        return List.copyOf(manager.getSortedImportsClosure(ontology));
    }

    private List<OWLOntology> parseZip(Path zip) throws IOException {
        Path extracted = extract(zip);
        try {
            List<DocumentRef> documents = inspectAll(extracted);
            if (documents.isEmpty()) {
                throw new OntologyParseException("zip contains no ontology document", null, null);
            }
            DocumentRef root = singleRoot(documents, extracted);
            Map<IRI, Path> mapped = new LinkedHashMap<>();
            for (DocumentRef document : documents) {
                remember(mapped, document.ontologyIri(), document.file());
                remember(mapped, document.versionIri(), document.file());
            }
            return loadClosure(root, documents, mapped);
        } finally {
            deleteTree(extracted);
        }
    }

    private List<OWLOntology> loadClosure(DocumentRef root, List<DocumentRef> documents, Map<IRI, Path> mapped) {
        OWLOntologyManager manager = manager();
        manager.getIRIMappers().add(new ArchiveImportMapper(mapped));
        OWLOntology ontology = load(manager, root.file(), null);
        List<OWLOntology> closure = List.copyOf(manager.getSortedImportsClosure(ontology));
        if (closure.size() != documents.size()) {
            throw new OntologyParseException(
                    "zip contains an ontology that is not reachable from the root document", null, null);
        }
        return closure;
    }

    private List<DocumentRef> inspectAll(Path root) throws IOException {
        List<DocumentRef> documents = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                if (junk(root, file)) {
                    continue;
                }
                try {
                    documents.add(inspect(file));
                } catch (OntologyParseException ex) {
                    if (ontologyExtension(file)) {
                        throw ex;
                    }
                    log.debug("skipping non-ontology zip entry {}", root.relativize(file));
                }
            }
        }
        return documents;
    }

    private DocumentRef inspect(Path file) {
        OWLOntologyManager manager = manager();
        manager.getIRIMappers().add(iri -> BLOCKED);
        try {
            OWLOntology ontology = load(manager, file, file.getFileName().toString());
            OWLOntologyID id = ontology.getOntologyID();
            Set<IRI> imports = new LinkedHashSet<>();
            for (OWLImportsDeclaration declaration : ontology.getImportsDeclarations()) {
                imports.add(declaration.getIRI());
            }
            return new DocumentRef(
                    file.toAbsolutePath().normalize(), id.getOntologyIRI(), id.getVersionIRI(), Set.copyOf(imports));
        } finally {
            manager.clearOntologies();
        }
    }

    private static DocumentRef singleRoot(List<DocumentRef> documents, Path extracted) {
        List<DocumentRef> roots = new ArrayList<>();
        for (DocumentRef document : documents) {
            if (!importedByOther(document, documents)) {
                roots.add(document);
            }
        }
        if (roots.isEmpty()) {
            throw new OntologyParseException("zip contains no root ontology document", null, null);
        }
        if (roots.size() > 1) {
            throw new OntologyParseException(
                    "zip contains more than one root ontology document: " + names(extracted, roots), null, null);
        }
        return roots.getFirst();
    }

    private static boolean importedByOther(DocumentRef document, List<DocumentRef> documents) {
        for (DocumentRef other : documents) {
            if (other.file().equals(document.file())) {
                continue;
            }
            if (mentioned(document.ontologyIri(), other.imports())
                    || mentioned(document.versionIri(), other.imports())) {
                return true;
            }
        }
        return false;
    }

    private static boolean mentioned(Optional<IRI> iri, Set<IRI> imports) {
        return iri.isPresent() && imports.contains(iri.get());
    }

    private static void remember(Map<IRI, Path> documents, Optional<IRI> iri, Path file) {
        if (iri.isEmpty()) {
            return;
        }
        Path existing = documents.putIfAbsent(iri.get(), file);
        if (existing != null && !existing.equals(file)) {
            throw new OntologyParseException("duplicate ontology document IRI: " + iri.get(), null, null);
        }
    }

    private static String names(Path extracted, List<DocumentRef> roots) {
        return roots.stream()
                .map(document -> extracted.relativize(document.file()).toString())
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("unknown");
    }

    private Path extract(Path zip) throws IOException {
        Path root = Files.createTempDirectory("wp-upload-");
        try {
            unzip(zip, root);
            return root;
        } catch (RuntimeException | IOException ex) {
            deleteTree(root);
            throw ex;
        }
    }

    private void unzip(Path zip, Path root) throws IOException {
        long remaining = maxBytes;
        int entries = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                entries++;
                if (entries > MAX_ENTRIES) {
                    throw new OntologyParseException("zip contains too many entries", null, null);
                }
                if (entry.isDirectory()) {
                    continue;
                }
                Path target = safeDestination(root, entry.getName());
                long used = junk(root, target) ? drain(in, remaining) : writeEntry(in, target, remaining);
                remaining -= used;
            }
        }
    }

    private static Path safeDestination(Path root, String entryName) {
        String name = entryName == null ? "" : entryName.replace('\\', '/');
        if (name.isBlank() || name.indexOf('\0') >= 0 || name.length() > 1024) {
            throw new OntologyParseException("zip entry name is invalid", null, null);
        }
        Path target = root.resolve(name).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new OntologyParseException("zip entry escapes the archive directory", null, null);
        }
        return target;
    }

    private static long writeEntry(InputStream in, Path target, long remaining) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                written += read;
                if (written > remaining) {
                    throw new OntologyParseException("extracted archive exceeds the upload size limit", null, null);
                }
                out.write(buffer, 0, read);
            }
        }
        return written;
    }

    private static long drain(InputStream in, long remaining) throws IOException {
        long written = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) >= 0) {
            written += read;
            if (written > remaining) {
                throw new OntologyParseException("extracted archive exceeds the upload size limit", null, null);
            }
        }
        return written;
    }

    private static boolean junk(Path root, Path file) {
        Path relative = root.relativize(file.toAbsolutePath().normalize());
        for (Path part : relative) {
            String name = part.toString();
            if (name.equals("__MACOSX") || name.equals(".DS_Store") || name.startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    private static boolean ontologyExtension(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return ONTOLOGY_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private static boolean isZip(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] magic = in.readNBytes(4);
            return magic.length == 4
                    && magic[0] == 'P'
                    && magic[1] == 'K'
                    && ((magic[2] == 3 && magic[3] == 4)
                            || (magic[2] == 5 && magic[3] == 6)
                            || (magic[2] == 7 && magic[3] == 8));
        }
    }

    private static OWLOntology load(OWLOntologyManager manager, Path file, String label) {
        try {
            return manager.loadOntologyFromOntologyDocument(new FileDocumentSource(file.toFile()), LOADER);
        } catch (OWLOntologyCreationException | OWLRuntimeException ex) {
            throw failure(label, ex);
        }
    }

    private static OWLOntologyManager manager() {
        OWLOntologyManager manager = OWLManager.createOWLOntologyManager();
        Set<OWLParserFactory> parsers = new LinkedHashSet<>();
        parsers.add(new ManchesterOWLSyntaxOntologyParserFactory());
        parsers.add(new TurtleOntologyParserFactory());
        parsers.add(new OWLFunctionalSyntaxOWLParserFactory());
        parsers.add(new OWLXMLParserFactory());
        parsers.add(new RDFXMLParserFactory());
        manager.setOntologyParsers(parsers);
        manager.getOntologyConfigurator()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setFollowRedirects(false);
        return manager;
    }

    private static OntologyParseException failure(String label, Exception exception) {
        OWLParserException parser = best(exception);
        String message = parser == null ? fallback(exception) : text(parser);
        if (label != null && !label.isBlank() && !label.startsWith("wp-upload-")) {
            message = label + ": " + message;
        }
        Integer line = parser != null && parser.getLineNumber() > 0 ? parser.getLineNumber() : null;
        return new OntologyParseException(message, line, exception);
    }

    private static OWLParserException best(Throwable failure) {
        OWLParserException chosen = null;
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (current instanceof UnparsableOntologyException unparsable) {
                for (OWLParserException candidate : unparsable.getExceptions().values()) {
                    chosen = prefer(chosen, candidate);
                }
            }
            if (current instanceof OWLParserException candidate) {
                chosen = prefer(chosen, candidate);
            }
            Throwable next = current.getCause();
            current = next == current ? null : next;
        }
        return chosen;
    }

    private static OWLParserException prefer(OWLParserException current, OWLParserException candidate) {
        if (candidate == null) {
            return current;
        }
        if (current == null) {
            return candidate;
        }
        int candidateLine = Math.max(candidate.getLineNumber(), 0);
        int currentLine = Math.max(current.getLineNumber(), 0);
        if (candidateLine != currentLine) {
            return candidateLine > currentLine ? candidate : current;
        }
        return text(candidate).length() >= text(current).length() ? candidate : current;
    }

    private static String fallback(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "ontology document could not be parsed";
        }
        return clip(firstLine(message));
    }

    private static String text(OWLParserException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return "ontology document could not be parsed";
        }
        return clip(firstLine(message));
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.strip();
    }

    private static String clip(String message) {
        if (message.length() <= MESSAGE_LIMIT) {
            return message;
        }
        return message.substring(0, MESSAGE_LIMIT);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(OntologySourceParser::deleteQuietly);
        } catch (IOException ex) {
            log.warn("failed to delete extracted upload {}", root, ex);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("failed to delete {}", path, ex);
        }
    }

    private record DocumentRef(Path file, Optional<IRI> ontologyIri, Optional<IRI> versionIri, Set<IRI> imports) {}

    private static final class ArchiveImportMapper implements OWLOntologyIRIMapper {

        private final Map<IRI, IRI> documents;

        private ArchiveImportMapper(Map<IRI, Path> paths) {
            Map<IRI, IRI> mapped = new LinkedHashMap<>();
            paths.forEach((iri, path) -> mapped.put(iri, IRI.create(path.toFile())));
            this.documents = Map.copyOf(mapped);
        }

        @Override
        public IRI getDocumentIRI(IRI iri) {
            IRI local = documents.get(iri);
            return local != null ? local : BLOCKED;
        }
    }
}
