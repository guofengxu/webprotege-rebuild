import type { SettingsTab } from "../settingsTabs";
import WebhooksTab from "./WebhooksTab.vue";

export const settingsTab = {
  key: "webhooks",
  labelKey: "settings.webhooks.tab",
  order: 60,
  component: WebhooksTab,
} satisfies SettingsTab;
