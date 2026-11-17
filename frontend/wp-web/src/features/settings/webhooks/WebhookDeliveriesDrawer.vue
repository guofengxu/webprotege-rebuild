<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { problemMessage } from "@/shared/api/problem";
import { useWebhookDeliveries } from "./api";

const visible = defineModel<boolean>({ default: false });

const props = withDefaults(
  defineProps<{
    projectId: string;
    webhookId: string | null;
    canManage?: boolean;
  }>(),
  { canManage: true },
);

const { t, locale } = useI18n();

const deliveriesQuery = useWebhookDeliveries(
  () => props.projectId,
  () => props.webhookId,
  {
    enabled: computed(
      () =>
        props.canManage &&
        visible.value &&
        (props.webhookId?.length ?? 0) > 0,
    ),
    limit: 20,
  },
);

const rows = computed(() => deliveriesQuery.data.value ?? []);
const loadError = computed(() => {
  if (!deliveriesQuery.isError.value) {
    return "";
  }
  return problemMessage(deliveriesQuery.error.value, t);
});

const dateFormatter = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      dateStyle: "medium",
      timeStyle: "short",
    }),
);

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

function refresh(): void {
  void deliveriesQuery.refetch();
}
</script>

<template>
  <el-drawer
    v-model="visible"
    :title="t('settings.webhooks.deliveriesTitle')"
    size="36rem"
    destroy-on-close
  >
    <div class="webhook-deliveries">
      <div class="webhook-deliveries__toolbar">
        <el-button
          class="webhook-deliveries__hit"
          data-testid="webhook-deliveries-refresh"
          :loading="deliveriesQuery.isFetching.value"
          @click="refresh"
        >
          {{ t("settings.webhooks.deliveriesRefresh") }}
        </el-button>
      </div>

      <el-alert
        v-if="loadError"
        type="error"
        show-icon
        :closable="false"
        :title="t('settings.webhooks.deliveriesLoadError')"
        :description="loadError"
      />

      <div
        v-else-if="deliveriesQuery.isPending.value && rows.length === 0"
        role="status"
        aria-live="polite"
      >
        {{ t("settings.webhooks.deliveriesLoading") }}
      </div>

      <el-table
        v-else
        :data="rows"
        row-key="deliveryId"
        stripe
        :empty-text="t('settings.webhooks.deliveriesEmpty')"
        data-testid="webhook-deliveries-table"
        :aria-busy="deliveriesQuery.isFetching.value ? 'true' : 'false'"
        :aria-label="t('settings.webhooks.deliveriesTitle')"
      >
        <el-table-column
          :label="t('settings.webhooks.deliveryStatus')"
          min-width="100"
        >
          <template #default="{ row }">
            {{ row.status ?? "" }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('settings.webhooks.deliveryAttempts')"
          min-width="90"
        >
          <template #default="{ row }">
            {{ row.attempts ?? "" }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('settings.webhooks.deliveryHttpStatus')"
          min-width="110"
        >
          <template #default="{ row }">
            {{ row.lastStatusCode ?? "" }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('settings.webhooks.deliveryError')"
          min-width="140"
          show-overflow-tooltip
        >
          <template #default="{ row }">
            {{ row.lastError ?? "" }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('settings.webhooks.deliveryCreatedAt')"
          min-width="140"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column
          :label="t('settings.webhooks.deliveryFinishedAt')"
          min-width="140"
        >
          <template #default="{ row }">
            {{ formatDateTime(row.finishedAt) }}
          </template>
        </el-table-column>
      </el-table>
    </div>
  </el-drawer>
</template>

<style scoped>
.webhook-deliveries {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-3, 12px);
}

.webhook-deliveries__toolbar {
  display: flex;
  justify-content: flex-end;
}

.webhook-deliveries__hit {
  min-height: 32px;
  min-width: 32px;
}
</style>
