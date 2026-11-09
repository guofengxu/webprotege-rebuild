package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.upload.OntologySourceParser;
import wprebuild.kernel.change.OntologyKey;
import wprebuild.kernel.generator.InitialImportGenerator;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;
import wprebuild.kernel.runtime.ChangeApplicationResult;

/**
 * Parses the upload outside the transaction, then writes the project row and revision 1.
 *
 * <p>Not the empty-project declaration: revision 1 is {@link InitialImportGenerator}.
 */
@Service
public final class ImportOntologyFromUploadHandler implements CommandHandler<ImportOntologyFromUpload, ProjectId> {

    private static final Logger log = LoggerFactory.getLogger(ImportOntologyFromUploadHandler.class);

    private final OntologySourceParser parser;

    private final ProjectProvisioning projects;

    private final ProjectRuntimeRegistry registry;

    private final TransactionTemplate transactions;

    public ImportOntologyFromUploadHandler(
            OntologySourceParser parser,
            ProjectProvisioning projects,
            ProjectRuntimeRegistry registry,
            PlatformTransactionManager transactions) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactions, "transactions"));
    }

    @Override
    public Class<ImportOntologyFromUpload> commandType() {
        return ImportOntologyFromUpload.class;
    }

    @Override
    public Optional<ProjectId> project(ImportOntologyFromUpload command) {
        Objects.requireNonNull(command, "command");
        return Optional.empty();
    }

    @Override
    public Optional<Action> requiredAction(ImportOntologyFromUpload command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.UPLOAD_PROJECT);
    }

    @Override
    public ProjectId handle(ImportOntologyFromUpload command, CommandContext context) {
        List<OWLOntology> sources = parser.parse(command.file());
        if (sources.isEmpty()) {
            throw new OntologyParseException("upload did not contain an ontology", null, null);
        }
        try {
            ProjectId projectId = transactions.execute(status -> persist(command, context.user(), sources));
            if (projectId == null) {
                throw new IllegalStateException("upload transaction returned no project");
            }
            return projectId;
        } finally {
            release(sources);
        }
    }

    private ProjectId persist(ImportOntologyFromUpload command, UserId user, List<OWLOntology> sources) {
        ProjectId projectId = projects.insert(command.name(), command.description(), user);
        try {
            ChangeApplicationResult<List<OntologyKey>> applied =
                    registry.get(projectId).pipeline().apply(user, new InitialImportGenerator(sources));
            if (applied.revision().isEmpty()) {
                throw new IllegalStateException("uploaded project did not create a revision");
            }
            log.info("imported {} ontologies into project {}", sources.size(), projectId);
            return projectId;
        } catch (RuntimeException failure) {
            registry.evict(projectId);
            throw failure;
        }
    }

    private static void release(List<OWLOntology> sources) {
        for (OWLOntology ontology : sources) {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            try {
                manager.removeOntology(ontology);
            } catch (RuntimeException ex) {
                log.debug("could not drop parsed ontology after import", ex);
            }
        }
    }
}
