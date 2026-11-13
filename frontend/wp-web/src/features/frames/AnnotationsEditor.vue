<script setup lang="ts">
import type { AnnotationValue, EntityNode, LiteralValue } from "@wp/api-types";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import {
  BUILTIN_ANNOTATION_PROPERTIES,
  RDF_LANG_STRING,
  RDFS_LABEL,
} from "./builtins";
import EntityPicker from "./EntityPicker.vue";
import {
  iriValue,
  isIriValue,
  isLiteralValue,
  literalValue,
} from "./frameModel";
import LiteralValueInput from "./LiteralValueInput.vue";

const props = defineProps<{
  projectId: string;
  modelValue: AnnotationValue[];
}>();

const emit = defineEmits<{
  "update:modelValue": [value: AnnotationValue[]];
}>();

const { t } = useI18n();

type DisplayRow = {
  readonly index: number;
  readonly annotation: AnnotationValue;
};

const displayRows = computed((): DisplayRow[] => {
  const groups = new Map<string, DisplayRow[]>();
  const order: string[] = [];
  props.modelValue.forEach((annotation, index) => {
    const key = annotation.property?.iri ?? `__empty__:${index}`;
    if (!groups.has(key)) {
      groups.set(key, []);
      order.push(key);
    }
    groups.get(key)!.push({ index, annotation });
  });
  return order.flatMap((key) => groups.get(key) ?? []);
});

function emptyLiteral(): LiteralValue {
  return literalValue("", RDF_LANG_STRING, "");
}

function replaceAt(index: number, next: AnnotationValue): void {
  emit(
    "update:modelValue",
    props.modelValue.map((entry, i) => (i === index ? next : entry)),
  );
}

function removeAt(index: number): void {
  emit(
    "update:modelValue",
    props.modelValue.filter((_, i) => i !== index),
  );
}

function addLabel(): void {
  emit("update:modelValue", [
    ...props.modelValue,
    { property: RDFS_LABEL, value: emptyLiteral() },
  ]);
}

function addBlank(): void {
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

function valueKind(annotation: AnnotationValue): "literal" | "iri" {
  if (isIriValue(annotation.value)) {
    return "iri";
  }
  return "literal";
}

function setValueKind(index: number, kind: "literal" | "iri"): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  if (kind === "literal") {
    replaceAt(index, { ...current, value: emptyLiteral() });
    return;
  }
  replaceAt(index, {
    ...current,
    value: iriValue({
      iri: "",
      entityType: "IRI",
      displayName: "",
      deprecated: false,
      hasChildren: false,
    }),
  });
}

function setLiteral(index: number, value: LiteralValue): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  replaceAt(index, { ...current, value });
}

function setIriEntity(index: number, entity: EntityNode | null): void {
  const current = props.modelValue[index];
  if (current === undefined) {
    return;
  }
  if (entity === null) {
    replaceAt(index, {
      ...current,
      value: iriValue({
        iri: "",
        entityType: "IRI",
        displayName: "",
        deprecated: false,
        hasChildren: false,
      }),
    });
    return;
  }
  replaceAt(index, { ...current, value: iriValue(entity) });
}

function literalOf(annotation: AnnotationValue): LiteralValue {
  if (isLiteralValue(annotation.value)) {
    return annotation.value;
  }
  return emptyLiteral();
}

function iriEntityOf(annotation: AnnotationValue): EntityNode | null {
  if (isIriValue(annotation.value) && annotation.value.entity !== undefined) {
    const entity = annotation.value.entity;
    return entity.iri.length > 0 ? entity : null;
  }
  return null;
}
</script>

<template>
  <div class="annotations-editor">
    <div class="annotations-editor__toolbar">
      <el-button
        data-testid="add-label"
        @click="addLabel"
      >
        {{ t("framesInputs.addLabel") }}
      </el-button>
      <el-button @click="addBlank">
        {{ t("framesInputs.addAnnotation") }}
      </el-button>
    </div>

    <div
      v-for="row in displayRows"
      :key="row.index"
      class="annotations-editor__row"
    >
      <EntityPicker
        :project-id="projectId"
        :types="['annotation-property']"
        :model-value="row.annotation.property ?? null"
        :builtins="BUILTIN_ANNOTATION_PROPERTIES"
        v-bind="{ ariaLabel: t('framesInputs.property') }"
        @update:model-value="(value) => setProperty(row.index, value)"
      />

      <div
        class="annotations-editor__value-type"
        role="group"
        :aria-label="t('framesInputs.value')"
      >
        <el-button
          data-testid="value-type-literal"
          :type="valueKind(row.annotation) === 'literal' ? 'primary' : 'default'"
          :aria-pressed="valueKind(row.annotation) === 'literal'"
          @click="setValueKind(row.index, 'literal')"
        >
          {{ t("framesInputs.valueTypeLiteral") }}
        </el-button>
        <el-button
          data-testid="value-type-iri"
          :type="valueKind(row.annotation) === 'iri' ? 'primary' : 'default'"
          :aria-pressed="valueKind(row.annotation) === 'iri'"
          @click="setValueKind(row.index, 'iri')"
        >
          {{ t("framesInputs.valueTypeIri") }}
        </el-button>
      </div>

      <LiteralValueInput
        v-if="valueKind(row.annotation) === 'literal'"
        :model-value="literalOf(row.annotation)"
        @update:model-value="(value) => setLiteral(row.index, value)"
      />
      <EntityPicker
        v-else
        :project-id="projectId"
        :types="[
          'class',
          'object-property',
          'data-property',
          'annotation-property',
          'named-individual',
          'datatype',
        ]"
        :model-value="iriEntityOf(row.annotation)"
        allow-raw-iri
        v-bind="{ ariaLabel: t('framesInputs.value') }"
        @update:model-value="(value) => setIriEntity(row.index, value)"
      />

      <el-button
        :data-testid="`remove-annotation-${row.index}`"
        :aria-label="t('framesInputs.removeAnnotation')"
        @click="removeAt(row.index)"
      >
        {{ t("framesInputs.removeAnnotation") }}
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.annotations-editor {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.annotations-editor__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
}

.annotations-editor__row {
  display: grid;
  gap: 0.5rem;
  padding: 0.75rem;
  border: 1px solid var(--el-border-color);
  border-radius: 4px;
}

.annotations-editor__value-type {
  display: flex;
  gap: 0.35rem;
}
</style>
