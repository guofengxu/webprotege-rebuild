package wprebuild.app.form;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import wprebuild.app.support.DatabaseTest;
import wprebuild.kernel.id.ProjectId;

@DatabaseTest
@Transactional
class FormRepositoryTest {

    private static final String TEXT_DESCRIPTOR = """
            {
              "formId": "%s",
              "label": {"en": "Water", "de": "Wasser"},
              "fields": [{
                "id": "%s",
                "owlBinding": {"type": "PROPERTY", "property": "http://www.w3.org/2000/01/rdf-schema#comment"},
                "label": {"en": "Comment"},
                "fieldRun": "START",
                "control": {
                  "type": "TEXT",
                  "placeholder": {"en": "Enter brand name"},
                  "stringType": "SIMPLE_STRING",
                  "lineMode": "SINGLE_LINE",
                  "pattern": "Pattern",
                  "patternViolationErrorMessage": {"en": "There's an error"}
                },
                "repeatability": "NON_REPEATABLE",
                "optionality": "OPTIONAL",
                "readOnly": true,
                "initialExpansionState": "COLLAPSED",
                "help": {"en": "Help text"}
              }]
            }
            """;

    private static final String NUMBER_DESCRIPTOR = """
            {
              "formId": "%s",
              "label": {"en": "Mass"},
              "fields": [{
                "id": "%s",
                "owlBinding": {"type": "PROPERTY", "property": "http://example.org/mass"},
                "label": {"en": "Mass"},
                "fieldRun": "START",
                "control": {
                  "type": "NUMBER",
                  "format": "#.#",
                  "length": 6,
                  "placeholder": {"en": "0"},
                  "range": {
                    "lowerBound": "-Infinity",
                    "lowerBoundType": "INCLUSIVE",
                    "upperBound": "Infinity",
                    "upperBoundType": "INCLUSIVE"
                  },
                  "widgetType": "PLAIN"
                },
                "repeatability": "NON_REPEATABLE",
                "optionality": "OPTIONAL",
                "readOnly": false,
                "initialExpansionState": "EXPANDED",
                "help": {}
              }]
            }
            """;

    private static final String CRITERIA = """
            {
              "type": "CompositeCriteria",
              "matchType": "ALL",
              "criteria": [{
                "type": "SubClassOf",
                "target": "http://www.co-ode.org/ontologies/amino-acid/2006/05/18/amino-acid.owl#SpecificAminoAcid",
                "filterType": "DIRECT"
              }]
            }
            """;

    private final FormDescriptorRepository descriptors;
    private final FormSelectorRepository selectors;
    private final JdbcClient jdbc;

    private ProjectId projectId;

    FormRepositoryTest(FormDescriptorRepository descriptors, FormSelectorRepository selectors, JdbcClient jdbc) {
        this.descriptors = descriptors;
        this.selectors = selectors;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void seedProject() {
        String userId = "form-" + UUID.randomUUID();
        projectId = new ProjectId(UUID.randomUUID());
        jdbc.sql("insert into users (user_id, display_name) values (?, ?)")
                .param(userId)
                .param("Form Tester")
                .update();
        jdbc.sql("""
                        insert into projects (
                            project_id, display_name, owner, created_at, created_by, modified_at, modified_by
                        ) values (?, 'Forms', ?, now(), ?, now(), ?)
                        """)
                .param(projectId.value())
                .param(userId)
                .param(userId)
                .param(userId)
                .update();
    }

    @Test
    void tables_exist_after_migration() {
        List<String> tables = jdbc.sql("""
                        select table_name
                        from information_schema.tables
                        where table_schema = 'public'
                          and table_type = 'BASE TABLE'
                          and table_name in ('project_form', 'project_form_selector')
                        """).query(String.class).list();
        assertThat(tables).containsExactlyInAnyOrder("project_form", "project_form_selector");
    }

    @Test
    void saves_and_loads_polymorphic_descriptor_and_selector() {
        UUID formId = UUID.randomUUID();
        UUID fieldId = UUID.randomUUID();
        String descriptorJson = TEXT_DESCRIPTOR.formatted(formId, fieldId);

        descriptors.save(projectId, formId, descriptorJson);
        selectors.save(projectId, formId, CRITERIA);

        FormDescriptorRow loaded = descriptors.find(projectId, formId).orElseThrow();
        assertThat(loaded.ordinal()).isZero();
        assertThat(loaded.descriptorJson()).contains("\"type\": \"TEXT\"");
        assertThat(loaded.descriptorJson()).contains("Enter brand name");

        FormSelectorRow selector = selectors.find(projectId, formId).orElseThrow();
        assertThat(selector.criteriaJson()).contains("\"type\": \"CompositeCriteria\"");
        assertThat(selector.criteriaJson()).contains("SubClassOf");

        assertThat(descriptors.listByProject(projectId))
                .extracting(FormDescriptorRow::formId)
                .containsExactly(formId);
        assertThat(selectors.listByProject(projectId))
                .extracting(FormSelectorRow::formId)
                .containsExactly(formId);
    }

    @Test
    void deleting_descriptor_cascades_selector() {
        UUID formId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        descriptors.save(projectId, formId, TEXT_DESCRIPTOR.formatted(formId, UUID.randomUUID()));
        descriptors.save(projectId, otherId, NUMBER_DESCRIPTOR.formatted(otherId, UUID.randomUUID()));
        selectors.save(projectId, formId, CRITERIA);
        selectors.save(projectId, otherId, CRITERIA);

        assertThat(descriptors.delete(projectId, formId)).isTrue();

        assertThat(descriptors.find(projectId, formId)).isEmpty();
        assertThat(selectors.find(projectId, formId)).isEmpty();
        assertThat(descriptors.find(projectId, otherId)).isPresent();
        assertThat(selectors.find(projectId, otherId)).isPresent();
        assertThat(descriptors.listByProject(projectId)).hasSize(1);
    }

    @Test
    void replace_all_renumbers_ordinals_and_drops_orphan_selectors() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID removed = UUID.randomUUID();
        descriptors.save(projectId, removed, TEXT_DESCRIPTOR.formatted(removed, UUID.randomUUID()));
        selectors.save(projectId, removed, CRITERIA);
        descriptors.save(projectId, first, TEXT_DESCRIPTOR.formatted(first, UUID.randomUUID()));
        selectors.save(projectId, first, CRITERIA);

        String secondJson = NUMBER_DESCRIPTOR.formatted(second, UUID.randomUUID());
        String firstJson = TEXT_DESCRIPTOR.formatted(first, UUID.randomUUID());
        descriptors.replaceAll(
                projectId,
                List.of(new FormDescriptorWrite(second, secondJson), new FormDescriptorWrite(first, firstJson)));

        List<FormDescriptorRow> listed = descriptors.listByProject(projectId);
        assertThat(listed).extracting(FormDescriptorRow::formId).containsExactly(second, first);
        assertThat(listed).extracting(FormDescriptorRow::ordinal).containsExactly(0, 1);
        assertThat(listed.get(0).descriptorJson()).contains("\"type\": \"NUMBER\"");
        assertThat(listed.get(1).descriptorJson()).contains("\"type\": \"TEXT\"");

        assertThat(descriptors.find(projectId, removed)).isEmpty();
        assertThat(selectors.find(projectId, removed)).isEmpty();
        assertThat(selectors.find(projectId, first)).isPresent();
    }

    @Test
    void save_preserves_existing_ordinal() {
        UUID formId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        descriptors.replaceAll(
                projectId,
                List.of(
                        new FormDescriptorWrite(formId, TEXT_DESCRIPTOR.formatted(formId, UUID.randomUUID())),
                        new FormDescriptorWrite(other, NUMBER_DESCRIPTOR.formatted(other, UUID.randomUUID()))));

        String updated = TEXT_DESCRIPTOR.formatted(formId, UUID.randomUUID());
        descriptors.save(projectId, formId, updated);

        FormDescriptorRow row = descriptors.find(projectId, formId).orElseThrow();
        assertThat(row.ordinal()).isZero();
        assertThat(descriptors.find(projectId, other).orElseThrow().ordinal()).isEqualTo(1);
    }
}
