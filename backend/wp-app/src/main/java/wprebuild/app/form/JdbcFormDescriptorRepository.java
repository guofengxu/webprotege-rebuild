package wprebuild.app.form;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.kernel.id.ProjectId;

/** JDBC storage for {@code project_form}. Delete cascades {@code project_form_selector} (BS-16). */
@Repository
public class JdbcFormDescriptorRepository implements FormDescriptorRepository {

    private final JdbcClient jdbc;

    public JdbcFormDescriptorRepository(JdbcClient jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public List<FormDescriptorRow> listByProject(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        return List.copyOf(jdbc.sql("""
                        select project_id, form_id, ordinal, descriptor::text as descriptor
                        from project_form
                        where project_id = ?
                        order by ordinal, form_id
                        """)
                .param(projectId.value())
                .query(this::mapDescriptor)
                .list());
    }

    @Override
    public Optional<FormDescriptorRow> find(ProjectId projectId, UUID formId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        return jdbc.sql("""
                        select project_id, form_id, ordinal, descriptor::text as descriptor
                        from project_form
                        where project_id = ? and form_id = ?
                        """)
                .param(projectId.value())
                .param(formId)
                .query(this::mapDescriptor)
                .optional();
    }

    @Override
    public FormDescriptorRow save(ProjectId projectId, UUID formId, String descriptorJson) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        Objects.requireNonNull(descriptorJson, "descriptor json");
        Integer existing = jdbc.sql("""
                        select ordinal from project_form where project_id = ? and form_id = ?
                        """)
                .param(projectId.value())
                .param(formId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        int ordinal = existing != null ? existing : nextOrdinal(projectId);
        jdbc.sql("""
                        insert into project_form (project_id, form_id, ordinal, descriptor)
                        values (?, ?, ?, cast(? as jsonb))
                        on conflict (project_id, form_id) do update
                        set ordinal = excluded.ordinal,
                            descriptor = excluded.descriptor
                        """)
                .param(projectId.value())
                .param(formId)
                .param(ordinal)
                .param(descriptorJson)
                .update();
        return find(projectId, formId).orElseThrow(() -> new IllegalStateException("form descriptor was not stored"));
    }

    @Override
    @Transactional
    public boolean delete(ProjectId projectId, UUID formId) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(formId, "form id");
        int removed = jdbc.sql("delete from project_form where project_id = ? and form_id = ?")
                .param(projectId.value())
                .param(formId)
                .update();
        if (removed > 0) {
            // BS-16: deleting a descriptor also drops its selector.
            jdbc.sql("delete from project_form_selector where project_id = ? and form_id = ?")
                    .param(projectId.value())
                    .param(formId)
                    .update();
        }
        return removed > 0;
    }

    @Override
    @Transactional
    public void replaceAll(ProjectId projectId, List<FormDescriptorWrite> descriptors) {
        Objects.requireNonNull(projectId, "project id");
        Objects.requireNonNull(descriptors, "descriptors");
        Set<UUID> kept = new HashSet<>();
        for (FormDescriptorWrite write : descriptors) {
            Objects.requireNonNull(write, "descriptor write");
            if (!kept.add(write.formId())) {
                throw new IllegalArgumentException("duplicate form id: " + write.formId());
            }
        }
        jdbc.sql("delete from project_form where project_id = ?")
                .param(projectId.value())
                .update();
        for (int ordinal = 0; ordinal < descriptors.size(); ordinal++) {
            FormDescriptorWrite write = descriptors.get(ordinal);
            jdbc.sql("""
                            insert into project_form (project_id, form_id, ordinal, descriptor)
                            values (?, ?, ?, cast(? as jsonb))
                            """)
                    .param(projectId.value())
                    .param(write.formId())
                    .param(ordinal)
                    .param(write.descriptorJson())
                    .update();
        }
        List<UUID> orphanSelectors = jdbc.sql("""
                        select form_id from project_form_selector where project_id = ?
                        """).param(projectId.value()).query(UUID.class).list().stream()
                .filter(id -> !kept.contains(id))
                .toList();
        for (UUID orphan : orphanSelectors) {
            jdbc.sql("delete from project_form_selector where project_id = ? and form_id = ?")
                    .param(projectId.value())
                    .param(orphan)
                    .update();
        }
    }

    private int nextOrdinal(ProjectId projectId) {
        Integer max = jdbc.sql("select max(ordinal) from project_form where project_id = ?")
                .param(projectId.value())
                .query(Integer.class)
                .optional()
                .orElse(null);
        return max == null ? 0 : max + 1;
    }

    private FormDescriptorRow mapDescriptor(ResultSet rs, int row) throws SQLException {
        return new FormDescriptorRow(
                new ProjectId(rs.getObject("project_id", UUID.class)),
                rs.getObject("form_id", UUID.class),
                rs.getInt("ordinal"),
                rs.getString("descriptor"));
    }
}
