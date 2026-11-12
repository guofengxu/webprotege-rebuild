<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useCreateProject } from "./api";

const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const router = useRouter();
const createProject = useCreateProject();

const form = reactive({
  name: "",
  description: "",
  ontologyIri: "",
});
const nameError = ref("");
const iriError = ref("");
const submitError = ref("");
let submitLock = false;

const pending = computed(() => createProject.isPending.value);

watch(visible, (open) => {
  if (!open) {
    return;
  }
  form.name = "";
  form.description = "";
  form.ontologyIri = "";
  nameError.value = "";
  iriError.value = "";
  submitError.value = "";
});

watch(
  () => form.name,
  () => {
    nameError.value = "";
  },
);

watch(
  () => form.ontologyIri,
  () => {
    iriError.value = "";
  },
);

async function submit(): Promise<void> {
  if (submitLock || createProject.isPending.value) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    if (!validate()) {
      return;
    }
    const body = {
      name: form.name.trim(),
      ...(form.description.trim().length > 0
        ? { description: form.description.trim() }
        : {}),
      ...(form.ontologyIri.trim().length > 0
        ? { ontologyIri: form.ontologyIri.trim() }
        : {}),
    };
    try {
      const project = await createProject.mutateAsync(body);
      const projectId = project.projectId;
      if (projectId === undefined || projectId.length === 0) {
        submitError.value = t("errors.unknown");
        return;
      }
      visible.value = false;
      await router.push(`/projects/${projectId}`);
    } catch (error) {
      if (error instanceof ApiError && error.code === "PERMISSION_DENIED") {
        ElMessage.error(problemMessage(error, t));
      }
      submitError.value = problemMessage(error, t);
    }
  } finally {
    submitLock = false;
  }
}

function validate(): boolean {
  nameError.value =
    form.name.trim().length === 0 ? t("projects.nameRequired") : "";
  const iri = form.ontologyIri.trim();
  iriError.value =
    iri.length > 0 && !isAbsoluteIri(iri) ? t("projects.ontologyIriInvalid") : "";
  return nameError.value === "" && iriError.value === "";
}

function isAbsoluteIri(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol.length > 0 && !/\s/.test(value);
  } catch {
    return false;
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('projects.createTitle')"
    width="28rem"
    destroy-on-close
    :append-to-body="false"
  >
    <el-alert
      v-if="submitError"
      class="create-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('projects.name')">
        <el-input
          v-model="form.name"
          name="name"
          :disabled="pending"
          :aria-invalid="nameError ? 'true' : undefined"
          :aria-describedby="nameError ? 'create-name-error' : undefined"
        />
        <p
          v-if="nameError"
          id="create-name-error"
          class="field-error"
          role="alert"
        >
          {{ nameError }}
        </p>
      </el-form-item>
      <el-form-item :label="t('projects.description')">
        <el-input
          v-model="form.description"
          name="description"
          type="textarea"
          :disabled="pending"
        />
      </el-form-item>
      <el-form-item :label="t('projects.ontologyIri')">
        <el-input
          v-model="form.ontologyIri"
          name="ontologyIri"
          :disabled="pending"
          :aria-invalid="iriError ? 'true' : undefined"
          :aria-describedby="iriError ? 'create-iri-error' : undefined"
        />
        <p
          v-if="iriError"
          id="create-iri-error"
          class="field-error"
          role="alert"
        >
          {{ iriError }}
        </p>
      </el-form-item>
      <div class="create-actions">
        <el-button
          :disabled="pending"
          @click="visible = false"
        >
          {{ t("projects.cancel") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :loading="pending"
        >
          {{ t("projects.createSubmit") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.create-alert {
  margin-bottom: 12px;
}

.create-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.field-error {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
  font-weight: 600;
}
</style>
