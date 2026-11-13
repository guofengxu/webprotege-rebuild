<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import type { EntityNode } from "./frameModel";

const props = defineProps<{
  subject: EntityNode;
}>();

const { t } = useI18n();

const typeLabel = computed(() =>
  t(`framesCommon.entityType.${props.subject.entityType}`),
);

async function copyIri(): Promise<void> {
  try {
    await navigator.clipboard.writeText(props.subject.iri);
    ElMessage.success(t("framesCommon.iriCopied"));
  } catch {
    ElMessage.error(t("errors.unknown"));
  }
}
</script>

<template>
  <header class="frame-header">
    <div class="frame-header__title-row">
      <h2 class="frame-header__name">
        {{ subject.displayName }}
      </h2>
      <el-tag
        size="small"
        type="info"
      >
        {{ typeLabel }}
      </el-tag>
      <el-tag
        v-if="subject.deprecated"
        size="small"
        type="warning"
      >
        {{ t("framesCommon.deprecated") }}
      </el-tag>
    </div>
    <div class="frame-header__iri-row">
      <code class="frame-header__iri">{{ subject.iri }}</code>
      <el-button
        size="small"
        :aria-label="t('framesCommon.copyIri')"
        @click="copyIri"
      >
        {{ t("framesCommon.copyIri") }}
      </el-button>
    </div>
  </header>
</template>

<style scoped>
.frame-header {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.frame-header__title-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wp-space-2, 0.5rem);
}

.frame-header__name {
  margin: 0;
  font-size: var(--wp-font-size-lg, 1.125rem);
  font-weight: 600;
}

.frame-header__iri-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--wp-space-2, 0.5rem);
}

.frame-header__iri {
  font-family: var(
    --wp-font-mono,
    ui-monospace,
    SFMono-Regular,
    Menlo,
    Monaco,
    Consolas,
    monospace
  );
  font-size: var(--wp-font-size-sm, 0.875rem);
  word-break: break-all;
}

.frame-header :deep(.el-button:focus-visible) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}

.frame-header :deep(.el-button--small) {
  min-width: 24px;
  min-height: 24px;
}
</style>
