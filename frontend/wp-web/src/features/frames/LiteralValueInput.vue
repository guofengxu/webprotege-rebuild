<script setup lang="ts">
import type { LiteralValue } from "@wp/api-types";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import {
  BUILTIN_DATATYPES,
  DEFAULT_DATATYPE,
  RDF_LANG_STRING,
} from "./builtins";
import { literalValue } from "./frameModel";

const props = withDefaults(
  defineProps<{
    modelValue: LiteralValue;
    disabled?: boolean;
  }>(),
  {
    disabled: false,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: LiteralValue];
}>();

const { t } = useI18n();

const lexical = computed({
  get(): string {
    return props.modelValue.lexical ?? "";
  },
  set(value: string) {
    emitNext(
      value,
      props.modelValue.datatype ?? DEFAULT_DATATYPE.iri,
      props.modelValue.lang,
    );
  },
});

const datatype = computed({
  get(): string {
    return props.modelValue.datatype ?? DEFAULT_DATATYPE.iri;
  },
  set(value: string) {
    const lang =
      value === RDF_LANG_STRING ? (props.modelValue.lang ?? "") : undefined;
    emitNext(props.modelValue.lexical ?? "", value, lang);
  },
});

const lang = computed({
  get(): string {
    return props.modelValue.lang ?? "";
  },
  set(value: string) {
    const trimmed = value.trim();
    if (trimmed.length > 0) {
      emitNext(props.modelValue.lexical ?? "", RDF_LANG_STRING, trimmed);
      return;
    }
    emitNext(
      props.modelValue.lexical ?? "",
      props.modelValue.datatype === RDF_LANG_STRING
        ? RDF_LANG_STRING
        : (props.modelValue.datatype ?? DEFAULT_DATATYPE.iri),
      "",
    );
  },
});

const langEditable = computed(() => datatype.value === RDF_LANG_STRING);

function emitNext(
  nextLexical: string,
  nextDatatype: string,
  nextLang?: string,
): void {
  if (nextDatatype === RDF_LANG_STRING) {
    emit(
      "update:modelValue",
      literalValue(nextLexical, RDF_LANG_STRING, nextLang ?? ""),
    );
    return;
  }
  emit("update:modelValue", literalValue(nextLexical, nextDatatype));
}

function datatypeLabel(iri: string): string {
  const match = BUILTIN_DATATYPES.find((node) => node.iri === iri);
  return match?.displayName ?? iri;
}
</script>

<template>
  <div class="literal-value-input">
    <label class="literal-value-input__field">
      <span class="literal-value-input__label">{{ t("framesInputs.lexical") }}</span>
      <el-input
        v-model="lexical"
        type="textarea"
        :rows="2"
        data-testid="annotation-lexical"
        :disabled="disabled"
        :aria-label="t('framesInputs.lexical')"
      />
    </label>
    <label class="literal-value-input__field">
      <span class="literal-value-input__label">{{ t("framesInputs.datatype") }}</span>
      <el-select
        v-model="datatype"
        data-testid="annotation-datatype"
        :disabled="disabled"
        :aria-label="t('framesInputs.datatype')"
      >
        <el-option
          v-for="node in BUILTIN_DATATYPES"
          :key="node.iri"
          :label="datatypeLabel(node.iri)"
          :value="node.iri"
        />
      </el-select>
    </label>
    <label class="literal-value-input__field">
      <span class="literal-value-input__label">{{ t("framesInputs.lang") }}</span>
      <el-input
        v-model="lang"
        data-testid="annotation-lang"
        :disabled="disabled || !langEditable"
        :aria-label="t('framesInputs.lang')"
      />
    </label>
  </div>
</template>

<style scoped>
.literal-value-input {
  display: grid;
  gap: 0.5rem;
  grid-template-columns: 1fr;
}

@media (min-width: 720px) {
  .literal-value-input {
    grid-template-columns: 2fr 1.2fr 0.8fr;
    align-items: start;
  }
}

.literal-value-input__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.literal-value-input__label {
  font-size: 0.75rem;
  color: var(--el-text-color-secondary);
}
</style>
