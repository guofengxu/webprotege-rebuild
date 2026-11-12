<script setup lang="ts">
import type { ProjectResponse } from "@wp/api-types";
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { problemMessage } from "@/shared/api/problem";
import {
  useProjects,
  useRestoreProject,
  useTrashProject,
  useTrashedProjects,
} from "./api";
import CreateProjectDialog from "./CreateProjectDialog.vue";
import UploadProjectDialog from "./UploadProjectDialog.vue";

const { t, locale } = useI18n();
const activeTab = ref<"active" | "trash">("active");
const createOpen = ref(false);
const uploadOpen = ref(false);

const projects = useProjects();
const trashed = useTrashedProjects();
const trashProject = useTrashProject();
const restoreProject = useRestoreProject();

const activeRows = computed(() => projects.data.value ?? []);
const trashRows = computed(() => trashed.data.value ?? []);

const listError = computed(() => {
  if (activeTab.value === "active" && projects.isError.value) {
    return problemMessage(projects.error.value, t);
  }
  if (activeTab.value === "trash" && trashed.isError.value) {
    return problemMessage(trashed.error.value, t);
  }
  return "";
});

const loading = computed(() =>
  activeTab.value === "active" ? projects.isPending.value : trashed.isPending.value,
);

const dateFormatter = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      dateStyle: "medium",
      timeStyle: "short",
    }),
);

function formatModifiedAt(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return dateFormatter.value.format(date);
}

function openCreate(): void {
  createOpen.value = true;
}

function openUpload(): void {
  uploadOpen.value = true;
}

async function confirmTrash(row: ProjectResponse): Promise<void> {
  const projectId = row.projectId;
  if (projectId === undefined) {
    return;
  }
  const name = row.displayName ?? projectId;
  try {
    await ElMessageBox.confirm(
      t("projects.trashConfirmMessage", { name }),
      t("projects.trashConfirmTitle"),
      {
        type: "warning",
        confirmButtonText: t("projects.confirm"),
        cancelButtonText: t("projects.cancel"),
      },
    );
  } catch {
    return;
  }
  try {
    await trashProject.mutateAsync(projectId);
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  }
}

async function restore(row: ProjectResponse): Promise<void> {
  const projectId = row.projectId;
  if (projectId === undefined) {
    return;
  }
  try {
    await restoreProject.mutateAsync(projectId);
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  }
}
</script>

<template>
  <section class="project-list">
    <header class="project-list__header">
      <h1>{{ t("projects.title") }}</h1>
      <div class="project-list__actions">
        <el-button @click="openUpload">
          {{ t("projects.upload") }}
        </el-button>
        <el-button
          type="primary"
          @click="openCreate"
        >
          {{ t("projects.create") }}
        </el-button>
      </div>
    </header>

    <el-alert
      v-if="listError"
      class="project-list__alert"
      :title="listError"
      type="error"
      show-icon
      :closable="false"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane
        :label="t('projects.myProjects')"
        name="active"
      >
        <el-table
          v-loading="loading && activeTab === 'active'"
          :data="activeRows"
          row-key="projectId"
          empty-text=""
        >
          <el-table-column
            :label="t('projects.name')"
            min-width="160"
          >
            <template #default="{ row }">
              <RouterLink
                v-if="row.projectId"
                :to="`/projects/${row.projectId}`"
              >
                {{ row.displayName ?? row.projectId }}
              </RouterLink>
            </template>
          </el-table-column>
          <el-table-column
            prop="description"
            :label="t('projects.description')"
            min-width="180"
          />
          <el-table-column
            prop="owner"
            :label="t('projects.owner')"
            min-width="100"
          />
          <el-table-column
            :label="t('projects.modifiedAt')"
            min-width="160"
          >
            <template #default="{ row }">
              {{ formatModifiedAt(row.modifiedAt) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="modifiedBy"
            :label="t('projects.modifiedBy')"
            min-width="100"
          />
          <el-table-column
            :label="t('projects.actions')"
            width="140"
          >
            <template #default="{ row }">
              <el-button
                link
                type="danger"
                :disabled="trashProject.isPending.value"
                @click="confirmTrash(row)"
              >
                {{ t("projects.trashAction") }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="t('projects.empty')">
              <el-button
                type="primary"
                @click="openCreate"
              >
                {{ t("projects.create") }}
              </el-button>
            </el-empty>
          </template>
        </el-table>
      </el-tab-pane>

      <el-tab-pane
        :label="t('projects.trash')"
        name="trash"
      >
        <el-table
          v-loading="loading && activeTab === 'trash'"
          :data="trashRows"
          row-key="projectId"
          empty-text=""
        >
          <el-table-column
            :label="t('projects.name')"
            min-width="160"
          >
            <template #default="{ row }">
              {{ row.displayName ?? row.projectId }}
            </template>
          </el-table-column>
          <el-table-column
            prop="description"
            :label="t('projects.description')"
            min-width="180"
          />
          <el-table-column
            prop="owner"
            :label="t('projects.owner')"
            min-width="100"
          />
          <el-table-column
            :label="t('projects.modifiedAt')"
            min-width="160"
          >
            <template #default="{ row }">
              {{ formatModifiedAt(row.modifiedAt) }}
            </template>
          </el-table-column>
          <el-table-column
            prop="modifiedBy"
            :label="t('projects.modifiedBy')"
            min-width="100"
          />
          <el-table-column
            :label="t('projects.actions')"
            width="120"
          >
            <template #default="{ row }">
              <el-button
                link
                type="primary"
                :disabled="restoreProject.isPending.value"
                @click="restore(row)"
              >
                {{ t("projects.restoreAction") }}
              </el-button>
            </template>
          </el-table-column>
          <template #empty>
            <el-empty :description="t('projects.emptyTrash')" />
          </template>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <CreateProjectDialog v-model="createOpen" />
    <UploadProjectDialog v-model="uploadOpen" />
  </section>
</template>

<style scoped>
.project-list {
  padding: 24px;
}

.project-list__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.project-list__header h1 {
  margin: 0;
  font-size: 1.5rem;
  font-weight: 600;
}

.project-list__actions {
  display: flex;
  gap: 8px;
}

.project-list__alert {
  margin-bottom: 16px;
}
</style>
