import type { SettingsTab } from "../settingsTabs";
import PrefixSettingsForm from "./PrefixSettingsForm.vue";

export const settingsTab = {
  key: "prefixes",
  labelKey: "settings.prefixes",
  order: 20,
  component: PrefixSettingsForm,
} satisfies SettingsTab;
