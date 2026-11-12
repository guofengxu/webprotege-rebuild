import { flushPromises } from "@vue/test-utils";
import { defineComponent, h, provide } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import HierarchyPanel from "./HierarchyPanel.vue";
import {
  WorkspaceRegistryKey,
  type WorkspaceRegistry,
} from "./registry";
import { WORKSPACE_KIND_PATH } from "./routes";

const received: Array<{ projectId: string; kind: string; source: string }> = [];

const StubPanel = defineComponent({
  name: "StubPanel",
  props: {
    projectId: { type: String, required: true },
    kind: { type: String, required: true },
  },
  setup(props) {
    received.push({
      projectId: props.projectId,
      kind: props.kind,
      source: "panel",
    });
    return () =>
      h("div", { "data-testid": "stub-panel" }, [
        `panel:${props.projectId}:${props.kind}`,
      ]);
  },
});

const StubToolbar = defineComponent({
  name: "StubToolbar",
  props: {
    projectId: { type: String, required: true },
    kind: { type: String, required: true },
  },
  setup(props) {
    received.push({
      projectId: props.projectId,
      kind: props.kind,
      source: "toolbar",
    });
    return () =>
      h("div", { "data-testid": "stub-toolbar" }, [
        `toolbar:${props.projectId}:${props.kind}`,
      ]);
  },
});

afterEach(() => {
  received.length = 0;
  localStorage.removeItem("wp.locale");
  localStorage.removeItem("wp.workspace.layout");
});

test("registered panel and hierarchyToolbar receive projectId and kind; toolbar renders above the panel", async () => {
  const registry: WorkspaceRegistry = {
    hierarchyPanels: { class: StubPanel },
    detailEditors: {},
    detailTabs: [],
    workspaceToolbar: [],
    hierarchyToolbar: [StubToolbar],
    revisionActions: [],
  };

  const Host = defineComponent({
    name: "HierarchyPanelHost",
    setup() {
      provide(WorkspaceRegistryKey, registry);
      return () => h(HierarchyPanel);
    },
  });

  const { wrapper } = await renderWithProviders(Host, {
    route: "/projects/p1/class",
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

  expect(wrapper.get('[data-testid="stub-panel"]').text()).toBe(
    "panel:p1:class",
  );
  expect(wrapper.get('[data-testid="stub-toolbar"]').text()).toBe(
    "toolbar:p1:class",
  );

  const html = wrapper.html();
  expect(html.indexOf("stub-toolbar")).toBeLessThan(
    html.indexOf("stub-panel"),
  );
  expect(received).toEqual([
    { projectId: "p1", kind: "class", source: "toolbar" },
    { projectId: "p1", kind: "class", source: "panel" },
  ]);
  wrapper.unmount();
});
