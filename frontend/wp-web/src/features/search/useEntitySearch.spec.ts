import type { EntityNode } from "@wp/api-types";
import { flushPromises } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { afterEach, beforeEach, expect, test, vi } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import {
  GLOBAL_SEARCH_TYPES,
  groupSearchResults,
  useEntitySearch,
  type UseEntitySearchResult,
} from "./useEntitySearch";

const CLASS_A: EntityNode = {
  iri: "http://ex.org#A",
  entityType: "CLASS",
  displayName: "A",
  hasChildren: false,
  deprecated: false,
};

const CLASS_B: EntityNode = {
  iri: "http://ex.org#B",
  entityType: "CLASS",
  displayName: "B",
  hasChildren: false,
  deprecated: false,
};

const OBJ: EntityNode = {
  iri: "http://ex.org#hasPart",
  entityType: "OBJECT_PROPERTY",
  displayName: "hasPart",
  hasChildren: false,
  deprecated: false,
};

const IND: EntityNode = {
  iri: "http://ex.org#i1",
  entityType: "NAMED_INDIVIDUAL",
  displayName: "i1",
  hasChildren: false,
  deprecated: false,
};

const DT: EntityNode = {
  iri: "http://www.w3.org/2001/XMLSchema#string",
  entityType: "DATATYPE",
  displayName: "string",
  hasChildren: false,
  deprecated: false,
};

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(() => {
  vi.useRealTimers();
  localStorage.removeItem("wp.locale");
});

test("blank trimmed input does not request", async () => {
  const { api, wrapper, requests } = await mountSearch();
  api.query.value = "   ";
  await flushPromises();
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();

  expect(requests).toHaveLength(0);
  expect(api.groups.value).toEqual([]);
  wrapper.unmount();
});

test("rapid input only sends one request for the final query", async () => {
  const { api, wrapper, requests } = await mountSearch({
    "GET /api/projects/{id}/search": () => ({
      json: [CLASS_A],
    }),
  });

  api.query.value = "p";
  await flushPromises();
  await vi.advanceTimersByTimeAsync(100);
  api.query.value = "pa";
  await flushPromises();
  await vi.advanceTimersByTimeAsync(100);
  api.query.value = "pal";
  await flushPromises();
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();

  const searchRequests = requests.filter(
    (request) => request.path === "/api/projects/p1/search",
  );
  expect(searchRequests).toHaveLength(1);
  expect(searchRequests[0]?.query.get("q")).toBe("pal");
  expect(searchRequests[0]?.query.get("limit")).toBe("50");
  expect(api.groups.value).toEqual([
    { entityType: "CLASS", items: [CLASS_A] },
  ]);
  wrapper.unmount();
});

test("results are grouped by entity type without reordering within a group", () => {
  const groups = groupSearchResults([IND, CLASS_B, DT, OBJ, CLASS_A]);
  expect(groups).toEqual([
    { entityType: "CLASS", items: [CLASS_B, CLASS_A] },
    { entityType: "OBJECT_PROPERTY", items: [OBJ] },
    { entityType: "NAMED_INDIVIDUAL", items: [IND] },
    { entityType: "DATATYPE", items: [DT] },
  ]);
});

test("search query key uses projectId, q, types, and limit 50", async () => {
  const { api, wrapper, queryClient } = await mountSearch({
    "GET /api/projects/{id}/search": () => ({ json: [] }),
  });

  api.query.value = "owl";
  await flushPromises();
  await vi.advanceTimersByTimeAsync(250);
  await flushPromises();

  expect(
    queryClient.getQueryData(
      queryKeys.search("p1", "owl", [...GLOBAL_SEARCH_TYPES], 50),
    ),
  ).toEqual([]);
  wrapper.unmount();
});

async function mountSearch(
  handlers: Parameters<typeof renderWithProviders>[1] extends infer T
    ? T extends { handlers?: infer H }
      ? H
      : never
    : never = {},
) {
  let api!: UseEntitySearchResult & { query: ReturnType<typeof ref<string>> };
  const Host = defineComponent({
    name: "EntitySearchHost",
    setup() {
      const query = ref("");
      const search = useEntitySearch("p1", query);
      api = { ...search, query };
      return () =>
        h("div", {
          "data-groups": String(search.groups.value.length),
          "data-q": query.value,
        });
    },
  });
  const rendered = await renderWithProviders(Host, {
    handlers,
    route: "/projects/p1/class",
    routes: [
      { path: "/projects/:projectId/:kind?", component: Host },
    ],
  });
  return { ...rendered, api };
}
