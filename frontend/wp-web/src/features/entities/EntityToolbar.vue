<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  kindToEntityType,
  type WorkspaceKind,
} from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useDeleteEntities } from "./api";
import CreateEntitiesDialog from "./CreateEntitiesDialog.vue";
import { confirmDelete } from "./DeleteEntitiesConfirm";
import { iriLocalName } from "./iriLocalName";

const props = defineProps<{
  projectId: string;
  kind: WorkspaceKind;
}>();

const { t } = useI18n();
const { entityIri, typeIri, selectEntity } = useWorkspaceRoute();
const deleteEntities = useDeleteEntities(() => props.projectId);

const createOpen = ref(false);

const createDisabled = computed(() => {
  if (props.kind !== "individual") {
    return false;
  }
  return typeIri.value === null || typeIri.value.length === 0;
});

const deleteDisabled = computed(() => {
  return entityIri.value === null || entityIri.value.length === 0;
});

const createEntityType = computed(() => kindToEntityType(props.kind));

const createParent = computed(() => {
  if (props.kind === "individual") {
    const type = typeIri.value;
    if (type === null || type.length === 0) {
      return undefined;
    }
    return { iri: type, label: iriLocalName(type) };
  }
  const entity = entityIri.value;
  if (entity === null || entity.length === 0) {
    return undefined;
  }
  return { iri: entity, label: iriLocalName(entity) };
});

function onCreate(): void {
  if (createDisabled.value) {
    return;
  }
  createOpen.value = true;
}

async function onDelete(): Promise<void> {
  const iri = entityIri.value;
  if (iri === null || iri.length === 0) {
    return;
  }
  const items = [{ iri, label: iriLocalName(iri) }];
  const confirmed = await confirmDelete(items, t);
  if (!confirmed) {
    return;
  }
  try {
    await deleteEntities.mutateAsync({ iris: [iri] });
    selectEntity(null);
  } catch (error) {
    if (error instanceof ApiError && error.code === "PERMISSION_DENIED") {
      ElMessage.error(problemMessage(error, t));
      return;
    }
    ElMessage.error(problemMessage(error, t));
  }
}
</script>

<template>
  <div
    class="entity-toolbar"
    role="toolbar"
    :aria-label="t('entities.menuLabel')"
  >
    <el-button
      type="primary"
      :disabled="createDisabled"
      @click="onCreate"
    >
      {{ t("entities.create") }}
    </el-button>
    <el-button
      type="danger"
      plain
      :disabled="deleteDisabled"
      @click="onDelete"
    >
      {{ t("entities.delete") }}
    </el-button>

    <CreateEntitiesDialog
      v-model="createOpen"
      :project-id="projectId"
      :entity-type="createEntityType"
      :parent="createParent"
    />
  </div>
</template>

<style scoped>
.entity-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  align-items: center;
}
</style>
