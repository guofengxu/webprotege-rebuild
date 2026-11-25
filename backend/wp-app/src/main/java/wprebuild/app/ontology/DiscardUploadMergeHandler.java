package wprebuild.app.ontology;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.app.ontology.upload.UploadStaging;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;

/** Removes a staged upload-and-merge preview owned by the caller (BS-14 / P6-50). */
@Service
public final class DiscardUploadMergeHandler implements CommandHandler<DiscardUploadMerge, Void> {

    private final UploadStaging staging;

    public DiscardUploadMergeHandler(UploadStaging staging) {
        this.staging = Objects.requireNonNull(staging, "staging");
    }

    @Override
    public Class<DiscardUploadMerge> commandType() {
        return DiscardUploadMerge.class;
    }

    @Override
    public Optional<ProjectId> project(DiscardUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(DiscardUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.UPLOAD_AND_MERGE);
    }

    @Override
    public Set<Action> requiredActions(DiscardUploadMerge command) {
        Objects.requireNonNull(command, "command");
        return Set.of(Action.UPLOAD_AND_MERGE, Action.UPLOAD_AND_MERGE_ADDITIONS);
    }

    @Override
    public Void handle(DiscardUploadMerge command, CommandContext context) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(context, "context");
        staging.find(command.projectId(), context.user(), command.uploadId())
                .orElseThrow(() -> new NotFoundException(NotFoundException.UPLOAD_NOT_FOUND, "upload not found"));
        staging.discard(command.projectId(), context.user(), command.uploadId());
        return null;
    }
}
