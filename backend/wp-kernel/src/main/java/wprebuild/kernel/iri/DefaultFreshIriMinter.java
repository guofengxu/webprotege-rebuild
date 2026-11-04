package wprebuild.kernel.iri;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.EntityType;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAssertionAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLEntity;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.FreshIriMinter;

/**
 * Replaces fresh IRIs once per placeholder and appends the entity-creation changes (BS-05).
 *
 * <p>An annotation-assertion subject is a bare IRI, so it is not part of the axiom signature. The
 * entity type still comes from an {@link OWLEntity} in the same change list.
 */
public final class DefaultFreshIriMinter implements FreshIriMinter {

    private final EntityCreator creator;

    private final CreatePermission permission;

    public DefaultFreshIriMinter(EntityCreator creator, CreatePermission permission) {
        this.creator = Objects.requireNonNull(creator, "entity creator");
        this.permission = Objects.requireNonNull(permission, "create permission");
    }

    @Override
    public MintResult mint(UserId user, List<OntologyChange> changes, ProjectOntologies ontologies) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(changes, "changes");
        Objects.requireNonNull(ontologies, "ontologies");
        Map<IRI, EntityType<?>> types = discover(changes);
        if (types.isEmpty()) {
            return MintResult.unchanged(changes);
        }
        Map<IRI, FreshEntityIri> parsed = parseAll(types.keySet());
        requirePermission(user, types);
        return mintAll(user, ontologies, changes, types, parsed);
    }

    private MintResult mintAll(
            UserId user,
            ProjectOntologies ontologies,
            List<OntologyChange> changes,
            Map<IRI, EntityType<?>> types,
            Map<IRI, FreshEntityIri> parsed) {
        Map<IRI, IRI> rename = new LinkedHashMap<>();
        List<OntologyChange> creation = new ArrayList<>();
        for (Map.Entry<IRI, EntityType<?>> entry : types.entrySet()) {
            EntityCreator.CreatedEntity created = Objects.requireNonNull(
                    creator.create(entry.getValue(), parsed.get(entry.getKey()), user, ontologies), "created entity");
            if (!created.entity().getEntityType().equals(entry.getValue())) {
                throw new IllegalArgumentException(
                        "entity creator returned " + created.entity().getEntityType() + " for " + entry.getValue());
            }
            rename.put(entry.getKey(), created.entity().getIRI());
            creation.addAll(created.creationChanges());
        }
        List<OntologyChange> renamed = IriReplacer.replace(changes, rename);
        // BS-05: creation changes follow the renamed client changes. Replace them too, in case a
        // strategy still mentioned the placeholder.
        List<OntologyChange> created = creation.isEmpty() ? List.of() : IriReplacer.replace(creation, rename);
        List<OntologyChange> all = append(renamed, created);
        requireResolved(all);
        return new MintResult(all, rename);
    }

    private void requirePermission(UserId user, Map<IRI, EntityType<?>> types) {
        for (EntityType<?> type : types.values()) {
            if (!permission.canCreate(user, type)) {
                throw new CreatePermissionDeniedException(user, type);
            }
        }
    }

    private static Map<IRI, FreshEntityIri> parseAll(Set<IRI> iris) {
        Map<IRI, FreshEntityIri> parsed = new LinkedHashMap<>();
        for (IRI iri : iris) {
            parsed.put(
                    iri,
                    FreshEntityIri.parse(iri)
                            .orElseThrow(() -> new IllegalArgumentException("malformed fresh entity IRI: " + iri)));
        }
        return parsed;
    }

    private static Map<IRI, EntityType<?>> discover(List<OntologyChange> changes) {
        Map<IRI, EntityType<?>> types = new LinkedHashMap<>();
        Set<IRI> subjects = new LinkedHashSet<>();
        for (OntologyChange change : changes) {
            Objects.requireNonNull(change, "change");
            collectEntities(change, types);
            collectSubject(change, subjects);
        }
        for (IRI subject : subjects) {
            if (!types.containsKey(subject)) {
                throw new IllegalArgumentException("fresh annotation subject has no entity type: " + subject);
            }
        }
        return Map.copyOf(types);
    }

    private static void collectEntities(OntologyChange change, Map<IRI, EntityType<?>> types) {
        for (OWLEntity entity : change.signature()) {
            IRI iri = entity.getIRI();
            if (!FreshEntityIri.isFresh(iri)) {
                continue;
            }
            EntityType<?> previous = types.putIfAbsent(iri, entity.getEntityType());
            if (previous != null && !previous.equals(entity.getEntityType())) {
                throw new IllegalArgumentException(
                        "fresh IRI is used as " + previous + " and " + entity.getEntityType() + ": " + iri);
            }
        }
    }

    private static void requireResolved(List<OntologyChange> changes) {
        for (OntologyChange change : changes) {
            for (OWLEntity entity : change.signature()) {
                if (FreshEntityIri.isFresh(entity.getIRI())) {
                    throw new IllegalArgumentException("fresh IRI remains after minting: " + entity.getIRI());
                }
            }
            Set<IRI> subjects = new LinkedHashSet<>();
            collectSubject(change, subjects);
            if (!subjects.isEmpty()) {
                throw new IllegalArgumentException("fresh IRI remains after minting: "
                        + subjects.iterator().next());
            }
        }
    }

    private static void collectSubject(OntologyChange change, Set<IRI> subjects) {
        Optional<OWLAxiom> axiom = change.axiom();
        if (axiom.isEmpty() || !(axiom.get() instanceof OWLAnnotationAssertionAxiom assertion)) {
            return;
        }
        if (assertion.getSubject() instanceof IRI iri && FreshEntityIri.isFresh(iri)) {
            subjects.add(iri);
        }
    }

    private static List<OntologyChange> append(List<OntologyChange> changes, List<OntologyChange> creation) {
        if (creation.isEmpty()) {
            return changes;
        }
        List<OntologyChange> all = new ArrayList<>(changes.size() + creation.size());
        all.addAll(changes);
        all.addAll(creation);
        return all;
    }
}
