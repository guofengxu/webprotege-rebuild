package wprebuild.kernel.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.formats.FunctionalSyntaxDocumentFormat;
import org.semanticweb.owlapi.functional.parser.OWLFunctionalSyntaxOWLParserFactory;
import org.semanticweb.owlapi.io.OWLParserFactory;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.MissingImportHandlingStrategy;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyChange;
import org.semanticweb.owlapi.model.OWLOntologyCreationException;
import org.semanticweb.owlapi.model.OWLOntologyID;
import org.semanticweb.owlapi.model.OWLOntologyLoaderConfiguration;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.semanticweb.owlapi.model.OWLOntologyStorageException;
import org.semanticweb.owlapi.model.OWLRuntimeException;
import org.semanticweb.owlapi.model.parameters.Imports;
import uk.ac.manchester.cs.owl.owlapi.OWLOntologyManagerImpl;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.ontology.MutableProjectOntologies;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.OwlApiProjectOntologies;
import wprebuild.kernel.ontology.ProjectOntologies;

/**
 * {@code ofn-zip/1}: one functional-syntax document per ontology, plus {@code registry.tsv}
 * (ADR-011). The ontology key is the registry row, not the id OWL API writes into the document.
 */
public final class OfnZipSnapshotCodec implements SnapshotCodec {

    public static final String ID = "ofn-zip/1";

    private static final String REGISTRY = "registry.tsv";

    private static final String MISSING = "-";

    private static final Pattern DOCUMENT_NAME = Pattern.compile("o[1-9][0-9]*\\.ofn");

    /** {@code Import(<iri>)}, with whitespace allowed between the keyword, '(', and '<'. */
    private static final Pattern IMPORT_DECLARATION = Pattern.compile("Import\\s*\\(\\s*<([^>\\s]*)>");

    /** Not a document. Import loads fail here instead of opening the import IRI. */
    private static final IRI BLOCKED_IMPORT = IRI.create("urn:wp:snapshot:blocked-import");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public byte[] encode(ProjectOntologies ontologies) {
        Objects.requireNonNull(ontologies, "ontologies");
        List<OntologyKey> keys = sortedKeys(ontologies.keys());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            put(zip, REGISTRY, registry(ontologies, keys).getBytes(StandardCharsets.UTF_8));
            for (OntologyKey key : keys) {
                put(zip, documentName(key), functionalSyntax(ontologies, key));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("failed to zip ofn-zip/1 snapshot", ex);
        }
        return bytes.toByteArray();
    }

    @Override
    public MutableProjectOntologies decode(byte[] payload) {
        Objects.requireNonNull(payload, "payload");
        Map<String, byte[]> entries = unzip(payload);
        byte[] registryBytes = entries.get(REGISTRY);
        if (registryBytes == null) {
            throw new IllegalArgumentException("snapshot zip is missing registry.tsv");
        }
        List<OntologyInfo> rows = parseRegistry(utf8(registryBytes));
        if (!entries.keySet().equals(expectedEntries(rows))) {
            throw new IllegalArgumentException("snapshot entries do not match registry.tsv");
        }
        MutableProjectOntologies ontologies = OwlApiProjectOntologies.empty();
        ontologies.apply(changes(entries, rows));
        return ontologies;
    }

    private static void put(ZipOutputStream zip, String name, byte[] body) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(body);
        zip.closeEntry();
    }

    private static String registry(ProjectOntologies ontologies, List<OntologyKey> keys) {
        StringBuilder text = new StringBuilder();
        for (OntologyKey key : keys) {
            OntologyInfo info = requireInfo(ontologies, key);
            text.append(key.value())
                    .append('\t')
                    .append(iriText(info.ontologyIri()))
                    .append('\t')
                    .append(iriText(info.versionIri()))
                    .append('\n');
        }
        return text.toString();
    }

    private static byte[] functionalSyntax(ProjectOntologies ontologies, OntologyKey key) {
        OntologyInfo info = requireInfo(ontologies, key);
        OWLOntologyManager manager = writingManager();
        try {
            OWLOntology ontology = create(manager, info);
            try (Stream<OWLAxiom> axioms = ontologies.axioms(key)) {
                manager.addAxioms(ontology, axioms.toList());
            }
            List<OWLOntologyChange> header = headerChanges(ontology, ontologies, key);
            if (!header.isEmpty()) {
                manager.applyChanges(header);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            FunctionalSyntaxDocumentFormat format = new FunctionalSyntaxDocumentFormat();
            format.setAddMissingTypes(false);
            ontology.saveOntology(format, out);
            byte[] document = out.toByteArray();
            requireLossless(ontologies, key, document);
            return document;
        } catch (OWLOntologyCreationException | OWLOntologyStorageException ex) {
            throw new IllegalStateException("failed to write " + documentName(key), ex);
        }
    }

    private static List<OWLOntologyChange> headerChanges(
            OWLOntology ontology, ProjectOntologies ontologies, OntologyKey key) {
        List<OWLOntologyChange> changes = new ArrayList<>();
        for (OWLAnnotation annotation : ontologies.annotations(key)) {
            changes.add(new org.semanticweb.owlapi.model.AddOntologyAnnotation(ontology, annotation));
        }
        OWLDataFactory data = ontology.getOWLOntologyManager().getOWLDataFactory();
        for (IRI iri : ontologies.imports(key)) {
            changes.add(new org.semanticweb.owlapi.model.AddImport(ontology, data.getOWLImportsDeclaration(iri)));
        }
        return changes;
    }

    private static OWLOntology create(OWLOntologyManager manager, OntologyInfo info)
            throws OWLOntologyCreationException {
        if (info.ontologyIri().isEmpty()) {
            return manager.createOntology();
        }
        return manager.createOntology(new OWLOntologyID(info.ontologyIri(), info.versionIri()));
    }

    private static List<OntologyChange> changes(Map<String, byte[]> entries, List<OntologyInfo> rows) {
        List<OntologyChange> changes = new ArrayList<>();
        for (OntologyInfo info : rows) {
            // registry.tsv owns the key and the ontology id. The .ofn header is not consulted.
            OWLOntology loaded = parse(entries.get(documentName(info.key())));
            OntologyKey key = info.key();
            changes.add(new DeclareOntology(key, info.ontologyIri(), info.versionIri()));
            try (Stream<OWLAxiom> axioms = loaded.axioms(Imports.EXCLUDED)) {
                axioms.forEach(axiom -> changes.add(new AddAxiom(key, axiom)));
            }
            for (OWLAnnotation annotation : loaded.getAnnotations()) {
                changes.add(new AddOntologyAnnotation(key, annotation));
            }
            for (IRI iri : loaded.getDirectImportsDocuments()) {
                changes.add(new AddImport(key, iri));
            }
        }
        return List.copyOf(changes);
    }

    private static void requireLossless(ProjectOntologies source, OntologyKey key, byte[] document) {
        OWLOntology loaded = parse(document);
        if (!axiomSet(source.axioms(key)).equals(axiomSet(loaded.axioms(Imports.EXCLUDED)))
                || !source.annotations(key).equals(Set.copyOf(loaded.getAnnotations()))
                || !source.imports(key).equals(Set.copyOf(loaded.getDirectImportsDocuments()))) {
            throw new IllegalArgumentException("functional syntax render is not lossless: " + key.value());
        }
    }

    private static Set<OWLAxiom> axiomSet(Stream<OWLAxiom> axioms) {
        try (axioms) {
            return axioms.collect(Collectors.toSet());
        }
    }

    private static OWLOntology parse(byte[] document) {
        Objects.requireNonNull(document, "ontology document");
        try {
            OWLOntologyManager manager = readingManager();
            // Import declarations are recorded and not loaded. makeLoadImportRequest reads the
            // loader configuration, not the configurator. Only those IRIs are ignored: each
            // addIgnoredImport copies the ignored set, so ignoring every bracket IRI is quadratic.
            OWLOntologyLoaderConfiguration config =
                    manager.getOntologyConfigurator().buildLoaderConfiguration();
            for (IRI iri : importIris(document)) {
                config = config.addIgnoredImport(iri);
            }
            manager.setOntologyLoaderConfiguration(config);
            OWLOntology ontology = manager.loadOntologyFromOntologyDocument(new ByteArrayInputStream(document));
            if (!(manager.getOntologyFormat(ontology) instanceof FunctionalSyntaxDocumentFormat)) {
                throw new IllegalArgumentException("snapshot ontology is not functional syntax");
            }
            return ontology;
        } catch (OWLOntologyCreationException | OWLRuntimeException ex) {
            throw new IllegalArgumentException("failed to parse snapshot ontology", ex);
        }
    }

    /**
     * IRIs of functional-syntax {@code Import(<iri>)} declarations, in first-seen order.
     * A literal that happens to contain the same text only adds an extra ignored IRI.
     */
    static Set<IRI> importIris(byte[] document) {
        Objects.requireNonNull(document, "ontology document");
        Matcher matcher = IMPORT_DECLARATION.matcher(new String(document, StandardCharsets.UTF_8));
        Set<IRI> iris = new LinkedHashSet<>();
        while (matcher.find()) {
            String iri = matcher.group(1);
            if (!iri.isBlank()) {
                iris.add(IRI.create(iri));
            }
        }
        return Collections.unmodifiableSet(iris);
    }

    private static OWLOntologyManager writingManager() {
        OWLOntologyManager manager = OWLManager.createConcurrentOWLOntologyManager();
        manager.getOntologyConfigurator()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .withSaveIdsForAllAnonymousIndividuals(true);
        return manager;
    }

    /**
     * Only the functional-syntax parser is installed. The stock set includes OBO, which accepts
     * arbitrary text. Import IRIs are not fetched (P1-04).
     */
    private static OWLOntologyManager readingManager() {
        OWLOntologyManager template = OWLManager.createOWLOntologyManager();
        OWLOntologyManager manager =
                new OWLOntologyManagerImpl(template.getOWLDataFactory(), new ReentrantReadWriteLock());
        Set<OWLParserFactory> parsers = new LinkedHashSet<>();
        parsers.add(new OWLFunctionalSyntaxOWLParserFactory());
        manager.setOntologyParsers(parsers);
        manager.setOntologyFactories(copy(template.getOntologyFactories()));
        manager.getOntologyConfigurator()
                .setMissingImportHandlingStrategy(MissingImportHandlingStrategy.SILENT)
                .setConnectionTimeout(1)
                .setRetriesToAttempt(0)
                .withRemapAllAnonymousIndividualsIds(false);
        manager.getIRIMappers().add(iri -> BLOCKED_IMPORT);
        return manager;
    }

    private static <T> Set<T> copy(Iterable<T> items) {
        Set<T> copy = new LinkedHashSet<>();
        items.forEach(copy::add);
        return copy;
    }

    private static List<OntologyInfo> parseRegistry(String text) {
        if (text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("registry.tsv contains a carriage return");
        }
        if (text.isEmpty()) {
            return List.of();
        }
        String[] lines = text.split("\n", -1);
        int end = lines.length;
        if (lines[end - 1].isEmpty()) {
            end--;
        }
        List<OntologyInfo> rows = new ArrayList<>();
        Set<OntologyKey> seen = new HashSet<>();
        for (int i = 0; i < end; i++) {
            OntologyInfo info = parseRow(lines[i], i);
            if (!seen.add(info.key())) {
                throw new IllegalArgumentException(
                        "duplicate ontology key in registry.tsv: " + info.key().value());
            }
            rows.add(info);
        }
        return List.copyOf(rows);
    }

    private static OntologyInfo parseRow(String line, int index) {
        String[] fields = line.split("\t", -1);
        if (fields.length != 3) {
            throw new IllegalArgumentException("registry.tsv line " + index + " must have 3 fields");
        }
        try {
            return new OntologyInfo(new OntologyKey(fields[0]), iriField(fields[1]), iriField(fields[2]));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("registry.tsv line " + index + " is invalid", ex);
        }
    }

    private static Optional<IRI> iriField(String field) {
        if (MISSING.equals(field)) {
            return Optional.empty();
        }
        if (field.isBlank()) {
            throw new IllegalArgumentException("blank IRI in registry.tsv");
        }
        return Optional.of(IRI.create(field));
    }

    private static String iriText(Optional<IRI> iri) {
        if (iri.isEmpty()) {
            return MISSING;
        }
        String text = iri.get().toString();
        if (text.isBlank()
                || MISSING.equals(text)
                || text.indexOf('\t') >= 0
                || text.indexOf('\n') >= 0
                || text.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("ontology IRI cannot be written in registry.tsv: " + text);
        }
        return text;
    }

    private static Set<String> expectedEntries(List<OntologyInfo> rows) {
        Set<String> expected = new HashSet<>();
        expected.add(REGISTRY);
        for (OntologyInfo info : rows) {
            expected.add(documentName(info.key()));
        }
        return expected;
    }

    private static Map<String, byte[]> unzip(byte[] payload) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(payload), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !allowed(name)) {
                    throw new IllegalArgumentException("unexpected snapshot entry: " + name);
                }
                if (entries.containsKey(name)) {
                    throw new IllegalArgumentException("duplicate snapshot entry: " + name);
                }
                entries.put(name, zip.readAllBytes());
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("payload is not an ofn-zip/1 zip", ex);
        }
        return entries;
    }

    private static boolean allowed(String name) {
        return REGISTRY.equals(name) || DOCUMENT_NAME.matcher(name).matches();
    }

    private static List<OntologyKey> sortedKeys(Set<OntologyKey> keys) {
        return keys.stream().sorted(OfnZipSnapshotCodec::compareKeys).toList();
    }

    /** Positive integers without leading zeros: shorter text is the smaller number. */
    private static int compareKeys(OntologyKey left, OntologyKey right) {
        String a = left.value().substring(1);
        String b = right.value().substring(1);
        int byLength = Integer.compare(a.length(), b.length());
        if (byLength != 0) {
            return byLength;
        }
        return a.compareTo(b);
    }

    private static String documentName(OntologyKey key) {
        return key.value() + ".ofn";
    }

    private static OntologyInfo requireInfo(ProjectOntologies ontologies, OntologyKey key) {
        return ontologies
                .info(key)
                .orElseThrow(() -> new IllegalStateException("ontology is not registered: " + key.value()));
    }

    private static String utf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("registry.tsv is not valid UTF-8", ex);
        }
    }
}
