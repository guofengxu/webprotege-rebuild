package wprebuild.kernel.generator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.semanticweb.owlapi.apibinding.OWLManager;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationProperty;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClass;
import org.semanticweb.owlapi.model.OWLDataFactory;
import org.semanticweb.owlapi.model.OWLDataProperty;
import org.semanticweb.owlapi.model.OWLEntity;
import org.semanticweb.owlapi.model.OWLNamedIndividual;
import org.semanticweb.owlapi.model.OWLObjectProperty;
import wprebuild.kernel.change.AddAxiom;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.iri.FreshEntityIri;
import wprebuild.kernel.runtime.ChangeContext;
import wprebuild.kernel.runtime.ChangeListGenerator;
import wprebuild.kernel.runtime.GeneratedChanges;

/**
 * Creates one entity per supplied name and places it under the given parents (BS-07).
 *
 * <p>Each name becomes a fresh IRI (BS-05) whose discriminator is a random UUID. Parent axioms are
 * the only changes when a parent is present, so the fresh-IRI minter can add the declaration and
 * label (BS-06). {@code owl:Thing} is not used as a parent, matching the legacy class and individual
 * generators; a declaration is then added so the minter still sees the fresh entity.
 */
public final class CreateEntitiesGenerator<E extends OWLEntity> implements ChangeListGenerator<List<E>> {

    private final EntityType<E> entityType;

    private final List<String> names;

    private final String langTag;

    private final List<OWLEntity> parents;

    private final OntologyKey targetOntology;

    public CreateEntitiesGenerator(
            EntityType<E> entityType,
            List<String> names,
            Optional<String> lang,
            Set<? extends OWLEntity> parents,
            OntologyKey targetOntology) {
        this.entityType = supported(entityType);
        this.names = copyNames(names);
        this.langTag = language(lang);
        this.parents = copyParents(parents);
        this.targetOntology = Objects.requireNonNull(targetOntology, "target ontology");
        requireParentTypes(this.entityType, this.parents);
    }

    @Override
    public GeneratedChanges<List<E>> generate(ChangeContext context) {
        Objects.requireNonNull(context, "context");
        if (names.isEmpty()) {
            return new GeneratedChanges<>(List.of(), List.of());
        }
        OWLDataFactory data = context.ontologies().dataFactory();
        List<OntologyChange> changes = new ArrayList<>();
        List<E> created = new ArrayList<>(names.size());
        Set<IRI> parentIris = parentIris();
        for (String name : names) {
            E entity = data.getOWLEntity(entityType, freshIri(name, parentIris));
            created.add(entity);
            addEntity(data, entity, changes);
        }
        return new GeneratedChanges<>(changes, List.copyOf(created));
    }

    @Override
    public String describe(GeneratedChanges<List<E>> generated, List<OntologyChange> effective) {
        Objects.requireNonNull(generated, "generated changes");
        Objects.requireNonNull(effective, "effective changes");
        if (names.size() == 1) {
            return "Created " + printName(entityType) + " " + names.getFirst();
        }
        return "Created " + names.size() + " " + entityType.getPluralPrintName().toLowerCase(Locale.ROOT);
    }

    @Override
    public List<E> renameResult(List<E> result, Map<IRI, IRI> renameMap) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(renameMap, "rename map");
        if (renameMap.isEmpty()) {
            return List.copyOf(result);
        }
        OWLDataFactory data = OWLManager.getOWLDataFactory();
        List<E> renamed = new ArrayList<>(result.size());
        for (E entity : result) {
            IRI current = Objects.requireNonNull(entity, "entity").getIRI();
            renamed.add(data.getOWLEntity(entityType, renameMap.getOrDefault(current, current)));
        }
        return List.copyOf(renamed);
    }

    private void addEntity(OWLDataFactory data, E entity, List<OntologyChange> changes) {
        List<OWLAxiom> placed = placement(data, entity);
        if (placed.isEmpty()) {
            // BS-05: a fresh IRI is discovered from an axiom signature. With no parent axiom, the
            // declaration is that signature.
            changes.add(new AddAxiom(targetOntology, data.getOWLDeclarationAxiom(entity)));
            return;
        }
        for (OWLAxiom axiom : placed) {
            changes.add(new AddAxiom(targetOntology, axiom));
        }
    }

    private List<OWLAxiom> placement(OWLDataFactory data, E entity) {
        List<OWLAxiom> axioms = new ArrayList<>();
        for (OWLEntity parent : parents) {
            placementAxiom(data, entity, parent).ifPresent(axioms::add);
        }
        return axioms;
    }

    private Optional<OWLAxiom> placementAxiom(OWLDataFactory data, OWLEntity child, OWLEntity parent) {
        if (EntityType.CLASS.equals(entityType)) {
            OWLClass superClass = (OWLClass) parent;
            if (superClass.isOWLThing()) {
                return Optional.empty();
            }
            return Optional.of(data.getOWLSubClassOfAxiom((OWLClass) child, superClass));
        }
        if (EntityType.OBJECT_PROPERTY.equals(entityType)) {
            return Optional.of(
                    data.getOWLSubObjectPropertyOfAxiom((OWLObjectProperty) child, (OWLObjectProperty) parent));
        }
        if (EntityType.DATA_PROPERTY.equals(entityType)) {
            return Optional.of(data.getOWLSubDataPropertyOfAxiom((OWLDataProperty) child, (OWLDataProperty) parent));
        }
        if (EntityType.ANNOTATION_PROPERTY.equals(entityType)) {
            return Optional.of(data.getOWLSubAnnotationPropertyOfAxiom(
                    (OWLAnnotationProperty) child, (OWLAnnotationProperty) parent));
        }
        if (!EntityType.NAMED_INDIVIDUAL.equals(entityType)) {
            throw new IllegalStateException("unsupported entity type: " + entityType.getPrintName());
        }
        OWLClass type = (OWLClass) parent;
        if (type.isOWLThing()) {
            return Optional.empty();
        }
        return Optional.of(data.getOWLClassAssertionAxiom(type, (OWLNamedIndividual) child));
    }

    private IRI freshIri(String name, Set<IRI> parentIris) {
        return new FreshEntityIri(name, langTag, UUID.randomUUID().toString(), parentIris).toIri();
    }

    private Set<IRI> parentIris() {
        List<IRI> iris = new ArrayList<>(parents.size());
        for (OWLEntity parent : parents) {
            iris.add(parent.getIRI());
        }
        return Set.copyOf(iris);
    }

    private static <E extends OWLEntity> EntityType<E> supported(EntityType<E> entityType) {
        Objects.requireNonNull(entityType, "entity type");
        if (EntityType.CLASS.equals(entityType)
                || EntityType.OBJECT_PROPERTY.equals(entityType)
                || EntityType.DATA_PROPERTY.equals(entityType)
                || EntityType.ANNOTATION_PROPERTY.equals(entityType)
                || EntityType.NAMED_INDIVIDUAL.equals(entityType)) {
            return entityType;
        }
        throw new IllegalArgumentException("unsupported entity type: " + entityType.getPrintName());
    }

    private static List<String> copyNames(List<String> names) {
        Objects.requireNonNull(names, "names");
        List<String> copied = new ArrayList<>(names.size());
        for (String name : names) {
            String trimmed = Objects.requireNonNull(name, "name").trim();
            if (trimmed.isEmpty()) {
                throw new IllegalArgumentException("entity name must not be blank");
            }
            if (trimmed.indexOf('#') >= 0) {
                throw new IllegalArgumentException("entity name must not contain '#'");
            }
            copied.add(trimmed);
        }
        return List.copyOf(copied);
    }

    private static String language(Optional<String> lang) {
        String value = Objects.requireNonNull(lang, "language").orElse("").trim();
        if (value.indexOf('&') >= 0 || value.indexOf('#') >= 0) {
            throw new IllegalArgumentException("language must not contain '&' or '#'");
        }
        return value;
    }

    private static List<OWLEntity> copyParents(Set<? extends OWLEntity> parents) {
        Objects.requireNonNull(parents, "parents");
        List<OWLEntity> copied = new ArrayList<>(parents.size());
        for (OWLEntity parent : parents) {
            copied.add(Objects.requireNonNull(parent, "parent"));
        }
        copied.sort(Comparator.comparing(parent -> parent.getIRI().toString()));
        return List.copyOf(copied);
    }

    private static void requireParentTypes(EntityType<?> entityType, List<OWLEntity> parents) {
        Class<? extends OWLEntity> expected = parentClass(entityType);
        for (OWLEntity parent : parents) {
            if (!expected.isInstance(parent)) {
                throw new IllegalArgumentException(
                        "parent of " + printName(entityType) + " has the wrong type: " + parent.getIRI());
            }
        }
    }

    private static Class<? extends OWLEntity> parentClass(EntityType<?> entityType) {
        if (EntityType.OBJECT_PROPERTY.equals(entityType)) {
            return OWLObjectProperty.class;
        }
        if (EntityType.DATA_PROPERTY.equals(entityType)) {
            return OWLDataProperty.class;
        }
        if (EntityType.ANNOTATION_PROPERTY.equals(entityType)) {
            return OWLAnnotationProperty.class;
        }
        if (EntityType.CLASS.equals(entityType) || EntityType.NAMED_INDIVIDUAL.equals(entityType)) {
            return OWLClass.class;
        }
        throw new IllegalStateException("unsupported entity type: " + entityType.getPrintName());
    }

    private static String printName(EntityType<?> entityType) {
        return entityType.getPrintName().toLowerCase(Locale.ROOT);
    }
}
