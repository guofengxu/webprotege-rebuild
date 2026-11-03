package wprebuild.kernel.revision;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;

/** Thread-safe {@link RevisionLog} that keeps revisions in process memory. */
public final class InMemoryRevisionLog implements RevisionLog {

    private final ConcurrentHashMap<ProjectId, ProjectRevisions> projects = new ConcurrentHashMap<>();

    @Override
    public RevisionNumber head(ProjectId projectId) {
        ProjectRevisions project = projects.get(requireProject(projectId));
        return project == null ? RevisionNumber.ZERO : project.head();
    }

    @Override
    public void append(ProjectId projectId, RevisionNumber expectedHead, Revision revision) {
        Objects.requireNonNull(expectedHead, "expected head");
        Objects.requireNonNull(revision, "revision");
        RevisionNumber required = expectedHead.next();
        if (!revision.number().equals(required)) {
            throw new IllegalArgumentException("revision number must be %s, not %s"
                    .formatted(required.value(), revision.number().value()));
        }
        project(requireProject(projectId)).append(expectedHead, revision);
    }

    @Override
    public Optional<Revision> get(ProjectId projectId, RevisionNumber number) {
        Objects.requireNonNull(number, "revision number");
        ProjectRevisions project = projects.get(requireProject(projectId));
        return project == null ? Optional.empty() : project.get(number);
    }

    @Override
    public Stream<Revision> read(ProjectId projectId, RevisionNumber fromInclusive, RevisionNumber toInclusive) {
        Objects.requireNonNull(fromInclusive, "from");
        Objects.requireNonNull(toInclusive, "to");
        ProjectRevisions project = projects.get(requireProject(projectId));
        if (project == null) {
            return Stream.empty();
        }
        return project.read(fromInclusive, toInclusive).stream();
    }

    private ProjectRevisions project(ProjectId projectId) {
        return projects.computeIfAbsent(projectId, ProjectRevisions::new);
    }

    private static ProjectId requireProject(ProjectId projectId) {
        return Objects.requireNonNull(projectId, "project id");
    }

    private static final class ProjectRevisions {
        private final ProjectId projectId;
        private final List<Revision> revisions = new ArrayList<>();

        private ProjectRevisions(ProjectId projectId) {
            this.projectId = projectId;
        }

        synchronized RevisionNumber head() {
            return revisions.isEmpty()
                    ? RevisionNumber.ZERO
                    : revisions.getLast().number();
        }

        synchronized void append(RevisionNumber expectedHead, Revision revision) {
            RevisionNumber actualHead = head();
            if (!actualHead.equals(expectedHead)) {
                throw new ConcurrentAppendException(projectId, expectedHead, actualHead);
            }
            revisions.add(revision);
        }

        synchronized Optional<Revision> get(RevisionNumber number) {
            for (Revision revision : revisions) {
                if (revision.number().equals(number)) {
                    return Optional.of(revision);
                }
            }
            return Optional.empty();
        }

        synchronized List<Revision> read(RevisionNumber fromInclusive, RevisionNumber toInclusive) {
            if (fromInclusive.compareTo(toInclusive) > 0 || revisions.isEmpty()) {
                return List.of();
            }
            List<Revision> result = new ArrayList<>();
            for (Revision revision : revisions) {
                if (revision.number().compareTo(fromInclusive) >= 0
                        && revision.number().compareTo(toInclusive) <= 0) {
                    result.add(revision);
                }
            }
            return List.copyOf(result);
        }
    }
}
