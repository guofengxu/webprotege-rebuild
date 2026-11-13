<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";

const props = withDefaults(
  defineProps<{
    modelValue: string[];
    ariaLabel: string;
    disabled?: boolean;
  }>(),
  {
    disabled: false,
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: string[]];
}>();

const { t } = useI18n();
const draft = ref("");
const error = ref<string | undefined>();
const errorId = "iri-list-input-error";

const describedBy = computed(() =>
  error.value !== undefined ? errorId : undefined,
);

watch(
  () => props.modelValue,
  () => {
    draft.value = "";
    error.value = undefined;
  },
);

watch(draft, () => {
  if (error.value !== undefined) {
    error.value = undefined;
  }
});

function removeAt(index: number): void {
  emit(
    "update:modelValue",
    props.modelValue.filter((_, i) => i !== index),
  );
}

function tryAdd(): void {
  if (props.disabled) {
    return;
  }
  const trimmed = draft.value.trim();
  if (trimmed.length === 0) {
    return;
  }
  if (!isAbsoluteIri(trimmed)) {
    error.value = t("framesProperty.iriInvalid");
    return;
  }
  if (props.modelValue.includes(trimmed)) {
    draft.value = "";
    error.value = undefined;
    return;
  }
  emit("update:modelValue", [...props.modelValue, trimmed]);
  draft.value = "";
  error.value = undefined;
}

function isAbsoluteIri(value: string): boolean {
  if (value.length === 0 || /\s/.test(value)) {
    return false;
  }
  try {
    const url = new URL(value);
    return url.protocol.length > 0;
  } catch {
    return false;
  }
}
</script>

<template>
  <div
    class="iri-list-input"
    role="group"
    :aria-label="ariaLabel"
  >
    <div
      v-if="modelValue.length === 0"
      class="iri-list-input__empty"
    >
      {{ t("framesProperty.iriEmpty") }}
    </div>
    <div
      v-else
      class="iri-list-input__tags"
    >
      <el-tag
        v-for="(iri, index) in modelValue"
        :key="`${iri}-${index}`"
        :closable="!disabled"
        :disable-transitions="true"
        :title="iri"
        @close="removeAt(index)"
      >
        {{ iri }}
      </el-tag>
    </div>
    <form
      class="iri-list-input__form"
      @submit.prevent="tryAdd"
    >
      <el-input
        v-model="draft"
        data-testid="iri-list-input"
        :disabled="disabled"
        :placeholder="t('framesProperty.iriPlaceholder')"
        :aria-label="ariaLabel"
        :aria-invalid="error !== undefined ? 'true' : undefined"
        :aria-describedby="describedBy"
      />
    </form>
    <p
      v-if="error !== undefined"
      :id="errorId"
      class="iri-list-input__error"
      role="alert"
    >
      {{ error }}
    </p>
  </div>
</template>

<style scoped>
.iri-list-input {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.iri-list-input__empty {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: var(--el-font-size-small);
}

.iri-list-input__tags {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wp-space-1, 0.25rem);
}

.iri-list-input__error {
  margin: 0;
  color: var(--el-color-danger);
  font-size: var(--el-font-size-small);
}

.iri-list-input :deep(.el-input__wrapper.is-focus) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
