import { flushPromises } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { defineComponent, h, reactive } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { WORKSPACE_KIND_PATH } from "@/features/workspace/routes";
import type { HierarchyKind } from "@/features/workspace/hierarchyKinds";
import PropertyHierarchyPanel from "./PropertyHierarchyPanel.vue";

const PROPERTY_KINDS = [
  "object-property",
  "data-property",
  "annotation-property",
] as const satisfies readonly HierarchyKind[];

const ROOTS: Record<(typeof PROPERTY_KINDS)[number], EntityNode> = {
  "object-property": {
    iri: "http://www.w3.org/2002/07/owl#topObjectProperty",
    entityType: "OBJECT_PROPERTY",
    displayName: "owl:topObjectProperty",
    hasChildren: true,
    deprecated: false,
  },
  "data-property": {
    iri: "http://www.w3.org/2002/07/owl#topDataProperty",
    entityType: "DATA_PROPERTY",
    displayName: "owl:topDataProperty",
    hasChildren: false,
    deprecated: false,
  },
  "annotation-property": {
    iri: "http://www.w3.org/2000/01/rdf-schema#label",
    entityType: "ANNOTATION_PROPERTY",
    displayName: "rdfs:label",
    hasChildren: false,
    deprecated: false,
  },
};

const CHILD: EntityNode = {
  iri: "http://ex.org/hasPart",
  entityType: "OBJECT_PROPERTY",
  displayName: "hasPart",
  hasChildren: false,
  deprecated: false,
};

afterEach(() => {
  vi.unstubAllGlobals();
  localStorage.removeItem("wp.locale");
  localStorage.removeItem("wp.workspace.layout");
});

function rootsHandlers() {
  return {
    "GET /api/projects/{id}/hierarchies/{kind}/roots": (req: {
      params: Record<string, string>;
    }) => {
      const raw = req.params.kind;
      const root =
        raw === "object-property" ||
        raw === "data-property" ||
        raw === "annotation-property"
          ? ROOTS[raw]
          : undefined;
      return { json: root === undefined ? [] : [root] };
    },
    "GET /api/projects/{id}/hierarchies/{kind}/children": () => ({
      json: [CHILD],
    }),
    "GET /api/projects/{id}/hierarchies/{kind}/paths": () => ({
      json: [],
    }),
  };
}

for (const kind of PROPERTY_KINDS) {
  test(`mounts ${kind} and requests /hierarchies/${kind}/roots`, async () => {
    const { wrapper, requests } = await mountPanel(kind);
    await flushPromises();

    expect(
      requests.some(
        (request) =>
          request.method === "GET" &&
          request.path === `/api/projects/p1/hierarchies/${kind}/roots`,
      ),
    ).toBe(true);
    expect(wrapper.text()).toContain(ROOTS[kind].displayName);
    wrapper.unmount();
  });
}

test("switching kind does not reuse the previous hierarchy cache", async () => {
  const state = reactive<{ kind: (typeof PROPERTY_KINDS)[number] }>({
    kind: "object-property",
  });
  const Host = defineComponent({
    name: "PropertyHierarchySwitchHost",
    setup() {
      return () =>
        h(PropertyHierarchyPanel, {
          projectId: "p1",
          kind: state.kind,
        });
    },
  });

  const { wrapper, requests } = await renderWithProviders(Host, {
    route: "/projects/p1/object-property",
    routes: workspaceRoutes(Host),
    handlers: rootsHandlers(),
  });
  await flushPromises();

  expect(wrapper.text()).toContain("owl:topObjectProperty");
  expect(wrapper.text()).not.toContain("owl:topDataProperty");

  state.kind = "data-property";
  await flushPromises();
  await flushPromises();

  expect(
    requests.some(
      (request) =>
        request.path === "/api/projects/p1/hierarchies/data-property/roots",
    ),
  ).toBe(true);
  expect(wrapper.text()).toContain("owl:topDataProperty");
  expect(wrapper.text()).not.toContain("owl:topObjectProperty");
  wrapper.unmount();
});

test("selecting a property updates entity in the URL and keeps kind", async () => {
  const { wrapper, router, requests } = await mountPanel("object-property");
  await flushPromises();

  const expandIcon = wrapper.find(".el-tree-node__expand-icon");
  expect(expandIcon.exists()).toBe(true);
  await expandIcon.trigger("click");
  await flushPromises();

  expect(requests.some((request) => request.path.endsWith("/children"))).toBe(
    true,
  );

  const content = wrapper
    .findAll(".el-tree-node__content")
    .find((node) => node.text().includes("hasPart"));
  expect(content).toBeDefined();
  await content!.trigger("click");
  await flushPromises();

  expect(router.currentRoute.value.path).toBe("/projects/p1/object-property");
  expect(router.currentRoute.value.query.entity).toBe(CHILD.iri);
  expect(router.currentRoute.value.params.kind).toBe("object-property");
  wrapper.unmount();
});

async function mountPanel(kind: (typeof PROPERTY_KINDS)[number]) {
  const Host = defineComponent({
    name: "PropertyHierarchyPanelHost",
    setup() {
      return () =>
        h(PropertyHierarchyPanel, {
          projectId: "p1",
          kind,
        });
    },
  });
  return renderWithProviders(Host, {
    route: `/projects/p1/${kind}`,
    routes: workspaceRoutes(Host),
    handlers: rootsHandlers(),
  });
}

function workspaceRoutes(
  component: ReturnType<typeof defineComponent>,
) {
  return [
    {
      path: "/projects/:projectId",
      children: [
        {
          path: WORKSPACE_KIND_PATH,
          name: "workspace",
          component,
        },
      ],
    },
  ];
}
