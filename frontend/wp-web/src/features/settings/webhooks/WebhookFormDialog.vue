<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, reactive, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import {
  useCreateWebhook,
  useUpdateWebhook,
  type CreateWebhookRequest,
  type UpdateWebhookRequest,
  type WebhookResponse,
} from "./api";
import { isAbsoluteHttpUrl } from "./displayPayloadHost";

export type SecretChangeMode = "KEEP" | "REPLACE" | "CLEAR";

const EVENT_PROJECT_CHANGED = "PROJECT_CHANGED";
const MIN_SECRET_LENGTH = 16;

const visible = defineModel<boolean>({ default: false });

const props = withDefaults(
  defineProps<{
    projectId: string;
    webhook: WebhookResponse | null;
    canManage?: boolean;
  }>(),
  { canManage: true },
);

const { t } = useI18n();
const createWebhook = useCreateWebhook(() => props.projectId);
const updateWebhook = useUpdateWebhook(() => props.projectId);

const form = reactive({
  payloadUrl: "",
  projectChanged: true,
  enabled: true,
  secret: "",
  secretMode: "KEEP" as SecretChangeMode,
});

const urlError = ref("");
const eventError = ref("");
const secretError = ref("");
const submitError = ref("");
let submitLock = false;

const isEdit = computed(() => props.webhook !== null);
const pending = computed(
  () => createWebhook.isPending.value || updateWebhook.isPending.value,
);
const title = computed(() =>
  isEdit.value
    ? t("settings.webhooks.editTitle")
    : t("settings.webhooks.createTitle"),
);
const showSecretInput = computed(
  () => !isEdit.value || form.secretMode === "REPLACE",
);
const hasSecretLabel = computed(() => {
  if (props.webhook?.hasSecret === true) {
    return t("settings.webhooks.secretSet");
  }
  return t("settings.webhooks.secretUnset");
});

watch(
  visible,
  (open) => {
    if (!open) {
      clearSecretField();
      return;
    }
    resetForm();
  },
  { immediate: true },
);

watch(
  () => form.payloadUrl,
  () => {
    urlError.value = "";
  },
);
watch(
  () => form.projectChanged,
  () => {
    eventError.value = "";
  },
);
watch(
  () => form.secret,
  () => {
    secretError.value = "";
  },
);
watch(
  () => form.secretMode,
  () => {
    secretError.value = "";
    clearSecretField();
  },
);

function resetForm(): void {
  const webhook = props.webhook;
  form.payloadUrl = webhook?.payloadUrl ?? "";
  form.projectChanged =
    webhook === null ||
    (webhook.eventTypes ?? []).includes(EVENT_PROJECT_CHANGED);
  form.enabled = webhook?.enabled !== false;
  form.secret = "";
  form.secretMode = "KEEP";
  urlError.value = "";
  eventError.value = "";
  secretError.value = "";
  submitError.value = "";
}

function clearSecretField(): void {
  form.secret = "";
}

function validate(): boolean {
  const url = form.payloadUrl.trim();
  if (url.length === 0) {
    urlError.value = t("settings.webhooks.urlRequired");
  } else if (!isAbsoluteHttpUrl(url)) {
    urlError.value = t("settings.webhooks.urlInvalid");
  } else {
    urlError.value = "";
  }

  eventError.value = form.projectChanged
    ? ""
    : t("settings.webhooks.eventRequired");

  const needsSecret =
    (!isEdit.value && form.secret.length > 0) ||
    (isEdit.value && form.secretMode === "REPLACE");
  if (needsSecret && form.secret.length < MIN_SECRET_LENGTH) {
    secretError.value = t("settings.webhooks.secretTooShort");
  } else {
    secretError.value = "";
  }

  return (
    urlError.value === "" &&
    eventError.value === "" &&
    secretError.value === ""
  );
}

function mapServerError(error: unknown): void {
  if (!(error instanceof ApiError)) {
    submitError.value = problemMessage(error, t);
    return;
  }
  if (error.code === "INVALID_WEBHOOK_URL") {
    urlError.value = t("settings.webhooks.errorInvalidUrl");
    return;
  }
  if (error.code === "WEBHOOK_TARGET_NOT_ALLOWED") {
    urlError.value = t("settings.webhooks.errorTargetNotAllowed");
    return;
  }
  if (error.code === "WEBHOOK_LIMIT_REACHED") {
    submitError.value = t("settings.webhooks.formLimitReached");
    return;
  }
  submitError.value = problemMessage(error, t);
}

async function submit(): Promise<void> {
  if (submitLock || pending.value || !props.canManage) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    if (!validate()) {
      return;
    }
    const eventTypes = form.projectChanged ? [EVENT_PROJECT_CHANGED] : [];
    try {
      if (isEdit.value) {
        const webhookId = props.webhook?.webhookId?.trim() ?? "";
        if (webhookId.length === 0) {
          submitError.value = t("errors.unknown");
          return;
        }
        const body = {
          payloadUrl: form.payloadUrl.trim(),
          eventTypes,
          enabled: form.enabled,
          secretChange: form.secretMode,
          ...(form.secretMode === "REPLACE" ? { secret: form.secret } : {}),
        } satisfies UpdateWebhookRequest;
        await updateWebhook.mutateAsync({ webhookId, body });
      } else {
        const body = {
          payloadUrl: form.payloadUrl.trim(),
          eventTypes,
          enabled: form.enabled,
          ...(form.secret.length > 0 ? { secret: form.secret } : {}),
        } satisfies CreateWebhookRequest;
        await createWebhook.mutateAsync(body);
      }
      clearSecretField();
      visible.value = false;
      ElMessage.success(t("settings.webhooks.saveSuccess"));
    } catch (error) {
      mapServerError(error);
    }
  } finally {
    submitLock = false;
  }
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="title"
    width="32rem"
    destroy-on-close
    :append-to-body="false"
  >
    <el-alert
      v-if="submitError"
      class="webhook-form__alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('settings.webhooks.payloadUrl')">
        <el-input
          v-model="form.payloadUrl"
          name="payloadUrl"
          data-testid="webhook-payload-url"
          autocomplete="off"
          :disabled="pending"
          :aria-invalid="urlError ? 'true' : undefined"
          :aria-describedby="urlError ? 'webhook-url-error' : undefined"
        />
        <p
          v-if="urlError"
          id="webhook-url-error"
          class="webhook-form__error"
          role="alert"
        >
          {{ urlError }}
        </p>
      </el-form-item>

      <el-form-item :label="t('settings.webhooks.eventTypes')">
        <el-checkbox
          v-model="form.projectChanged"
          data-testid="webhook-event-project-changed"
          :disabled="pending"
          :aria-invalid="eventError ? 'true' : undefined"
          :aria-describedby="eventError ? 'webhook-event-error' : undefined"
        >
          {{ t("settings.webhooks.eventProjectChanged") }}
        </el-checkbox>
        <p
          v-if="eventError"
          id="webhook-event-error"
          class="webhook-form__error"
          role="alert"
        >
          {{ eventError }}
        </p>
      </el-form-item>

      <el-form-item :label="t('settings.webhooks.enabled')">
        <el-switch
          v-model="form.enabled"
          data-testid="webhook-enabled"
          :disabled="pending"
        />
      </el-form-item>

      <template v-if="isEdit">
        <el-form-item :label="t('settings.webhooks.secretMode')">
          <p
            class="webhook-form__secret-status"
            data-testid="webhook-secret-status"
          >
            {{ hasSecretLabel }}
          </p>
          <el-radio-group
            v-model="form.secretMode"
            data-testid="webhook-secret-mode"
            :disabled="pending"
          >
            <el-radio value="KEEP">
              {{ t("settings.webhooks.secretKeep") }}
            </el-radio>
            <el-radio value="REPLACE">
              {{ t("settings.webhooks.secretReplaceOption") }}
            </el-radio>
            <el-radio value="CLEAR">
              {{ t("settings.webhooks.secretClear") }}
            </el-radio>
          </el-radio-group>
        </el-form-item>
      </template>

      <el-form-item
        v-if="showSecretInput"
        :label="
          isEdit
            ? t('settings.webhooks.secretReplace')
            : t('settings.webhooks.secretOptional')
        "
      >
        <el-input
          v-model="form.secret"
          name="secret"
          type="password"
          data-testid="webhook-secret"
          autocomplete="new-password"
          :placeholder="t('settings.webhooks.secretPlaceholder')"
          :disabled="pending"
          :aria-invalid="secretError ? 'true' : undefined"
          :aria-describedby="secretError ? 'webhook-secret-error' : undefined"
        />
        <p
          v-if="secretError"
          id="webhook-secret-error"
          class="webhook-form__error"
          role="alert"
        >
          {{ secretError }}
        </p>
      </el-form-item>

      <div class="webhook-form__actions">
        <el-button
          class="webhook-form__hit"
          :disabled="pending"
          @click="visible = false"
        >
          {{ t("settings.webhooks.cancel") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          class="webhook-form__hit"
          data-testid="webhook-form-submit"
          :loading="pending"
        >
          {{ pending ? t("settings.webhooks.saving") : t("settings.webhooks.save") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.webhook-form__alert {
  margin-bottom: var(--wp-space-3, 12px);
}

.webhook-form__actions {
  display: flex;
  justify-content: flex-end;
  gap: var(--wp-space-2, 8px);
}

.webhook-form__error {
  margin: 4px 0 0;
  color: var(--el-color-danger);
  font-size: var(--wp-font-size-sm, 12px);
  font-weight: 600;
}

.webhook-form__secret-status {
  margin: 0 0 var(--wp-space-2, 8px);
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
  font-size: var(--wp-font-size-sm, 12px);
}

.webhook-form__hit {
  min-height: 32px;
  min-width: 32px;
}
</style>
