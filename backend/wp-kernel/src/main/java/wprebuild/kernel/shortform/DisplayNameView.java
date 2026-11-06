package wprebuild.kernel.shortform;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLLiteral;
import org.semanticweb.owlapi.vocab.DublinCoreVocabulary;
import org.semanticweb.owlapi.vocab.OWL2Datatype;
import org.semanticweb.owlapi.vocab.OWLRDFVocabulary;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.AddImport;
import wprebuild.kernel.change.AddOntologyAnnotation;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.change.RemoveImport;
import wprebuild.kernel.change.RemoveOntologyAnnotation;
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
 * {@code onApplied}; {@code rebuild} clears it.
 *
 * <p>{@code rebuild} still collects every annotation assertion and indexes the whole signature.
 * {@code onApplied} recomputes only IRIs the change can affect: the IRI subject of each added or
 * removed annotation assertion, plus every entity IRI in {@link OntologyChange#signature()}.
 * Import changes and {@code DeclareOntology} contribute an empty signature. Entities named by an
 * ontology annotation are included through that signature; an extra IRI is harmless and a missing
 * one is not (BS-09, P1-25). For each affected IRI the six entity types are refreshed from
 * {@code annotationAssertions} and {@code containsEntity}. An entity whose IRI was not affected
 * keeps its name, because neither its annotations nor its presence in the signature changed. The
 * maps are mutated in place under the project write lock. Queries throw until {@code rebuild} or
 * {@code onApplied} has taken a snapshot.
 */
public final class DisplayNameView implements DerivedView {

    private static final List<DisplayNameSetting> DEFAULT_SETTINGS = List.of(
            new DisplayNameSetting(SKOSVocabulary.PREFLABEL.getIRI(), ""),
            new DisplayNameSetting(OWLRDFVocabulary.RDFS_LABEL.getIRI(), ""),
            new DisplayNameSetting(DublinCoreVocabulary.TITLE.getIRI(), ""),
            new DisplayNameSetting(IRI.create("http://purl.org/dc/terms/title"), ""));

    private static final Map<OWLEntity, String> BUILT_IN = builtInNames();

    private static final List<EntityType<?>> ENTITY_TYPES = List.copyOf(EntityType.values());

    private final List<DisplayNameSetting> settings;

    private boolean ready;

    private Map<IRI, Map<DisplayNameSetting, String>> annotations = new HashMap<>();

    private Map<OWLEntity, String> names = new HashMap<>();

    private Set<OWLEntity> lastChanged = Set.of();

    public DisplayNameView(List<DisplayNameSetting> settings) {
        Objects.requireNonNull(settings, "settings");
        List<DisplayNameSetting> copied = List.copyOf(settings);
        this.settings = copied.isEmpty() ? DEFAULT_SETTINGS : copied;
    }

    @Override
    public void rebuild(ProjectOntologies ontologies) {
        Objects.requireNonNull(ontologies, "ontologies");
        Map<IRI, Map<DisplayNameSetting, String>> collected = collect(ontologies);
        annotations = new HashMap<>(collected);
        names = index(ontologies, collected);
        lastChanged = Set.of();
        ready = true;
    }

    @Override
    public void onApplied(List<OntologyChange> effectiveChanges, ProjectOntologies after) {
        Objects.requireNonNull(effectiveChanges, "effectiveChanges");
        effectiveChanges.forEach(change -> Objects.requireNonNull(change, "change"));
        Objects.requireNonNull(after, "ontologies");
        applyIncremental(effectiveChanges, after);
        ready = true;
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

    private void applyIncremental(List<OntologyChange> changes, ProjectOntologies after) {
        Set<IRI> affected = affectedIris(changes);
        // Reads finish before the maps change, so a failed lookup leaves the previous snapshot intact.
        List<TrackedEntity> tracked = trackedEntities(after, affected);
        Map<IRI, Map<DisplayNameSetting, String>> refreshed = refreshedAnnotations(after, affected);
        refreshed.forEach(this::replaceAnnotations);
        for (TrackedEntity trackedEntity : tracked) {
            if (trackedEntity.present()) {
                names.put(trackedEntity.entity(), resolve(trackedEntity.entity(), annotations));
            } else {
                names.remove(trackedEntity.entity());
            }
        }
        lastChanged = changedEntities(tracked);
    }

    private List<TrackedEntity> trackedEntities(ProjectOntologies after, Set<IRI> affected) {
        List<TrackedEntity> tracked = new ArrayList<>();
        OWLDataFactory data = after.dataFactory();
        for (IRI iri : affected) {
            for (EntityType<?> type : ENTITY_TYPES) {
                OWLEntity entity = entity(data, type, iri);
                boolean present = after.containsEntity(entity);
                if (names.containsKey(entity) || present) {
                    tracked.add(new TrackedEntity(entity, nameOf(entity, names, annotations), present));
                }
            }
        }
        return tracked;
    }

    private Map<IRI, Map<DisplayNameSetting, String>> refreshedAnnotations(ProjectOntologies after, Set<IRI> affected) {
        Map<IRI, Map<DisplayNameSetting, String>> refreshed = new HashMap<>();
        for (IRI iri : affected) {
            refreshed.put(iri, annotationsOf(after, iri));
        }
        return refreshed;
    }

    private Set<OWLEntity> changedEntities(List<TrackedEntity> tracked) {
        Set<OWLEntity> changed = new LinkedHashSet<>();
        for (TrackedEntity trackedEntity : tracked) {
            String current = nameOf(trackedEntity.entity(), names, annotations);
            if (!trackedEntity.previousName().equals(current)) {
                changed.add(trackedEntity.entity());
            }
        }
        return Set.copyOf(changed);
    }

    private void replaceAnnotations(IRI iri, Map<DisplayNameSetting, String> refreshed) {
        if (refreshed.isEmpty()) {
            annotations.remove(iri);
        } else {
            annotations.put(iri, refreshed);
        }
    }

    private static Set<IRI> affectedIris(List<OntologyChange> changes) {
        Set<IRI> iris = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            addAnnotationSubject(change, iris);
            for (OWLEntity entity : change.signature()) {
                iris.add(entity.getIRI());
            }
        }
        return iris;
    }

    private static void addAnnotationSubject(OntologyChange change, Set<IRI> iris) {
        switch (change) {
            case AddAxiom add -> addSubject(add.owlAxiom(), iris);
            case RemoveAxiom remove -> addSubject(remove.owlAxiom(), iris);
            case AddOntologyAnnotation ignored -> {}
            case RemoveOntologyAnnotation ignored -> {}
            case AddImport ignored -> {}
            case RemoveImport ignored -> {}
            case DeclareOntology ignored -> {}
        }
    }

    private static void addSubject(OWLAxiom axiom, Set<IRI> iris) {
        if (axiom instanceof OWLAnnotationAssertionAxiom assertion && assertion.getSubject() instanceof IRI subject) {
            iris.add(subject);
        }
    }

    private Map<DisplayNameSetting, String> annotationsOf(ProjectOntologies ontologies, IRI iri) {
        Map<IRI, Map<DisplayNameSetting, String>> best = new HashMap<>();
        ontologies.annotationAssertions(iri).forEach(axiom -> remember(best, axiom));
        return inSettingOrder(best.getOrDefault(iri, Map.of()));
    }

    private static OWLEntity entity(OWLDataFactory data, EntityType<?> type, IRI iri) {
        // EntityType.values() is a wildcard list, so the type argument is recovered for getOWLEntity.
        return data.getOWLEntity(asEntityType(type), iri);
    }

    @SuppressWarnings("unchecked")
    private static EntityType<OWLEntity> asEntityType(EntityType<?> type) {
        return (EntityType<OWLEntity>) type;
    }

    private void requireReady() {
        if (!ready) {
            throw new IllegalStateException("display names have not been rebuilt");
        }
    }

    private Map<OWLEntity, String> index(
            ProjectOntologies ontologies, Map<IRI, Map<DisplayNameSetting, String>> annotations) {
        Map<OWLEntity, String> indexed = new HashMap<>();
        for (OWLEntity entity : ontologies.signature().toList()) {
            indexed.put(entity, resolve(entity, annotations));
        }
        return indexed;
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

    /** One signature entity touched by this batch, with the name it had before the maps changed. */
    private record TrackedEntity(OWLEntity entity, String previousName, boolean present) {}
}
