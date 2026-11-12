import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { REVISION_PAGE } from "./fixtures";
import EntityHistoryTab from "./EntityHistoryTab.vue";
import RevisionHistoryPage from "./RevisionHistoryPage.vue";

const ENTITY_IRI = "http://ex.org/Pizza";

afterEach(() => {
  localStorage.removeItem("wp.locale");
});

test("requests entity history with iri and links to history?rev=", async () => {
  const { wrapper, router, requests } = await mountTab();
  await flushPromises();

  const request = requests.find(
    (entry) =>
      entry.method === "GET" &&
      entry.path === "/api/projects/p1/entities/history",
  );
  expect(request).toBeDefined();
  expect(request!.query.get("iri")).toBe(ENTITY_IRI);
  expect(request!.query.get("page")).toBe("0");

  const link = wrapper
    .findAll("a")
    .find((anchor) => anchor.text() === "查看差异");
  expect(link).toBeDefined();
  await link!.trigger("click");
  await flushPromises();

  expect(router.currentRoute.value.path).toBe("/projects/p1/history");
  expect(router.currentRoute.value.query.rev).toBe("3");
  wrapper.unmount();
});

async function mountTab() {
  const Host = defineComponent({
    name: "EntityHistoryHost",
    setup() {
      return () =>
        h(EntityHistoryTab, {
          projectId: "p1",
          iri: ENTITY_IRI,
        });
    },
  });
  return renderWithProviders(Host, {
    route: `/projects/p1/class?entity=${encodeURIComponent(ENTITY_IRI)}`,
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
            component: Host,
          },
        ],
      },
    ],
    handlers: {
      "GET /api/projects/{id}/entities/history": () => ({ json: REVISION_PAGE }),
      "GET /api/projects/{id}/revisions": () => ({ json: REVISION_PAGE }),
      "GET /api/projects/{id}/revisions/{rev}": () => ({
        json: {
          revNo: 3,
          changes: [],
          limit: 200,
          total: 0,
        },
      }),
    },
  });
}
