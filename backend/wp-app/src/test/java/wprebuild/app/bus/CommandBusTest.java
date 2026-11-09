package wprebuild.app.bus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.app.bus.ProjectExistenceCheck.ProjectState;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.app.shared.PermissionDeniedException;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** Command bus checks project state and permission before the handler runs (ADR-007). */
class CommandBusTest {

    private static final UserId ADA = new UserId("ada");

    private static final ProjectId PROJECT = ProjectId.of("11111111-1111-1111-1111-111111111111");

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final AccessService access = mock(AccessService.class);

    private final ScriptedProjects projects = new ScriptedProjects();

    private final AtomicInteger applicationChecks = new AtomicInteger();

    private final AtomicInteger projectChecks = new AtomicInteger();

    private boolean allowed = true;

    @BeforeEach
    void stubPermissionChecks() {
        Mockito.lenient()
                .doAnswer(invocation -> {
                    applicationChecks.incrementAndGet();
                    if (!allowed) {
                        throw new PermissionDeniedException("permission denied");
                    }
                    return null;
                })
                .when(access)
                .require(any(UserId.class), any(Action.class));
        Mockito.lenient()
                .doAnswer(invocation -> {
                    projectChecks.incrementAndGet();
                    if (!allowed) {
                        throw new PermissionDeniedException("permission denied");
                    }
                    return null;
                })
                .when(access)
                .require(any(UserId.class), any(ProjectId.class), any(Action.class));
    }

    @Test
    void runs_a_login_only_command_and_records_one_timer_per_command_class() {
        StubHandler ping = new StubHandler();
        EchoHandler echo = new EchoHandler();
        CommandBus bus = bus(List.of(ping, echo));

        assertThat(bus.execute(ADA, new Ping())).isEqualTo("ok");
        assertThat(bus.execute(ADA, new Echo("hi"))).isEqualTo("hi");
        assertThat(ping.users.get()).isEqualTo(ADA);
        assertThat(ping.calls.get()).isEqualTo(1);
        assertThat(applicationChecks.get()).isEqualTo(0);
        assertThat(projectChecks.get()).isEqualTo(0);

        assertThat(timer(Ping.class).count()).isEqualTo(1);
        assertThat(timer(Echo.class).count()).isEqualTo(1);
        assertThat(bus.execute(ADA, new Ping())).isEqualTo("ok");
        assertThat(timer(Ping.class).count()).isEqualTo(2);
    }

    @Test
    void missing_project_is_not_found_before_permission_is_checked() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.action = Optional.of(Action.VIEW_PROJECT);
        projects.state = ProjectState.MISSING;

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("project not found");
        assertThat(handler.calls.get()).isEqualTo(0);
        assertThat(projectChecks.get()).isEqualTo(0);
        assertThat(timer(Ping.class).count()).isEqualTo(1);
    }

    @Test
    void trashed_project_conflicts_when_the_handler_keeps_the_default() {
        LoginOnlyHandler handler = new LoginOnlyHandler(PROJECT);
        projects.state = ProjectState.TRASHED;

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOfSatisfying(ConflictException.class, conflict -> {
                    assertThat(conflict.code()).isEqualTo("PROJECT_TRASHED");
                    assertThat(conflict).hasMessage("project is in the trash");
                });
        assertThat(handler.calls.get()).isEqualTo(0);
        assertThat(projectChecks.get()).isEqualTo(0);
    }

    @Test
    void missing_project_stays_not_found_when_the_handler_allows_trash() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.allowsTrashed = true;
        projects.state = ProjectState.MISSING;

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("project not found");
        assertThat(handler.calls.get()).isEqualTo(0);
    }

    @Test
    void trashed_project_is_allowed_only_when_the_handler_opts_in() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.action = Optional.of(Action.MOVE_ANY_PROJECT_TO_TRASH);
        handler.allowsTrashed = true;
        projects.state = ProjectState.TRASHED;

        assertThat(bus(handler).execute(ADA, new Ping())).isEqualTo("ok");
        assertThat(projectChecks.get()).isEqualTo(1);
    }

    @Test
    void permission_is_checked_after_a_trashed_project_is_allowed() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.action = Optional.of(Action.MOVE_ANY_PROJECT_TO_TRASH);
        handler.allowsTrashed = true;
        projects.state = ProjectState.TRASHED;
        allowed = false;

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessage("permission denied");
        assertThat(handler.calls.get()).isEqualTo(0);
    }

    @Test
    void checks_every_required_action_before_the_handler_runs() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.actions = new LinkedHashSet<>(List.of(Action.DELETE_CLASS, Action.DELETE_PROPERTY));
        projects.state = ProjectState.ACTIVE;

        assertThat(bus(handler).execute(ADA, new Ping())).isEqualTo("ok");
        assertThat(projectChecks.get()).isEqualTo(2);
        assertThat(handler.calls.get()).isEqualTo(1);
    }

    @Test
    void denies_when_any_required_action_is_missing() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.actions = new LinkedHashSet<>(List.of(Action.DELETE_CLASS, Action.DELETE_PROPERTY));
        projects.state = ProjectState.ACTIVE;
        Mockito.doAnswer(invocation -> {
                    projectChecks.incrementAndGet();
                    Action action = invocation.getArgument(2);
                    if (action == Action.DELETE_PROPERTY) {
                        throw new PermissionDeniedException("permission denied");
                    }
                    return null;
                })
                .when(access)
                .require(any(UserId.class), any(ProjectId.class), any(Action.class));

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping())).isInstanceOf(PermissionDeniedException.class);
        assertThat(handler.calls.get()).isEqualTo(0);
        assertThat(projectChecks.get()).isPositive();
    }

    @Test
    void denies_a_project_action_on_an_active_project() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.action = Optional.of(Action.VIEW_PROJECT);
        projects.state = ProjectState.ACTIVE;
        allowed = false;

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOf(PermissionDeniedException.class)
                .hasMessage("permission denied");
        assertThat(handler.calls.get()).isEqualTo(0);
        assertThat(projectChecks.get()).isEqualTo(1);
        assertThat(applicationChecks.get()).isEqualTo(0);
    }

    @Test
    void checks_an_application_action_without_the_project_overload() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        handler.action = Optional.of(Action.CREATE_EMPTY_PROJECT);
        projects.state = ProjectState.ACTIVE;

        assertThat(bus(handler).execute(ADA, new Ping())).isEqualTo("ok");
        assertThat(applicationChecks.get()).isEqualTo(1);
        assertThat(projectChecks.get()).isEqualTo(0);
    }

    @Test
    void denies_an_application_action_when_the_command_has_no_project() {
        StubHandler handler = new StubHandler();
        handler.action = Optional.of(Action.CREATE_ACCOUNT);
        allowed = false;

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping())).isInstanceOf(PermissionDeniedException.class);
        assertThat(handler.calls.get()).isEqualTo(0);
        assertThat(applicationChecks.get()).isEqualTo(1);
    }

    @Test
    void project_scoped_action_without_a_project_is_a_handler_error() {
        StubHandler handler = new StubHandler();
        handler.action = Optional.of(Action.VIEW_PROJECT);

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(Action.VIEW_PROJECT.name());
        assertThat(handler.calls.get()).isEqualTo(0);
        assertThat(projectChecks.get()).isEqualTo(0);
    }

    @Test
    void missing_existence_check_fails_when_a_command_names_a_project() {
        StubHandler handler = new StubHandler();
        handler.project = Optional.of(PROJECT);
        CommandBus bus = new CommandBus(List.of(handler), access, null, meters);

        assertThatThrownBy(() -> bus.execute(ADA, new Ping()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ProjectExistenceCheck");
        assertThat(handler.calls.get()).isEqualTo(0);
    }

    @Test
    void unknown_command_is_rejected() {
        CommandBus bus = bus(new StubHandler());

        assertThatThrownBy(() -> bus.execute(ADA, new Echo("hi"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void records_the_timer_when_the_handler_fails() {
        StubHandler handler = new StubHandler();
        handler.failure = new IllegalStateException("boom");

        assertThatThrownBy(() -> bus(handler).execute(ADA, new Ping()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(timer(Ping.class).count()).isEqualTo(1);
    }

    @Test
    void rejects_null_arguments() {
        CommandBus bus = bus(new StubHandler());

        assertThatThrownBy(() -> bus.execute(null, new Ping())).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> bus.execute(ADA, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void duplicate_command_handler_fails_startup() {
        new ApplicationContextRunner()
                .withBean(AccessService.class, () -> mock(AccessService.class))
                .withBean(FirstPingHandler.class)
                .withBean(SecondPingHandler.class)
                .withBean(CommandBus.class)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("multiple handlers for " + Ping.class.getName())
                            .hasMessageContaining(FirstPingHandler.class.getName())
                            .hasMessageContaining(SecondPingHandler.class.getName());
                });
    }

    @Test
    void starts_when_each_command_has_one_handler() {
        SimpleMeterRegistry registered = new SimpleMeterRegistry();
        new ApplicationContextRunner()
                .withBean(AccessService.class, () -> mock(AccessService.class))
                .withBean(MeterRegistry.class, () -> registered)
                .withBean(FirstPingHandler.class)
                .withBean(CommandBus.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CommandBus bus = context.getBean(CommandBus.class);
                    assertThat(bus.execute(ADA, new Ping())).isEqualTo("first");
                    Timer timer = registered
                            .find("wp.command")
                            .tag("command", Ping.class.getName())
                            .timer();
                    assertThat(timer).isNotNull();
                    assertThat(timer.count()).isEqualTo(1);
                });
    }

    @Test
    void spring_bus_reads_the_project_existence_bean() {
        new ApplicationContextRunner()
                .withBean(AccessService.class, () -> mock(AccessService.class))
                .withBean(ProjectPingHandler.class)
                .withBean(ProjectExistenceCheck.class, () -> projectId -> ProjectState.MISSING)
                .withBean(CommandBus.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CommandBus bus = context.getBean(CommandBus.class);
                    assertThatThrownBy(() -> bus.execute(ADA, new Ping()))
                            .isInstanceOf(NotFoundException.class)
                            .hasMessage("project not found");
                });
    }

    @Test
    void spring_bus_without_an_existence_bean_rejects_a_project_command() {
        new ApplicationContextRunner()
                .withBean(AccessService.class, () -> mock(AccessService.class))
                .withBean(ProjectPingHandler.class)
                .withBean(CommandBus.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CommandBus bus = context.getBean(CommandBus.class);
                    assertThatThrownBy(() -> bus.execute(ADA, new Ping()))
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("ProjectExistenceCheck");
                });
    }

    private CommandBus bus(CommandHandler<?, ?> handler) {
        return bus(List.of(handler));
    }

    private CommandBus bus(List<CommandHandler<?, ?>> handlers) {
        return new CommandBus(handlers, access, projects, meters);
    }

    private Timer timer(Class<?> commandType) {
        Timer timer =
                meters.find("wp.command").tag("command", commandType.getName()).timer();
        assertThat(timer).isNotNull();
        return timer;
    }

    public record Ping() implements Command<String> {}

    public record Echo(String text) implements Command<String> {}

    private static final class StubHandler implements CommandHandler<Ping, String> {

        private Optional<ProjectId> project = Optional.empty();

        private Optional<Action> action = Optional.empty();

        private Set<Action> actions;

        private boolean allowsTrashed;

        private RuntimeException failure;

        private final AtomicInteger calls = new AtomicInteger();

        private final AtomicReference<UserId> users = new AtomicReference<>();

        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }

        @Override
        public Optional<ProjectId> project(Ping command) {
            return project;
        }

        @Override
        public Optional<Action> requiredAction(Ping command) {
            return action;
        }

        @Override
        public Set<Action> requiredActions(Ping command) {
            if (actions != null) {
                return actions;
            }
            return CommandHandler.super.requiredActions(command);
        }

        @Override
        public boolean allowsTrashedProject() {
            return allowsTrashed;
        }

        @Override
        public String handle(Ping command, CommandContext context) {
            calls.incrementAndGet();
            users.set(context.user());
            if (failure != null) {
                throw failure;
            }
            return "ok";
        }
    }

    /** Does not override {@link CommandHandler#allowsTrashedProject()}. */
    private static final class LoginOnlyHandler implements CommandHandler<Ping, String> {

        private final ProjectId projectId;

        private final AtomicInteger calls = new AtomicInteger();

        private LoginOnlyHandler(ProjectId projectId) {
            this.projectId = projectId;
        }

        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }

        @Override
        public Optional<ProjectId> project(Ping command) {
            return Optional.of(projectId);
        }

        @Override
        public Optional<Action> requiredAction(Ping command) {
            return Optional.empty();
        }

        @Override
        public String handle(Ping command, CommandContext context) {
            calls.incrementAndGet();
            return "ok";
        }
    }

    private static final class EchoHandler implements CommandHandler<Echo, String> {

        @Override
        public Class<Echo> commandType() {
            return Echo.class;
        }

        @Override
        public Optional<ProjectId> project(Echo command) {
            return Optional.empty();
        }

        @Override
        public Optional<Action> requiredAction(Echo command) {
            return Optional.empty();
        }

        @Override
        public String handle(Echo command, CommandContext context) {
            return command.text();
        }
    }

    public static class FirstPingHandler implements CommandHandler<Ping, String> {

        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }

        @Override
        public Optional<ProjectId> project(Ping command) {
            return Optional.empty();
        }

        @Override
        public Optional<Action> requiredAction(Ping command) {
            return Optional.empty();
        }

        @Override
        public String handle(Ping command, CommandContext context) {
            return "first";
        }
    }

    public static class SecondPingHandler implements CommandHandler<Ping, String> {

        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }

        @Override
        public Optional<ProjectId> project(Ping command) {
            return Optional.empty();
        }

        @Override
        public Optional<Action> requiredAction(Ping command) {
            return Optional.empty();
        }

        @Override
        public String handle(Ping command, CommandContext context) {
            return "second";
        }
    }

    public static class ProjectPingHandler implements CommandHandler<Ping, String> {

        @Override
        public Class<Ping> commandType() {
            return Ping.class;
        }

        @Override
        public Optional<ProjectId> project(Ping command) {
            return Optional.of(PROJECT);
        }

        @Override
        public Optional<Action> requiredAction(Ping command) {
            return Optional.empty();
        }

        @Override
        public String handle(Ping command, CommandContext context) {
            return "project";
        }
    }

    private static final class ScriptedProjects implements ProjectExistenceCheck {

        private ProjectState state = ProjectState.ACTIVE;

        @Override
        public ProjectState state(ProjectId projectId) {
            return state;
        }
    }
}
