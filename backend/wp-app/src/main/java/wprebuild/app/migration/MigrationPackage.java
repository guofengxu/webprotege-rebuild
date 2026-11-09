package wprebuild.app.migration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.IRI;
import wprebuild.kernel.change.DeclareOntology;
import wprebuild.kernel.change.OntologyChange;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.id.UserId;

/** One validated migration package. Package-private: only the importer consumes it. */
record MigrationPackage(List<MigrationProject> projects) {

    MigrationPackage {
        projects = List.copyOf(Objects.requireNonNull(projects, "projects"));
    }
}

/** One project directory after IC-1 through IC-6. */
record MigrationProject(
        ProjectId projectId,
        String location,
        String displayName,
        String description,
        String owner,
        boolean inTrash,
        Instant createdAt,
        String createdBy,
        Instant modifiedAt,
        String modifiedBy,
        List<RegisteredOntology> ontologies,
        List<MigrationRevision> revisions,
        Path headFile) {

    MigrationProject {
        Objects.requireNonNull(projectId, "projectId");
        location = required(location, "location");
        displayName = blankToEmpty(displayName);
        description = blankToEmpty(description);
        owner = blankToEmpty(owner);
        Objects.requireNonNull(createdAt, "createdAt");
        createdBy = blankToEmpty(createdBy);
        Objects.requireNonNull(modifiedAt, "modifiedAt");
        modifiedBy = blankToEmpty(modifiedBy);
        ontologies = List.copyOf(Objects.requireNonNull(ontologies, "ontologies"));
        revisions = List.copyOf(Objects.requireNonNull(revisions, "revisions"));
        Objects.requireNonNull(headFile, "headFile");
    }

    /** Usernames cited by the manifest or the revision log. Blank manifest fields are omitted. */
    List<String> userNames() {
        Set<String> names = new LinkedHashSet<>();
        addUser(names, owner);
        addUser(names, createdBy);
        addUser(names, modifiedBy);
        for (MigrationRevision revision : revisions) {
            names.add(revision.user().value());
        }
        return List.copyOf(names);
    }

    private static void addUser(Set<String> names, String userId) {
        if (!userId.isBlank()) {
            names.add(userId);
        }
    }

    private static String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}

/** Ontology row from {@code manifest.ontologies}. */
record RegisteredOntology(OntologyKey key, Optional<IRI> ontologyIri, Optional<IRI> versionIri) {

    RegisteredOntology {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(ontologyIri, "ontologyIri");
        Objects.requireNonNull(versionIri, "versionIri");
        if (versionIri.isPresent() && ontologyIri.isEmpty()) {
            throw new IllegalArgumentException("version IRI requires an ontology IRI");
        }
    }

    DeclareOntology declare() {
        return new DeclareOntology(key, ontologyIri, versionIri);
    }
}

/** One {@code revisions.jsonl} object, already decoded with {@code OfnLines.decodeLine}. */
record MigrationRevision(
        RevisionNumber number, UserId user, Instant timestamp, String description, List<OntologyChange> changes) {

    MigrationRevision {
        Objects.requireNonNull(number, "number");
        if (number.isZero()) {
            throw new IllegalArgumentException("revision number must be greater than 0");
        }
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(timestamp, "timestamp");
        description = description == null ? "" : description;
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}

/** Mutable parse scratch so a failed project is not returned. Not part of the package model. */
final class RevisionDraft {

    private final List<Long> numbers = new ArrayList<>();

    private final List<MigrationRevision> revisions = new ArrayList<>();

    private long changeCount;

    private boolean readable = true;

    void noteNumber(Long number) {
        numbers.add(number);
    }

    void countChanges(int changes) {
        if (changes < 0) {
            throw new IllegalArgumentException("change count must be >= 0");
        }
        changeCount += changes;
    }

    void add(MigrationRevision revision) {
        revisions.add(Objects.requireNonNull(revision, "revision"));
    }

    void unreadable() {
        readable = false;
    }

    boolean readable() {
        return readable;
    }

    List<Long> numbers() {
        return List.copyOf(numbers);
    }

    List<MigrationRevision> revisions() {
        return List.copyOf(revisions);
    }

    long changeCount() {
        return changeCount;
    }

    /** Every physical line was a revision object that decoded. */
    boolean complete() {
        return readable && revisions.size() == numbers.size();
    }
}
