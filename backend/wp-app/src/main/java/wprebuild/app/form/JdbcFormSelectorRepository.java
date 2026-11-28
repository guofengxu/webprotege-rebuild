package wprebuild.app.form;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import wprebuild.kernel.id.ProjectId;

/** JDBC storage for {@code project_form_selector}. */
@Repository
public class JdbcFormSelectorRepository implements FormSelectorRepository {

    private final JdbcClient jdbc;

    public JdbcFormSelectorRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<FormSelectorRow> listByProject(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return List.copyOf(
                jdbc.sql("""
                        select project_id, form_id, criteria::text as criteria
                        from project_form_selector
                        where project_id = ?
                        order by form_id
                        """).param(projectId.value()).query(this::mapSelector).list());
    }

    @Override
    public Optional<FormSelectorRow> find(ProjectId projectId, UUID formId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        return jdbc.sql("""
                        select project_id, form_id, criteria::text as criteria
                        from project_form_selector
                        where project_id = ? and form_id = ?
                        """)
                .param(projectId.value())
                .param(formId)
                .query(this::mapSelector)
                .optional();
    }

    @Override
    public FormSelectorRow save(ProjectId projectId, UUID formId, String criteriaJson) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(criteriaJson, "criteria json");
        jdbc.sql("""
                        insert into project_form_selector (project_id, form_id, criteria)
                        values (?, ?, cast(? as jsonb))
                        on conflict (project_id, form_id) do update
                        set criteria = excluded.criteria
                        """).param(projectId.value()).param(formId).param(criteriaJson).update();
        return find(projectId, formId).orElseThrow(() -> new IllegalStateException("form selector was not stored"));
    }

    @Override
    public boolean delete(ProjectId projectId, UUID formId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        return jdbc.sql("delete from project_form_selector where project_id = ? and form_id = ?")
                        .param(projectId.value())
                        .param(formId)
                        .update()
                > 0;
    }

    private FormSelectorRow mapSelector(ResultSet rs, int row) throws SQLException {
        return new FormSelectorRow(
                new ProjectId(rs.getObject("project_id", UUID.class)),
                rs.getObject("form_id", UUID.class),
                rs.getString("criteria"));
    }
}
