<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useProjectPermissions } from "@/features/permissions/useProjectPermissions";
import type { WorkspaceKind } from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { isBuiltInEntityIri } from "./builtInEntities";
import MergeEntitiesDialog from "./MergeEntitiesDialog.vue";

const props = defineProps<{
  projectId: string;
  kind: WorkspaceKind;
}>();

const { t } = useI18n();
const { entityIri } = useWorkspaceRoute();
const { permission } = useProjectPermissions(() => props.projectId);
const mergePermission = permission("MERGE_ENTITIES");

const dialogOpen = ref(false);

const visible = computed(() => {
  if (mergePermission.value !== "allowed") {
    return false;
  }
  const iri = entityIri.value;
  if (iri === null || iri.length === 0) {
    return false;
  }
  return !isBuiltInEntityIri(iri);
});

const sourceIri = computed(() => entityIri.value ?? "");

function onOpen(): void {
  if (!visible.value) {
    return;
  }
  dialogOpen.value = true;
}
</script>

<template>
  <div
    v-if="visible"
    class="merge-entities-button"
  >
    <el-button
      plain
      aria-haspopup="dialog"
      :aria-expanded="dialogOpen"
      @click="onOpen"
    >
      {{ t("mergeEntities.action") }}
    </el-button>
    <MergeEntitiesDialog
      v-model="dialogOpen"
      :project-id="projectId"
      :kind="kind"
      :source-iri="sourceIri"
    />
  </div>
</template>

<style scoped>
.merge-entities-button {
  display: inline-flex;
  align-items: center;
}
</style>
