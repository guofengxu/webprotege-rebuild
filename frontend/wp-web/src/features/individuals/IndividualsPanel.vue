<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref } from "vue";
import { useRoute, useRouter, type LocationQuery } from "vue-router";
import EntityContextMenu, {
  type ContextMenuTarget,
} from "@/features/entities/EntityContextMenu.vue";
import HierarchyTree from "@/features/hierarchy/HierarchyTree.vue";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import IndividualList from "./IndividualList.vue";

defineProps<{
  projectId: string;
  /** Passed by HierarchyPanel; this panel always shows individuals. */
  kind?: string;
}>();

const route = useRoute();
const router = useRouter();
const { entityIri, typeIri, selectEntity } = useWorkspaceRoute();
const selectedTypeIri = computed(() => typeIri.value);
const selectedEntityIri = computed(() => entityIri.value);
const menuTarget = ref<ContextMenuTarget | null>(null);

function onSelectType(node: EntityNode): void {
  const next: LocationQuery = { ...route.query, type: node.iri };
  delete next.entity;
  void router.replace({ query: next });
}

function onSelectEntity(node: EntityNode): void {
  selectEntity(node.iri);
}

function onTreeContextmenu(node: EntityNode, event: MouseEvent): void {
  menuTarget.value = {
    node,
    clientX: event.clientX,
    clientY: event.clientY,
    source: "tree",
  };
}

function onRowContextmenu(node: EntityNode, event: MouseEvent): void {
  menuTarget.value = {
    node,
    clientX: event.clientX,
    clientY: event.clientY,
    source: "individual-row",
  };
}

function onMenuClose(): void {
  menuTarget.value = null;
}
</script>

<template>
  <div class="individuals-panel">
    <div class="individuals-panel__tree">
      <HierarchyTree
        :project-id="projectId"
        kind="class"
        :selected-iri="selectedTypeIri"
        @select="onSelectType"
        @contextmenu="onTreeContextmenu"
      />
    </div>
    <div class="individuals-panel__list">
      <IndividualList
        :project-id="projectId"
        :type-iri="selectedTypeIri"
        :selected-iri="selectedEntityIri"
        @select="onSelectEntity"
        @row-contextmenu="onRowContextmenu"
      />
    </div>
    <EntityContextMenu
      :project-id="projectId"
      kind="individual"
      :target="menuTarget"
      @close="onMenuClose"
    />
  </div>
</template>


<style scoped>
.individuals-panel {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  height: 100%;
  min-height: 0;
}

.individuals-panel__tree {
  flex: 1 1 50%;
  min-height: 0;
  overflow: hidden;
}

.individuals-panel__list {
  flex: 1 1 50%;
  min-height: 0;
  overflow: auto;
}
</style>
