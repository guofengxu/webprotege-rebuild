<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { kindToEntityType } from "./hierarchyKinds";
import { useWorkspaceRegistry } from "./registry";
import { useWorkspaceRoute } from "./useWorkspaceRoute";

const { t } = useI18n();
const { projectId, kind, entityIri } = useWorkspaceRoute();
const registry = useWorkspaceRegistry();

const entityType = computed(() => kindToEntityType(kind.value));
const editor = computed(
  () => registry.detailEditors[entityType.value] ?? null,
);
const hasEntity = computed(() => entityIri.value !== null);
</script>

<template>
  <section
    class="entity-detail-panel"
    :aria-label="t('workspace.detailPanel')"
  >
    <el-empty
      v-if="!hasEntity"
      :description="t('workspace.noEntitySelected')"
    />
    <el-tabs
      v-else
      class="entity-detail-panel__tabs"
    >
      <el-tab-pane
        name="detail"
        :label="t('workspace.detailTab')"
      >
        <component
          :is="editor"
          v-if="editor !== null && entityIri !== null"
          :key="entityIri"
          :project-id="projectId"
          :iri="entityIri"
        />
        <div
          v-else-if="entityIri !== null"
          class="entity-detail-panel__fallback"
        >
          <p class="entity-detail-panel__iri">
            <span class="entity-detail-panel__iri-label">
              {{ t("workspace.entityIri") }}
            </span>
            {{ entityIri }}
          </p>
          <p class="entity-detail-panel__hint">
            {{ t("workspace.notImplemented") }}
          </p>
        </div>
      </el-tab-pane>
      <el-tab-pane
        v-for="tab in registry.detailTabs"
        :key="tab.key"
        :name="tab.key"
        :label="t(tab.labelKey)"
      >
        <component
          :is="tab.component"
          v-if="entityIri !== null"
          :key="`${tab.key}:${entityIri}`"
          :project-id="projectId"
          :iri="entityIri"
        />
      </el-tab-pane>
    </el-tabs>
  </section>
</template>

<style scoped>
.entity-detail-panel {
  height: 100%;
  min-height: 0;
  overflow: auto;
  padding: 0.5rem;
}

.entity-detail-panel__fallback {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.entity-detail-panel__iri {
  margin: 0;
  word-break: break-all;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.entity-detail-panel__iri-label {
  display: block;
  margin-bottom: 0.25rem;
  font-family: inherit;
  color: var(--el-text-color-secondary);
}

.entity-detail-panel__hint {
  margin: 0;
  color: var(--el-text-color-secondary);
}
</style>
