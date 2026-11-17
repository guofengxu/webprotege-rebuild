<script setup lang="ts">
import { ElMessage, ElMessageBox } from "element-plus";
import { computed, onMounted, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useProjectPermissions } from "@/features/permissions/useProjectPermissions";
import { problemMessage } from "@/shared/api/problem";
import {
  useDeleteWebhook,
  useUpdateWebhook,
  useWebhooks,
  type WebhookResponse,
} from "./api";
import { displayPayloadHost } from "./displayPayloadHost";
import WebhookDeliveriesDrawer from "./WebhookDeliveriesDrawer.vue";
import WebhookFormDialog from "./WebhookFormDialog.vue";

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  "update:dirty": [value: boolean];
}>();

const { t, locale } = useI18n();
const { permission } = useProjectPermissions(() => props.projectId);
const editPermission = permission("EDIT_PROJECT_SETTINGS");

const canManage = computed(() => editPermission.value === "allowed");
const permissionPending = computed(() => editPermission.value === "pending");

const webhooksQuery = useWebhooks(() => props.projectId, {
  enabled: canManage,
});
const updateWebhook = useUpdateWebhook(() => props.projectId);
const deleteWebhook = useDeleteWebhook(() => props.projectId);

const formOpen = ref(false);
const editing = ref<WebhookResponse | null>(null);
const deliveriesOpen = ref(false);
const deliveriesWebhookId = ref<string | null>(null);
const busyIds = ref<Record<string, boolean>>({});
let deleteLock = false;

onMounted(() => {
  emit("update:dirty", false);
});

watch(canManage, (ok) => {
  if (ok) {
    return;
  }
  formOpen.value = false;
  editing.value = null;
  deliveriesOpen.value = false;
  deliveriesWebhookId.value = null;
});

const rows = computed(() => webhooksQuery.data.value ?? []);
const loadError = computed(() => {
  if (!webhooksQuery.isError.value) {
    return "";
  }
  return problemMessage(webhooksQuery.error.value, t);
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

function hostFor(url: string | undefined): string {
  if (url === undefined || url.length === 0) {
    return "";
  }
  return displayPayloadHost(url);
}

function lastDeliveryText(row: WebhookResponse): string {
  const last = row.lastDelivery;
  if (last === undefined) {
    return t("settings.webhooks.lastDeliveryNone");
  }
  const parts = [
    last.status ?? "",
    last.statusCode !== undefined ? String(last.statusCode) : "",
    formatDateTime(last.finishedAt),
    last.error ?? "",
  ].filter((part) => part.length > 0);
  return parts.length > 0
    ? parts.join(" · ")
    : t("settings.webhooks.lastDeliveryNone");
}

function openCreate(): void {
  editing.value = null;
  formOpen.value = true;
}

function openEdit(row: WebhookResponse): void {
  editing.value = row;
  formOpen.value = true;
}

function openDeliveries(row: WebhookResponse): void {
  deliveriesWebhookId.value = row.webhookId ?? null;
  deliveriesOpen.value = true;
}

async function onToggle(row: WebhookResponse): Promise<void> {
  if (!canManage.value) {
    return;
  }
  const webhookId = row.webhookId?.trim() ?? "";
  if (webhookId.length === 0 || busyIds.value[webhookId] === true) {
    return;
  }
  busyIds.value = { ...busyIds.value, [webhookId]: true };
  try {
    await updateWebhook.mutateAsync({
      webhookId,
      body: {
        payloadUrl: row.payloadUrl,
        eventTypes: row.eventTypes ?? ["PROJECT_CHANGED"],
        enabled: !(row.enabled === true),
        secretChange: "KEEP",
      },
    });
    ElMessage.success(t("settings.webhooks.toggleSuccess"));
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  } finally {
    const next = { ...busyIds.value };
    delete next[webhookId];
    busyIds.value = next;
    emit("update:dirty", false);
  }
}

async function onDelete(row: WebhookResponse): Promise<void> {
  if (!canManage.value) {
    return;
  }
  const webhookId = row.webhookId?.trim() ?? "";
  if (webhookId.length === 0 || deleteLock || deleteWebhook.isPending.value) {
    return;
  }
  try {
    await ElMessageBox.confirm(
      t("settings.webhooks.deleteMessage"),
      t("settings.webhooks.deleteTitle"),
      {
        type: "warning",
        confirmButtonText: t("settings.webhooks.deleteConfirm"),
        cancelButtonText: t("settings.webhooks.cancel"),
      },
    );
  } catch {
    return;
  }
  deleteLock = true;
  busyIds.value = { ...busyIds.value, [webhookId]: true };
  try {
    await deleteWebhook.mutateAsync(webhookId);
    ElMessage.success(t("settings.webhooks.deleteSuccess"));
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  } finally {
    const next = { ...busyIds.value };
    delete next[webhookId];
    busyIds.value = next;
    deleteLock = false;
    emit("update:dirty", false);
  }
}
</script>

<template>
  <div class="webhooks-tab">
    <el-alert
      v-if="!permissionPending && !canManage"
      type="info"
      show-icon
      :closable="false"
      :title="t('settings.webhooks.forbidden')"
      data-testid="webhooks-forbidden"
    />

    <template v-else-if="canManage">
      <p class="webhooks-tab__hint">
        {{ t("settings.webhooks.hint") }}
      </p>

      <el-alert
        v-if="loadError"
        type="error"
        show-icon
        :closable="false"
        :title="t('settings.webhooks.loadError')"
        :description="loadError"
      >
        <el-button
          type="primary"
          class="webhooks-tab__hit"
          @click="webhooksQuery.refetch()"
        >
          {{ t("settings.webhooks.retry") }}
        </el-button>
      </el-alert>

      <template v-else>
        <div
          v-if="webhooksQuery.isPending.value && rows.length === 0"
          role="status"
          aria-live="polite"
        >
          {{ t("settings.webhooks.loading") }}
        </div>

        <template v-else>
          <div class="webhooks-tab__toolbar">
            <el-button
              type="primary"
              class="webhooks-tab__hit"
              data-testid="webhook-create"
              @click="openCreate"
            >
              {{ t("settings.webhooks.create") }}
            </el-button>
          </div>

          <el-table
            :data="rows"
            row-key="webhookId"
            stripe
            :empty-text="t('settings.webhooks.empty')"
            data-testid="webhooks-table"
          >
            <el-table-column
              :label="t('settings.webhooks.payloadUrl')"
              min-width="160"
            >
              <template #default="{ row }">
                <span :data-webhook-host="row.webhookId ?? ''">
                  {{ hostFor(row.payloadUrl) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column
              :label="t('settings.webhooks.eventTypes')"
              min-width="140"
            >
              <template #default="{ row }">
                {{ (row.eventTypes ?? []).join(", ") }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('settings.webhooks.enabled')"
              min-width="80"
            >
              <template #default="{ row }">
                {{
                  row.enabled === true
                    ? t("settings.webhooks.enable")
                    : t("settings.webhooks.disable")
                }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('settings.webhooks.hasSecret')"
              min-width="90"
            >
              <template #default="{ row }">
                {{
                  row.hasSecret === true
                    ? t("settings.webhooks.secretSet")
                    : t("settings.webhooks.secretUnset")
                }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('settings.webhooks.lastDelivery')"
              min-width="220"
              show-overflow-tooltip
            >
              <template #default="{ row }">
                {{ lastDeliveryText(row) }}
              </template>
            </el-table-column>
            <el-table-column
              :label="t('settings.webhooks.actions')"
              min-width="280"
            >
              <template #default="{ row }">
                <div class="webhooks-tab__actions">
                  <el-button
                    class="webhooks-tab__hit"
                    :data-testid="`webhook-edit-${row.webhookId ?? ''}`"
                    :disabled="busyIds[row.webhookId ?? ''] === true"
                    :aria-label="`${t('settings.webhooks.edit')} ${hostFor(row.payloadUrl)}`"
                    @click="openEdit(row)"
                  >
                    {{ t("settings.webhooks.edit") }}
                  </el-button>
                  <el-button
                    class="webhooks-tab__hit"
                    :data-testid="`webhook-toggle-${row.webhookId ?? ''}`"
                    :disabled="busyIds[row.webhookId ?? ''] === true"
                    :aria-label="`${
                      row.enabled === true
                        ? t('settings.webhooks.disable')
                        : t('settings.webhooks.enable')
                    } ${hostFor(row.payloadUrl)}`"
                    @click="onToggle(row)"
                  >
                    {{
                      row.enabled === true
                        ? t("settings.webhooks.disable")
                        : t("settings.webhooks.enable")
                    }}
                  </el-button>
                  <el-button
                    class="webhooks-tab__hit"
                    :data-testid="`webhook-deliveries-${row.webhookId ?? ''}`"
                    :aria-label="`${t('settings.webhooks.deliveries')} ${hostFor(row.payloadUrl)}`"
                    @click="openDeliveries(row)"
                  >
                    {{ t("settings.webhooks.deliveries") }}
                  </el-button>
                  <el-button
                    type="danger"
                    plain
                    class="webhooks-tab__hit"
                    :data-testid="`webhook-delete-${row.webhookId ?? ''}`"
                    :disabled="busyIds[row.webhookId ?? ''] === true"
                    :aria-label="`${t('settings.webhooks.delete')} ${hostFor(row.payloadUrl)}`"
                    @click="onDelete(row)"
                  >
                    {{ t("settings.webhooks.delete") }}
                  </el-button>
                </div>
              </template>
            </el-table-column>
          </el-table>
        </template>
      </template>
    </template>

    <div
      v-else
      role="status"
      aria-live="polite"
    >
      {{ t("settings.webhooks.loading") }}
    </div>

    <WebhookFormDialog
      v-model="formOpen"
      :project-id="projectId"
      :webhook="editing"
      :can-manage="canManage"
    />
    <WebhookDeliveriesDrawer
      v-model="deliveriesOpen"
      :project-id="projectId"
      :webhook-id="deliveriesWebhookId"
      :can-manage="canManage"
    />
  </div>
</template>

<style scoped>
.webhooks-tab {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-4, 16px);
  max-width: 64rem;
}

.webhooks-tab__hint {
  margin: 0;
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
  font-size: var(--wp-font-size-sm, 12px);
}

.webhooks-tab__toolbar {
  display: flex;
  justify-content: flex-end;
}

.webhooks-tab__actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wp-space-2, 8px);
}

.webhooks-tab__hit {
  min-height: 32px;
  min-width: 32px;
}
</style>
