<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed } from "vue";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import HierarchyTree from "./HierarchyTree.vue";

defineProps<{
  projectId: string;
  /** Passed by HierarchyPanel; this panel always shows the class hierarchy. */
  kind?: string;
}>();

const { entityIri, selectEntity } = useWorkspaceRoute();
const selectedIri = computed(() => entityIri.value);

function onSelect(node: EntityNode): void {
  selectEntity(node.iri);
}
</script>

<template>
  <HierarchyTree
    :project-id="projectId"
    kind="class"
    :selected-iri="selectedIri"
    @select="onSelect"
  />
</template>
