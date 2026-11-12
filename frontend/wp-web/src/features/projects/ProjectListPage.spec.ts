import { flushPromises } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import ProjectListPage from "./ProjectListPage.vue";

const StubPage = defineComponent({
  name: "StubPage",
  setup() {
    return () => h("p", "stub");
  },
});

const activeProject = {
  projectId: "p1",
  displayName: "Alpha",
  description: "first project",
  owner: "ada",
  modifiedAt: "2026-03-04T05:06:07Z",
  modifiedBy: "ada",
};

const trashedProject = {
  projectId: "p2",
  displayName: "Beta",
  description: "trashed",
  owner: "ada",
  modifiedAt: "2026-02-01T00:00:00Z",
  modifiedBy: "bob",
  inTrash: true,
};

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
});

test("renders listProjects results", async () => {
  const { wrapper } = await renderPage({
    "GET /api/projects": () => ({ json: [activeProject] }),
    "GET /api/projects/trash": () => ({ json: [] }),
  });

  await flushPromises();
  expect(wrapper.text()).toContain("Alpha");
  expect(wrapper.text()).toContain("first project");
  expect(wrapper.text()).toContain("ada");
  expect(wrapper.get(`a[href="/projects/p1"]`).text()).toContain("Alpha");
  wrapper.unmount();
});

test("confirming trash calls trashProject and refreshes the list", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
  let active = [activeProject];
  const { wrapper, requests } = await renderPage({
    "GET /api/projects": () => ({ json: active }),
    "GET /api/projects/trash": () => ({ json: [] }),
    "POST /api/projects/{id}/trash": () => {
      active = [];
      return { json: { ...activeProject, inTrash: true } };
    },
  });

  await flushPromises();
  const trashButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("移入回收站"));
  expect(trashButton).toBeDefined();
  await trashButton!.trigger("click");
  await flushPromises();

  const trash = requests.find(
    (request) =>
      request.method === "POST" && request.path === "/api/projects/p1/trash",
  );
  expect(trash?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(wrapper.text()).not.toContain("Alpha");
  expect(
    requests.filter((request) => request.method === "GET" && request.path === "/api/projects")
      .length,
  ).toBeGreaterThan(1);
  wrapper.unmount();
});

test("restore on the trash tab calls restoreProject", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  let trashed = [trashedProject];
  const { wrapper, requests } = await renderPage({
    "GET /api/projects": () => ({ json: [] }),
    "GET /api/projects/trash": () => ({ json: trashed }),
    "DELETE /api/projects/{id}/trash": () => {
      trashed = [];
      return { json: { ...trashedProject, inTrash: false } };
    },
  });

  await flushPromises();
  const tabs = wrapper.findAll('[role="tab"]');
  const trashTab = tabs.find((tab) => tab.text().includes("回收站"));
  expect(trashTab).toBeDefined();
  await trashTab!.trigger("click");
  await flushPromises();

  expect(wrapper.text()).toContain("Beta");
  const restoreButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("恢复"));
  expect(restoreButton).toBeDefined();
  await restoreButton!.trigger("click");
  await flushPromises();

  const restore = requests.find(
    (request) =>
      request.method === "DELETE" && request.path === "/api/projects/p2/trash",
  );
  expect(restore?.headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
  expect(wrapper.text()).not.toContain("Beta");
  wrapper.unmount();
});

async function renderPage(
  handlers: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never,
) {
  return renderWithProviders(ProjectListPage, {
    route: "/projects",
    handlers,
    routes: [
      { path: "/projects", component: ProjectListPage },
      { path: "/projects/:projectId", component: StubPage },
    ],
  });
}
