import { flushPromises } from "@vue/test-utils";
import type { EntityNode, IndividualPage } from "@wp/api-types";
import { defineComponent, h } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import IndividualsPanel from "./IndividualsPanel.vue";

const OWL_THING: EntityNode = {
  iri: "http://www.w3.org/2002/07/owl#Thing",
  entityType: "CLASS",
  displayName: "owl:Thing",
  hasChildren: true,
  deprecated: false,
};

const PIZZA: EntityNode = {
  iri: "http://ex.org/Pizza",
  entityType: "CLASS",
  displayName: "Pizza",
  hasChildren: false,
  deprecated: false,
};

const MARGHERITA: EntityNode = {
  iri: "http://ex.org/ont#Margherita",
  entityType: "NAMED_INDIVIDUAL",
  displayName: "Margherita",
  hasChildren: false,
  deprecated: false,
};

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
  localStorage.removeItem("wp.workspace.layout");
});

test("selecting a class sets type in the URL and loads individuals", async () => {
  const { wrapper, router, requests } = await mountPanel();
  await flushPromises();

  await expandAndSelectClass(wrapper, "Pizza");
  await flushPromises();

  expect(router.currentRoute.value.query.type).toBe(PIZZA.iri);
  expect(
    requests.some(
      (entry) =>
        entry.method === "GET" &&
        entry.path === "/api/projects/p1/individuals" &&
        entry.query.get("type") === PIZZA.iri,
    ),
  ).toBe(true);
  expect(wrapper.text()).toContain("Margherita");
  wrapper.unmount();
});

test("selecting an individual sets entity while keeping type", async () => {
  const { wrapper, router } = await mountPanel(
    `/projects/p1/individual?type=${encodeURIComponent(PIZZA.iri)}`,
  );
  await flushPromises();

  expect(wrapper.text()).toContain("Margherita");

  const row = wrapper
    .findAll(".el-table__row")
    .find((entry) => entry.text().includes("Margherita"));
  expect(row).toBeDefined();
  await row!.trigger("click");
  await flushPromises();

  expect(router.currentRoute.value.query.type).toBe(PIZZA.iri);
  expect(router.currentRoute.value.query.entity).toBe(MARGHERITA.iri);
  wrapper.unmount();
});

test("remounting the same URL restores type and entity selection", async () => {
  const route = `/projects/p1/individual?type=${encodeURIComponent(PIZZA.iri)}&entity=${encodeURIComponent(MARGHERITA.iri)}`;
  const first = await mountPanel(route);
  await flushPromises();

  expect(first.router.currentRoute.value.query.type).toBe(PIZZA.iri);
  expect(first.router.currentRoute.value.query.entity).toBe(MARGHERITA.iri);
  expect(first.wrapper.text()).toContain("Margherita");
  expect(first.wrapper.text()).toContain("Pizza");
  first.wrapper.unmount();

  const second = await mountPanel(route);
  await flushPromises();

  expect(second.router.currentRoute.value.query.type).toBe(PIZZA.iri);
  expect(second.router.currentRoute.value.query.entity).toBe(MARGHERITA.iri);
  expect(second.wrapper.text()).toContain("Margherita");
  expect(second.wrapper.text()).toContain("Pizza");
  second.wrapper.unmount();
});

async function expandAndSelectClass(
  wrapper: Awaited<ReturnType<typeof mountPanel>>["wrapper"],
  label: string,
): Promise<void> {
  const expandIcon = wrapper.find(".el-tree-node__expand-icon");
  expect(expandIcon.exists()).toBe(true);
  await expandIcon.trigger("click");
  await flushPromises();

  const content = wrapper
    .findAll(".el-tree-node__content")
    .find((node) => node.text().includes(label));
  expect(content).toBeDefined();
  await content!.trigger("click");
}

async function mountPanel(route = "/projects/p1/individual") {
  const Host = defineComponent({
    name: "IndividualsPanelHost",
    setup() {
      return () => h(IndividualsPanel, { projectId: "p1" });
    },
  });
  return renderWithProviders(Host, {
    route,
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
    handlers: {
      "GET /api/projects/{id}/hierarchies/{kind}/roots": () => ({
        json: [OWL_THING],
      }),
      "GET /api/projects/{id}/hierarchies/{kind}/children": (req: {
        query: URLSearchParams;
      }) => {
        if (req.query.get("parent") === OWL_THING.iri) {
          return { json: [PIZZA] };
        }
        return { json: [] };
      },
      "GET /api/projects/{id}/hierarchies/{kind}/paths": (req: {
        query: URLSearchParams;
      }) => {
        if (req.query.get("entity") === PIZZA.iri) {
          return { json: [[OWL_THING, PIZZA]] };
        }
        return { json: [] };
      },
      "GET /api/projects/{id}/individuals": (req: {
        query: URLSearchParams;
      }) => {
        if (req.query.get("type") !== PIZZA.iri) {
          return {
            json: {
              individuals: [],
              page: 0,
              size: 50,
              total: 0,
            } satisfies IndividualPage,
          };
        }
        return {
          json: {
            individuals: [MARGHERITA],
            page: 0,
            size: 50,
            total: 1,
          } satisfies IndividualPage,
        };
      },
    },
  });
}
