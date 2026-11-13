import type { SettingsTab } from "../settingsTabs";
import DisplayNameSettingsForm from "./DisplayNameSettingsForm.vue";

export const settingsTab = {
  key: "display-names",
  labelKey: "settingsNaming.displayNames",
  order: 40,
  component: DisplayNameSettingsForm,
} satisfies SettingsTab;
