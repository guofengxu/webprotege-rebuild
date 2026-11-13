<script setup lang="ts">
import { computed, inject, nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  projectEventsConnectionKey,
  type ProjectEventsStatus,
} from "./useProjectEvents";

defineProps<{
  projectId?: string;
}>();

const { t } = useI18n();
const connection = inject(projectEventsConnectionKey, {
  status: ref<ProjectEventsStatus>("idle"),
  stopReason: ref<string | null>(null),
  retry: () => undefined,
});

const statusTextRef = ref<HTMLElement | null>(null);
const messageId = "project-events-connection-status";

const showChrome = computed(
  () =>
    connection.status.value === "reconnecting" ||
    connection.status.value === "connecting" ||
    connection.status.value === "stopped",
);

const liveMessage = computed(() => {
  switch (connection.status.value) {
    case "connecting":
      return t("events.connecting");
    case "reconnecting":
      return t("events.reconnecting");
    case "stopped":
      return stopReasonLabel.value;
    default:
      return "";
  }
});

const stopReasonLabel = computed(() => {
  const reason = connection.stopReason.value;
  switch (reason) {
    case "forbidden":
      return t("events.stopReasons.forbidden");
    case "notFound":
      return t("events.stopReasons.notFound");
    case "conflict":
      return t("events.stopReasons.conflict");
    case "unauthorized":
      return t("events.stopReasons.unauthorized");
    default:
      return t("events.stopped");
  }
});

async function onRetry(): Promise<void> {
  connection.retry();
  await nextTick();
  statusTextRef.value?.focus();
}

watch(
  () => connection.status.value,
  async (next, previous) => {
    if (previous === "stopped" && next !== "stopped") {
      await nextTick();
      statusTextRef.value?.focus();
    }
  },
);
</script>

<template>
  <div
    class="connection-status"
    :class="{ 'connection-status--hidden': !showChrome }"
  >
    <span
      :id="messageId"
      ref="statusTextRef"
      class="connection-status__text"
      role="status"
      aria-live="polite"
      aria-atomic="true"
      tabindex="-1"
    >
      {{ liveMessage }}
    </span>
    <button
      v-if="connection.status.value === 'stopped'"
      type="button"
      class="connection-status__retry"
      :aria-describedby="messageId"
      @click="onRetry"
    >
      {{ t("events.retry") }}
    </button>
  </div>
</template>

<style scoped>
.connection-status {
  display: inline-flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: var(--el-text-color-regular);
}

.connection-status--hidden {
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

.connection-status__text {
  outline: none;
}

.connection-status__text:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.connection-status__retry {
  margin: 0;
  padding: 0.125rem 0.5rem;
  min-height: 24px;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
}

.connection-status__retry:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}
</style>
