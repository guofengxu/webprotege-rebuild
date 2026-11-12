<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { DeviceSession } from "@wp/api-types";
import { problemMessage } from "@/shared/api/problem";
import { useDevices, useRevokeDevice } from "./api";

const { t, locale } = useI18n();
const devices = useDevices();
const revokeDevice = useRevokeDevice();

let revokeLock = false;

const dateFormatter = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      dateStyle: "medium",
      timeStyle: "short",
    }),
);

const rows = computed(() => devices.data.value ?? []);

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

async function onRevoke(row: DeviceSession): Promise<void> {
  if (revokeLock || revokeDevice.isPending.value) {
    return;
  }
  const tokenId = row.tokenId;
  if (tokenId === undefined || tokenId.length === 0) {
    return;
  }
  const name = row.deviceName?.trim() || tokenId;
  try {
    await ElMessageBox.confirm(
      t("account.revokeDeviceMessage", { name }),
      t("account.revokeDeviceTitle"),
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
    await revokeDevice.mutateAsync(tokenId);
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  } finally {
    revokeLock = false;
  }
}

function revokeLabel(row: DeviceSession): string {
  const name = row.deviceName?.trim() || row.tokenId || "";
  return `${t("account.revoke")} ${name}`.trim();
}
</script>

<template>
  <div class="devices-tab">
    <el-alert
      v-if="devices.isError.value"
      type="error"
      :closable="false"
      show-icon
      :title="t('account.loadError')"
    >
      <el-button
        text
        type="primary"
        @click="devices.refetch()"
      >
        {{ t("account.retry") }}
      </el-button>
    </el-alert>

    <el-skeleton
      v-else-if="devices.isPending.value"
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
        :label="t('account.deviceName')"
        prop="deviceName"
        min-width="140"
      />
      <el-table-column
        :label="t('account.createdAt')"
        min-width="150"
      >
        <template #default="{ row }">
          {{ formatDateTime(row.createdAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('account.lastUsedAt')"
        min-width="150"
      >
        <template #default="{ row }">
          {{ formatLastUsed(row.lastUsedAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('account.accessExpiresAt')"
        min-width="150"
      >
        <template #default="{ row }">
          {{ formatDateTime(row.accessExpiresAt) }}
        </template>
      </el-table-column>
      <el-table-column
        :label="t('account.refreshExpiresAt')"
        min-width="150"
      >
        <template #default="{ row }">
          {{ formatDateTime(row.refreshExpiresAt) }}
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
        {{ t("account.emptyDevices") }}
      </template>
    </el-table>
  </div>
</template>

<style scoped>
.devices-tab {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}
</style>
