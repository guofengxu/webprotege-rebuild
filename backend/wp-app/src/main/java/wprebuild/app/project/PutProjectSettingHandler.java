package wprebuild.app.project;

import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import wprebuild.app.access.Action;
import wprebuild.app.bus.CommandContext;
import wprebuild.app.bus.CommandHandler;
import wprebuild.kernel.id.ProjectId;

@Component
class PutProjectSettingHandler implements CommandHandler<PutProjectSetting, JsonNode> {

    private final ProjectSettingsStore settings;

    PutProjectSettingHandler(ProjectSettingsStore settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Class<PutProjectSetting> commandType() {
        return PutProjectSetting.class;
    }

    @Override
    public Optional<ProjectId> project(PutProjectSetting command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(PutProjectSetting command) {
        return Optional.of(ProjectSettingKeys.editAction(command.key()));
    }

    @Override
    public JsonNode handle(PutProjectSetting command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return settings.write(command.projectId(), command.key(), command.document());
    }
}
