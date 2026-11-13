<script setup lang="ts">
import type { EntityNode, LiteralValue, PropertyValue } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { SearchType } from "@/features/workspace/hierarchyKinds";
import { BUILTIN_DATATYPES, DEFAULT_DATATYPE } from "../builtins";
import EntityPicker from "../EntityPicker.vue";
import {
  iriValue,
  isIriValue,
  isLiteralValue,
  literalValue,
} from "../frameModel";
import LiteralValueInput from "../LiteralValueInput.vue";

const props = defineProps<{
  projectId: string;
  modelValue: PropertyValue[];
  valid: boolean;
  disabled?: boolean;
}>();

const emit = defineEmits<{
  "update:modelValue": [value: PropertyValue[]];
  "update:valid": [value: boolean];
}>();

const { t } = useI18n();

type Kind = NonNullable<PropertyValue["kind"]>;

const KINDS: readonly Kind[] = ["SOME", "HAS_VALUE", "DATA_SOME"];

const KIND_OPTIONS: readonly { value: Kind; labelKey: string }[] = [
  { value: "SOME", labelKey: "framesClass.kindSome" },
  { value: "HAS_VALUE", labelKey: "framesClass.kindHasValue" },
  { value: "DATA_SOME", labelKey: "framesClass.kindDataSome" },
];

let nextRowKey = 1;
const rowKeys = ref<string[]>([]);

watch(
  () => props.modelValue.length,
  (length) => {
    while (rowKeys.value.length < length) {
      rowKeys.value = [...rowKeys.value, `pv-${nextRowKey++}`];
    }
    if (rowKeys.value.length > length) {
      rowKeys.value = rowKeys.value.slice(0, length);
    }
  },
  { immediate: true },
);

function isKind(value: unknown): value is Kind {
  return typeof value === "string" && (KINDS as readonly string[]).includes(value);
}

function emptyLiteral(): LiteralValue {
  return literalValue("", DEFAULT_DATATYPE.iri);
}

function isRowComplete(row: PropertyValue): boolean {
  if (row.kind === undefined || row.property === undefined) {
    return false;
  }
  const filler = row.filler;
  if (filler === undefined) {
    return false;
  }
  if (filler.type === "iri") {
    const iri = filler.entity?.iri ?? "";
    return iri.length > 0;
  }
  return (filler.lexical ?? "").length > 0 && (filler.datatype ?? "").length > 0;
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

function replaceAt(index: number, next: PropertyValue): void {
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
  rowKeys.value = [...rowKeys.value, `pv-${nextRowKey++}`];
  emit("update:modelValue", [
    ...props.modelValue,
    { kind: "SOME", property: undefined, filler: undefined },
  ]);
}

function propertyTypesFor(kind: Kind | undefined): SearchType[] {
  if (kind === "SOME") {
    return ["object-property"];
  }
  if (kind === "DATA_SOME") {
    return ["data-property"];
  }
  return ["object-property", "data-property"];
}

function propertyCompatible(
  kind: Kind | undefined,
  property: EntityNode | undefined,
): boolean {
  if (property === undefined || kind === undefined) {
    return true;
  }
  if (kind === "SOME") {
    return property.entityType === "OBJECT_PROPERTY";
  }
  if (kind === "DATA_SOME") {
    return property.entityType === "DATA_PROPERTY";
  }
  return (
    property.entityType === "OBJECT_PROPERTY" ||
    property.entityType === "DATA_PROPERTY"
  );
}

function fillerMode(
  kind: Kind | undefined,
  property: EntityNode | undefined,
): "class" | "individual" | "literal" | "datatype" | "none" {
  if (kind === "SOME") {
    return "class";
  }
  if (kind === "DATA_SOME") {
    return "datatype";
  }
  if (kind === "HAS_VALUE") {
    if (property?.entityType === "OBJECT_PROPERTY") {
      return "individual";
    }
    if (property?.entityType === "DATA_PROPERTY") {
      return "literal";
    }
  }
  return "none";
}

function setKind(index: number, kind: Kind): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  const property = propertyCompatible(kind, current.property)
    ? current.property
    : undefined;
  const mode = fillerMode(kind, property);
  replaceAt(index, {
    kind,
    property,
    filler: compatibleFiller(mode, current.filler),
  });
}

function setProperty(index: number, property: EntityNode | null): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  const nextProperty = property ?? undefined;
  const mode = fillerMode(current.kind, nextProperty);
  replaceAt(index, {
    ...current,
    property: nextProperty,
    filler: compatibleFiller(mode, current.filler),
  });
}

function compatibleFiller(
  mode: ReturnType<typeof fillerMode>,
  current: PropertyValue["filler"],
): PropertyValue["filler"] {
  if (mode === "none") {
    return undefined;
  }
  if (mode === "literal") {
    if (isLiteralValue(current)) {
      return current;
    }
    return emptyLiteral();
  }
  if (isIriValue(current) && current.entity !== undefined) {
    const entity = current.entity;
    if (mode === "class" && entity.entityType === "CLASS") {
      return current;
    }
    if (mode === "individual" && entity.entityType === "NAMED_INDIVIDUAL") {
      return current;
    }
    if (mode === "datatype" && entity.entityType === "DATATYPE") {
      return current;
    }
  }
  return undefined;
}

function setIriFiller(index: number, entity: EntityNode | null): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  replaceAt(index, {
    ...current,
    filler: entity === null ? undefined : iriValue(entity),
  });
}

function setLiteralFiller(index: number, value: LiteralValue): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  replaceAt(index, { ...current, filler: value });
}

function iriEntityOf(row: PropertyValue): EntityNode | null {
  if (isIriValue(row.filler) && row.filler.entity !== undefined) {
    const entity = row.filler.entity;
    return entity.iri.length > 0 ? entity : null;
  }
  return null;
}

function literalOf(row: PropertyValue): LiteralValue {
  if (isLiteralValue(row.filler)) {
    return row.filler;
  }
  return emptyLiteral();
}

function modeOf(row: PropertyValue): ReturnType<typeof fillerMode> {
  return fillerMode(row.kind, row.property);
}

function onKindUpdate(index: number, value: unknown): void {
  if (isKind(value)) {
    setKind(index, value);
  }
}

function rowLabel(index: number): string {
  return t("framesClass.propertyValueRow", {
    index: index + 1,
    total: props.modelValue.length,
  });
}
</script>

<template>
  <div
    class="property-values-editor"
    role="group"
    :aria-label="t('framesClass.propertyValues')"
  >
    <div class="property-values-editor__toolbar">
      <el-button
        data-testid="add-property-value"
        :disabled="disabled"
        @click="addRow"
      >
        {{ t("framesClass.addPropertyValue") }}
      </el-button>
    </div>

    <div
      v-for="(row, index) in modelValue"
      :key="rowKeys[index] ?? `pv-fallback-${index}`"
      class="property-values-editor__row"
      role="group"
      :aria-label="rowLabel(index)"
      :data-testid="`property-value-row-${index}`"
    >
      <label class="property-values-editor__field">
        <span class="property-values-editor__label">{{ t("framesClass.kind") }}</span>
        <el-select
          :model-value="row.kind"
          :disabled="disabled"
          :aria-label="t('framesClass.kind')"
          data-testid="property-value-kind"
          @update:model-value="(value) => onKindUpdate(index, value)"
        >
          <el-option
            v-for="option in KIND_OPTIONS"
            :key="option.value"
            :label="t(option.labelKey)"
            :value="option.value"
          />
        </el-select>
      </label>

      <div class="property-values-editor__field">
        <span class="property-values-editor__label">{{ t("framesClass.property") }}</span>
        <EntityPicker
          :project-id="projectId"
          :types="propertyTypesFor(row.kind)"
          :model-value="row.property ?? null"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesClass.property') }"
          @update:model-value="(value) => setProperty(index, value)"
        />
      </div>

      <div class="property-values-editor__field">
        <span class="property-values-editor__label">{{ t("framesClass.filler") }}</span>
        <EntityPicker
          v-if="modeOf(row) === 'class'"
          :project-id="projectId"
          :types="['class']"
          :model-value="iriEntityOf(row)"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesClass.filler') }"
          data-testid="property-value-filler-class"
          @update:model-value="(value) => setIriFiller(index, value)"
        />
        <EntityPicker
          v-else-if="modeOf(row) === 'individual'"
          :project-id="projectId"
          :types="['named-individual']"
          :model-value="iriEntityOf(row)"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesClass.filler') }"
          data-testid="property-value-filler-individual"
          @update:model-value="(value) => setIriFiller(index, value)"
        />
        <LiteralValueInput
          v-else-if="modeOf(row) === 'literal'"
          :model-value="literalOf(row)"
          :disabled="disabled"
          data-testid="property-value-filler-literal"
          @update:model-value="(value) => setLiteralFiller(index, value)"
        />
        <EntityPicker
          v-else-if="modeOf(row) === 'datatype'"
          :project-id="projectId"
          :types="['datatype']"
          :model-value="iriEntityOf(row)"
          :builtins="BUILTIN_DATATYPES"
          :disabled="disabled"
          v-bind="{ ariaLabel: t('framesClass.filler') }"
          data-testid="property-value-filler-datatype"
          @update:model-value="(value) => setIriFiller(index, value)"
        />
      </div>

      <el-button
        :disabled="disabled"
        :data-testid="`remove-property-value-${index}`"
        :aria-label="t('framesClass.removePropertyValueRow', { index: index + 1 })"
        @click="removeAt(index)"
      >
        {{ t("framesClass.removePropertyValue") }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.property-values-editor {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.property-values-editor__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.property-values-editor__row {
  display: grid;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}

.property-values-editor__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.property-values-editor__label {
  font-size: 0.75rem;
  color: var(--el-text-color-secondary);
}

.property-values-editor :deep(.el-button:focus-visible),
.property-values-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
