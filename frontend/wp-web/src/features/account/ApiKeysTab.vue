<script setup lang="ts">
import { useQueryClient } from "@tanstack/vue-query";
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import type { ApiKeyInfo, IssuedApiKeyResponse } from "@wp/api-types";
import { problemMessage } from "@/shared/api/problem";
import { useApiKeys, useCreateApiKey, useRevokeApiKey } from "./api";

const { t, locale } = useI18n();
const queryClient = useQueryClient();
const apiKeys = useApiKeys();
const createApiKey = useCreateApiKey();
const revokeApiKey = useRevokeApiKey();

const createOpen = ref(false);
const createName = ref("");
const createNameError = ref("");
const createSubmitError = ref("");
const issuedOpen = ref(false);
const issuedKey = ref<string | null>(null);
let createLock = false;
let revokeLock = false;

const dateFormatter = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      dateStyle: "medium",
      timeStyle: "short",
    }),
);

const rows = computed(() => apiKeys.data.value ?? []);

function formatDateTime(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    return "";
  }
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return dateFormatter.value.format(date);
}

function formatLastUsed(value: string | undefined): string {
  if (value === undefined || value.length === 0) {
    return t("account.neverUsed");
  }
  return formatDateTime(value);
}

function openCreate(): void {
  createName.value = "";
  createNameError.value = "";
  createSubmitError.value = "";
  createOpen.value = true;
}

async function submitCreate(): Promise<void> {
  if (createLock || createApiKey.isPending.value) {
    return;
  }
  createLock = true;
  createSubmitError.value = "";
  try {
    const name = createName.value.trim();
    if (name.length === 0) {
      createNameError.value = t("account.apiKeyNameRequired");
      return;
    }
    createNameError.value = "";
    try {
      const issued: IssuedApiKeyResponse = await createApiKey.mutateAsync({
        name,
      });
      const plaintext = issued.apiKey?.trim() ?? "";
      scrubCreateMutationSecrets();
      if (plaintext.length === 0) {
        createSubmitError.value = t("errors.unknown");
        return;
      }
      createOpen.value = false;
      issuedKey.value = plaintext;
      issuedOpen.value = true;
    } catch (error) {
      createSubmitError.value = problemMessage(error, t);
    }
  } finally {
    createLock = false;
  }
}

function closeIssued(): void {
  issuedOpen.value = false;
  issuedKey.value = null;
  scrubCreateMutationSecrets();
}

function scrubCreateMutationSecrets(): void {
  createApiKey.reset();
  const cache = queryClient.getMutationCache();
  for (const mutation of cache.getAll()) {
    const data = mutation.state.data;
    if (
      data !== null &&
      typeof data === "object" &&
      "apiKey" in data &&
      typeof (data as { apiKey?: unknown }).apiKey === "string"
    ) {
      cache.remove(mutation);
    }
  }
}

async function copyIssued(): Promise<void> {
  const value = issuedKey.value;
  if (value === null) {
    return;
  }
  try {
    await navigator.clipboard.writeText(value);
    ElMessage.success(t("account.copySuccess"));
  } catch {
    ElMessage.error(t("account.copyFailed"));
  }
}

async function onRevoke(row: ApiKeyInfo): Promise<void> {
  if (revokeLock || revokeApiKey.isPending.value) {
    return;
  }
  const keyId = row.keyId;
  if (keyId === undefined || keyId.length === 0) {
    return;
  }
  const name = row.name?.trim() || keyId;
  try {
    await ElMessageBox.confirm(
      t("account.revokeApiKeyMessage", { name }),
      t("account.revokeApiKeyTitle"),
      {
        type: "warning",
        confirmButtonText: t("account.revokeConfirm"),
        cancelButtonText: t("account.cancel"),
      },
    );
  } catch {
    return;
  }
  revokeLock = true;
  try {
    await revokeApiKey.mutateAsync(keyId);
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  } finally {
    revokeLock = false;
  }
}

function revokeLabel(row: ApiKeyInfo): string {
  const name = row.name?.trim() || row.keyId || "";
  return `${t("account.revoke")} ${name}`.trim();
}
</script>

<template>
  <div class="api-keys-tab">
    <div class="api-keys-tab__toolbar">
      <el-button
        type="primary"
        @click="openCreate"
      >
        {{ t("account.createApiKey") }}
      </el-button>
    </div>

    <el-alert
      v-if="apiKeys.isError.value"
      type="error"
      :closable="false"
      show-icon
      :title="t('account.loadError')"
    >
      <el-button
        text
        type="primary"
        @click="apiKeys.refetch()"
      >
        {{ t("account.retry") }}
      </el-button>
    </el-alert>

    <el-skeleton
      v-else-if="apiKeys.isPending.value"
      :rows="4"
      animated
    />

    <el-table
      v-else
      :data="rows"
      stripe
      empty-text=""
    >
      <el-table-column
        :label="t('account.name')"
        prop="name"
        min-width="160"
      />
      <el-table-column
        :label="t('account.createdAt')"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('account.lastUsedAt')"
        min-width="160"
      >
        <template #default="{ row }">
          {{ formatLastUsed(row.lastUsedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('account.revoke')"
        width="100"
        fixed="right"
      >
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :aria-label="revokeLabel(row)"
            @click="onRevoke(row)"
          >
            {{ t("account.revoke") }}
          </el-button>
        </template>
      </el-table-column>
      <template #empty>
        {{ t("account.emptyApiKeys") }}
      </template>
    </el-table>

    <el-dialog
      v-model="createOpen"
      :title="t('account.createApiKeyTitle')"
      width="28rem"
      destroy-on-close
      :append-to-body="false"
    >
      <el-form
        label-position="top"
        @submit.prevent="submitCreate"
      >
        <el-form-item
          :label="t('account.name')"
          :error="createNameError"
          required
        >
          <el-input
            v-model="createName"
            maxlength="128"
            autocomplete="off"
            @input="createNameError = ''"
          />
        </el-form-item>
        <el-alert
          v-if="createSubmitError"
          type="error"
          :closable="false"
          show-icon
          :title="createSubmitError"
        />
      </el-form>
      <template #footer>
        <el-button @click="createOpen = false">
          {{ t("account.cancel") }}
        </el-button>
        <el-button
          type="primary"
          :loading="createApiKey.isPending.value"
          @click="submitCreate"
        >
          {{
            createApiKey.isPending.value
              ? t("account.creating")
              : t("account.create")
          }}
        </el-button>
      </template>
    </el-dialog>

    <el-dialog
      :model-value="issuedOpen"
      :title="t('account.issuedTitle')"
      width="32rem"
      destroy-on-close
      :append-to-body="false"
      :close-on-click-modal="false"
      @close="closeIssued"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        :title="t('account.issuedWarning')"
      />
      <div
        v-if="issuedKey !== null"
        class="api-keys-tab__issued"
      >
        <el-input
          :model-value="issuedKey"
          readonly
          class="api-keys-tab__issued-input"
          :aria-label="t('account.issuedTitle')"
        />
        <el-button
          type="primary"
          @click="copyIssued"
        >
          {{ t("account.copy") }}
        </el-button>
      </div>
      <template #footer>
        <el-button
          type="primary"
          @click="closeIssued"
        >
          {{ t("account.close") }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.api-keys-tab {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.api-keys-tab__toolbar {
  display: flex;
  justify-content: flex-end;
}

.api-keys-tab__issued {
  display: flex;
  gap: 0.75rem;
  align-items: center;
  margin-top: 1rem;
}

.api-keys-tab__issued-input :deep(.el-input__inner) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}
</style>
