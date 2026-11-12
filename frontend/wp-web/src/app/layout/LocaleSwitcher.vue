<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { SUPPORTED_LOCALES, useAppLocale, type AppLocale } from "@/app/i18n";

const { t } = useI18n();
const { locale, setLocale } = useAppLocale();

const labels: Record<AppLocale, string> = {
  "zh-CN": "layout.localeZh",
  "en-US": "layout.localeEn",
};

const triggerLabel = computed(() => t(labels[locale.value]));
const triggerAriaLabel = computed(
  () => `${t("layout.localeSwitcher")}, ${triggerLabel.value}`,
);

function onCommand(command: string | number | object): void {
  if (typeof command !== "string") {
    return;
  }
  if ((SUPPORTED_LOCALES as readonly string[]).includes(command)) {
    setLocale(command as AppLocale);
  }
}
</script>

<template>
  <el-dropdown
    trigger="click"
    @command="onCommand"
  >
    <button
      type="button"
      class="locale-switcher"
      :aria-label="triggerAriaLabel"
    >
      {{ triggerLabel }}
      <span
        class="locale-switcher__caret"
        aria-hidden="true"
      >▾</span>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="item in SUPPORTED_LOCALES"
          :key="item"
          :command="item"
          :class="{ 'is-active-locale': item === locale }"
          :aria-current="item === locale ? 'true' : undefined"
        >
          {{ t(labels[item]) }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<style scoped>
.locale-switcher {
  display: inline-flex;
  align-items: center;
  gap: var(--wp-space-1);
  min-height: 24px;
  padding: var(--wp-space-1) var(--wp-space-2);
  border: 1px solid var(--wp-color-border);
  border-radius: var(--wp-radius-md);
  background: var(--wp-color-bg);
  color: var(--wp-color-text-primary);
  font-size: var(--wp-font-size-sm);
  cursor: pointer;
}

.locale-switcher:focus-visible {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: 2px;
}

.locale-switcher__caret {
  font-size: var(--wp-font-size-xs);
  color: var(--wp-color-text-tertiary);
}

:deep(.is-active-locale) {
  font-weight: 600;
  color: var(--wp-color-brand);
}
</style>
