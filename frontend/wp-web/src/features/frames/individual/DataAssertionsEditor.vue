<script setup lang="ts">
import type {
  EntityNode,
  LiteralValue,
  NamedIndividualFrame,
} from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { DEFAULT_DATATYPE } from "../builtins";
import { literalValue } from "../frameModel";
import EntityPicker from "../EntityPicker.vue";
import LiteralValueInput from "../LiteralValueInput.vue";

type DataPropertyAssertion = NonNullable<
  NamedIndividualFrame["dataPropertyAssertions"]
>[number];

const props = defineProps<{
  projectId: string;
  modelValue: DataPropertyAssertion[];
  valid: boolean;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: DataPropertyAssertion[]];
  "update:valid": [value: boolean];
}>();

const { t } = useI18n();

let nextRowKey = 1;
const rowKeys = ref<string[]>([]);

watch(
  () => props.modelValue.length,
  (length) => {
    while (rowKeys.value.length < length) {
      rowKeys.value = [...rowKeys.value, `da-${nextRowKey++}`];
    }
    if (rowKeys.value.length > length) {
      rowKeys.value = rowKeys.value.slice(0, length);
    }
  },
  { immediate: true },
);

function emptyLiteral(): LiteralValue {
  return literalValue("", DEFAULT_DATATYPE.iri);
}

function isRowComplete(row: DataPropertyAssertion): boolean {
  const propertyIri = row.property?.iri ?? "";
  const lexical = row.value?.lexical ?? "";
  const datatype = row.value?.datatype ?? "";
  return propertyIri.length > 0 && lexical.length > 0 && datatype.length > 0;
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

function replaceAt(index: number, next: DataPropertyAssertion): void {
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
  rowKeys.value = [...rowKeys.value, `da-${nextRowKey++}`];
  emit("update:modelValue", [
    ...props.modelValue,
    { property: undefined, value: emptyLiteral() },
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

function setValue(index: number, value: LiteralValue): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  replaceAt(index, { ...current, value });
}

function literalOf(row: DataPropertyAssertion): LiteralValue {
  return row.value ?? emptyLiteral();
}

function rowLabel(index: number): string {
  return t("framesIndividual.dataAssertionRow", {
    index: index + 1,
    total: props.modelValue.length,
  });
}
</script>

<template>
  <div
    class="data-assertions-editor"
    role="group"
    :aria-label="t('framesIndividual.dataAssertions')"
  >
    <div class="data-assertions-editor__toolbar">
      <el-button
        data-testid="add-data-assertion"
        :disabled="disabled"
        @click="addRow"
      >
        {{ t("framesIndividual.addDataAssertion") }}
      </el-button>
    </div>

    <div
      v-for="(row, index) in modelValue"
      :key="rowKeys[index] ?? `da-fallback-${index}`"
      class="data-assertions-editor__row"
      role="group"
      :aria-label="rowLabel(index)"
      :data-testid="`data-assertion-row-${index}`"
    >
      <div class="data-assertions-editor__field">
        <span class="data-assertions-editor__label">
          {{ t("framesIndividual.property") }}
        </span>
        <EntityPicker
          :project-id="projectId"
          :types="['data-property']"
          :model-value="row.property ?? null"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesIndividual.property') }"
          @update:model-value="(value) => setProperty(index, value)"
        />
      </div>

      <div class="data-assertions-editor__field">
        <span class="data-assertions-editor__label">
          {{ t("framesIndividual.value") }}
        </span>
        <LiteralValueInput
          :model-value="literalOf(row)"
          :disabled="disabled"
          @update:model-value="(value) => setValue(index, value)"
        />
      </div>

      <el-button
        :disabled="disabled"
        :data-testid="`remove-data-assertion-${index}`"
        :aria-label="
          t('framesIndividual.removeDataAssertionRow', { index: index + 1 })
        "
        @click="removeAt(index)"
      >
        {{ t("framesIndividual.removeAssertion") }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.data-assertions-editor {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.data-assertions-editor__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.data-assertions-editor__row {
  display: grid;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}

.data-assertions-editor__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.data-assertions-editor__label {
  font-size: 0.75rem;
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
}

.data-assertions-editor :deep(.el-button:focus-visible),
.data-assertions-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
