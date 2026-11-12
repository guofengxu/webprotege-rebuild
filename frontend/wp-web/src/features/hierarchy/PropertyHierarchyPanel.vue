<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed } from "vue";
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

function onSelect(node: EntityNode): void {
  selectEntity(node.iri);
}
</script>

<template>
  <HierarchyTree
    :project-id="props.projectId"
    :kind="props.kind"
    :selected-iri="selectedIri"
    @select="onSelect"
  />
</template>
