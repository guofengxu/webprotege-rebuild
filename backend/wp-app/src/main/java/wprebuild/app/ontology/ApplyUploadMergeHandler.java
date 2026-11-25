package wprebuild.app.ontology;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.upload.OntologySourceParser;
import wprebuild.app.ontology.upload.StagedUpload;
import wprebuild.app.ontology.upload.StagedUploadMeta;
import wprebuild.app.ontology.upload.UploadStaging;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.generator.UploadMergeGenerator;
import wprebuild.kernel.generator.UploadMergePlan;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.revision.Revision;
import wprebuild.kernel.runtime.ChangeApplicationResult;
import wprebuild.kernel.runtime.StaleHeadException;

/**
 * Re-parses a staged upload and applies it under {@code expectedHead} (BS-14 / P6-50). Head is not
 * checked before {@code pipeline.apply}; staleness is detected inside the generation lock.
 */
@Service
public final class ApplyUploadMergeHandler implements CommandHandler<ApplyUploadMerge, ApplyUploadMerge.Result> {

    public static final String PROJECT_CHANGED_SINCE_PREVIEW = "PROJECT_CHANGED_SINCE_PREVIEW";

    private static final Logger log = LoggerFactory.getLogger(ApplyUploadMergeHandler.class);

    private final OntologySourceParser parser;

    private final ProjectRuntimeRegistry registry;

    private final UploadStaging staging;

    public ApplyUploadMergeHandler(
            OntologySourceParser parser, ProjectRuntimeRegistry registry, UploadStaging staging) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.staging = Objects.requireNonNull(staging, "staging");
    }

    @Override
    public Class<ApplyUploadMerge> commandType() {
        return ApplyUploadMerge.class;
    }

    @Override
    public Optional<ProjectId> project(ApplyUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(ApplyUploadMerge command) {
        Objects.requireNonNull(command, "command");
        // Mode lives on the staged upload (needs the signed-in user). Both actions are CAN_MANAGE-only
        // (BS-10); require both so either staged mode is authorized before handle loads the file.
        return Optional.of(Action.UPLOAD_AND_MERGE);
    }

    @Override
    public Set<Action> requiredActions(ApplyUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Set.of(Action.UPLOAD_AND_MERGE, Action.UPLOAD_AND_MERGE_ADDITIONS);
    }

    @Override
    public ApplyUploadMerge.Result handle(ApplyUploadMerge command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        StagedUpload staged = staging.find(command.projectId(), context.user(), command.uploadId())
                .orElseThrow(() -> new NotFoundException(NotFoundException.UPLOAD_NOT_FOUND, "upload not found"));
        StagedUploadMeta meta = staged.meta();
        if (meta.baseRevision() != command.baseRevision()) {
            throw new IllegalArgumentException("baseRevision does not match the staged preview");
        }
        List<OWLOntology> sources = parser.parse(staged.file());
        if (sources.isEmpty()) {
            throw new OntologyParseException("upload did not contain an ontology", null, null);
        }
        ProjectHandle handle = registry.get(command.projectId());
        try {
            ChangeApplicationResult<UploadMergePlan> applied;
            try {
                applied = handle.pipeline()
                        .apply(
                                context.user(),
                                new UploadMergeGenerator(
                                        meta.mode(), sources, meta.targetOntology(), command.description()),
                                RevisionNumber.of(command.baseRevision()));
            } catch (StaleHeadException ex) {
                // Keep the staged file so the caller can re-preview (BS-14).
                throw new ConflictException(PROJECT_CHANGED_SINCE_PREVIEW, ex.getMessage());
            }
            staging.discard(command.projectId(), context.user(), command.uploadId());
            Long revision = applied.revision()
                    .map(Revision::number)
                    .map(RevisionNumber::value)
                    .orElse(null);
            log.info(
                    "applied upload merge {} to project {} (revision {})",
                    command.uploadId(),
                    command.projectId(),
                    revision);
            return new ApplyUploadMerge.Result(revision);
        } finally {
            release(sources);
        }
    }

    private static void release(List<OWLOntology> sources) {
        for (OWLOntology ontology : sources) {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            try {
                manager.removeOntology(ontology);
            } catch (RuntimeException ex) {
                log.debug("could not drop parsed ontology after apply", ex);
            }
        }
    }
}
