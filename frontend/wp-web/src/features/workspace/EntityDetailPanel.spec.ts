import { flushPromises } from "@vue/test-utils";
import { expect, test } from "vitest";
import { defineComponent, h, provide } from "vue";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import EntityDetailPanel from "./EntityDetailPanel.vue";
import {
  WorkspaceRegistryKey,
  type WorkspaceRegistry,
} from "./registry";
import { WORKSPACE_KIND_PATH } from "./routes";

test("selects the detail tab so the frame editor is not display:none", async () => {
  const Editor = defineComponent({
    name: "StubClassEditor",
    props: {
      projectId: { type: String, required: true },
      iri: { type: String, required: true },
    },
    setup(props) {
      return () =>
        h(
          "section",
          { "data-testid": "class-frame-editor" },
          `editor:${props.iri}`,
        );
    },
  });
  const HistoryTab = defineComponent({
    name: "StubHistoryTab",
    setup() {
      return () => h("div", { "data-testid": "entity-history" }, "history");
    },
  });

  const registry: WorkspaceRegistry = {
    hierarchyPanels: {},
    detailEditors: { CLASS: Editor },
    detailTabs: [
      { key: "history", labelKey: "history.entityTab", component: HistoryTab },
    ],
    workspaceToolbar: [],
    hierarchyToolbar: [],
    revisionActions: [],
  };

  const Host = defineComponent({
    name: "EntityDetailPanelHost",
    setup() {
      provide(WorkspaceRegistryKey, registry);
      return () => h(EntityDetailPanel);
    },
  });

  const iri = "http://example.org/ont#Pizza";
  const { wrapper } = await renderWithProviders(Host, {
    route: `/projects/p1/class?entity=${encodeURIComponent(iri)}`,
    routes: [
      {
        path: "/projects/:projectId",
        children: [
          {
            path: WORKSPACE_KIND_PATH,
            name: "workspace",
            component: Host,
          },
        ],
      },
    ],
  });

  await flushPromises();

  const detailTab = wrapper
    .findAll('[role="tab"]')
    .find((tab) => tab.text().includes("详情"));
  expect(detailTab).toBeDefined();
  expect(detailTab!.attributes("aria-selected")).toBe("true");

  const editor = wrapper.get('[data-testid="class-frame-editor"]');
  expect(editor.attributes("style") ?? "").not.toMatch(/display:\s*none/);
  const pane = editor.element.closest('[role="tabpanel"]') as HTMLElement | null;
  expect(pane).not.toBeNull();
  expect(pane!.getAttribute("aria-hidden")).not.toBe("true");
  expect(pane!.style.display).not.toBe("none");
  expect(editor.text()).toContain(iri);

  wrapper.unmount();
});
