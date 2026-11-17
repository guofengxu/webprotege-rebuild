package wprebuild.kernel.generator;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.semanticweb.owlapi.model.AxiomType;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotation;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyDomainAxiom;
import org.semanticweb.owlapi.model.OWLAnnotationPropertyRangeAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.vocab.SKOSVocabulary;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.change.RemoveAxiom;
import wprebuild.kernel.iri.IriReplacer;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Merges source entities into a target entity (BS-13).
 *
 * <p>Change order matches the legacy generator: rewrite usages, demote preferred labels to {@code
 * skos:altLabel}, then deprecate or leave the sources deleted. Label demotion only removes a
 * rewritten preferred-label assertion when the target did not already carry that exact axiom.
 */
public final class MergeEntitiesGenerator implements ChangeListGenerator<OWLEntity> {

    private static final List<EntityType<?>> ENTITY_TYPES = List.copyOf(EntityType.values());

    private final Set<IRI> sources;

    private final IRI target;

    private final MergedEntityTreatment treatment;

    private final String description;

    public MergeEntitiesGenerator(Set<IRI> sources, IRI target, MergedEntityTreatment treatment, String description) {
        this.sources = copySources(sources);
        this.target = Objects.requireNonNull(target, "target");
        this.treatment = Objects.requireNonNull(treatment, "treatment");
        this.description = Objects.requireNonNull(description, "description");
    }

    @Override
    public GeneratedChanges<OWLEntity> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        ProjectOntologies ontologies = context.ontologies();
        OWLDataFactory data = ontologies.dataFactory();
        validate(ontologies, data);
        EntityType<?> targetType = requireSingleType(declaredTypes(ontologies, target), target);
        for (IRI source : sources) {
            requireSourceType(declaredTypes(ontologies, source), source, targetType);
        }
        OWLEntity targetEntity = data.getOWLEntity(targetType, target);
        Map<IRI, IRI> replacements = replacementMap();
        Map<OntologyKey, Set<OWLAnnotationAssertionAxiom>> targetAssertions = annotationAssertions(ontologies, target);
        Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> sourceAssertions =
                sourceAnnotationAssertions(ontologies, sources);
        List<OntologyChange> changes = new ArrayList<>();
        replaceUsage(ontologies, replacements, changes);
        demoteLabels(data, targetAssertions, sourceAssertions, changes);
        if (treatment == MergedEntityTreatment.DEPRECATE_MERGED_ENTITY) {
            deprecateSources(ontologies, data, targetType, sourceAssertions, changes);
        }
        return new GeneratedChanges<>(changes, targetEntity);
    }

    @Override
    public String describe(GeneratedChanges<OWLEntity> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        if (!description.isBlank()) {
            return description;
        }
        return "Merged " + sources.size() + " entities into " + shortForm(target);
    }

    @Override
    public OWLEntity renameResult(OWLEntity result, Map<IRI, IRI> renameMap) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(renameMap, "rename map");
        return result;
    }

    private void validate(ProjectOntologies ontologies, OWLDataFactory data) {
        if (sources.isEmpty()) {
            throw new MergeEntitiesException(MergeEntitiesException.Reason.EMPTY_SOURCES, "source set is empty");
        }
        if (sources.contains(target)) {
            throw new MergeEntitiesException(
                    MergeEntitiesException.Reason.TARGET_IN_SOURCES, "target IRI is also a source: " + target);
        }
        for (IRI iri : allIris()) {
            if (isBuiltIn(data, iri)) {
                throw new MergeEntitiesException(
                        MergeEntitiesException.Reason.BUILT_IN_ENTITY, "built-in entity cannot be merged: " + iri);
            }
        }
        for (IRI iri : allIris()) {
            if (declaredTypes(ontologies, iri).isEmpty()) {
                throw new MergeEntitiesException(
                        MergeEntitiesException.Reason.UNKNOWN_ENTITY, "entity is not declared: " + iri);
            }
        }
    }

    private void replaceUsage(ProjectOntologies ontologies, Map<IRI, IRI> replacements, List<OntologyChange> changes) {
        List<OntologyKey> keys = new ArrayList<>();
        List<OWLAxiom> doomed = new ArrayList<>();
        for (OntologyKey key : sorted(ontologies.keys())) {
            for (OWLAxiom axiom : ontologies.axioms(key).toList()) {
                if (!mentionsSource(axiom)) {
                    continue;
                }
                keys.add(key);
                doomed.add(axiom);
            }
        }
        // One duplicator for the whole merge (avoid creating a manager per axiom).
        List<OWLAxiom> rewritten = IriReplacer.replaceObjects(doomed, replacements);
        Set<OWLAxiom> addedInOntology = new LinkedHashSet<>();
        OntologyKey previous = null;
        for (int index = 0; index < doomed.size(); index++) {
            OntologyKey key = keys.get(index);
            if (!key.equals(previous)) {
                addedInOntology = new LinkedHashSet<>();
                previous = key;
            }
            changes.add(new RemoveAxiom(key, doomed.get(index)));
            // One ontology keeps a single copy of each rewritten axiom (BS-13).
            if (addedInOntology.add(rewritten.get(index))) {
                changes.add(new AddAxiom(key, rewritten.get(index)));
            }
        }
    }

    private void demoteLabels(
            OWLDataFactory data,
            Map<OntologyKey, Set<OWLAnnotationAssertionAxiom>> targetAssertions,
            Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> sourceAssertions,
            List<OntologyChange> changes) {
        for (OntologyKey key : sortedKeys(targetAssertions.keySet(), sourceAssertions)) {
            Set<OWLAnnotationAssertionAxiom> originalOnTarget = targetAssertions.getOrDefault(key, Set.of());
            for (IRI source : sources) {
                for (OWLAnnotationAssertionAxiom original :
                        sourceAssertions.getOrDefault(source, Map.of()).getOrDefault(key, List.of())) {
                    if (!isPreferredLabel(original)) {
                        continue;
                    }
                    // Step 1 rewrote this preferred label onto the target with the same property.
                    OWLAnnotationAssertionAxiom rewrittenOnTarget = data.getOWLAnnotationAssertionAxiom(
                            target, original.getAnnotation(), original.getAnnotations());
                    // New-system fix: keep a preferred label the target already had before the merge.
                    if (!originalOnTarget.contains(rewrittenOnTarget)) {
                        changes.add(new RemoveAxiom(key, rewrittenOnTarget));
                    }
                    OWLAnnotation alt = data.getOWLAnnotation(
                            data.getOWLAnnotationProperty(SKOSVocabulary.ALTLABEL.getIRI()),
                            original.getValue(),
                            original.getAnnotation().getAnnotations());
                    changes.add(new AddAxiom(
                            key, data.getOWLAnnotationAssertionAxiom(target, alt, original.getAnnotations())));
                }
            }
        }
    }

    private void deprecateSources(
            ProjectOntologies ontologies,
            OWLDataFactory data,
            EntityType<?> targetType,
            Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> sourceAssertions,
            List<OntologyChange> changes) {
        OntologyKey defaultOntology = defaultOntology(ontologies);
        for (IRI source : sources) {
            changes.add(new AddAxiom(defaultOntology, data.getDeprecatedOWLAnnotationAssertionAxiom(source)));
            // New-system addition: put the source back in the signature so hierarchy/search/Frame see it.
            changes.add(
                    new AddAxiom(defaultOntology, data.getOWLDeclarationAxiom(data.getOWLEntity(targetType, source))));
            for (Map.Entry<OntologyKey, List<OWLAnnotationAssertionAxiom>> entry :
                    sourceAssertions.getOrDefault(source, Map.of()).entrySet()) {
                for (OWLAnnotationAssertionAxiom original : entry.getValue()) {
                    changes.add(new AddAxiom(entry.getKey(), original));
                }
            }
        }
    }

    /**
     * Same IRI positions as legacy EntityRenamer / {@link DeleteEntitiesGenerator}: signature,
     * annotation-assertion subject or IRI value, annotation-property domain/range, and IRI values
     * nested in axiom annotations. BS-13 delete requires the source IRI leave every axiom.
     */
    private boolean mentionsSource(OWLAxiom axiom) {
        for (OWLEntity entity : axiom.getSignature()) {
            if (sources.contains(entity.getIRI())) {
                return true;
            }
        }
        if (axiom instanceof OWLAnnotationAssertionAxiom assertion) {
            if (assertion.getSubject() instanceof IRI subject && sources.contains(subject)) {
                return true;
            }
            if (assertion.getValue() instanceof IRI value && sources.contains(value)) {
                return true;
            }
        } else if (axiom instanceof OWLAnnotationPropertyDomainAxiom domain && sources.contains(domain.getDomain())) {
            return true;
        } else if (axiom instanceof OWLAnnotationPropertyRangeAxiom range && sources.contains(range.getRange())) {
            return true;
        }
        return annotationsMentionSource(axiom.getAnnotations());
    }

    private boolean annotationsMentionSource(Iterable<OWLAnnotation> annotations) {
        for (OWLAnnotation annotation : annotations) {
            if (annotation.getValue() instanceof IRI value && sources.contains(value)) {
                return true;
            }
            if (annotationsMentionSource(annotation.getAnnotations())) {
                return true;
            }
        }
        return false;
    }

    private Map<IRI, IRI> replacementMap() {
        Map<IRI, IRI> map = new LinkedHashMap<>();
        for (IRI source : sources) {
            map.put(source, target);
        }
        return Map.copyOf(map);
    }

    private List<IRI> allIris() {
        List<IRI> iris = new ArrayList<>(sources.size() + 1);
        iris.addAll(sources);
        iris.add(target);
        return iris;
    }

    private static List<OntologyKey> sortedKeys(
            Set<OntologyKey> targetKeys,
            Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> sourceAssertions) {
        Set<OntologyKey> keys = new LinkedHashSet<>(targetKeys);
        for (Map<OntologyKey, List<OWLAnnotationAssertionAxiom>> byOntology : sourceAssertions.values()) {
            keys.addAll(byOntology.keySet());
        }
        return sorted(keys);
    }

    private static Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> sourceAnnotationAssertions(
            ProjectOntologies ontologies, Set<IRI> sources) {
        Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> bySource = new LinkedHashMap<>();
        for (OntologyKey key : sorted(ontologies.keys())) {
            for (OWLAxiom axiom : ontologies.axioms(key).toList()) {
                if (!(axiom instanceof OWLAnnotationAssertionAxiom assertion)) {
                    continue;
                }
                if (!(assertion.getSubject() instanceof IRI subject) || !sources.contains(subject)) {
                    continue;
                }
                bySource.computeIfAbsent(subject, ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(key, ignored -> new ArrayList<>())
                        .add(assertion);
            }
        }
        Map<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> frozen = new LinkedHashMap<>();
        for (Map.Entry<IRI, Map<OntologyKey, List<OWLAnnotationAssertionAxiom>>> source : bySource.entrySet()) {
            Map<OntologyKey, List<OWLAnnotationAssertionAxiom>> byOntology = new LinkedHashMap<>();
            for (Map.Entry<OntologyKey, List<OWLAnnotationAssertionAxiom>> ontology :
                    source.getValue().entrySet()) {
                byOntology.put(ontology.getKey(), List.copyOf(ontology.getValue()));
            }
            frozen.put(source.getKey(), Map.copyOf(byOntology));
        }
        return Map.copyOf(frozen);
    }

    private static Map<OntologyKey, Set<OWLAnnotationAssertionAxiom>> annotationAssertions(
            ProjectOntologies ontologies, IRI subject) {
        Map<OntologyKey, Set<OWLAnnotationAssertionAxiom>> byOntology = new LinkedHashMap<>();
        for (OntologyKey key : sorted(ontologies.keys())) {
            Set<OWLAnnotationAssertionAxiom> assertions = new LinkedHashSet<>();
            for (OWLAxiom axiom : ontologies.axioms(key).toList()) {
                if (axiom instanceof OWLAnnotationAssertionAxiom assertion
                        && assertion.getSubject() instanceof IRI iri
                        && iri.equals(subject)) {
                    assertions.add(assertion);
                }
            }
            if (!assertions.isEmpty()) {
                byOntology.put(key, Set.copyOf(assertions));
            }
        }
        return Map.copyOf(byOntology);
    }

    private static Set<EntityType<?>> declaredTypes(ProjectOntologies ontologies, IRI iri) {
        Set<EntityType<?>> types = new LinkedHashSet<>();
        for (OWLDeclarationAxiom declaration :
                ontologies.axiomsOfType(AxiomType.DECLARATION).toList()) {
            if (declaration.getEntity().getIRI().equals(iri)) {
                types.add(declaration.getEntity().getEntityType());
            }
        }
        return types;
    }

    private static EntityType<?> requireSingleType(Set<EntityType<?>> types, IRI iri) {
        if (types.size() != 1) {
            throw new MergeEntitiesException(
                    MergeEntitiesException.Reason.TYPE_MISMATCH,
                    "target must be declared as exactly one entity type: " + iri);
        }
        return types.iterator().next();
    }

    private static void requireSourceType(Set<EntityType<?>> types, IRI iri, EntityType<?> targetType) {
        if (!types.contains(targetType)) {
            throw new MergeEntitiesException(
                    MergeEntitiesException.Reason.TYPE_MISMATCH,
                    "source is not declared as " + targetType.getPrintName() + ": " + iri);
        }
    }

    private static boolean isBuiltIn(OWLDataFactory data, IRI iri) {
        for (EntityType<?> type : ENTITY_TYPES) {
            if (data.getOWLEntity(type, iri).isBuiltIn()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPreferredLabel(OWLAnnotationAssertionAxiom axiom) {
        return axiom.getProperty().isLabel()
                || SKOSVocabulary.PREFLABEL.getIRI().equals(axiom.getProperty().getIRI());
    }

    private static OntologyKey defaultOntology(ProjectOntologies ontologies) {
        List<OntologyKey> keys = sorted(ontologies.keys());
        if (keys.isEmpty()) {
            throw new IllegalStateException("project has no ontology");
        }
        // Same rule as the legacy DefaultOntologyIdManager: first registered ontology.
        return keys.getFirst();
    }

    private static List<OntologyKey> sorted(Set<OntologyKey> keys) {
        List<OntologyKey> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator.comparingInt(key -> Integer.parseInt(key.value().substring(1))));
        return ordered;
    }

    private static String shortForm(IRI iri) {
        String text = iri.toString();
        int hash = text.lastIndexOf('#');
        if (hash >= 0 && hash < text.length() - 1) {
            return decode(text.substring(hash + 1));
        }
        int slash = text.lastIndexOf('/');
        if (slash >= 0 && slash < text.length() - 1) {
            return decode(text.substring(slash + 1));
        }
        return text;
    }

    private static String decode(String raw) {
        try {
            return URLDecoder.decode(raw, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return raw;
        }
    }

    private static Set<IRI> copySources(Set<IRI> sources) {
        Objects.requireNonNull(sources, "sources");
        Set<IRI> copied = new LinkedHashSet<>();
        for (IRI source : sources) {
            copied.add(Objects.requireNonNull(source, "source"));
        }
        return Set.copyOf(copied);
    }
}
