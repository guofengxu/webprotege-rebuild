import { defineComponent, h } from "vue";
import { expect, test } from "vitest";
import { mergeRegistrations } from "./registry";

const PanelA = defineComponent({
  name: "PanelA",
  setup: () => () => h("div", "panel-a"),
});
const PanelB = defineComponent({
  name: "PanelB",
  setup: () => () => h("div", "panel-b"),
});
const EditorA = defineComponent({
  name: "EditorA",
  setup: () => () => h("div", "editor-a"),
});
const EditorB = defineComponent({
  name: "EditorB",
  setup: () => () => h("div", "editor-b"),
});
const TabA = defineComponent({
  name: "TabA",
  setup: () => () => h("div", "tab-a"),
});
const TabB = defineComponent({
  name: "TabB",
  setup: () => () => h("div", "tab-b"),
});
const ToolbarA = defineComponent({
  name: "ToolbarA",
  setup: () => () => h("div", "toolbar-a"),
});
const ToolbarB = defineComponent({
  name: "ToolbarB",
  setup: () => () => h("div", "toolbar-b"),
});
const HierarchyToolbarA = defineComponent({
  name: "HierarchyToolbarA",
  setup: () => () => h("div", "hierarchy-toolbar-a"),
});
const HierarchyToolbarB = defineComponent({
  name: "HierarchyToolbarB",
  setup: () => () => h("div", "hierarchy-toolbar-b"),
});

test("throws when two modules register the same hierarchy kind", () => {
  expect(() =>
    mergeRegistrations({
      "/src/features/a/register.ts": {
        registration: { hierarchyPanels: { class: PanelA } },
      },
      "/src/features/b/register.ts": {
        registration: { hierarchyPanels: { class: PanelB } },
      },
    }),
  ).toThrow(/Duplicate hierarchy panel registration for kind "class"/);
});

test("throws when two modules register the same entity type editor", () => {
  expect(() =>
    mergeRegistrations({
      "/src/features/a/register.ts": {
        registration: { detailEditors: { CLASS: EditorA } },
      },
      "/src/features/b/register.ts": {
        registration: { detailEditors: { CLASS: EditorB } },
      },
    }),
  ).toThrow(/Duplicate detail editor registration for entity type "CLASS"/);
});

test("merges detailTabs, workspaceToolbar, and hierarchyToolbar in module path order", () => {
  const merged = mergeRegistrations({
    "/src/features/z-later/register.ts": {
      registration: {
        detailTabs: [{ key: "z", labelKey: "z", component: TabB }],
        workspaceToolbar: [ToolbarB],
        hierarchyToolbar: [HierarchyToolbarB],
      },
    },
    "/src/features/a-first/register.ts": {
      registration: {
        detailTabs: [{ key: "a", labelKey: "a", component: TabA }],
        workspaceToolbar: [ToolbarA],
        hierarchyToolbar: [HierarchyToolbarA],
      },
    },
  });

  expect(merged.detailTabs.map((tab) => tab.key)).toEqual(["a", "z"]);
  expect(merged.workspaceToolbar).toEqual([ToolbarA, ToolbarB]);
  expect(merged.hierarchyToolbar).toEqual([
    HierarchyToolbarA,
    HierarchyToolbarB,
  ]);
});
