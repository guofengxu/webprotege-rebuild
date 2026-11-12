<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref } from "vue";
import EntityContextMenu, {
  type ContextMenuTarget,
} from "@/features/entities/EntityContextMenu.vue";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import HierarchyTree from "./HierarchyTree.vue";

defineProps<{
  projectId: string;
  /** Passed by HierarchyPanel; this panel always shows the class hierarchy. */
  kind?: string;
}>();

const { entityIri, selectEntity } = useWorkspaceRoute();
const selectedIri = computed(() => entityIri.value);
const menuTarget = ref<ContextMenuTarget | null>(null);

function onSelect(node: EntityNode): void {
  selectEntity(node.iri);
}

function onContextmenu(node: EntityNode, event: MouseEvent): void {
  menuTarget.value = {
    node,
    clientX: event.clientX,
    clientY: event.clientY,
    source: "tree",
  };
}

function onMenuClose(): void {
  menuTarget.value = null;
}
</script>

<template>
  <div class="class-hierarchy-panel">
    <HierarchyTree
      :project-id="projectId"
      kind="class"
      :selected-iri="selectedIri"
      @select="onSelect"
      @contextmenu="onContextmenu"
    />
    <EntityContextMenu
      :project-id="projectId"
      kind="class"
      :target="menuTarget"
      @close="onMenuClose"
    />
  </div>
</template>

<style scoped>
.class-hierarchy-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
</style>

