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
class GetProjectSettingHandler implements CommandHandler<GetProjectSetting, JsonNode> {

    private final ProjectSettingsStore settings;

    GetProjectSettingHandler(ProjectSettingsStore settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    @Override
    public Class<GetProjectSetting> commandType() {
        return GetProjectSetting.class;
    }

    @Override
    public Optional<ProjectId> project(GetProjectSetting command) {
        return Optional.of(command.projectId());
    }

    @Override
    public Optional<Action> requiredAction(GetProjectSetting command) {
        Objects.requireNonNull(command, "command");
        return Optional.of(Action.VIEW_PROJECT);
    }

    @Override
    public JsonNode handle(GetProjectSetting command, CommandContext context) {
        Objects.requireNonNull(context, "context");
        return settings.read(command.projectId(), command.key());
    }
}
