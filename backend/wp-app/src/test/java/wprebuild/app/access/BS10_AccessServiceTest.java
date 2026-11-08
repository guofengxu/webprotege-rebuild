package wprebuild.app.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.shared.ConflictException;
import wprebuild.app.shared.NotFoundException;
import wprebuild.app.shared.PermissionDeniedException;
import wprebuild.app.support.DatabaseTest;
import wprebuild.app.support.TestData;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/** BS-10: each project role includes the actions of the role to its left, and nothing else. */
@DatabaseTest
@Transactional
class BS10_AccessServiceTest {

    private static final Set<Action> VIEW =
            EnumSet.of(Action.VIEW_PROJECT, Action.VIEW_CHANGES, Action.DOWNLOAD_PROJECT);

    private static final Set<Action> EDIT = including(
            VIEW,
            Action.EDIT_ONTOLOGY,
            Action.EDIT_ONTOLOGY_ANNOTATIONS,
            Action.CREATE_CLASS,
            Action.DELETE_CLASS,
            Action.CREATE_PROPERTY,
            Action.DELETE_PROPERTY,
            Action.CREATE_INDIVIDUAL,
            Action.DELETE_INDIVIDUAL,
            Action.REVERT_CHANGES);

    private static final Set<Action> MANAGE = including(
            EDIT,
            Action.EDIT_PROJECT_SETTINGS,
            Action.EDIT_SHARING_SETTINGS,
            Action.EDIT_NEW_ENTITY_SETTINGS,
            Action.EDIT_PROJECT_PREFIXES,
            Action.MOVE_ANY_PROJECT_TO_TRASH);

    private static final Set<Action> CREATOR = EnumSet.of(Action.CREATE_EMPTY_PROJECT, Action.UPLOAD_PROJECT);

    private static final Set<Action> APPLICATION = including(
            CREATOR,
            Action.CREATE_ACCOUNT,
            Action.DELETE_ANY_ACCOUNT,
            Action.RESET_ANY_USER_PASSWORD,
            Action.EDIT_APPLICATION_SETTINGS);

    private final AccessService access;

    private final JdbcRoleAssignmentRepository assignments;

    private final JdbcClient jdbc;

    private final TestData data;

    private ProjectId project;

    BS10_AccessServiceTest(AccessService access, JdbcRoleAssignmentRepository assignments, JdbcClient jdbc) {
        this.access = access;
        this.assignments = assignments;
        this.jdbc = jdbc;
        this.data = new TestData(jdbc);
    }

    @BeforeEach
    void projectOwnedBySomeoneWithoutARole() {
        project = ProjectId.random();
        data.insertProject(project, new UserId("owner"));
    }

    @Test
    void action_catalog_matches_bs10() {
        assertThat(projectScoped()).containsExactlyInAnyOrderElementsOf(MANAGE);
        assertThat(applicationScoped()).containsExactlyInAnyOrderElementsOf(APPLICATION);
        assertThat(AppRole.SYSTEM_ADMIN.name()).isEqualTo(BootstrapAdmin.SYSTEM_ADMIN);
        assertThat(ProjectRole.CAN_COMMENT.actions()).isEqualTo(ProjectRole.CAN_VIEW.actions());
        assertThat(ProjectRole.CAN_EDIT.actions()).containsAll(ProjectRole.CAN_VIEW.actions());
        assertThat(ProjectRole.CAN_MANAGE.actions()).containsAll(ProjectRole.CAN_EDIT.actions());
    }

    @ParameterizedTest(name = "{0} {1} = {2}")
    @MethodSource("projectRoleMatrix")
    void project_role_matches_bs10(ProjectRole role, Action action, boolean allowed) {
        UserId member = new UserId("member");
        data.insertUser(member);
        assignments.assign(project, member, role);

        assertThat(access.can(member, project, action)).isEqualTo(allowed);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("projectActions")
    void system_admin_can_manage_every_project_and_an_unassigned_user_cannot(Action action) {
        UserId admin = new UserId("root");
        data.insertUser(admin);
        grant(admin, AppRole.SYSTEM_ADMIN);

        assertThat(access.can(admin, project, action)).isTrue();
        assertThat(access.can(new UserId("stranger"), project, action)).isFalse();
        assertThat(access.can(new UserId("owner"), project, action)).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("applicationActions")
    void application_actions_follow_app_roles(Action action) {
        UserId admin = new UserId("root");
        UserId creator = new UserId("creator");
        UserId plain = new UserId("plain");
        data.insertUser(admin);
        data.insertUser(creator);
        data.insertUser(plain);
        grant(admin, AppRole.SYSTEM_ADMIN);
        grant(creator, AppRole.PROJECT_CREATOR);

        assertThat(access.can(admin, action)).isTrue();
        assertThat(access.can(creator, action)).isEqualTo(CREATOR.contains(action));
        assertThat(access.can(plain, action)).isFalse();
    }

    @Test
    void project_and_application_checks_do_not_cross() {
        UserId admin = new UserId("root");
        data.insertUser(admin);
        grant(admin, AppRole.SYSTEM_ADMIN);

        assertThat(access.can(admin, Action.VIEW_PROJECT)).isFalse();
        assertThat(access.can(admin, project, Action.CREATE_ACCOUNT)).isFalse();
    }

    @Test
    void system_admin_keeps_manage_when_the_project_role_is_only_view() {
        UserId admin = new UserId("root");
        data.insertUser(admin);
        grant(admin, AppRole.SYSTEM_ADMIN);
        assignments.assign(project, admin, ProjectRole.CAN_VIEW);

        assertThat(access.can(admin, project, Action.MOVE_ANY_PROJECT_TO_TRASH)).isTrue();
        access.require(admin, project, Action.EDIT_ONTOLOGY);
    }

    @Test
    void grant_creator_stores_can_manage_and_require_accepts_it() {
        UserId owner = new UserId("owner");
        access.grantCreator(project, owner);
        access.grantCreator(project, owner);

        assertThat(assignments.find(project, owner)).contains(ProjectRole.CAN_MANAGE);
        access.require(owner, project, Action.EDIT_SHARING_SETTINGS);
        access.require(owner, project, Action.DOWNLOAD_PROJECT);
    }

    @Test
    void require_rejects_missing_permission() {
        UserId member = new UserId("member");
        data.insertUser(member);
        grant(member, AppRole.PROJECT_CREATOR);

        assertThatThrownBy(() -> access.require(member, project, Action.VIEW_PROJECT))
                .isInstanceOfSatisfying(PermissionDeniedException.class, denied -> {
                    assertThat(denied.code()).isEqualTo(PermissionDeniedException.CODE);
                    assertThat(denied).hasMessage("permission denied");
                });
        assertThatThrownBy(() -> access.require(member, Action.CREATE_ACCOUNT))
                .isInstanceOf(PermissionDeniedException.class);
        access.require(member, Action.UPLOAD_PROJECT);
    }

    @Test
    void replacing_a_role_drops_actions_the_new_role_does_not_include() {
        UserId member = new UserId("member");
        data.insertUser(member);
        assignments.assign(project, member, ProjectRole.CAN_MANAGE);
        assignments.assign(project, member, ProjectRole.CAN_VIEW);

        assertThat(access.can(member, project, Action.VIEW_CHANGES)).isTrue();
        assertThat(access.can(member, project, Action.EDIT_ONTOLOGY)).isFalse();
        assertThat(access.can(member, project, Action.EDIT_PROJECT_SETTINGS)).isFalse();
    }

    @Test
    void assignments_are_listed_per_project_and_removal_is_idempotent() {
        UserId alice = new UserId("alice");
        UserId bob = new UserId("bob");
        data.insertUser(alice);
        data.insertUser(bob);
        assignments.assign(project, bob, ProjectRole.CAN_EDIT);
        assignments.assign(project, alice, ProjectRole.CAN_VIEW);
        ProjectId other = ProjectId.random();
        data.insertProject(other, new UserId("owner"));
        assignments.assign(other, alice, ProjectRole.CAN_MANAGE);

        assertThat(assignments.list(project))
                .containsExactly(
                        new RoleAssignment(project, alice, ProjectRole.CAN_VIEW),
                        new RoleAssignment(project, bob, ProjectRole.CAN_EDIT));
        assertThatThrownBy(() -> assignments.list(project).clear()).isInstanceOf(UnsupportedOperationException.class);

        assignments.remove(project, alice);
        assignments.remove(project, alice);

        assertThat(assignments.find(project, alice)).isEmpty();
        assertThat(access.can(alice, project, Action.VIEW_PROJECT)).isFalse();
        assertThat(assignments.list(other)).containsExactly(new RoleAssignment(other, alice, ProjectRole.CAN_MANAGE));
    }

    @Test
    void unknown_stored_role_denies_the_project_action() {
        UserId member = new UserId("member");
        data.insertUser(member);
        jdbc.sql("""
                        insert into role_assignments (project_id, user_id, role)
                        values (?, ?, 'NOT_A_ROLE')
                        """).param(project.value()).param(member.value()).update();

        assertThat(access.can(member, project, Action.VIEW_PROJECT)).isFalse();
        assertThat(assignments.find(project, member)).isEmpty();
        assertThat(assignments.list(project)).isEmpty();
    }

    @Test
    void shared_exceptions_expose_stable_codes() {
        assertThat(new NotFoundException("missing project").code()).isEqualTo(NotFoundException.CODE);
        ConflictException conflict = new ConflictException("FRAME_CHANGED", "frame changed");
        assertThat(conflict.code()).isEqualTo("FRAME_CHANGED");
        assertThat(conflict).hasMessage("frame changed");
        assertThatThrownBy(() -> new ConflictException(" ", "frame changed"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PermissionDeniedException(" ")).isInstanceOf(IllegalArgumentException.class);
    }

    private void grant(UserId userId, AppRole role) {
        String name = role.name();
        if (!name.matches("[A-Z0-9_]+")) {
            throw new IllegalArgumentException("app role name cannot be embedded in SQL: " + name);
        }
        jdbc.sql("update users set app_roles = '{%s}'::text[] where user_id = ?".formatted(name))
                .param(userId.value())
                .update();
    }

    static Stream<Arguments> projectRoleMatrix() {
        return Stream.of(ProjectRole.values())
                .flatMap(role -> projectActions().map(action -> Arguments.of(role, action, allows(role, action))));
    }

    static Stream<Action> projectActions() {
        return MANAGE.stream();
    }

    static Stream<Action> applicationActions() {
        return APPLICATION.stream();
    }

    private static boolean allows(ProjectRole role, Action action) {
        return switch (role) {
            case CAN_VIEW, CAN_COMMENT -> VIEW.contains(action);
            case CAN_EDIT -> EDIT.contains(action);
            case CAN_MANAGE -> MANAGE.contains(action);
        };
    }

    private static Set<Action> projectScoped() {
        return EnumSet.copyOf(
                Stream.of(Action.values()).filter(Action::projectScoped).toList());
    }

    private static Set<Action> applicationScoped() {
        return EnumSet.copyOf(Stream.of(Action.values())
                .filter(action -> !action.projectScoped())
                .toList());
    }

    private static Set<Action> including(Set<Action> parent, Action... added) {
        EnumSet<Action> all = EnumSet.copyOf(parent);
        all.addAll(List.of(added));
        return Set.copyOf(all);
    }
}
