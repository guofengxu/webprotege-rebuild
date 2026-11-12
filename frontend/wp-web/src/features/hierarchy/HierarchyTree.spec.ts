import { flushPromises } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import HierarchyTree from "./HierarchyTree.vue";

const OWL_THING: EntityNode = {
  iri: "http://www.w3.org/2002/07/owl#Thing",
  entityType: "CLASS",
  displayName: "owl:Thing",
  hasChildren: true,
  deprecated: false,
};

const ACTIVE: EntityNode = {
  iri: "http://ex.org/Active",
  entityType: "CLASS",
  displayName: "Active",
  hasChildren: false,
  deprecated: false,
};

const DEPRECATED: EntityNode = {
  iri: "http://ex.org/Old",
  entityType: "CLASS",
  displayName: "Old",
  hasChildren: false,
  deprecated: true,
};

const TARGET: EntityNode = {
  iri: "http://ex.org/Target",
  entityType: "CLASS",
  displayName: "Target",
  hasChildren: false,
  deprecated: false,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

function handlers() {
  return {
    "GET /api/projects/{id}/hierarchies/{kind}/roots": () => ({
      json: [OWL_THING],
    }),
    "GET /api/projects/{id}/hierarchies/{kind}/children": (req: {
      query: URLSearchParams;
    }) => {
      const parent = req.query.get("parent");
      if (parent === OWL_THING.iri) {
        return { json: [ACTIVE, DEPRECATED, TARGET] };
      }
      return { json: [] };
    },
    "GET /api/projects/{id}/hierarchies/{kind}/paths": (req: {
      query: URLSearchParams;
    }) => {
      const entity = req.query.get("entity");
      if (entity === TARGET.iri) {
        return { json: [[OWL_THING, TARGET]] };
      }
      return { json: [] };
    },
  };
}

test("deprecated nodes carry the is-deprecated class", async () => {
  const { wrapper, requests } = await renderWithProviders(HierarchyTree, {
    props: { projectId: "p1", kind: "class", height: 240 },
    handlers: handlers(),
  });
  await flushPromises();

  const expandIcon = wrapper.find(".el-tree-node__expand-icon");
  expect(expandIcon.exists()).toBe(true);
  await expandIcon.trigger("click");
  await flushPromises();

  expect(requests.some((request) => request.path.endsWith("/children"))).toBe(
    true,
  );

  const deprecated = wrapper.findAll(".is-deprecated");
  expect(deprecated.length).toBeGreaterThan(0);
  expect(deprecated.some((node) => node.text().includes("Old"))).toBe(true);
  wrapper.unmount();
});

test("clicking a node emits select with the EntityNode", async () => {
  const { wrapper } = await renderWithProviders(HierarchyTree, {
    props: { projectId: "p1", kind: "class", height: 240 },
    handlers: handlers(),
  });
  await flushPromises();

  const expandIcon = wrapper.find(".el-tree-node__expand-icon");
  await expandIcon.trigger("click");
  await flushPromises();

  const content = wrapper
    .findAll(".el-tree-node__content")
    .find((node) => node.text().includes("Active"));
  expect(content).toBeDefined();
  await content!.trigger("click");
  await flushPromises();

  const tree = wrapper.getComponent(HierarchyTree);
  const emitted = tree.emitted("select");
  expect(emitted).toBeTruthy();
  expect(emitted?.[0]?.[0]).toEqual(ACTIVE);
  wrapper.unmount();
});

test("contextmenu on a node label emits contextmenu with the EntityNode", async () => {
  const { wrapper } = await renderWithProviders(HierarchyTree, {
    props: { projectId: "p1", kind: "class", height: 240 },
    handlers: handlers(),
  });
  await flushPromises();

  const expandIcon = wrapper.find(".el-tree-node__expand-icon");
  await expandIcon.trigger("click");
  await flushPromises();

  const label = wrapper
    .findAll(".hierarchy-tree__label")
    .find((node) => node.text().includes("Active"));
  expect(label).toBeDefined();
  await label!.trigger("contextmenu");
  await flushPromises();

  const tree = wrapper.getComponent(HierarchyTree);
  const emitted = tree.emitted("contextmenu");
  expect(emitted).toBeTruthy();
  expect(emitted?.[0]?.[0]).toEqual(ACTIVE);
  expect(emitted?.[0]?.[1]).toBeInstanceOf(MouseEvent);
  wrapper.unmount();
});

test("mounting with selectedIri calls getHierarchyPaths and highlights the target", async () => {
  const { wrapper, requests } = await renderWithProviders(HierarchyTree, {
    props: {
      projectId: "p1",
      kind: "class",
      height: 240,
      selectedIri: TARGET.iri,
    },
    handlers: handlers(),
  });
  await flushPromises();
  await flushPromises();

  expect(
    requests.some(
      (request) =>
        request.path.endsWith("/paths") &&
        request.query.get("entity") === TARGET.iri,
    ),
  ).toBe(true);

  const current = wrapper.find(".is-current");
  expect(current.exists()).toBe(true);
  expect(wrapper.text()).toContain("Target");
  wrapper.unmount();
});

test("kind is taken from props and requests the matching hierarchy path", async () => {
  const { wrapper, requests } = await renderWithProviders(HierarchyTree, {
    props: { projectId: "p1", kind: "object-property", height: 240 },
    handlers: handlers(),
  });
  await flushPromises();

  expect(
    requests.some(
      (request) =>
        request.method === "GET" &&
        request.path ===
          "/api/projects/p1/hierarchies/object-property/roots",
    ),
  ).toBe(true);
  expect(
    requests.every(
      (request) => !request.path.includes("/hierarchies/class/"),
    ),
  ).toBe(true);
  wrapper.unmount();
});
