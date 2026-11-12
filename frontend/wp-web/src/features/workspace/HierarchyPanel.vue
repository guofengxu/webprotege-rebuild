<script setup lang="ts">
import { useI18n } from "vue-i18n";
import {
  WORKSPACE_KINDS,
  isWorkspaceKind,
  type WorkspaceKind,
} from "./hierarchyKinds";
import { useWorkspaceRegistry } from "./registry";
import { useWorkspaceRoute } from "./useWorkspaceRoute";

const { t } = useI18n();
const { projectId, kind, setKind } = useWorkspaceRoute();
const registry = useWorkspaceRegistry();

function onTabChange(name: string | number): void {
  if (typeof name === "string" && isWorkspaceKind(name)) {
    setKind(name);
  }
}

function panelFor(tabKind: WorkspaceKind) {
  return registry.hierarchyPanels[tabKind] ?? null;
}
</script>

<template>
  <section
    class="hierarchy-panel"
    :aria-label="t('workspace.hierarchyPanel')"
  >
    <el-tabs
      :model-value="kind"
      class="hierarchy-panel__tabs"
      @tab-change="onTabChange"
    >
      <el-tab-pane
        v-for="tabKind in WORKSPACE_KINDS"
        :key="tabKind"
        :name="tabKind"
        :label="t(`workspace.kinds.${tabKind}`)"
        lazy
      >
        <div class="hierarchy-panel__toolbar">
          <component
            :is="toolbar"
            v-for="(toolbar, index) in registry.hierarchyToolbar"
            :key="index"
            :project-id="projectId"
            :kind="tabKind"
          />
        </div>
        <div class="hierarchy-panel__body">
          <component
            :is="panelFor(tabKind)"
            v-if="panelFor(tabKind) !== null"
            :project-id="projectId"
            :kind="tabKind"
          />
          <el-empty
            v-else
            :description="t('workspace.notImplemented')"
          />
        </div>
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.hierarchy-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.hierarchy-panel__tabs {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  min-height: 0;
}

.hierarchy-panel__tabs :deep(.el-tabs__content),
.hierarchy-panel__tabs :deep(.el-tab-pane) {
  height: 100%;
}

.hierarchy-panel__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  padding: 0 0.5rem;
}

.hierarchy-panel__body {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 0.5rem;
}
</style>
