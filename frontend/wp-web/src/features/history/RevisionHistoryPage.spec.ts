import { flushPromises } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { REVISION_PAGE, revisionDiff } from "./fixtures";
import RevisionHistoryPage from "./RevisionHistoryPage.vue";

afterEach(() => {
  localStorage.removeItem("wp.locale");
});

test("first page requests page=0 and renders descending revisions with revert", async () => {
  const { wrapper, requests } = await mountPage("/projects/p1/history");
  await flushPromises();

  const list = requests.find(
    (entry) =>
      entry.method === "GET" && entry.path === "/api/projects/p1/revisions",
  );
  expect(list).toBeDefined();
  expect(list!.query.get("page")).toBe("0");
  expect(list!.query.get("size")).toBe("50");
  expect(wrapper.text()).toContain("Add Pizza");
  expect(wrapper.text()).toContain("Rename");
  expect(wrapper.text()).toContain("Initial");
  expect(
    wrapper.findAll("button").filter((b) => b.text() === "回滚"),
  ).toHaveLength(3);
  wrapper.unmount();
});

test("clicking a row sets rev query and fetches the diff", async () => {
  const { wrapper, router, requests } = await mountPage("/projects/p1/history");
  await flushPromises();

  const revButton = wrapper
    .findAll("button")
    .find((button) => button.text().includes("3") && button.attributes("aria-label")?.includes("查看修订"));
  expect(revButton).toBeDefined();
  await revButton!.trigger("click");
  await flushPromises();

  expect(router.currentRoute.value.query.rev).toBe("3");
  const diff = requests.find(
    (entry) =>
      entry.method === "GET" &&
      entry.path === "/api/projects/p1/revisions/3",
  );
  expect(diff).toBeDefined();
  expect(wrapper.text()).toContain("修订 3 的变更");
  wrapper.unmount();
});

test("opening with ?rev= shows the diff immediately", async () => {
  const { wrapper, requests } = await mountPage("/projects/p1/history?rev=2");
  await flushPromises();

  const diff = requests.find(
    (entry) =>
      entry.method === "GET" &&
      entry.path === "/api/projects/p1/revisions/2",
  );
  expect(diff).toBeDefined();
  expect(wrapper.text()).toContain("修订 2 的变更");
  expect(wrapper.text()).toContain("Class: Pizza");
  wrapper.unmount();
});

async function mountPage(route: string) {
  return renderWithProviders(RevisionHistoryPage, {
    route,
    routes: [
      {
        path: "/projects/:projectId",
        children: [
          {
            path: "history",
            name: "history",
            component: RevisionHistoryPage,
          },
          {
            path: ":kind(class|object-property|data-property|annotation-property|individual)",
            name: "workspace",
            component: { template: "<div />" },
          },
        ],
      },
    ],
    handlers: {
      "GET /api/projects/{id}/revisions": () => ({ json: REVISION_PAGE }),
      "GET /api/projects/{id}/revisions/{rev}": (req) => ({
        json: revisionDiff(Number(req.params.rev)),
      }),
    },
  });
}
