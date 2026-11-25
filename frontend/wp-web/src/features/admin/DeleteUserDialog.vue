<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useDeleteUser } from "./api";

const visible = defineModel<boolean>({ default: false });
const props = defineProps<{
  userId: string;
}>();

const { t } = useI18n();
const deleteUser = useDeleteUser();

const confirmUserId = ref("");
const submitError = ref("");
let submitLock = false;

const pending = computed(() => deleteUser.isPending.value);
const confirmMatches = computed(
  () => confirmUserId.value === props.userId && props.userId.length > 0,
);
const canSubmit = computed(
  () => confirmMatches.value && !pending.value && props.userId.length > 0,
);

watch(visible, (open) => {
  if (!open) {
    clearForm();
    return;
  }
  clearForm();
});

function clearForm(): void {
  confirmUserId.value = "";
  submitError.value = "";
  deleteUser.reset();
}

function projectIdsFromError(error: ApiError): string[] {
  const raw = error.problem?.["projectIds"];
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw.filter(
    (value): value is string => typeof value === "string" && value.length > 0,
  );
}

function deleteErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return problemMessage(error, t);
  }
  if (error.code === "CANNOT_DELETE_SELF") {
    return t("admin.cannotDeleteSelf");
  }
  if (error.code === "LAST_SYSTEM_ADMIN") {
    return t("admin.lastSystemAdmin");
  }
  if (error.code === "LAST_MANAGER") {
    const projectIds = projectIdsFromError(error);
    return t("admin.deleteLastManager", {
      projectIds: projectIds.join(", "),
    });
  }
  if (error.status === 403) {
    return t("admin.forbiddenAction");
  }
  return problemMessage(error, t);
}

async function submit(): Promise<void> {
  if (submitLock || !canSubmit.value) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    try {
      await deleteUser.mutateAsync(props.userId);
      visible.value = false;
      ElMessage.success(t("admin.deleteUserSuccess"));
    } catch (error) {
      submitError.value = deleteErrorMessage(error);
    }
  } finally {
    submitLock = false;
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('admin.deleteUserTitle', { userId: props.userId })"
    width="28rem"
    destroy-on-close
    :append-to-body="false"
  >
    <el-alert
      v-if="submitError"
      class="delete-user-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <p class="delete-user-consequences">
      {{ t("admin.deleteUserConsequences") }}
    </p>
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item
        :label="t('admin.deleteUserConfirmHint', { userId: props.userId })"
      >
        <el-input
          v-model="confirmUserId"
          name="confirmUserId"
          autocomplete="off"
          :disabled="pending"
          :placeholder="t('admin.deleteUserConfirmPlaceholder')"
          :aria-label="
            t('admin.deleteUserConfirmHint', { userId: props.userId })
          "
        />
      </el-form-item>
      <div class="delete-user-actions">
        <el-button
          :disabled="pending"
          @click="visible = false"
        >
          {{ t("admin.cancel") }}
        </el-button>
        <el-button
          type="danger"
          native-type="submit"
          :disabled="!canSubmit"
          :loading="pending"
        >
          {{ t("admin.deleteUserConfirm") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.delete-user-alert {
  margin-bottom: 12px;
}

.delete-user-consequences {
  margin: 0 0 16px;
  color: var(--el-text-color-regular);
  line-height: 1.5;
}

.delete-user-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
