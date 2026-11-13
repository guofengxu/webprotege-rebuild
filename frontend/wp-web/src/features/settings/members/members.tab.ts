import type { SettingsTab } from "../settingsTabs";
import MembersTab from "./MembersTab.vue";

export const settingsTab = {
  key: "members",
  labelKey: "members.tab",
  order: 50,
  component: MembersTab,
} satisfies SettingsTab;
