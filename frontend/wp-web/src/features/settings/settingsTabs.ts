import type { Component } from "vue";

export type SettingsTab = {
  readonly key: string;
  readonly labelKey: string;
  readonly order: number;
  readonly component: Component;
};

export type SettingsTabModule = {
  readonly settingsTab?: SettingsTab;
};

const tabModules = import.meta.glob<SettingsTabModule>(
  "/src/features/settings/**/*.tab.ts",
  { eager: true, exhaustive: true },
);

export function mergeSettingsTabs(
  modules: Readonly<Record<string, SettingsTabModule>>,
): SettingsTab[] {
  const tabs: SettingsTab[] = [];
  const seen = new Map<string, string>();

  for (const [path, mod] of Object.entries(modules)) {
    const tab = mod.settingsTab;
    if (tab === undefined) {
      continue;
    }
    const previous = seen.get(tab.key);
    if (previous !== undefined) {
      throw new Error(
        `Duplicate settings tab key "${tab.key}" from ${path} (also ${previous})`,
      );
    }
    seen.set(tab.key, path);
    tabs.push(tab);
  }

  return [...tabs].sort((left, right) => left.order - right.order);
}

export const settingsTabs: readonly SettingsTab[] =
  mergeSettingsTabs(tabModules);
