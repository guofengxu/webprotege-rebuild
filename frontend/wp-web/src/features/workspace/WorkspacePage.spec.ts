import { flushPromises } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { WORKSPACE_KIND_PATH } from "./routes";
import WorkspacePage from "./WorkspacePage.vue";

afterEach(() => {
  localStorage.removeItem("wp.locale");
  localStorage.removeItem("wp.workspace.layout");
});

test("opening with ?entity= shows the same IRI in the detail panel", async () => {
  const iri = "http://example.org/ont#Pizza";
  const { wrapper } = await renderPage(
    `/projects/p1/class?entity=${encodeURIComponent(iri)}`,
  );

  await flushPromises();
  expect(wrapper.text()).toContain(iri);
  wrapper.unmount();
});

test("switching hierarchy tabs updates the kind segment in the URL", async () => {
  const { wrapper, router } = await renderPage("/projects/p1/class");

  await flushPromises();
  const tabs = wrapper.findAll('[role="tab"]');
  const objectPropertyTab = tabs.find((tab) =>
    tab.text().includes("对象属性"),
  );
  expect(objectPropertyTab).toBeDefined();
  await objectPropertyTab!.trigger("click");
  await flushPromises();

  expect(router.currentRoute.value.path).toBe("/projects/p1/object-property");
  expect(router.currentRoute.value.params.kind).toBe("object-property");
  wrapper.unmount();
});

async function renderPage(route: string) {
  return renderWithProviders(WorkspacePage, {
    route,
    routes: [
      {
        path: "/projects/:projectId",
        children: [
          {
            path: WORKSPACE_KIND_PATH,
            name: "workspace",
            component: WorkspacePage,
          },
          {
            path: "history",
            name: "history",
            component: WorkspacePage,
          },
          {
            path: "settings",
            name: "settings",
            component: WorkspacePage,
          },
        ],
      },
    ],
  });
}
