<script setup lang="ts">
import { ElMessageBox } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { onBeforeRouteLeave, useRoute, useRouter } from "vue-router";
import { settingsTabs } from "./settingsTabs";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();

const projectId = computed(() => {
  const raw = route.params.projectId;
  return typeof raw === "string" ? raw : "";
});

const tabs = settingsTabs;
const firstTabKey = tabs[0]?.key ?? "general";
const activeTab = ref(resolveTabKey(route.query.tab));
const isDirty = ref(false);
const syncingTabFromRoute = ref(false);
let routeTabSyncId = 0;

watch(
  () => route.query.tab,
  (tab) => {
    const next = resolveTabKey(tab);
    if (next === activeTab.value) {
      return;
    }
    const syncId = ++routeTabSyncId;
    void (async () => {
      const ok = await confirmLeaveIfDirty();
      if (syncId !== routeTabSyncId) {
        return;
      }
      if (!ok) {
        syncingTabFromRoute.value = true;
        await router.replace({
          query: { ...route.query, tab: activeTab.value },
        });
        syncingTabFromRoute.value = false;
        return;
      }
      syncingTabFromRoute.value = true;
      activeTab.value = next;
      syncingTabFromRoute.value = false;
    })();
  },
);

watch(
  activeTab,
  (tab) => {
    if (syncingTabFromRoute.value) {
      return;
    }
    if (route.query.tab === tab) {
      return;
    }
    void router.replace({
      query: { ...route.query, tab },
    });
  },
  { immediate: true },
);

function resolveTabKey(raw: unknown): string {
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value === "string" && tabs.some((tab) => tab.key === value)) {
    return value;
  }
  return firstTabKey;
}

function onDirty(value: boolean): void {
  isDirty.value = value;
}

async function confirmLeaveIfDirty(): Promise<boolean> {
  if (!isDirty.value) {
    return true;
  }
  try {
    await ElMessageBox.confirm(
      t("settings.unsavedMessage"),
      t("settings.unsavedTitle"),
      {
        confirmButtonText: t("settings.unsavedConfirm"),
        cancelButtonText: t("settings.unsavedCancel"),
        type: "warning",
      },
    );
    isDirty.value = false;
    return true;
  } catch {
    return false;
  }
}

async function beforeLeave(): Promise<boolean> {
  return confirmLeaveIfDirty();
}

onBeforeRouteLeave(() => confirmLeaveIfDirty());
</script>

<template>
  <div class="project-settings">
    <h1 class="project-settings__title">
      {{ t("settings.pageTitle") }}
    </h1>
    <el-tabs
      v-model="activeTab"
      tab-position="left"
      class="project-settings__tabs"
      :before-leave="beforeLeave"
    >
      <el-tab-pane
        v-for="tab in tabs"
        :key="tab.key"
        :name="tab.key"
        :label="t(tab.labelKey)"
      >
        <component
          :is="tab.component"
          :project-id="projectId"
          @update:dirty="onDirty"
        />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.project-settings {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-4);
  padding: var(--wp-space-4);
  min-height: 0;
}

.project-settings__title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.project-settings__tabs {
  min-height: 24rem;
}

.project-settings__tabs :deep(.el-tabs__content) {
  padding-left: var(--wp-space-4);
}
</style>
