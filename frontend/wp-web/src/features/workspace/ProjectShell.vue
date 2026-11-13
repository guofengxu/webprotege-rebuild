<script setup lang="ts">
import { useQuery } from "@tanstack/vue-query";
import { computed, nextTick, provide, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute } from "vue-router";
import {
  projectEventsConnectionKey,
  useProjectEvents,
} from "@/features/events/useProjectEvents";
import { useApiClient } from "@/shared/api/client";
import { ApiError, problemMessage, unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

const { t } = useI18n();
const route = useRoute();
const client = useApiClient();
const errorHeading = ref<HTMLElement | null>(null);

const projectId = computed(() => {
  const raw = route.params.projectId;
  return typeof raw === "string" && raw.length > 0 ? raw : undefined;
});

const projectQuery = useQuery({
  queryKey: computed(() =>
    projectId.value === undefined
      ? (["project-shell", null] as const)
      : queryKeys.projectDetails(projectId.value),
  ),
  enabled: computed(() => projectId.value !== undefined),
  queryFn: async () => {
    const id = projectId.value;
    if (id === undefined) {
      throw new Error("projectId is required");
    }
    return unwrap(
      await client.GET("/api/projects/{id}", {
        params: { path: { id } },
      }),
    );
  },
});

const errorMessage = computed(() => {
  const error = projectQuery.error.value;
  if (error === null) {
    return "";
  }
  if (error instanceof ApiError) {
    if (error.status === 409 && error.code === "PROJECT_TRASHED") {
      return t("workspace.errors.trashed");
    }
    if (error.status === 403) {
      return t("workspace.errors.forbidden");
    }
    if (error.status === 404) {
      return t("workspace.errors.notFound");
    }
  }
  return problemMessage(error, t);
});

const showError = computed(() => projectQuery.isError.value);
const showWorkspace = computed(
  () => projectQuery.isSuccess.value && !projectQuery.isError.value,
);

const eventsProjectId = computed(() => projectId.value ?? "");
const eventsEnabled = computed(
  () => projectQuery.isSuccess.value && projectId.value !== undefined,
);
const { status, stopReason, retry } = useProjectEvents(
  eventsProjectId,
  eventsEnabled,
);
provide(projectEventsConnectionKey, { status, stopReason, retry });

watch(showError, async (isError) => {
  if (!isError) {
    return;
  }
  await nextTick();
  errorHeading.value?.focus();
});
</script>

<template>
  <div
    v-if="showError"
    class="project-shell-error"
    role="alert"
  >
    <h1
      ref="errorHeading"
      class="project-shell-error__message"
      tabindex="-1"
    >
      {{ errorMessage }}
    </h1>
    <RouterLink
      class="project-shell-error__back"
      to="/projects"
    >
      {{ t("workspace.backToProjects") }}
    </RouterLink>
  </div>
  <RouterView v-else-if="showWorkspace" />
  <p
    v-else
    class="project-shell-loading"
    role="status"
  >
    {{ t("workspace.loading") }}
  </p>
</template>

<style scoped>
.project-shell-error {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  align-items: flex-start;
  padding: 2rem;
}

.project-shell-error__message {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.project-shell-error__message:focus {
  outline: none;
}

.project-shell-error__message:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.project-shell-error__back {
  color: var(--el-color-primary);
  padding: 0.25rem 0.125rem;
  min-height: 24px;
}

.project-shell-error__back:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.project-shell-loading {
  margin: 0;
  padding: 2rem;
}
</style>
