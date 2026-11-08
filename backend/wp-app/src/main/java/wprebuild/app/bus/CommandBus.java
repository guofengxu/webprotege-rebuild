package wprebuild.app.bus;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.bus.ProjectExistenceCheck.ProjectState;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Single entry for commands. Checks run in the order logged-in, project state, permission (ADR-007).
 * The web layer has already authenticated {@code user}.
 */
@Component
public final class CommandBus {

    static final String PROJECT_TRASHED = "PROJECT_TRASHED";

    private static final String TIMER_NAME = "wp.command";

    private static final String TIMER_TAG = "command";

    private static final Logger log = LoggerFactory.getLogger(CommandBus.class);

    private final Map<Class<?>, CommandHandler<?, ?>> handlers;

    private final AccessService access;

    private final Supplier<ProjectExistenceCheck> projects;

    private final MeterRegistry meters;

    @Autowired
    public CommandBus(
            List<CommandHandler<?, ?>> handlers,
            AccessService access,
            ObjectProvider<ProjectExistenceCheck> projects,
            ObjectProvider<MeterRegistry> meters) {
        this(handlers, access, () -> projects.getIfAvailable(), meters.getIfAvailable(SimpleMeterRegistry::new));
    }

    CommandBus(
            List<CommandHandler<?, ?>> handlers,
            AccessService access,
            ProjectExistenceCheck projects,
            MeterRegistry meters) {
        this(handlers, access, () -> projects, meters);
    }

    private CommandBus(
            List<CommandHandler<?, ?>> handlers,
            AccessService access,
            Supplier<ProjectExistenceCheck> projects,
            MeterRegistry meters) {
        this.handlers = index(handlers);
        this.access = Objects.requireNonNull(access, "access");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.meters = Objects.requireNonNull(meters, "meters");
    }

    public <R> R execute(UserId user, Command<R> command) {
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(command, "command");
        return timer(command.getClass()).record(() -> dispatch(user, command));
    }

    private <C extends Command<R>, R> R dispatch(UserId user, C command) {
        CommandHandler<C, R> handler = handler(command);
        Optional<ProjectId> project = required(handler.project(command), "project", command);
        Optional<Action> action = required(handler.requiredAction(command), "required action", command);
        requireUsableProject(handler, project);
        authorize(user, project, action);
        return handler.handle(command, new CommandContext(user));
    }

    @SuppressWarnings("unchecked")
    private <C extends Command<R>, R> CommandHandler<C, R> handler(C command) {
        CommandHandler<?, ?> found = handlers.get(command.getClass());
        if (found == null) {
            throw new IllegalArgumentException(
                    "no handler for " + command.getClass().getName());
        }
        return (CommandHandler<C, R>) found;
    }

    private void requireUsableProject(CommandHandler<?, ?> handler, Optional<ProjectId> project) {
        if (project.isEmpty()) {
            return;
        }
        ProjectId projectId = project.get();
        ProjectExistenceCheck check = projects.get();
        if (check == null) {
            throw new IllegalStateException("ProjectExistenceCheck is not available");
        }
        ProjectState state = check.state(projectId);
        if (state == null) {
            throw new IllegalStateException("project state is null for " + projectId);
        }
        switch (state) {
            case ACTIVE -> {
                // The project can accept the command.
            }
            case TRASHED -> {
                if (!handler.allowsTrashedProject()) {
                    log.info("rejected command because project {} is in the trash", projectId);
                    throw new ConflictException(PROJECT_TRASHED, "project is in the trash");
                }
            }
            case MISSING -> {
                log.info("rejected command because project {} does not exist", projectId);
                throw new NotFoundException("project not found");
            }
        }
    }

    private void authorize(UserId user, Optional<ProjectId> project, Optional<Action> action) {
        if (action.isEmpty()) {
            return;
        }
        Action required = action.get();
        if (required.projectScoped()) {
            if (project.isEmpty()) {
                throw new IllegalStateException(required.name() + " requires a project");
            }
            access.require(user, project.get(), required);
            return;
        }
        access.require(user, required);
    }

    private Timer timer(Class<?> commandType) {
        return Timer.builder(TIMER_NAME).tag(TIMER_TAG, commandType.getName()).register(meters);
    }

    private static Map<Class<?>, CommandHandler<?, ?>> index(List<CommandHandler<?, ?>> handlers) {
        Objects.requireNonNull(handlers, "handlers");
        Map<Class<?>, CommandHandler<?, ?>> byType = new LinkedHashMap<>();
        for (CommandHandler<?, ?> handler : handlers) {
            if (handler == null) {
                throw new IllegalStateException("command handler must not be null");
            }
            Class<?> type = handler.commandType();
            if (type == null) {
                throw new IllegalStateException(
                        "command type is null for " + handler.getClass().getName());
            }
            CommandHandler<?, ?> existing = byType.putIfAbsent(type, handler);
            if (existing != null) {
                throw new IllegalStateException("multiple handlers for " + type.getName() + ": "
                        + existing.getClass().getName() + " and "
                        + handler.getClass().getName());
            }
        }
        return Map.copyOf(byType);
    }

    private static <T> Optional<T> required(Optional<T> value, String label, Command<?> command) {
        if (value == null) {
            throw new IllegalStateException(
                    label + " returned null for " + command.getClass().getName());
        }
        return value;
    }
}
