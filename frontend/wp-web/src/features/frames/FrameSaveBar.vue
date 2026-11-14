<script setup lang="ts">
import { ElMessageBox } from "element-plus";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { PermissionState } from "@/features/permissions/useProjectPermissions";

const props = withDefaults(
  defineProps<{
    isDirty: boolean;
    isSaving: boolean;
    conflict: boolean;
    serverChanged: boolean;
    invalid?: boolean;
    readOnly?: boolean;
    editPermission?: PermissionState;
  }>(),
  {
    invalid: false,
    readOnly: false,
    editPermission: "allowed",
  },
);

const emit = defineEmits<{
  save: [];
  reset: [];
  reload: [];
}>();

const { t } = useI18n();

const incompleteHintId = "frame-save-incomplete";

const showActions = computed(() => !props.readOnly);

const showReadOnlyHint = computed(
  () => props.readOnly && props.editPermission === "denied",
);

const saveDisabled = computed(
  () =>
    props.readOnly ||
    !props.isDirty ||
    props.isSaving ||
    props.invalid ||
    props.conflict,
);

const saveLabel = computed(() =>
  props.isSaving ? t("framesCommon.saving") : t("framesCommon.save"),
);

async function onReload(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      t("framesCommon.conflictReloadConfirmMessage"),
      t("framesCommon.conflictReloadConfirmTitle"),
      {
        confirmButtonText: t("framesCommon.conflictReloadConfirm"),
        cancelButtonText: t("framesCommon.conflictReloadCancel"),
        type: "warning",
      },
    );
    emit("reload");
  } catch {
    // cancelled
  }
}
</script>

<template>
  <div class="frame-save-bar">
    <el-alert
      v-if="showReadOnlyHint"
      type="info"
      :closable="false"
      show-icon
      role="status"
      :title="t('framesCommon.readOnlyDenied')"
    />
    <el-alert
      v-if="conflict"
      type="error"
      :closable="false"
      show-icon
      role="alert"
      :title="t('framesCommon.conflictTitle')"
    >
      <el-button
        type="primary"
        @click="onReload"
      >
        {{ t("framesCommon.conflictReload") }}
      </el-button>
    </el-alert>
    <el-alert
      v-else-if="serverChanged"
      type="info"
      :closable="false"
      show-icon
      role="status"
      :title="t('framesCommon.serverChanged')"
    >
      <el-button @click="onReload">
        {{ t("framesCommon.conflictReload") }}
      </el-button>
    </el-alert>
    <div
      v-if="showActions"
      class="frame-save-bar__actions"
    >
      <el-button
        type="primary"
        :disabled="saveDisabled"
        :loading="isSaving"
        :aria-label="saveLabel"
        :aria-describedby="invalid ? incompleteHintId : undefined"
        @click="emit('save')"
      >
        {{ saveLabel }}
      </el-button>
      <span
        v-if="invalid"
        :id="incompleteHintId"
        class="frame-save-bar__hint"
      >
        {{ t("framesCommon.incompleteRows") }}
      </span>
      <el-button
        :disabled="!isDirty || isSaving || readOnly"
        :aria-label="t('framesCommon.reset')"
        @click="emit('reset')"
      >
        {{ t("framesCommon.reset") }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.frame-save-bar {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.frame-save-bar__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wp-space-2, 0.5rem);
}

.frame-save-bar__hint {
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
  font-size: var(--wp-font-size-sm, 0.875rem);
}

.frame-save-bar :deep(.el-button:focus-visible) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
