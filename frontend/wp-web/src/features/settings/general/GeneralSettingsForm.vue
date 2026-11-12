<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useProjectDetails, useUpdateProject } from "../api";

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  "update:dirty": [value: boolean];
}>();

const { t } = useI18n();
const projectQuery = useProjectDetails(() => props.projectId);
const updateProject = useUpdateProject(() => props.projectId);

const name = ref("");
const description = ref("");
const nameError = ref("");
const submitError = ref("");
const readOnly = ref(false);
const seeded = ref(false);
let submitLock = false;

const pending = computed(() => updateProject.isPending.value);
const loading = computed(
  () => projectQuery.isPending.value && projectQuery.data.value === undefined,
);
const loadFailed = computed(() => projectQuery.isError.value);

const isDirty = computed(() => {
  const project = projectQuery.data.value;
  if (project === undefined) {
    return false;
  }
  return (
    name.value !== (project.displayName ?? "") ||
    description.value !== (project.description ?? "")
  );
});

watch(
  () => projectQuery.data.value,
  (project) => {
    if (project === undefined) {
      return;
    }
    if (seeded.value && isDirty.value && !readOnly.value) {
      return;
    }
    name.value = project.displayName ?? "";
    description.value = project.description ?? "";
    nameError.value = "";
    submitError.value = "";
    seeded.value = true;
  },
  { immediate: true },
);

watch(
  isDirty,
  (dirty) => {
    emit("update:dirty", dirty && !readOnly.value);
  },
  { immediate: true },
);

watch(name, () => {
  nameError.value = "";
});

async function onSubmit(): Promise<void> {
  if (readOnly.value || submitLock || updateProject.isPending.value) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    if (name.value.trim().length === 0) {
      nameError.value = t("settings.nameRequired");
      return;
    }
    try {
      const saved = await updateProject.mutateAsync({
        name: name.value.trim(),
        description: description.value,
      });
      name.value = saved.displayName ?? name.value.trim();
      description.value = saved.description ?? description.value;
      ElMessage.success(t("settings.saveSuccess"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        readOnly.value = true;
        submitError.value = t("settings.forbidden");
        emit("update:dirty", false);
        return;
      }
      submitError.value = problemMessage(error, t);
    }
  } finally {
    submitLock = false;
  }
}
</script>

<template>
  <div class="general-settings">
    <p
      v-if="loading"
      role="status"
    >
      {{ t("settings.loading") }}
    </p>
    <div
      v-else-if="loadFailed"
      role="alert"
    >
      <p>{{ t("settings.loadError") }}</p>
      <el-button
        type="primary"
        @click="projectQuery.refetch()"
      >
        {{ t("settings.retry") }}
      </el-button>
    </div>
    <form
      v-else
      class="general-settings__form"
      @submit.prevent="onSubmit"
    >
      <el-alert
        v-if="submitError"
        :title="submitError"
        type="error"
        show-icon
        :closable="false"
      />
      <el-alert
        v-else-if="readOnly"
        :title="t('settings.forbidden')"
        type="warning"
        show-icon
        :closable="false"
      />

      <label class="general-settings__field">
        <span>{{ t("settings.name") }}</span>
        <el-input
          v-model="name"
          name="name"
          :disabled="readOnly || pending"
          :aria-invalid="nameError !== ''"
          :aria-describedby="nameError !== '' ? 'general-name-error' : undefined"
        />
        <span
          v-if="nameError"
          id="general-name-error"
          class="general-settings__error"
          role="alert"
        >
          {{ nameError }}
        </span>
      </label>

      <label class="general-settings__field">
        <span>{{ t("settings.description") }}</span>
        <el-input
          v-model="description"
          name="description"
          type="textarea"
          :rows="4"
          :disabled="readOnly || pending"
        />
      </label>

      <div class="general-settings__actions">
        <el-button
          type="primary"
          native-type="submit"
          :disabled="readOnly || pending || !isDirty"
          :loading="pending"
        >
          {{ pending ? t("settings.saving") : t("settings.save") }}
        </el-button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.general-settings__form {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-4);
  max-width: 36rem;
}

.general-settings__field {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2);
  font-size: var(--wp-font-size-sm);
  color: var(--wp-color-text-primary);
}

.general-settings__error {
  color: var(--el-color-danger);
}

.general-settings__actions {
  display: flex;
  justify-content: flex-start;
}
</style>
