import type { SettingsTab } from "../settingsTabs";
import NewEntitySettingsForm from "./NewEntitySettingsForm.vue";

export const settingsTab = {
  key: "new-entity",
  labelKey: "settingsNaming.newEntity",
  order: 30,
  component: NewEntitySettingsForm,
} satisfies SettingsTab;
