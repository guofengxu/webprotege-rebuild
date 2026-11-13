<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useResetUserPassword } from "./api";
import { randomPassword } from "./randomPassword";

const MIN_PASSWORD_LENGTH = 12;

const visible = defineModel<boolean>({ default: false });
const props = defineProps<{
  userId: string;
}>();

const { t } = useI18n();
const resetPassword = useResetUserPassword();

const newPassword = ref("");
const passwordError = ref("");
const submitError = ref("");
const showPassword = ref(false);
const passwordInputRef = ref<{ focus?: () => void } | null>(null);
const generateStatus = ref("");
let submitLock = false;

const pending = computed(() => resetPassword.isPending.value);
const passwordInputType = computed(() =>
  showPassword.value ? "text" : "password",
);

watch(visible, (open) => {
  if (!open) {
    clearForm();
    return;
  }
  clearForm();
});

watch(newPassword, () => {
  passwordError.value = "";
});

function clearForm(): void {
  newPassword.value = "";
  passwordError.value = "";
  submitError.value = "";
  showPassword.value = false;
  generateStatus.value = "";
  resetPassword.reset();
}

function generatePassword(): void {
  newPassword.value = randomPassword();
  showPassword.value = true;
  passwordError.value = "";
  generateStatus.value = t("admin.passwordGenerated");
  void nextTick(() => {
    passwordInputRef.value?.focus?.();
  });
}

function validate(): boolean {
  passwordError.value =
    newPassword.value.length < MIN_PASSWORD_LENGTH
      ? t("admin.passwordTooShort")
      : "";
  return passwordError.value === "";
}

async function focusPasswordField(): Promise<void> {
  await nextTick();
  passwordInputRef.value?.focus?.();
}

async function submit(): Promise<void> {
  if (
    submitLock ||
    resetPassword.isPending.value ||
    props.userId.length === 0
  ) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    if (!validate()) {
      await focusPasswordField();
      return;
    }
    const password = newPassword.value;
    try {
      await resetPassword.mutateAsync({
        userId: props.userId,
        body: { newPassword: password },
      });
      newPassword.value = "";
      showPassword.value = false;
      resetPassword.reset();
      visible.value = false;
      ElMessage.success(t("admin.resetPasswordSuccess"));
    } catch (error) {
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
    :title="t('admin.resetPasswordTitle', { userId: props.userId })"
    width="28rem"
    destroy-on-close
    :append-to-body="false"
  >
    <el-alert
      v-if="submitError"
      class="reset-password-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <p
      class="sr-only"
      role="status"
      aria-live="polite"
    >
      {{ generateStatus }}
    </p>
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('admin.newPassword')">
        <div class="password-row">
          <el-input
            ref="passwordInputRef"
            v-model="newPassword"
            name="newPassword"
            :type="passwordInputType"
            autocomplete="new-password"
            :disabled="pending"
            :aria-invalid="passwordError ? 'true' : undefined"
            :aria-describedby="
              passwordError
                ? 'reset-password-hint reset-password-error'
                : 'reset-password-hint'
            "
          />
          <el-button
            :disabled="pending"
            :aria-pressed="showPassword"
            :aria-label="
              showPassword ? t('admin.hidePassword') : t('admin.showPassword')
            "
            @click="showPassword = !showPassword"
          >
            {{
              showPassword ? t("admin.hidePassword") : t("admin.showPassword")
            }}
          </el-button>
        </div>
        <el-button
          class="generate-password"
          type="primary"
          link
          :disabled="pending"
          @click="generatePassword"
        >
          {{ t("admin.generatePassword") }}
        </el-button>
        <p
          id="reset-password-hint"
          class="field-hint"
        >
          {{ t("admin.passwordHint") }}
        </p>
        <p
          v-if="passwordError"
          id="reset-password-error"
          class="field-error"
          role="alert"
        >
          {{ passwordError }}
        </p>
      </el-form-item>
      <div class="reset-actions">
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
          {{ t("admin.resetPassword") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.reset-password-alert {
  margin-bottom: 12px;
}

.password-row {
  display: flex;
  gap: 8px;
  width: 100%;
}

.password-row :deep(.el-input) {
  flex: 1;
}

.generate-password {
  margin-top: 4px;
  padding-left: 0;
}

.reset-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.field-hint {
  margin: 4px 0 0;
  color: var(--el-text-color-regular);
  font-size: 12px;
}

.field-error {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: 12px;
  font-weight: 600;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
