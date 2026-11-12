import { defineComponent, h } from "vue";
import { expect, test } from "vitest";
import { mergeSettingsTabs } from "./settingsTabs";

const TabA = defineComponent({
  name: "TabA",
  setup: () => () => h("div", "a"),
});
const TabB = defineComponent({
  name: "TabB",
  setup: () => () => h("div", "b"),
});

test("sorts registered tabs by order", () => {
  const tabs = mergeSettingsTabs({
    "/src/features/settings/z/z.tab.ts": {
      settingsTab: {
        key: "z",
        labelKey: "settings.z",
        order: 30,
        component: TabB,
      },
    },
    "/src/features/settings/a/a.tab.ts": {
      settingsTab: {
        key: "a",
        labelKey: "settings.a",
        order: 10,
        component: TabA,
      },
    },
  });

  expect(tabs.map((tab) => tab.key)).toEqual(["a", "z"]);
});

test("throws when two modules register the same key", () => {
  expect(() =>
    mergeSettingsTabs({
      "/src/features/settings/a/a.tab.ts": {
        settingsTab: {
          key: "general",
          labelKey: "settings.general",
          order: 10,
          component: TabA,
        },
      },
      "/src/features/settings/b/b.tab.ts": {
        settingsTab: {
          key: "general",
          labelKey: "settings.general",
          order: 20,
          component: TabB,
        },
      },
    }),
  ).toThrow(/Duplicate settings tab key "general"/);
});
