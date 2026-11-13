<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { RouterLink, type RouteLocationRaw } from "vue-router";
import type { SearchType } from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import EntityPicker from "./EntityPicker.vue";

const props = withDefaults(
  defineProps<{
    projectId: string;
    types: SearchType[];
    modelValue: EntityNode[];
    builtins?: readonly EntityNode[];
    allowRawIri?: boolean;
    excludeIris?: readonly string[];
    ariaLabel: string;
    disabled?: boolean;
  }>(),
  {
    builtins: () => [],
    allowRawIri: false,
    excludeIris: () => [],
    disabled: false,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: EntityNode[]];
}>();

const { t } = useI18n();
const { linkTo } = useWorkspaceRoute();
const draft = ref<EntityNode | null>(null);

type TagItem = {
  readonly node: EntityNode;
  readonly index: number;
  readonly to: RouteLocationRaw | null;
};

const tags = computed((): TagItem[] =>
  props.modelValue.map((node, index) => ({
    node,
    index,
    to: linkTo(node.entityType, node.iri),
  })),
);

function removeAt(index: number): void {
  emit(
    "update:modelValue",
    props.modelValue.filter((_, i) => i !== index),
  );
}

function onPick(node: EntityNode | null): void {
  draft.value = null;
  if (node === null) {
    return;
  }
  if (props.modelValue.some((entry) => entry.iri === node.iri)) {
    return;
  }
  emit("update:modelValue", [...props.modelValue, node]);
}
</script>

<template>
  <div
    class="entity-ref-list"
    role="group"
    :aria-label="ariaLabel"
  >
    <div
      v-if="modelValue.length === 0"
      class="entity-ref-list__empty"
    >
      {{ t("framesInputs.emptyList") }}
    </div>
    <div
      v-else
      class="entity-ref-list__tags"
    >
      <el-tag
        v-for="entry in tags"
        :key="entry.node.iri"
        :closable="!disabled"
        class="entity-ref-list__tag"
        :class="{ 'entity-ref-list__tag--deprecated': entry.node.deprecated }"
        @close="removeAt(entry.index)"
      >
        <RouterLink
          v-if="entry.to !== null"
          class="entity-ref-list__link"
          :to="entry.to"
        >
          {{ entry.node.displayName }}
        </RouterLink>
        <span v-else>{{ entry.node.displayName }}</span>
      </el-tag>
    </div>
    <EntityPicker
      :project-id="projectId"
      :types="types"
      :model-value="draft"
      :builtins="builtins"
      :allow-raw-iri="allowRawIri"
      :exclude-iris="excludeIris"
      :disabled="disabled"
      v-bind="{ ariaLabel }"
      @update:model-value="onPick"
    />
  </div>
</template>

<style scoped>
.entity-ref-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.entity-ref-list__tags {
  display: flex;
  flex-wrap: wrap;
  gap: 0.35rem;
}

.entity-ref-list__tag--deprecated :deep(span),
.entity-ref-list__tag--deprecated .entity-ref-list__link {
  text-decoration: line-through;
}

.entity-ref-list__link {
  color: inherit;
  text-decoration: none;
}

.entity-ref-list__link:hover {
  text-decoration: underline;
}

.entity-ref-list__empty {
  color: var(--el-text-color-secondary);
  font-size: 0.875rem;
}
</style>
