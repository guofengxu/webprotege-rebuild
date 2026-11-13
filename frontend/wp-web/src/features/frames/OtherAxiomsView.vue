<script setup lang="ts">
import { useI18n } from "vue-i18n";

defineProps<{
  axioms: string[];
}>();

const { t } = useI18n();
const headingId = "other-axioms-heading";
</script>

<template>
  <section
    v-if="axioms.length > 0"
    class="other-axioms"
    :aria-labelledby="headingId"
  >
    <h3
      :id="headingId"
      class="other-axioms__title"
    >
      {{ t("framesCommon.otherAxioms") }}
    </h3>
    <p class="other-axioms__note">
      {{ t("framesCommon.otherAxiomsReadonly") }}
    </p>
    <ul class="other-axioms__list">
      <li
        v-for="(axiom, index) in axioms"
        :key="`${index}-${axiom}`"
        class="other-axioms__item"
      >
        <code>{{ axiom }}</code>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.other-axioms {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.other-axioms__title {
  margin: 0;
  font-size: var(--wp-font-size-md, 1rem);
}

.other-axioms__note {
  margin: 0;
  color: var(--wp-color-text-secondary, var(--el-text-color-secondary));
  font-size: var(--wp-font-size-sm, 0.875rem);
}

.other-axioms__list {
  margin: 0;
  padding-left: 1.25rem;
}

.other-axioms__item code {
  font-family: var(
    --wp-font-mono,
    ui-monospace,
    SFMono-Regular,
    Menlo,
    Monaco,
    Consolas,
    monospace
  );
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
