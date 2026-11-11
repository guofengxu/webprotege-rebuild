<script setup lang="ts">
import type { FormRules } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { safeRedirect } from "@/app/authGuard";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useLogin } from "./useAuth";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const login = useLogin();
const form = reactive({
  username: "",
  password: "",
});
const usernameError = ref("");
const passwordError = ref("");
let submitLock = false;

const rules = computed<FormRules>(() => ({
  username: [{ required: true, message: t("auth.usernameRequired"), trigger: "blur" }],
  password: [{ required: true, message: t("auth.passwordRequired"), trigger: "blur" }],
}));

const networkMessage = computed(() =>
  route.query.reason === "network" ? t("errors.network") : "",
);

const submitMessage = computed(() => describeLoginError(login.error.value, t));
const pending = computed(() => login.isPending.value);

watch(
  () => form.username,
  () => {
    usernameError.value = "";
  },
);
watch(
  () => form.password,
  () => {
    passwordError.value = "";
  },
);

async function submit(): Promise<void> {
  if (submitLock || login.isPending.value) {
    return;
  }
  submitLock = true;
  try {
    if (!markMissingFields()) {
      return;
    }
    const signedIn = await login
      .mutateAsync({ username: form.username, password: form.password })
      .then(
        () => true,
        () => false,
      );
    if (signedIn) {
      await router.replace(safeRedirect(route.query.redirect));
    }
  } finally {
    submitLock = false;
  }
}

function markMissingFields(): boolean {
  usernameError.value =
    form.username.trim().length === 0 ? t("auth.usernameRequired") : "";
  passwordError.value = form.password.length === 0 ? t("auth.passwordRequired") : "";
  return usernameError.value === "" && passwordError.value === "";
}

function describeLoginError(error: unknown, translate: typeof t): string {
  if (error instanceof ApiError && error.code === "PASSWORD_RESET_REQUIRED") {
    return translate("auth.passwordResetRequired");
  }
  if (error == null) {
    return "";
  }
  return problemMessage(error, translate);
}
</script>

<template>
  <main class="login-page">
    <el-card class="login-card">
      <header class="login-header">
        <p class="login-product">
          {{ t("app.title") }}
        </p>
        <h1 id="login-title">
          {{ t("auth.title") }}
        </h1>
      </header>
      <el-alert
        v-if="networkMessage"
        class="login-alert"
        :title="networkMessage"
        type="warning"
        show-icon
        :closable="false"
      />
      <el-alert
        v-if="submitMessage"
        class="login-alert"
        :title="submitMessage"
        type="error"
        show-icon
        :closable="false"
      />
      <el-form
        :model="form"
        :rules="rules"
        label-position="top"
        aria-labelledby="login-title"
        @submit.prevent="submit"
        @keyup.enter="submit"
      >
        <el-form-item
          :label="t('auth.username')"
          prop="username"
        >
          <el-input
            v-model="form.username"
            name="username"
            autocomplete="username"
            :disabled="pending"
            :aria-invalid="usernameError ? 'true' : undefined"
            :aria-describedby="usernameError ? 'login-username-error' : undefined"
          />
          <p
            v-if="usernameError"
            id="login-username-error"
            class="login-field-error"
            role="alert"
          >
            {{ usernameError }}
          </p>
        </el-form-item>
        <el-form-item
          :label="t('auth.password')"
          prop="password"
        >
          <el-input
            v-model="form.password"
            name="password"
            type="password"
            autocomplete="current-password"
            :disabled="pending"
            :aria-invalid="passwordError ? 'true' : undefined"
            :aria-describedby="passwordError ? 'login-password-error' : undefined"
          />
          <p
            v-if="passwordError"
            id="login-password-error"
            class="login-field-error"
            role="alert"
          >
            {{ passwordError }}
          </p>
        </el-form-item>
        <el-button
          class="login-submit"
          type="primary"
          native-type="submit"
          :loading="pending"
        >
          {{ t("auth.submit") }}
        </el-button>
      </el-form>
    </el-card>
  </main>
</template>

<style scoped>
.login-page {
  display: grid;
  min-height: 100vh;
  place-items: center;
  padding: 24px;
  background: var(--el-bg-color-page, var(--el-fill-color-lighter));
}

.login-card {
  width: min(100%, 24rem);
}

.login-header {
  margin-bottom: 16px;
}

.login-product {
  margin: 0 0 4px;
}

.login-header h1 {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.login-alert {
  margin-bottom: 16px;
}

.login-submit {
  width: 100%;
}

.login-field-error {
  margin: 4px 0 0;
  color: var(--el-text-color-primary);
  font-size: 12px;
  font-weight: 600;
}
</style>
