import { flushPromises } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import ProjectSettingsPage from "./ProjectSettingsPage.vue";

const PROJECT = {
  projectId: "p1",
  displayName: "Demo",
  description: "A demo project",
};

const PREFIXES = {
  owl: "http://www.w3.org/2002/07/owl#",
  rdfs: "http://www.w3.org/2000/01/rdf-schema#",
};

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
});

test("?tab=prefixes opens the prefixes tab", async () => {
  const { wrapper, router } = await mountPage(
    "/projects/p1/settings?tab=prefixes",
  );
  await flushPromises();

  expect(router.currentRoute.value.query.tab).toBe("prefixes");
  expect(wrapper.text()).toContain("前缀");
  const prefixes = wrapper
    .findAll('input[name="prefix"]')
    .map((input) => (input.element as HTMLInputElement).value);
  const iris = wrapper
    .findAll('input[name="iri"]')
    .map((input) => (input.element as HTMLInputElement).value);
  expect(prefixes).toContain("owl");
  expect(iris).toContain("http://www.w3.org/2002/07/owl#");
  wrapper.unmount();
});

test("unknown tab falls back to general settings", async () => {
  const { wrapper, router } = await mountPage(
    "/projects/p1/settings?tab=unknown",
  );
  await flushPromises();

  expect(router.currentRoute.value.query.tab).toBe("general");
  expect(wrapper.get('input[name="name"]').element).toHaveProperty(
    "value",
    "Demo",
  );
  wrapper.unmount();
});

test("cancelling dirty tab switch keeps the current tab", async () => {
  vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
  const { wrapper, router } = await mountPage(
    "/projects/p1/settings?tab=general",
  );
  await flushPromises();

  const nameInput = wrapper.get('input[name="name"]');
  await nameInput.setValue("Changed");
  await flushPromises();

  const prefixesTab = wrapper
    .findAll("[role='tab']")
    .find((tab) => tab.text().includes("IRI 前缀"));
  expect(prefixesTab).toBeDefined();
  await prefixesTab!.trigger("click");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  expect(router.currentRoute.value.query.tab).toBe("general");
  expect(wrapper.get('input[name="name"]').element).toHaveProperty(
    "value",
    "Changed",
  );
  wrapper.unmount();
});

async function mountPage(route: string) {
  return renderWithProviders(ProjectSettingsPage, {
    route,
    routes: [
      {
        path: "/projects/:projectId",
        children: [
          {
            path: "settings",
            name: "settings",
            component: ProjectSettingsPage,
          },
        ],
      },
    ],
    handlers: {
      "GET /api/projects/{id}": () => ({ json: PROJECT }),
      "GET /api/projects/{id}/settings/prefixes": () => ({ json: PREFIXES }),
    },
  });
}
