package wprebuild.app.search;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.cjk.CJKAnalyzer;
import org.apache.lucene.analysis.miscellaneous.PerFieldAnalyzerWrapper;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field.Store;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexCommit;
import org.apache.lucene.index.IndexNotFoundException;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.IndexWriterConfig.OpenMode;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.vocab.DublinCoreVocabulary;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import wprebuild.app.ontology.HierarchyQuery;
import wprebuild.app.ontology.ProjectHandle;
import wprebuild.app.ontology.ProjectRuntimeRegistry;
import wprebuild.app.ontology.ProjectSettingsProvider;
import wprebuild.app.ontology.dto.EntityNode;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ProjectRuntime;
import wprebuild.kernel.shortform.DisplayNameSetting;
import wprebuild.kernel.shortform.DisplayNameView;

/**
 * One Lucene directory per project under {@code ${wp.data-dir}/search/<projectId>}.
 *
 * <p>Name values are indexed twice, with {@link StandardAnalyzer} and {@link CJKAnalyzer}. A query
 * must match every token from one of those analyzers; standard tokens are prefixes. The indexed
 * revision is the commit user data field {@code revision}. The per-project lock is taken before the
 * read lock, and the writer is closed before that lock is released.
 */
@Component
public final class ProjectSearchIndex {

    private static final Logger log = LoggerFactory.getLogger(ProjectSearchIndex.class);

    static final String REVISION = "revision";

    /** Display-name list used when the commit was written. A change forces a full rebuild. */
    static final String NAMES = "names";

    private static final String ID = "id";

    private static final String IRI_FIELD = "iri";

    private static final String TYPE = "type";

    private static final String LOCAL_STD = "local_std";

    private static final String LOCAL_CJK = "local_cjk";

    /** Same order {@link DisplayNameView} uses when a project has no display-name list (BS-09). */
    private static final List<DisplayNameSetting> DEFAULT_SETTINGS = List.of(
            new DisplayNameSetting(SKOSVocabulary.PREFLABEL.getIRI(), ""),
            new DisplayNameSetting(OWLRDFVocabulary.RDFS_LABEL.getIRI(), ""),
            new DisplayNameSetting(DublinCoreVocabulary.TITLE.getIRI(), ""),
            new DisplayNameSetting(IRI.create("http://purl.org/dc/terms/title"), ""));

    private final ProjectRuntimeRegistry registry;

    private final HierarchyQuery hierarchy;

    private final ProjectSettingsProvider settings;

    private final DataDirectoryProperties directories;

    private final ConcurrentHashMap<ProjectId, Object> locks = new ConcurrentHashMap<>();

    public ProjectSearchIndex(
            ProjectRuntimeRegistry registry,
            HierarchyQuery hierarchy,
            ProjectSettingsProvider settings,
            DataDirectoryProperties directories) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.hierarchy = Objects.requireNonNull(hierarchy, "hierarchy");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.directories = Objects.requireNonNull(directories, "directories");
    }

    public List<EntityNode> search(ProjectId projectId, String query, Set<EntityType<?>> types, int limit) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(types, "types");
        ProjectHandle project = registry.get(projectId);
        synchronized (lock(projectId)) {
            return project.gate().read(() -> searchLocked(projectId, project.runtime(), query, types, limit));
        }
    }

    public void onCommitted(ProjectId projectId, Revision revision) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(revision, "revision");
        ProjectHandle project = registry.get(projectId);
        synchronized (lock(projectId)) {
            project.gate().read(() -> {
                apply(projectId, project.runtime(), revision);
                return null;
            });
        }
    }

    /**
     * Waits until no writer for {@code projectId} is open. Writers are not kept after an update, so
     * the directory can be removed once this returns.
     */
    public void close(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        synchronized (lock(projectId)) {
            // The lock is the barrier. There is no retained writer.
        }
    }

    /** Revision stored in the latest index commit, or {@code -1} when the directory has no commit. */
    public long indexedRevision(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        synchronized (lock(projectId)) {
            try {
                return storedRevision(directories.indexDirectory(projectId));
            } catch (IOException ex) {
                throw failed(projectId, ex);
            }
        }
    }

    private List<EntityNode> searchLocked(
            ProjectId projectId, ProjectRuntime runtime, String query, Set<EntityType<?>> types, int limit) {
        requireUsable(projectId, runtime);
        try {
            List<DisplayNameSetting> names = nameSettings(projectId);
            Path path = directories.indexDirectory(projectId);
            if (storedRevision(path) != runtime.head().value()
                    || !namesKey(names).equals(storedNames(path))) {
                withWriter(path, names, writer -> rebuild(projectId, runtime, writer, names));
            }
            return hierarchy.nodes(runtime, hits(path, runtime, names, query, types, limit));
        } catch (IOException ex) {
            throw failed(projectId, ex);
        }
    }

    private void apply(ProjectId projectId, ProjectRuntime runtime, Revision revision) {
        requireUsable(projectId, runtime);
        try {
            List<DisplayNameSetting> names = nameSettings(projectId);
            Path path = directories.indexDirectory(projectId);
            withWriter(path, names, writer -> update(projectId, runtime, revision, writer, names));
        } catch (IOException ex) {
            throw failed(projectId, ex);
        }
    }

    private void update(
            ProjectId projectId,
            ProjectRuntime runtime,
            Revision revision,
            IndexWriter writer,
            List<DisplayNameSetting> names)
            throws IOException {
        long stored = revisionOf(writer.getDirectory());
        long head = revision.number().value();
        boolean namesChanged = !namesKey(names).equals(storedNames(writer.getDirectory()));
        if (stored == head && !namesChanged) {
            return;
        }
        if (stored != head - 1 || namesChanged) {
            rebuild(projectId, runtime, writer, names);
            return;
        }
        for (OWLEntity entity : affected(runtime, revision)) {
            Term id = new Term(ID, documentId(entity));
            if (runtime.ontologies().containsEntity(entity)) {
                writer.updateDocument(id, document(runtime, names, entity));
            } else {
                writer.deleteDocuments(id);
            }
        }
        commit(writer, head, namesKey(names));
    }

    private void rebuild(
            ProjectId projectId, ProjectRuntime runtime, IndexWriter writer, List<DisplayNameSetting> names)
            throws IOException {
        writer.deleteAll();
        for (OWLEntity entity : runtime.ontologies().signature().toList()) {
            writer.addDocument(document(runtime, names, entity));
        }
        commit(writer, runtime.head().value(), namesKey(names));
        log.info(
                "rebuilt search index for {} at revision {}",
                projectId,
                runtime.head().value());
    }

    private List<DisplayNameSetting> nameSettings(ProjectId projectId) {
        List<DisplayNameSetting> configured = settings.displayNames(projectId);
        return configured.isEmpty() ? DEFAULT_SETTINGS : configured;
    }

    private Object lock(ProjectId projectId) {
        return locks.computeIfAbsent(projectId, ignored -> new Object());
    }

    private void withWriter(Path path, List<DisplayNameSetting> names, WriterBody body) throws IOException {
        Files.createDirectories(path);
        StandardAnalyzer standard = new StandardAnalyzer();
        CJKAnalyzer cjk = new CJKAnalyzer();
        Analyzer analyzer = analyzer(standard, cjk, names.size());
        try (Directory directory = FSDirectory.open(path)) {
            IndexWriter writer = new IndexWriter(directory, writerConfig(analyzer));
            try {
                body.write(writer);
            } catch (IOException | RuntimeException failure) {
                rollback(writer, failure);
                throw failure;
            }
            writer.close();
        } finally {
            analyzer.close();
            standard.close();
            cjk.close();
        }
    }

    /**
     * Signature entities, plus the IRI subject of an annotation assertion. That subject is not an
     * OWL entity, so a label edit would otherwise leave the named entity stale.
     */
    private static Set<OWLEntity> affected(ProjectRuntime runtime, Revision revision) {
        Set<OWLEntity> affected = new LinkedHashSet<>();
        Set<IRI> subjects = new LinkedHashSet<>();
        for (OntologyChange change : revision.changes()) {
            affected.addAll(change.signature());
            annotationSubject(change).ifPresent(subjects::add);
        }
        OWLDataFactory data = runtime.ontologies().dataFactory();
        for (IRI iri : subjects) {
            for (EntityType<?> type : EntityType.values()) {
                OWLEntity entity = data.getOWLEntity(cast(type), iri);
                if (runtime.ontologies().containsEntity(entity)) {
                    affected.add(entity);
                }
            }
        }
        return affected;
    }

    private static Optional<IRI> annotationSubject(OntologyChange change) {
        OWLAxiom axiom = switch (change) {
            case AddAxiom add -> add.owlAxiom();
            case RemoveAxiom remove -> remove.owlAxiom();
            default -> null;
        };
        if (axiom instanceof OWLAnnotationAssertionAxiom assertion && assertion.getSubject() instanceof IRI iri) {
            return Optional.of(iri);
        }
        return Optional.empty();
    }

    private static Document document(ProjectRuntime runtime, List<DisplayNameSetting> names, OWLEntity entity) {
        Document document = new Document();
        document.add(new StringField(ID, documentId(entity), Store.NO));
        document.add(new StringField(IRI_FIELD, entity.getIRI().toString(), Store.YES));
        document.add(new StringField(TYPE, SearchTypes.code(entity.getEntityType()), Store.YES));
        addText(document, LOCAL_STD, LOCAL_CJK, localName(entity.getIRI()));
        Map<DisplayNameSetting, String> values =
                runtime.view(DisplayNameView.class).allNames(entity);
        for (int i = 0; i < names.size(); i++) {
            addText(document, nameField(i, true), nameField(i, false), values.get(names.get(i)));
        }
        return document;
    }

    private static void addText(Document document, String standardField, String cjkField, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        document.add(new TextField(standardField, value, Store.NO));
        document.add(new TextField(cjkField, value, Store.NO));
    }

    private static List<OWLEntity> hits(
            Path path,
            ProjectRuntime runtime,
            List<DisplayNameSetting> names,
            String text,
            Set<EntityType<?>> types,
            int limit)
            throws IOException {
        try (Directory directory = FSDirectory.open(path);
                DirectoryReader reader = DirectoryReader.open(directory);
                StandardAnalyzer standard = new StandardAnalyzer();
                CJKAnalyzer cjk = new CJKAnalyzer()) {
            IndexSearcher searcher = new IndexSearcher(reader);
            TopDocs top = searcher.search(query(names.size(), standard, cjk, text, types), limit);
            StoredFields stored = reader.storedFields();
            OWLDataFactory data = runtime.ontologies().dataFactory();
            List<OWLEntity> entities = new ArrayList<>();
            for (ScoreDoc hit : top.scoreDocs) {
                Document document = stored.document(hit.doc);
                OWLEntity entity = SearchTypes.entity(data, document.get(TYPE), document.get(IRI_FIELD));
                if (runtime.ontologies().containsEntity(entity)) {
                    entities.add(entity);
                }
            }
            return entities;
        }
    }

    private static Query query(int nameCount, Analyzer standard, Analyzer cjk, String text, Set<EntityType<?>> types)
            throws IOException {
        BooleanQuery.Builder root = new BooleanQuery.Builder();
        root.add(textQuery(nameCount, standard, cjk, text), Occur.MUST);
        if (!types.isEmpty()) {
            BooleanQuery.Builder filter = new BooleanQuery.Builder();
            for (EntityType<?> type : types) {
                filter.add(new TermQuery(new Term(TYPE, SearchTypes.code(type))), Occur.SHOULD);
            }
            filter.setMinimumNumberShouldMatch(1);
            root.add(filter.build(), Occur.MUST);
        }
        return root.build();
    }

    /**
     * Every token from one analyzer must match. Standard tokens are prefixes, so {@code pal} matches
     * {@code Pallet}. CJK tokens stay whole terms, so the bigrams of {@code 周转托盘} do not match
     * {@code 托盘架}. Either analyzer can satisfy the query.
     */
    private static Query textQuery(int nameCount, Analyzer standard, Analyzer cjk, String text) throws IOException {
        List<String> standardTokens = tokens(standard, LOCAL_STD, text);
        List<String> cjkTokens = tokens(cjk, LOCAL_CJK, text);
        if (standardTokens.isEmpty() && cjkTokens.isEmpty()) {
            throw new IllegalArgumentException("query is invalid");
        }
        BooleanQuery.Builder either = new BooleanQuery.Builder();
        if (!standardTokens.isEmpty()) {
            either.add(allTokens(fields(nameCount, true), standardTokens, true), Occur.SHOULD);
        }
        if (!cjkTokens.isEmpty()) {
            either.add(allTokens(fields(nameCount, false), cjkTokens, false), Occur.SHOULD);
        }
        either.setMinimumNumberShouldMatch(1);
        return either.build();
    }

    private static Query allTokens(String[] fields, List<String> tokens, boolean prefix) {
        BooleanQuery.Builder all = new BooleanQuery.Builder();
        for (String token : tokens) {
            BooleanQuery.Builder anyField = new BooleanQuery.Builder();
            for (String field : fields) {
                Term term = new Term(field, token);
                anyField.add(prefix ? new PrefixQuery(term) : new TermQuery(term), Occur.SHOULD);
            }
            anyField.setMinimumNumberShouldMatch(1);
            all.add(anyField.build(), Occur.MUST);
        }
        return all.build();
    }

    private static List<String> tokens(Analyzer analyzer, String field, String text) throws IOException {
        List<String> values = new ArrayList<>();
        try (TokenStream stream = analyzer.tokenStream(field, text)) {
            CharTermAttribute term = stream.addAttribute(CharTermAttribute.class);
            stream.reset();
            while (stream.incrementToken()) {
                values.add(term.toString());
            }
            stream.end();
        }
        return values;
    }

    private static void commit(IndexWriter writer, long revision, String namesKey) throws IOException {
        writer.setLiveCommitData(List.of(Map.entry(REVISION, Long.toString(revision)), Map.entry(NAMES, namesKey)));
        writer.commit();
    }

    private static String namesKey(List<DisplayNameSetting> names) {
        StringBuilder out = new StringBuilder();
        for (DisplayNameSetting name : names) {
            out.append(name.annotationProperty())
                    .append('\n')
                    .append(name.lang())
                    .append('\n');
        }
        return out.toString();
    }

    private static String storedNames(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return "";
        }
        try (Directory directory = FSDirectory.open(path)) {
            return storedNames(directory);
        }
    }

    private static String storedNames(Directory directory) throws IOException {
        final List<IndexCommit> commits;
        try {
            commits = DirectoryReader.listCommits(directory);
        } catch (IndexNotFoundException ex) {
            return "";
        }
        if (commits.isEmpty()) {
            return "";
        }
        String raw = commits.get(commits.size() - 1).getUserData().get(NAMES);
        return raw == null ? "" : raw;
    }

    private static long storedRevision(Path path) throws IOException {
        if (!Files.isDirectory(path)) {
            return -1L;
        }
        try (Directory directory = FSDirectory.open(path)) {
            return revisionOf(directory);
        }
    }

    private static long revisionOf(Directory directory) throws IOException {
        final List<IndexCommit> commits;
        try {
            commits = DirectoryReader.listCommits(directory);
        } catch (IndexNotFoundException ex) {
            return -1L;
        }
        if (commits.isEmpty()) {
            return -1L;
        }
        String raw = commits.get(commits.size() - 1).getUserData().get(REVISION);
        return raw == null ? -1L : Long.parseLong(raw);
    }

    private static void rollback(IndexWriter writer, Exception failure) {
        try {
            writer.rollback();
        } catch (IOException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static Analyzer analyzer(StandardAnalyzer standard, CJKAnalyzer cjk, int nameCount) {
        Map<String, Analyzer> mapping = new HashMap<>();
        for (String field : fields(nameCount, true)) {
            mapping.put(field, standard);
        }
        for (String field : fields(nameCount, false)) {
            mapping.put(field, cjk);
        }
        return new PerFieldAnalyzerWrapper(standard, mapping);
    }

    private static IndexWriterConfig writerConfig(Analyzer analyzer) {
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(OpenMode.CREATE_OR_APPEND);
        return config;
    }

    private static String documentId(OWLEntity entity) {
        return SearchTypes.code(entity.getEntityType()) + " " + entity.getIRI();
    }

    private static String nameField(int index, boolean standard) {
        return "n" + index + (standard ? "_std" : "_cjk");
    }

    private static String[] fields(int nameSettings, boolean standard) {
        String[] names = new String[nameSettings + 1];
        names[0] = standard ? LOCAL_STD : LOCAL_CJK;
        for (int i = 0; i < nameSettings; i++) {
            names[i + 1] = nameField(i, standard);
        }
        return names;
    }

    /** Fragment after {@code #}, otherwise the last path segment (BS-09). */
    private static String localName(IRI iri) {
        String text = iri.toString();
        int hash = text.lastIndexOf('#');
        if (hash >= 0 && hash < text.length() - 1) {
            return decode(text.substring(hash + 1));
        }
        int slash = text.lastIndexOf('/');
        if (slash >= 0 && slash < text.length() - 1) {
            return decode(text.substring(slash + 1));
        }
        return "";
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> cast(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }

    private static void requireUsable(ProjectId projectId, ProjectRuntime runtime) {
        if (runtime.isCorrupted()) {
            throw new IllegalStateException("project runtime is corrupted: " + projectId);
        }
    }

    private static IllegalStateException failed(ProjectId projectId, IOException failure) {
        return new IllegalStateException("search index failed for " + projectId, failure);
    }

    @FunctionalInterface
    private interface WriterBody {
        void write(IndexWriter writer) throws IOException;
    }
}
