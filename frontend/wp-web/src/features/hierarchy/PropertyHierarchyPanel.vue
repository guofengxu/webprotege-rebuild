<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref } from "vue";
import EntityContextMenu, {
  type ContextMenuTarget,
} from "@/features/entities/EntityContextMenu.vue";
import type { HierarchyKind } from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import HierarchyTree from "./HierarchyTree.vue";

export type PropertyHierarchyKind = Exclude<HierarchyKind, "class">;

const props = defineProps<{
  projectId: string;
  kind: PropertyHierarchyKind;
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
  <div class="property-hierarchy-panel">
    <HierarchyTree
      :project-id="props.projectId"
      :kind="props.kind"
      :selected-iri="selectedIri"
      @select="onSelect"
      @contextmenu="onContextmenu"
    />
    <EntityContextMenu
      :project-id="props.projectId"
      :kind="props.kind"
      :target="menuTarget"
      @close="onMenuClose"
    />
  </div>
</template>

<style scoped>
.property-hierarchy-panel {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
</style>

