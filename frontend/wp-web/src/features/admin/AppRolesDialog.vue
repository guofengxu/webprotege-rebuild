<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useCurrentUser } from "@/features/auth/useAuth";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useSetUserAppRoles } from "./api";

const ROLE_SYSTEM_ADMIN = "SYSTEM_ADMIN";
const ROLE_PROJECT_CREATOR = "PROJECT_CREATOR";

const visible = defineModel<boolean>({ default: false });
const props = defineProps<{
  userId: string;
  appRoles: readonly string[];
}>();

const { t } = useI18n();
const currentUser = useCurrentUser();
const setRoles = useSetUserAppRoles();

const roles = reactive({
  SYSTEM_ADMIN: false,
  PROJECT_CREATOR: false,
});
const submitError = ref("");
let submitLock = false;

const pending = computed(() => setRoles.isPending.value);
const currentUserId = computed(() => currentUser.data.value?.userId ?? "");

watch(
  visible,
  (open) => {
    if (!open) {
      clearState();
      return;
    }
    syncFromProps();
  },
  { immediate: true },
);

watch(
  () => props.appRoles,
  () => {
    if (visible.value) {
      syncFromProps();
    }
  },
);

function syncFromProps(): void {
  const set = new Set(props.appRoles);
  roles.SYSTEM_ADMIN = set.has(ROLE_SYSTEM_ADMIN);
  roles.PROJECT_CREATOR = set.has(ROLE_PROJECT_CREATOR);
  submitError.value = "";
}

function clearState(): void {
  roles.SYSTEM_ADMIN = false;
  roles.PROJECT_CREATOR = false;
  submitError.value = "";
}

function selectedRoles(): string[] {
  const next: string[] = [];
  if (roles.SYSTEM_ADMIN) {
    next.push(ROLE_SYSTEM_ADMIN);
  }
  if (roles.PROJECT_CREATOR) {
    next.push(ROLE_PROJECT_CREATOR);
  }
  return next;
}

function isRemovingOwnSystemAdmin(nextRoles: readonly string[]): boolean {
  const hadAdmin = props.appRoles.includes(ROLE_SYSTEM_ADMIN);
  const keepsAdmin = nextRoles.includes(ROLE_SYSTEM_ADMIN);
  return (
    hadAdmin &&
    !keepsAdmin &&
    props.userId.length > 0 &&
    props.userId === currentUserId.value
  );
}

async function confirmSelfDemotion(): Promise<boolean> {
  try {
    await ElMessageBox.confirm(
      t("admin.selfDemoteMessage"),
      t("admin.selfDemoteTitle"),
      {
        type: "warning",
        confirmButtonText: t("admin.selfDemoteConfirm"),
        cancelButtonText: t("admin.cancel"),
      },
    );
    return true;
  } catch {
    return false;
  }
}

async function submit(): Promise<void> {
  if (submitLock || setRoles.isPending.value || props.userId.length === 0) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    const appRoles = selectedRoles();
    if (isRemovingOwnSystemAdmin(appRoles)) {
      const confirmed = await confirmSelfDemotion();
      if (!confirmed) {
        return;
      }
    }
    try {
      await setRoles.mutateAsync({
        userId: props.userId,
        body: { appRoles },
      });
      visible.value = false;
      ElMessage.success(t("admin.rolesUpdated"));
    } catch (error) {
      if (error instanceof ApiError && error.code === "LAST_SYSTEM_ADMIN") {
        submitError.value = t("admin.lastSystemAdmin");
        return;
      }
      if (error instanceof ApiError && error.status === 403) {
        submitError.value = t("admin.forbiddenAction");
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
  <el-dialog
    v-model="visible"
    :title="t('admin.editRolesTitle', { userId: props.userId })"
    width="28rem"
    destroy-on-close
    :append-to-body="false"
  >
    <el-alert
      v-if="submitError"
      class="app-roles-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <fieldset class="role-fieldset">
        <legend class="role-legend">
          {{ t("admin.appRoles") }}
        </legend>
        <el-checkbox
          v-model="roles.SYSTEM_ADMIN"
          name="SYSTEM_ADMIN"
          :disabled="pending"
        >
          <span class="role-label">{{ t("admin.roleSystemAdmin") }}</span>
          <span class="role-hint">{{ t("admin.roleSystemAdminHint") }}</span>
        </el-checkbox>
        <el-checkbox
          v-model="roles.PROJECT_CREATOR"
          name="PROJECT_CREATOR"
          :disabled="pending"
        >
          <span class="role-label">{{ t("admin.roleProjectCreator") }}</span>
          <span class="role-hint">{{ t("admin.roleProjectCreatorHint") }}</span>
        </el-checkbox>
      </fieldset>
      <div class="app-roles-actions">
        <el-button
          :disabled="pending"
          @click="visible = false"
        >
          {{ t("admin.cancel") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :loading="pending"
        >
          {{ t("admin.saveRoles") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.app-roles-alert {
  margin-bottom: 12px;
}

.role-fieldset {
  margin: 0 0 16px;
  padding: 0;
  border: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.role-legend {
  padding: 0;
  margin-bottom: 8px;
  font-size: 14px;
  font-weight: 600;
}

.role-label {
  display: block;
  font-weight: 600;
}

.role-hint {
  display: block;
  margin-top: 2px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  font-weight: 400;
  white-space: normal;
}

.app-roles-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
