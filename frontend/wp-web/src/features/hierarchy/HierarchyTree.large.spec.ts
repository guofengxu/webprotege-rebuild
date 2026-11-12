import { flushPromises } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import HierarchyTree from "./HierarchyTree.vue";

const PARENT: EntityNode = {
  iri: "http://ex.org/Parent",
  entityType: "CLASS",
  displayName: "Parent",
  hasChildren: true,
  deprecated: false,
};

function makeChildren(count: number): EntityNode[] {
  return Array.from({ length: count }, (_, index) => ({
    iri: `http://ex.org/Child/${index}`,
    entityType: "CLASS" as const,
    displayName: `Child ${index}`,
    hasChildren: false,
    deprecated: false,
  }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

test("10_000 children render via virtual scroll (DOM node count between 1 and 100)", async () => {
  class ResizeObserverStub {
    observe(): void {
      // no-op for jsdom
    }
    unobserve(): void {
      // no-op
    }
    disconnect(): void {
      // no-op
    }
  }
  vi.stubGlobal("ResizeObserver", ResizeObserverStub);

  const children = makeChildren(10_000);
  const { wrapper } = await renderWithProviders(HierarchyTree, {
    props: { projectId: "p1", kind: "class", height: 600 },
    handlers: {
      "GET /api/projects/{id}/hierarchies/{kind}/roots": () => ({
        json: [PARENT],
      }),
      "GET /api/projects/{id}/hierarchies/{kind}/children": () => ({
        json: children,
      }),
      "GET /api/projects/{id}/hierarchies/{kind}/paths": () => ({
        json: [],
      }),
    },
  });
  await flushPromises();

  const expandIcon = wrapper.find(".el-tree-node__expand-icon");
  expect(expandIcon.exists()).toBe(true);
  await expandIcon.trigger("click");
  await flushPromises();

  const renderedLabels = wrapper.findAll(".hierarchy-tree__label");
  expect(renderedLabels.length).toBeGreaterThanOrEqual(1);
  expect(renderedLabels.length).toBeLessThanOrEqual(100);
  wrapper.unmount();
});
