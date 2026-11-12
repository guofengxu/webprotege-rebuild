import { flushPromises } from "@vue/test-utils";
import type { EntityNode, IndividualPage } from "@wp/api-types";
import { defineComponent, h, nextTick, reactive } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import {
  renderWithProviders,
  type RenderWithProvidersOptions,
} from "@/shared/testing/renderWithProviders";
import type { MockRequest } from "@/shared/testing/mockFetch";
import IndividualList from "./IndividualList.vue";

const TYPE_IRI = "http://ex.org/Pizza";

const INDIVIDUALS: EntityNode[] = [
  {
    iri: "http://ex.org/ont#Margherita",
    entityType: "NAMED_INDIVIDUAL",
    displayName: "Margherita",
    hasChildren: false,
    deprecated: false,
  },
  {
    iri: "http://ex.org/ont#OldPizza",
    entityType: "NAMED_INDIVIDUAL",
    displayName: "OldPizza",
    hasChildren: false,
    deprecated: true,
  },
];

const PAGE_ONE: EntityNode[] = Array.from({ length: 50 }, (_, index) => ({
  iri: `http://ex.org/ont#Ind${index}`,
  entityType: "NAMED_INDIVIDUAL" as const,
  displayName: `Ind${index}`,
  hasChildren: false,
  deprecated: false,
}));

const PAGE_TWO: EntityNode[] = [
  {
    iri: "http://ex.org/ont#Ind50",
    entityType: "NAMED_INDIVIDUAL",
    displayName: "Ind50",
    hasChildren: false,
    deprecated: false,
  },
];

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
});

test("requests individuals with type, page=0, and size", async () => {
  const { wrapper, requests } = await mountList({ typeIri: TYPE_IRI });
  await flushPromises();

  const request = requests.find(
    (entry) =>
      entry.method === "GET" &&
      entry.path === "/api/projects/p1/individuals",
  );
  expect(request).toBeDefined();
  expect(request!.query.get("type")).toBe(TYPE_IRI);
  expect(request!.query.get("page")).toBe("0");
  expect(request!.query.get("size")).toBe("50");
  expect(wrapper.text()).toContain("Margherita");
  expect(wrapper.text()).toContain("OldPizza");
  wrapper.unmount();
});

test("does not request when typeIri is empty", async () => {
  const { wrapper, requests } = await mountList({ typeIri: "" });
  await flushPromises();

  expect(
    requests.some((entry) => entry.path === "/api/projects/p1/individuals"),
  ).toBe(false);
  expect(wrapper.text()).toContain("请先在上方选择一个类");
  wrapper.unmount();
});

test("changing page issues a new request and keeps previous rows until it arrives", async () => {
  let resolvePageTwo: ((page: IndividualPage) => void) | undefined;
  const pageTwoPromise = new Promise<IndividualPage>((resolve) => {
    resolvePageTwo = resolve;
  });

  const { wrapper, requests } = await mountList({
    typeIri: TYPE_IRI,
    handlers: {
      "GET /api/projects/{id}/individuals": (req: MockRequest) => {
        const page = Number(req.query.get("page") ?? "0");
        if (page === 0) {
          return {
            json: {
              individuals: PAGE_ONE,
              page: 0,
              size: 50,
              total: 51,
            } satisfies IndividualPage,
          };
        }
        return pageTwoPromise.then((json) => ({ json }));
      },
    },
  });
  await flushPromises();

  expect(wrapper.text()).toContain("Ind0");
  expect(wrapper.text()).not.toContain("Ind50");

  const nextButton = wrapper.find(
    ".el-pagination .btn-next, .el-pagination button.btn-next",
  );
  expect(nextButton.exists()).toBe(true);
  await nextButton.trigger("click");
  await flushPromises();
  await nextTick();

  expect(
    requests.some(
      (entry) =>
        entry.path === "/api/projects/p1/individuals" &&
        entry.query.get("page") === "1",
    ),
  ).toBe(true);
  expect(wrapper.text()).toContain("Ind0");
  expect(wrapper.text()).not.toContain("Ind50");

  resolvePageTwo?.({
    individuals: PAGE_TWO,
    page: 1,
    size: 50,
    total: 51,
  });
  await flushPromises();
  await nextTick();

  expect(wrapper.text()).toContain("Ind50");
  expect(wrapper.text()).not.toContain("Ind0");
  wrapper.unmount();
});

test("total drives pagination page count", async () => {
  const { wrapper } = await mountList({
    typeIri: TYPE_IRI,
    handlers: {
      "GET /api/projects/{id}/individuals": () => ({
        json: {
          individuals: PAGE_ONE,
          page: 0,
          size: 50,
          total: 120,
        } satisfies IndividualPage,
      }),
    },
  });
  await flushPromises();

  const pager = wrapper.find(".el-pagination");
  expect(pager.exists()).toBe(true);
  expect(pager.text()).toMatch(/3/);
  wrapper.unmount();
});

test("clicking a row emits select with the EntityNode", async () => {
  const { wrapper } = await mountList({ typeIri: TYPE_IRI });
  await flushPromises();

  const row = wrapper
    .findAll(".el-table__row")
    .find((entry) => entry.text().includes("Margherita"));
  expect(row).toBeDefined();
  await row!.trigger("click");
  await flushPromises();

  const list = wrapper.getComponent(IndividualList);
  const emitted = list.emitted("select");
  expect(emitted).toBeTruthy();
  expect(emitted?.[0]?.[0]).toEqual(INDIVIDUALS[0]);
  wrapper.unmount();
});

test("deprecated individuals carry the is-deprecated class", async () => {
  const { wrapper } = await mountList({ typeIri: TYPE_IRI });
  await flushPromises();

  const deprecated = wrapper.findAll(".is-deprecated");
  expect(deprecated.some((node) => node.text().includes("OldPizza"))).toBe(
    true,
  );
  wrapper.unmount();
});

test("shows empty state when the selected class has no individuals", async () => {
  const { wrapper } = await mountList({
    typeIri: TYPE_IRI,
    handlers: {
      "GET /api/projects/{id}/individuals": () => ({
        json: {
          individuals: [],
          page: 0,
          size: 50,
          total: 0,
        } satisfies IndividualPage,
      }),
    },
  });
  await flushPromises();

  expect(wrapper.text()).toContain("该类没有直接个体");
  wrapper.unmount();
});

async function mountList(options: {
  typeIri: string;
  selectedIri?: string | null;
  handlers?: RenderWithProvidersOptions["handlers"];
}) {
  const props = reactive({
    projectId: "p1",
    typeIri: options.typeIri,
    selectedIri: options.selectedIri ?? null,
  });
  const Host = defineComponent({
    name: "IndividualListHost",
    setup() {
      return () =>
        h(IndividualList, {
          projectId: props.projectId,
          typeIri: props.typeIri,
          selectedIri: props.selectedIri,
        });
    },
  });
  return renderWithProviders(Host, {
    handlers: options.handlers ?? defaultHandlers(),
  });
}

function defaultHandlers() {
  return {
    "GET /api/projects/{id}/individuals": () => ({
      json: {
        individuals: INDIVIDUALS,
        page: 0,
        size: 50,
        total: INDIVIDUALS.length,
      } satisfies IndividualPage,
    }),
  };
}
