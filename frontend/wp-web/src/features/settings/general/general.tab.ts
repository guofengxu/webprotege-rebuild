import type { SettingsTab } from "../settingsTabs";
import GeneralSettingsForm from "./GeneralSettingsForm.vue";

export const settingsTab = {
  key: "general",
  labelKey: "settings.general",
  order: 10,
  component: GeneralSettingsForm,
} satisfies SettingsTab;
