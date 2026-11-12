<script setup lang="ts">
import { useQueryClient } from "@tanstack/vue-query";
import { ElMessage } from "element-plus";
import { computed, onUnmounted, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRouter } from "vue-router";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";
import {
  UploadAbortedError,
  UploadNetworkError,
  uploadProject,
} from "./uploadProject";

const ACCEPT =
  ".owl,.rdf,.ttl,.owx,.omn,.ofn,.zip,application/rdf+xml,text/turtle,application/zip";

const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const router = useRouter();
const queryClient = useQueryClient();

const form = reactive({
  name: "",
  description: "",
});
const selectedFile = ref<File | null>(null);
const nameError = ref("");
const fileError = ref("");
const submitError = ref("");
const parseDetail = ref("");
const progress = ref(0);
const uploading = ref(false);
let abortController: AbortController | null = null;
let submitLock = false;
/** True only when the user clicked Cancel during an upload (not dialog close). */
let showAbortMessage = false;

const pending = computed(() => uploading.value);
const progressPercent = computed(() => Math.round(progress.value * 100));

watch(visible, (open) => {
  if (open) {
    resetForm();
    return;
  }
  abortInFlight();
  resetForm();
});

onUnmounted(() => {
  abortInFlight();
});

watch(
  () => form.name,
  () => {
    nameError.value = "";
  },
);

function onFileChange(file: File | undefined): void {
  selectedFile.value = file ?? null;
  fileError.value = "";
  if (file !== undefined && form.name.trim().length === 0) {
    form.name = stripExtension(file.name);
  }
}

function onFileRemove(): void {
  selectedFile.value = null;
}

async function submit(): Promise<void> {
  if (submitLock || uploading.value) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  parseDetail.value = "";
  try {
    if (!validate()) {
      return;
    }
    const file = selectedFile.value;
    if (file === null) {
      return;
    }
    uploading.value = true;
    progress.value = 0;
    showAbortMessage = false;
    abortController = new AbortController();
    try {
      const project = await uploadProject(
        {
          file,
          name: form.name.trim(),
          ...(form.description.trim().length > 0
            ? { description: form.description.trim() }
            : {}),
        },
        (ratio) => {
          progress.value = ratio;
        },
        abortController.signal,
      );
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: queryKeys.projects() }),
        queryClient.invalidateQueries({ queryKey: queryKeys.trashedProjects() }),
      ]);
      const projectId = project.projectId;
      if (projectId === undefined || projectId.length === 0) {
        submitError.value = t("errors.unknown");
        return;
      }
      visible.value = false;
      await router.push(`/projects/${projectId}`);
    } catch (error) {
      handleUploadError(error);
    } finally {
      uploading.value = false;
      abortController = null;
      showAbortMessage = false;
    }
  } finally {
    submitLock = false;
  }
}

function cancelUpload(): void {
  showAbortMessage = true;
  abortInFlight();
}

function handleUploadError(error: unknown): void {
  if (error instanceof UploadAbortedError) {
    if (showAbortMessage && visible.value) {
      submitError.value = t("projects.aborted");
    }
    return;
  }
  if (!visible.value) {
    return;
  }
  if (error instanceof UploadNetworkError) {
    submitError.value = t("errors.network");
    return;
  }
  if (error instanceof ApiError) {
    if (error.code === "PERMISSION_DENIED") {
      ElMessage.error(problemMessage(error, t));
    }
    if (error.code === "UPLOAD_TOO_LARGE" || error.status === 413) {
      submitError.value = t("projects.tooLargeHint");
      return;
    }
    if (error.code === "ONTOLOGY_PARSE_FAILED") {
      submitError.value = t("errors.ONTOLOGY_PARSE_FAILED");
      parseDetail.value = error.detail ?? "";
      return;
    }
    submitError.value = problemMessage(error, t);
    return;
  }
  submitError.value = problemMessage(error, t);
}

function validate(): boolean {
  nameError.value =
    form.name.trim().length === 0 ? t("projects.nameRequired") : "";
  fileError.value =
    selectedFile.value === null ? t("projects.uploadFileRequired") : "";
  return nameError.value === "" && fileError.value === "";
}

function resetForm(): void {
  form.name = "";
  form.description = "";
  selectedFile.value = null;
  nameError.value = "";
  fileError.value = "";
  submitError.value = "";
  parseDetail.value = "";
  progress.value = 0;
  uploading.value = false;
}

function abortInFlight(): void {
  abortController?.abort();
  abortController = null;
}

function stripExtension(filename: string): string {
  const slash = Math.max(filename.lastIndexOf("/"), filename.lastIndexOf("\\"));
  const base = slash >= 0 ? filename.slice(slash + 1) : filename;
  const dot = base.lastIndexOf(".");
  return dot > 0 ? base.slice(0, dot) : base;
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('projects.uploadTitle')"
    width="32rem"
    destroy-on-close
    :append-to-body="false"
    :close-on-click-modal="!pending"
  >
    <el-alert
      v-if="submitError"
      class="upload-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <label
      v-if="parseDetail"
      class="parse-label"
      for="upload-parse-detail"
    >
      {{ t("projects.parseError") }}
    </label>
    <textarea
      v-if="parseDetail"
      id="upload-parse-detail"
      class="parse-detail"
      readonly
      aria-live="polite"
      :value="parseDetail"
      rows="4"
    />
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('projects.uploadFile')">
        <el-upload
          :auto-upload="false"
          :limit="1"
          :accept="ACCEPT"
          :disabled="pending"
          :on-change="(uploadFile) => onFileChange(uploadFile.raw ?? undefined)"
          :on-remove="onFileRemove"
        >
          <el-button
            :disabled="pending"
            :aria-invalid="fileError ? 'true' : undefined"
            :aria-describedby="fileError ? 'upload-file-error' : undefined"
          >
            {{ t("projects.uploadFile") }}
          </el-button>
        </el-upload>
        <p
          v-if="fileError"
          id="upload-file-error"
          class="field-error"
          role="alert"
        >
          {{ fileError }}
        </p>
      </el-form-item>
      <el-form-item :label="t('projects.name')">
        <el-input
          v-model="form.name"
          name="name"
          :disabled="pending"
          :aria-invalid="nameError ? 'true' : undefined"
          :aria-describedby="nameError ? 'upload-name-error' : undefined"
        />
        <p
          v-if="nameError"
          id="upload-name-error"
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
      <div
        v-if="pending"
        class="upload-progress"
      >
        <p id="upload-progress-label">
          {{ t("projects.uploadProgress") }}
        </p>
        <el-progress
          :percentage="progressPercent"
          aria-labelledby="upload-progress-label"
        />
      </div>
      <div class="upload-actions">
        <el-button
          v-if="pending"
          @click="cancelUpload"
        >
          {{ t("projects.uploadCancel") }}
        </el-button>
        <el-button
          v-else
          @click="visible = false"
        >
          {{ t("projects.cancel") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :loading="pending"
          :disabled="pending"
        >
          {{ t("projects.uploadSubmit") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.upload-alert {
  margin-bottom: 12px;
}

.parse-label {
  display: block;
  margin-bottom: 4px;
  font-size: 13px;
}

.parse-detail {
  display: block;
  width: 100%;
  margin-bottom: 12px;
  padding: 8px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: var(--el-fill-color-lighter);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  resize: vertical;
}

.upload-progress {
  margin-bottom: 12px;
}

.upload-progress p {
  margin: 0 0 4px;
  font-size: 13px;
}

.upload-actions {
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
