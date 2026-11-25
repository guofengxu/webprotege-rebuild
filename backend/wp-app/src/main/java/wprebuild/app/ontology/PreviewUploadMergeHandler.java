package wprebuild.app.ontology;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLOntologyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.UploadMergePreview.OntologyPatchSummary;
import wprebuild.app.ontology.upload.OntologySourceParser;
import wprebuild.app.ontology.upload.StagedUploadMeta;
import wprebuild.app.ontology.upload.UploadStaging;
import wprebuild.kernel.generator.UploadMergeMode;
import wprebuild.kernel.generator.UploadMergePlan;
import wprebuild.kernel.generator.UploadMergePlan.OntologyPatch;
import wprebuild.kernel.generator.UploadMergePlanner;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.RevisionNumber;
import wprebuild.kernel.ontology.OntologyInfo;
import wprebuild.kernel.runtime.ProjectRuntime;

/**
 * Parses outside the project lock, plans under the read lock, then stages the file (BS-14 / P6-50).
 */
@Service
public final class PreviewUploadMergeHandler implements CommandHandler<PreviewUploadMerge, UploadMergePreview> {

    private static final Logger log = LoggerFactory.getLogger(PreviewUploadMergeHandler.class);

    private final OntologySourceParser parser;

    private final ProjectRuntimeRegistry registry;

    private final UploadStaging staging;

    public PreviewUploadMergeHandler(
            OntologySourceParser parser, ProjectRuntimeRegistry registry, UploadStaging staging) {
        this.parser = Objects.requireNonNull(parser, "parser");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.staging = Objects.requireNonNull(staging, "staging");
    }

    @Override
    public Class<PreviewUploadMerge> commandType() {
        return PreviewUploadMerge.class;
    }

    @Override
    public Optional<ProjectId> project(PreviewUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(PreviewUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(
                command.mode() == UploadMergeMode.NEW_VERSION
                        ? Action.UPLOAD_AND_MERGE
                        : Action.UPLOAD_AND_MERGE_ADDITIONS);
    }

    @Override
    public UploadMergePreview handle(PreviewUploadMerge command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        // Parse outside the read lock so large uploads do not hold it (P6-50).
        List<OWLOntology> sources = parser.parse(command.file());
        if (sources.isEmpty()) {
            throw new OntologyParseException("upload did not contain an ontology", null, null);
        }
        ProjectHandle handle = registry.get(command.projectId());
        try {
            PreviewBuilt built = handle.gate().read(() -> buildPreview(handle, command, sources));
            UUID uploadId = staging.stage(
                    command.projectId(),
                    context.user(),
                    command.file(),
                    new StagedUploadMeta(
                            command.mode(),
                            command.target(),
                            built.baseRevision().value(),
                            Instant.EPOCH));
            log.info(
                    "previewed upload merge {} for project {} at revision {} (mode {})",
                    uploadId,
                    command.projectId(),
                    built.baseRevision().value(),
                    command.mode());
            return new UploadMergePreview(
                    uploadId,
                    command.mode(),
                    built.baseRevision().value(),
                    built.ontologies(),
                    built.unmatchedUploads(),
                    built.totalAdded(),
                    built.totalRemoved());
        } finally {
            release(sources);
        }
    }

    private static PreviewBuilt buildPreview(
            ProjectHandle handle, PreviewUploadMerge command, List<OWLOntology> sources) {
        ProjectRuntime runtime = handle.runtime();
        if (runtime.isCorrupted()) {
            throw new IllegalStateException("project runtime is corrupted: " + command.projectId());
        }
        RevisionNumber head = runtime.head();
        UploadMergePlan plan = switch (command.mode()) {
            case NEW_VERSION -> UploadMergePlanner.newVersion(runtime.ontologies(), sources);
            case ADDITIONS ->
                UploadMergePlanner.additions(
                        runtime.ontologies(), sources, command.target().orElseThrow());
        };
        List<OntologyPatchSummary> summaries = new ArrayList<>(plan.patches().size());
        long totalAdded = 0;
        long totalRemoved = 0;
        for (OntologyPatch patch : plan.patches()) {
            OntologyPatchSummary summary = summary(runtime, patch);
            summaries.add(summary);
            totalAdded += summary.addedAxiomCount() + summary.addedAnnotationCount();
            totalRemoved += summary.removedAxiomCount() + summary.removedAnnotationCount();
        }
        return new PreviewBuilt(head, List.copyOf(summaries), plan.unmatchedUploads(), totalAdded, totalRemoved);
    }

    private static OntologyPatchSummary summary(ProjectRuntime runtime, OntologyPatch patch) {
        String iri = runtime.ontologies()
                .info(patch.key())
                .flatMap(OntologyInfo::ontologyIri)
                .map(IRI::toString)
                .orElse(null);
        return new OntologyPatchSummary(
                patch.key().value(),
                iri,
                patch.addedAxioms().size(),
                patch.removedAxioms().size(),
                patch.addedAnnotations().size(),
                patch.removedAnnotations().size(),
                RevisionDiffRenderer.renderObjects(
                        runtime, patch.addedAxioms(), UploadMergePreview.PREVIEW_AXIOM_LIMIT),
                RevisionDiffRenderer.renderObjects(
                        runtime, patch.removedAxioms(), UploadMergePreview.PREVIEW_AXIOM_LIMIT));
    }

    private static void release(List<OWLOntology> sources) {
        for (OWLOntology ontology : sources) {
            OWLOntologyManager manager = ontology.getOWLOntologyManager();
            try {
                manager.removeOntology(ontology);
            } catch (RuntimeException ex) {
                log.debug("could not drop parsed ontology after preview", ex);
            }
        }
    }

    private record PreviewBuilt(
            RevisionNumber baseRevision,
            List<OntologyPatchSummary> ontologies,
            List<String> unmatchedUploads,
            long totalAdded,
            long totalRemoved) {}
}
