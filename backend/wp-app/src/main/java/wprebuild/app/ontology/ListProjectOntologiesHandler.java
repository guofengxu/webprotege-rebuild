package wprebuild.app.ontology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.IRI;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.ontology.ProjectOntologies;
import wprebuild.kernel.runtime.ProjectRuntime;

/** Reads ontology keys, IRIs, and axiom counts while holding the project read lock. */
@Service
public final class ListProjectOntologiesHandler
        implements CommandHandler<ListProjectOntologies, List<ProjectOntologySummary>> {

    private final ProjectRuntimeRegistry registry;

    public ListProjectOntologiesHandler(ProjectRuntimeRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public Class<ListProjectOntologies> commandType() {
        return ListProjectOntologies.class;
    }

    @Override
    public Optional<ProjectId> project(ListProjectOntologies command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ListProjectOntologies command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public List<ProjectOntologySummary> handle(ListProjectOntologies command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        ProjectHandle handle = registry.get(command.projectId());
        return handle.gate().read(() -> {
            if (handle.runtime().isCorrupted()) {
                throw new IllegalStateException("project runtime is corrupted: " + command.projectId());
            }
            return summaries(handle.runtime());
        });
    }

    private static List<ProjectOntologySummary> summaries(ProjectRuntime runtime) {
        ProjectOntologies ontologies = runtime.ontologies();
        List<OntologyKey> keys = new ArrayList<>(ontologies.keys());
        keys.sort(Comparator.comparing(OntologyKey::value));
        List<ProjectOntologySummary> rows = new ArrayList<>(keys.size());
        for (OntologyKey key : keys) {
            OntologyInfo info = ontologies.info(key).orElseThrow();
            long axioms;
            try (var stream = ontologies.axioms(key)) {
                axioms = stream.count();
            }
            rows.add(new ProjectOntologySummary(
                    key.value(), iriOrNull(info.ontologyIri()), iriOrNull(info.versionIri()), axioms));
        }
        return List.copyOf(rows);
    }

    private static String iriOrNull(Optional<IRI> iri) {
        return iri.map(IRI::toString).orElse(null);
    }
}
