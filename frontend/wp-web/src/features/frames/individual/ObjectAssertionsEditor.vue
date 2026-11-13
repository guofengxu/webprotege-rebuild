<script setup lang="ts">
import type { EntityNode, NamedIndividualFrame } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import EntityPicker from "../EntityPicker.vue";

type ObjectPropertyAssertion = NonNullable<
  NamedIndividualFrame["objectPropertyAssertions"]
>[number];

const props = defineProps<{
  projectId: string;
  modelValue: ObjectPropertyAssertion[];
  valid: boolean;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: ObjectPropertyAssertion[]];
  "update:valid": [value: boolean];
}>();

const { t } = useI18n();

let nextRowKey = 1;
const rowKeys = ref<string[]>([]);

watch(
  () => props.modelValue.length,
  (length) => {
    while (rowKeys.value.length < length) {
      rowKeys.value = [...rowKeys.value, `oa-${nextRowKey++}`];
    }
    if (rowKeys.value.length > length) {
      rowKeys.value = rowKeys.value.slice(0, length);
    }
  },
  { immediate: true },
);

function isRowComplete(row: ObjectPropertyAssertion): boolean {
  const propertyIri = row.property?.iri ?? "";
  const individualIri = row.individual?.iri ?? "";
  return propertyIri.length > 0 && individualIri.length > 0;
}

const rowsComplete = computed(() => props.modelValue.every(isRowComplete));

watch(
  rowsComplete,
  (next) => {
    if (next !== props.valid) {
      emit("update:valid", next);
    }
  },
  { immediate: true },
);

function replaceAt(index: number, next: ObjectPropertyAssertion): void {
  emit(
    "update:modelValue",
    props.modelValue.map((entry, i) => (i === index ? next : entry)),
  );
}

function removeAt(index: number): void {
  rowKeys.value = rowKeys.value.filter((_, i) => i !== index);
  emit(
    "update:modelValue",
    props.modelValue.filter((_, i) => i !== index),
  );
}

function addRow(): void {
  rowKeys.value = [...rowKeys.value, `oa-${nextRowKey++}`];
  emit("update:modelValue", [
    ...props.modelValue,
    { property: undefined, individual: undefined },
  ]);
}

function setProperty(index: number, property: EntityNode | null): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  replaceAt(index, {
    ...current,
    property: property ?? undefined,
  });
}

function setIndividual(index: number, individual: EntityNode | null): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  replaceAt(index, {
    ...current,
    individual: individual ?? undefined,
  });
}

function rowLabel(index: number): string {
  return t("framesIndividual.objectAssertionRow", {
    index: index + 1,
    total: props.modelValue.length,
  });
}
</script>

<template>
  <div
    class="object-assertions-editor"
    role="group"
    :aria-label="t('framesIndividual.objectAssertions')"
  >
    <div class="object-assertions-editor__toolbar">
      <el-button
        data-testid="add-object-assertion"
        :disabled="disabled"
        @click="addRow"
      >
        {{ t("framesIndividual.addObjectAssertion") }}
      </el-button>
    </div>

    <div
      v-for="(row, index) in modelValue"
      :key="rowKeys[index] ?? `oa-fallback-${index}`"
      class="object-assertions-editor__row"
      role="group"
      :aria-label="rowLabel(index)"
      :data-testid="`object-assertion-row-${index}`"
    >
      <div class="object-assertions-editor__field">
        <span class="object-assertions-editor__label">
          {{ t("framesIndividual.property") }}
        </span>
        <EntityPicker
          :project-id="projectId"
          :types="['object-property']"
          :model-value="row.property ?? null"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesIndividual.property') }"
          @update:model-value="(value) => setProperty(index, value)"
        />
      </div>

      <div class="object-assertions-editor__field">
        <span class="object-assertions-editor__label">
          {{ t("framesIndividual.individual") }}
        </span>
        <EntityPicker
          :project-id="projectId"
          :types="['named-individual']"
          :model-value="row.individual ?? null"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesIndividual.individual') }"
          @update:model-value="(value) => setIndividual(index, value)"
        />
      </div>

      <el-button
        :disabled="disabled"
        :data-testid="`remove-object-assertion-${index}`"
        :aria-label="
          t('framesIndividual.removeObjectAssertionRow', { index: index + 1 })
        "
        @click="removeAt(index)"
      >
        {{ t("framesIndividual.removeAssertion") }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.object-assertions-editor {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.object-assertions-editor__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.object-assertions-editor__row {
  display: grid;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}

.object-assertions-editor__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.object-assertions-editor__label {
  font-size: 0.75rem;
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
}

.object-assertions-editor :deep(.el-button:focus-visible),
.object-assertions-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
