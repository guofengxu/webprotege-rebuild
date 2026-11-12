<script setup lang="ts">
import { useI18n } from "vue-i18n";
import EntityDetailPanel from "./EntityDetailPanel.vue";
import HierarchyPanel from "./HierarchyPanel.vue";
import { useWorkspaceRegistry } from "./registry";
import { useWorkspaceRoute } from "./useWorkspaceRoute";
import { useWorkspaceStore } from "./workspaceStore";

const { t } = useI18n();
const { projectId } = useWorkspaceRoute();
const registry = useWorkspaceRegistry();
const layout = useWorkspaceStore();

function onResizeEnd(_index: number, sizes: number[]): void {
  const left = sizes[0];
  if (typeof left === "number" && Number.isFinite(left) && left > 0) {
    layout.setLeftSize(left);
    layout.setLeftCollapsed(false);
  }
}

function onCollapse(
  index: number,
  _type: "start" | "end",
  sizes: number[],
): void {
  if (index !== 0) {
    return;
  }
  const left = sizes[0] ?? 0;
  layout.setLeftCollapsed(left === 0);
  if (left > 0) {
    layout.setLeftSize(left);
  }
}
</script>

<template>
  <div class="workspace-page">
    <header class="workspace-page__toolbar">
      <div class="workspace-page__toolbar-spacer" />
      <nav
        class="workspace-page__toolbar-end"
        :aria-label="t('workspace.projectNav')"
      >
        <component
          :is="item"
          v-for="(item, index) in registry.workspaceToolbar"
          :key="index"
          :project-id="projectId"
        />
        <RouterLink
          class="workspace-page__link"
          :to="`/projects/${projectId}/history`"
        >
          {{ t("workspace.revisionHistory") }}
        </RouterLink>
        <RouterLink
          class="workspace-page__link"
          :to="`/projects/${projectId}/settings`"
        >
          {{ t("workspace.projectSettings") }}
        </RouterLink>
      </nav>
    </header>

    <el-splitter
      class="workspace-page__splitter"
      @resize-end="onResizeEnd"
      @collapse="onCollapse"
    >
      <el-splitter-panel
        :size="layout.leftCollapsed ? 0 : layout.leftSize"
        :min="16"
        :collapsible="true"
      >
        <HierarchyPanel />
      </el-splitter-panel>
      <el-splitter-panel :min="30">
        <EntityDetailPanel />
      </el-splitter-panel>
    </el-splitter>
  </div>
</template>

<style scoped>
.workspace-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.workspace-page__toolbar {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.workspace-page__toolbar-spacer {
  flex: 1 1 auto;
}

.workspace-page__toolbar-end {
  display: flex;
  flex-wrap: wrap;
  gap: 0.75rem;
  align-items: center;
}

.workspace-page__link {
  color: var(--el-color-primary);
  padding: 0.25rem 0.125rem;
  min-height: 24px;
}

.workspace-page__link:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: 2px;
}

.workspace-page__splitter {
  flex: 1 1 auto;
  min-height: 0;
}
</style>
