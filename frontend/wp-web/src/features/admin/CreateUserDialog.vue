<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useCreateUser } from "./api";
import { randomPassword } from "./randomPassword";

const USER_ID_PATTERN = /^[A-Za-z0-9._-]{2,64}$/;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const MIN_PASSWORD_LENGTH = 12;

const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const createUser = useCreateUser();

const form = reactive({
  userId: "",
  displayName: "",
  email: "",
  initialPassword: "",
});
const userIdError = ref("");
const displayNameError = ref("");
const emailError = ref("");
const passwordError = ref("");
const submitError = ref("");
const showPassword = ref(false);
let submitLock = false;

const pending = computed(() => createUser.isPending.value);
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

watch(
  () => form.userId,
  () => {
    userIdError.value = "";
  },
);
watch(
  () => form.displayName,
  () => {
    displayNameError.value = "";
  },
);
watch(
  () => form.email,
  () => {
    emailError.value = "";
  },
);
watch(
  () => form.initialPassword,
  () => {
    passwordError.value = "";
  },
);

function clearForm(): void {
  form.userId = "";
  form.displayName = "";
  form.email = "";
  form.initialPassword = "";
  userIdError.value = "";
  displayNameError.value = "";
  emailError.value = "";
  passwordError.value = "";
  submitError.value = "";
  showPassword.value = false;
}

function generatePassword(): void {
  form.initialPassword = randomPassword();
  showPassword.value = true;
  passwordError.value = "";
}

function validate(): boolean {
  const userId = form.userId.trim();
  userIdError.value = USER_ID_PATTERN.test(userId)
    ? ""
    : t("admin.userIdInvalid");

  displayNameError.value =
    form.displayName.trim().length === 0 ? t("admin.displayNameRequired") : "";

  const email = form.email.trim();
  emailError.value = EMAIL_PATTERN.test(email) ? "" : t("admin.emailInvalid");

  passwordError.value =
    form.initialPassword.length < MIN_PASSWORD_LENGTH
      ? t("admin.passwordTooShort")
      : "";

  return (
    userIdError.value === "" &&
    displayNameError.value === "" &&
    emailError.value === "" &&
    passwordError.value === ""
  );
}

async function submit(): Promise<void> {
  if (submitLock || createUser.isPending.value) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    if (!validate()) {
      return;
    }
    const body = {
      userId: form.userId.trim(),
      displayName: form.displayName.trim(),
      email: form.email.trim(),
      initialPassword: form.initialPassword,
    };
    try {
      await createUser.mutateAsync(body);
      form.initialPassword = "";
      visible.value = false;
      ElMessage.success(t("admin.createSuccess"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        submitError.value = t("admin.userIdExists");
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
    :title="t('admin.createUserTitle')"
    width="28rem"
    destroy-on-close
    :append-to-body="false"
  >
    <el-alert
      v-if="submitError"
      class="create-user-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('admin.userId')">
        <el-input
          v-model="form.userId"
          name="userId"
          autocomplete="off"
          :disabled="pending"
          :aria-invalid="userIdError ? 'true' : undefined"
          :aria-describedby="userIdError ? 'create-user-id-error' : undefined"
        />
        <p
          v-if="userIdError"
          id="create-user-id-error"
          class="field-error"
          role="alert"
        >
          {{ userIdError }}
        </p>
      </el-form-item>
      <el-form-item :label="t('admin.displayName')">
        <el-input
          v-model="form.displayName"
          name="displayName"
          autocomplete="off"
          :disabled="pending"
          :aria-invalid="displayNameError ? 'true' : undefined"
          :aria-describedby="
            displayNameError ? 'create-display-name-error' : undefined
          "
        />
        <p
          v-if="displayNameError"
          id="create-display-name-error"
          class="field-error"
          role="alert"
        >
          {{ displayNameError }}
        </p>
      </el-form-item>
      <el-form-item :label="t('admin.email')">
        <el-input
          v-model="form.email"
          name="email"
          type="email"
          autocomplete="off"
          :disabled="pending"
          :aria-invalid="emailError ? 'true' : undefined"
          :aria-describedby="emailError ? 'create-email-error' : undefined"
        />
        <p
          v-if="emailError"
          id="create-email-error"
          class="field-error"
          role="alert"
        >
          {{ emailError }}
        </p>
      </el-form-item>
      <el-form-item :label="t('admin.initialPassword')">
        <div class="password-row">
          <el-input
            v-model="form.initialPassword"
            name="initialPassword"
            :type="passwordInputType"
            autocomplete="new-password"
            :disabled="pending"
            :aria-invalid="passwordError ? 'true' : undefined"
            :aria-describedby="
              passwordError ? 'create-password-error' : undefined
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
          v-if="passwordError"
          id="create-password-error"
          class="field-error"
          role="alert"
        >
          {{ passwordError }}
        </p>
      </el-form-item>
      <div class="create-actions">
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
          {{ t("admin.create") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.create-user-alert {
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
