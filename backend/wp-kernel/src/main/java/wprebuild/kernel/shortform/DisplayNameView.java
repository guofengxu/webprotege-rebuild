package wprebuild.kernel.shortform;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.vocab.DublinCoreVocabulary;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.DerivedView;

/**
 * Display names for every entity in a project (BS-09).
 *
 * <p>The constructor list is tried in order. The first literal whose property and language match is
 * the display name. An empty list uses {@code skos:prefLabel}, {@code rdfs:label}, {@code dc:title},
 * then {@code dcterms:title}, each with no language tag. When nothing matches, the name is the
 * fragment after {@code #}, otherwise the last path segment, percent-decoded. An empty segment uses
 * the full IRI. Built-in entities keep the prefixed name from the OWL vocabulary ({@code owl:Thing},
 * {@code rdfs:label}, and the same sets the legacy dictionary loaded). Several literals for one
 * setting resolve to the lowest lexical form so a later batch does not flicker.
 * {@link #lastChanged()} is signature entities whose display-name string differs after the latest
 * {@code onApplied}; {@code rebuild} clears it. Queries throw until {@code rebuild} or
 * {@code onApplied} has taken a snapshot.
 */
public final class DisplayNameView implements DerivedView {

    private static final List<DisplayNameSetting> DEFAULT_SETTINGS = List.of(
            new DisplayNameSetting(SKOSVocabulary.PREFLABEL.getIRI(), ""),
            new DisplayNameSetting(OWLRDFVocabulary.RDFS_LABEL.getIRI(), ""),
            new DisplayNameSetting(DublinCoreVocabulary.TITLE.getIRI(), ""),
            new DisplayNameSetting(IRI.create("http://purl.org/dc/terms/title"), ""));

    private static final Map<OWLEntity, String> BUILT_IN = builtInNames();

    private final List<DisplayNameSetting> settings;

    private boolean ready;

    private Map<IRI, Map<DisplayNameSetting, String>> annotations = Map.of();

    private Map<OWLEntity, String> names = Map.of();

    private Set<OWLEntity> lastChanged = Set.of();

    public DisplayNameView(List<DisplayNameSetting> settings) {
        Objects.requireNonNull(settings, "settings");
        List<DisplayNameSetting> copied = List.copyOf(settings);
        this.settings = copied.isEmpty() ? DEFAULT_SETTINGS : copied;
    }

    @Override
    public void rebuild(ProjectOntologies ontologies) {
        install(Objects.requireNonNull(ontologies, "ontologies"), false);
    }

    @Override
    public void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
        Objects.requireNonNull(effectiveChanges, "effectiveChanges");
        effectiveChanges.forEach(change -> Objects.requireNonNull(change, "change"));
        install(Objects.requireNonNull(after, "ontologies"), true);
    }

    /** Chosen display name, including the local-name and built-in fallbacks (BS-09). */
    public String displayName(OWLEntity entity) {
        Objects.requireNonNull(entity, "entity");
        requireReady();
        return nameOf(entity, names, annotations);
    }

    /**
     * Annotation values for the configured settings. A setting with no matching literal is absent.
     * Built-in and local-name fallbacks are not entries.
     */
    public Map<DisplayNameSetting, String> allNames(OWLEntity entity) {
        Objects.requireNonNull(entity, "entity");
        requireReady();
        return annotations.getOrDefault(entity.getIRI(), Map.of());
    }

    /** Entities whose display name changed in the latest {@code onApplied}. */
    public Set<OWLEntity> lastChanged() {
        requireReady();
        return lastChanged;
    }

    private void install(ProjectOntologies ontologies, boolean reportChanges) {
        Map<IRI, Map<DisplayNameSetting, String>> nextAnnotations = collect(ontologies);
        Map<OWLEntity, String> nextNames = index(ontologies, nextAnnotations);
        Set<OWLEntity> nextChanged = reportChanges ? diff(names, nextNames, annotations, nextAnnotations) : Set.of();
        this.annotations = nextAnnotations;
        this.names = nextNames;
        this.lastChanged = nextChanged;
        this.ready = true;
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalStateException("display names have not been rebuilt");
        }
    }

    private Map<OWLEntity, String> index(
            ProjectOntologies ontologies, Map<IRI, Map<DisplayNameSetting, String>> annotations) {
        Map<OWLEntity, String> indexed = new LinkedHashMap<>();
        for (OWLEntity entity : ontologies.signature().toList()) {
            indexed.put(entity, resolve(entity, annotations));
        }
        return Map.copyOf(indexed);
    }

    private Set<OWLEntity> diff(
            Map<OWLEntity, String> beforeNames,
            Map<OWLEntity, String> afterNames,
            Map<IRI, Map<DisplayNameSetting, String>> beforeAnnotations,
            Map<IRI, Map<DisplayNameSetting, String>> afterAnnotations) {
        Set<OWLEntity> seen = new LinkedHashSet<>();
        seen.addAll(beforeNames.keySet());
        seen.addAll(afterNames.keySet());
        Set<OWLEntity> changed = new LinkedHashSet<>();
        for (OWLEntity entity : seen) {
            String before = nameOf(entity, beforeNames, beforeAnnotations);
            String after = nameOf(entity, afterNames, afterAnnotations);
            if (!before.equals(after)) {
                changed.add(entity);
            }
        }
        return Set.copyOf(changed);
    }

    private String nameOf(
            OWLEntity entity, Map<OWLEntity, String> indexed, Map<IRI, Map<DisplayNameSetting, String>> annotations) {
        String cached = indexed.get(entity);
        if (cached != null) {
            return cached;
        }
        return resolve(entity, annotations);
    }

    private String resolve(OWLEntity entity, Map<IRI, Map<DisplayNameSetting, String>> annotations) {
        String builtIn = BUILT_IN.get(entity);
        if (builtIn != null) {
            return builtIn;
        }
        Map<DisplayNameSetting, String> found = annotations.getOrDefault(entity.getIRI(), Map.of());
        for (DisplayNameSetting setting : settings) {
            String value = found.get(setting);
            if (value != null) {
                return value;
            }
        }
        String localName = localName(entity.getIRI());
        return localName.isEmpty() ? entity.getIRI().toString() : localName;
    }

    private Map<IRI, Map<DisplayNameSetting, String>> collect(ProjectOntologies ontologies) {
        Map<IRI, Map<DisplayNameSetting, String>> best = new HashMap<>();
        for (OWLAnnotationAssertionAxiom axiom :
                ontologies.axiomsOfType(AxiomType.ANNOTATION_ASSERTION).toList()) {
            remember(best, axiom);
        }
        Map<IRI, Map<DisplayNameSetting, String>> ordered = new HashMap<>();
        for (Map.Entry<IRI, Map<DisplayNameSetting, String>> entry : best.entrySet()) {
            ordered.put(entry.getKey(), inSettingOrder(entry.getValue()));
        }
        return Map.copyOf(ordered);
    }

    private void remember(Map<IRI, Map<DisplayNameSetting, String>> best, OWLAnnotationAssertionAxiom axiom) {
        if (!(axiom.getSubject() instanceof IRI subject) || !(axiom.getValue() instanceof OWLLiteral literal)) {
            return;
        }
        String lang = literal.getLang().toLowerCase(Locale.ROOT);
        for (DisplayNameSetting setting : settings) {
            if (setting.annotationProperty().equals(axiom.getProperty().getIRI())
                    && setting.lang().equals(lang)) {
                best.computeIfAbsent(subject, ignored -> new HashMap<>())
                        .merge(setting, literal.getLiteral(), DisplayNameView::lower);
            }
        }
    }

    private Map<DisplayNameSetting, String> inSettingOrder(Map<DisplayNameSetting, String> values) {
        Map<DisplayNameSetting, String> ordered = new LinkedHashMap<>();
        for (DisplayNameSetting setting : settings) {
            String value = values.get(setting);
            if (value != null) {
                ordered.put(setting, value);
            }
        }
        return Map.copyOf(ordered);
    }

    /** Fragment after {@code #}, otherwise the last path segment. Empty when neither is present. */
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

    private static String lower(String left, String right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private static Map<OWLEntity, String> builtInNames() {
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        Map<OWLEntity, String> names = new HashMap<>();
        putClass(data, names, OWLRDFVocabulary.OWL_THING);
        putClass(data, names, OWLRDFVocabulary.OWL_NOTHING);
        putObjectProperty(data, names, OWLRDFVocabulary.OWL_TOP_OBJECT_PROPERTY);
        putObjectProperty(data, names, OWLRDFVocabulary.OWL_BOTTOM_OBJECT_PROPERTY);
        putDataProperty(data, names, OWLRDFVocabulary.OWL_TOP_DATA_PROPERTY);
        putDataProperty(data, names, OWLRDFVocabulary.OWL_BOTTOM_DATA_PROPERTY);
        putAnnotationProperty(data, names, OWLRDFVocabulary.OWL_VERSION_INFO);
        putAnnotationProperty(data, names, OWLRDFVocabulary.OWL_BACKWARD_COMPATIBLE_WITH);
        putAnnotationProperty(data, names, OWLRDFVocabulary.OWL_PRIOR_VERSION);
        putAnnotationProperty(data, names, OWLRDFVocabulary.OWL_INCOMPATIBLE_WITH);
        putAnnotationProperty(data, names, OWLRDFVocabulary.OWL_DEPRECATED);
        putAnnotationProperty(data, names, OWLRDFVocabulary.RDFS_LABEL);
        putAnnotationProperty(data, names, OWLRDFVocabulary.RDFS_COMMENT);
        putAnnotationProperty(data, names, OWLRDFVocabulary.RDFS_SEE_ALSO);
        putAnnotationProperty(data, names, OWLRDFVocabulary.RDFS_IS_DEFINED_BY);
        for (DublinCoreVocabulary vocab : DublinCoreVocabulary.values()) {
            names.put(data.getOWLAnnotationProperty(vocab.getIRI()), vocab.getPrefixedName());
        }
        for (SKOSVocabulary vocab : SKOSVocabulary.values()) {
            if (vocab.getEntityType().equals(EntityType.ANNOTATION_PROPERTY)) {
                names.put(data.getOWLAnnotationProperty(vocab.getIRI()), vocab.getPrefixedName());
            }
        }
        for (OWL2Datatype datatype : OWL2Datatype.values()) {
            names.put(data.getOWLDatatype(datatype.getIRI()), datatype.getPrefixedName());
        }
        return Map.copyOf(names);
    }

    private static void putClass(OWLDataFactory data, Map<OWLEntity, String> names, OWLRDFVocabulary vocab) {
        names.put(data.getOWLClass(vocab.getIRI()), vocab.getPrefixedName());
    }

    private static void putObjectProperty(OWLDataFactory data, Map<OWLEntity, String> names, OWLRDFVocabulary vocab) {
        names.put(data.getOWLObjectProperty(vocab.getIRI()), vocab.getPrefixedName());
    }

    private static void putDataProperty(OWLDataFactory data, Map<OWLEntity, String> names, OWLRDFVocabulary vocab) {
        names.put(data.getOWLDataProperty(vocab.getIRI()), vocab.getPrefixedName());
    }

    private static void putAnnotationProperty(
            OWLDataFactory data, Map<OWLEntity, String> names, OWLRDFVocabulary vocab) {
        names.put(data.getOWLAnnotationProperty(vocab.getIRI()), vocab.getPrefixedName());
    }
}
