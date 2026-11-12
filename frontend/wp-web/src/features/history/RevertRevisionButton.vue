<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { RevisionSummary } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useRevertRevision } from "./api";

const props = defineProps<{
  projectId: string;
  revision: RevisionSummary;
}>();

const { t } = useI18n();
const revert = useRevertRevision(() => props.projectId);

const dialogOpen = ref(false);
const typedRev = ref("");
const submitError = ref("");
let submitLock = false;

const expectedRev = computed(() => String(props.revision.revNo));
const canConfirm = computed(
  () => typedRev.value.trim() === expectedRev.value && !revert.isPending.value,
);

watch(dialogOpen, (open) => {
  if (open) {
    typedRev.value = "";
    submitError.value = "";
  }
});

function openDialog(): void {
  dialogOpen.value = true;
}

function closeDialog(): void {
  dialogOpen.value = false;
}

async function confirmRevert(): Promise<void> {
  if (!canConfirm.value || submitLock) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    const result = await revert.mutateAsync(props.revision.revNo);
    const newRev = result.revision;
    dialogOpen.value = false;
    ElMessage.success(
      t("history.revertSuccess", {
        rev: newRev === undefined ? "?" : String(newRev),
      }),
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 409) {
      submitError.value = t("history.revertConflict");
      return;
    }
    if (error instanceof ApiError && error.status === 403) {
      submitError.value = t("history.revertForbidden");
      return;
    }
    submitError.value = problemMessage(error, t) || t("history.revertError");
  } finally {
    submitLock = false;
  }
}
</script>

<template>
  <span class="revert-revision">
    <el-button
      size="small"
      :aria-label="t('history.revertNamed', { rev: revision.revNo })"
      @click.stop="openDialog"
    >
      {{ t("history.revert") }}
    </el-button>

    <el-dialog
      v-model="dialogOpen"
      :title="t('history.revertTitle', { rev: revision.revNo })"
      width="28rem"
      destroy-on-close
      :append-to-body="true"
      @closed="typedRev = ''"
    >
      <p>{{ t("history.revertDescription", { description: revision.description }) }}</p>
      <p>{{ t("history.revertChangeCount", { count: revision.changeCount }) }}</p>
      <p>{{ t("history.revertHint") }}</p>
      <el-form
        label-position="top"
        @submit.prevent="confirmRevert"
      >
        <el-form-item :label="t('history.revertPrompt', { rev: revision.revNo })">
          <el-input
            v-model="typedRev"
            :aria-invalid="submitError ? 'true' : 'false'"
            :aria-describedby="submitError ? 'revert-error' : undefined"
            autocomplete="off"
          />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="submitError"
        id="revert-error"
        type="error"
        :closable="false"
        :title="submitError"
        show-icon
        role="alert"
      />
      <template #footer>
        <el-button @click="closeDialog">
          {{ t("history.revertCancel") }}
        </el-button>
        <el-button
          type="danger"
          :disabled="!canConfirm"
          :loading="revert.isPending.value"
          :aria-label="t('history.revertConfirmNamed', { rev: revision.revNo })"
          @click="confirmRevert"
        >
          {{ t("history.revertConfirm") }}
        </el-button>
      </template>
    </el-dialog>
  </span>
</template>
