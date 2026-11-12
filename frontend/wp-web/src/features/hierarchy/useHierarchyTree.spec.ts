import { flushPromises } from "@vue/test-utils";
import type { EntityNode } from "@wp/api-types";
import { defineComponent, h, ref } from "vue";
import { expect, test } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { KEY_SEPARATOR } from "./treeNodes";
import {
  useHierarchyTree,
  type UseHierarchyTreeResult,
} from "./useHierarchyTree";

const OWL_THING: EntityNode = {
  iri: "http://www.w3.org/2002/07/owl#Thing",
  entityType: "CLASS",
  displayName: "owl:Thing",
  hasChildren: true,
  deprecated: false,
};

const CLASS_A: EntityNode = {
  iri: "http://ex.org/A",
  entityType: "CLASS",
  displayName: "A",
  hasChildren: true,
  deprecated: false,
};

const CLASS_B: EntityNode = {
  iri: "http://ex.org/B",
  entityType: "CLASS",
  displayName: "B",
  hasChildren: false,
  deprecated: false,
};

const CLASS_C: EntityNode = {
  iri: "http://ex.org/C",
  entityType: "CLASS",
  displayName: "C",
  hasChildren: false,
  deprecated: false,
};

const CLASS_SHARED: EntityNode = {
  iri: "http://ex.org/Shared",
  entityType: "CLASS",
  displayName: "Shared",
  hasChildren: false,
  deprecated: false,
};

async function mountTree(): Promise<{
  wrapper: Awaited<ReturnType<typeof renderWithProviders>>["wrapper"];
  requests: Awaited<ReturnType<typeof renderWithProviders>>["requests"];
  queryClient: Awaited<ReturnType<typeof renderWithProviders>>["queryClient"];
  api: UseHierarchyTreeResult;
}> {
  const holder: { api?: UseHierarchyTreeResult } = {};
  const Probe = defineComponent({
    name: "HierarchyTreeProbe",
    setup() {
      holder.api = useHierarchyTree(ref("p1"), ref("class"));
      return () =>
        h(
          "pre",
          { "data-testid": "tree-json" },
          JSON.stringify({
            roots: holder.api?.treeData.value.map((node) => ({
              key: node.key,
              iri: node.iri,
              placeholderChildren: node.children?.map(
                (child) => child.placeholder === true,
              ),
            })),
            expanded: holder.api?.expandedKeys.value,
          }),
        );
    },
  });

  const rendered = await renderWithProviders(Probe, {
    handlers: {
      "GET /api/projects/{id}/hierarchies/{kind}/roots": () => ({
        json: [OWL_THING],
      }),
      "GET /api/projects/{id}/hierarchies/{kind}/children": (req) => {
        const parent = req.query.get("parent");
        if (parent === OWL_THING.iri) {
          return { json: [CLASS_A, CLASS_SHARED] };
        }
        if (parent === CLASS_A.iri) {
          return { json: [CLASS_B, CLASS_SHARED] };
        }
        return { json: [] };
      },
      "GET /api/projects/{id}/hierarchies/{kind}/paths": (req) => {
        const entity = req.query.get("entity");
        if (entity === CLASS_B.iri) {
          return { json: [[OWL_THING, CLASS_A, CLASS_B]] };
        }
        return { json: [] };
      },
    },
  });
  await flushPromises();
  if (holder.api === undefined) {
    throw new Error("useHierarchyTree was not created");
  }
  return { ...rendered, api: holder.api };
}

test("roots render with a placeholder child when hasChildren is true", async () => {
  const { wrapper, api } = await mountTree();
  const root = api.treeData.value[0];
  expect(root?.iri).toBe(OWL_THING.iri);
  expect(root?.children).toHaveLength(1);
  expect(root?.children?.[0]?.placeholder).toBe(true);
  wrapper.unmount();
});

test("expand loads children once and a second expand does not refetch", async () => {
  const { wrapper, api, requests } = await mountTree();
  const rootKey = api.treeData.value[0]?.key;
  expect(rootKey).toBeDefined();

  await api.expand(rootKey!);
  await flushPromises();
  const afterFirst = requests.filter((request) =>
    request.path.endsWith("/children"),
  ).length;

  await api.expand(rootKey!);
  await flushPromises();
  const afterSecond = requests.filter((request) =>
    request.path.endsWith("/children"),
  ).length;

  expect(afterFirst).toBe(1);
  expect(afterSecond).toBe(1);
  expect(
    api.treeData.value[0]?.children?.every((child) => child.placeholder !== true),
  ).toBe(true);
  expect(api.expandedKeys.value).toContain(rootKey);
  wrapper.unmount();
});

test("the same class under two parents gets distinct keys", async () => {
  const { wrapper, api } = await mountTree();
  const rootKey = api.treeData.value[0]!.key;
  await api.expand(rootKey);
  await flushPromises();

  const underThing = api.treeData.value[0]?.children?.find(
    (node) => node.iri === CLASS_SHARED.iri,
  );
  const classA = api.treeData.value[0]?.children?.find(
    (node) => node.iri === CLASS_A.iri,
  );
  expect(classA).toBeDefined();
  await api.expand(classA!.key);
  await flushPromises();

  const classAAfter = api.treeData.value[0]?.children?.find(
    (node) => node.iri === CLASS_A.iri,
  );
  const underA = classAAfter?.children?.find(
    (node) => node.iri === CLASS_SHARED.iri,
  );
  expect(underThing?.key).toBe(
    `${OWL_THING.iri}${KEY_SEPARATOR}${CLASS_SHARED.iri}`,
  );
  expect(underA?.key).toBe(
    `${OWL_THING.iri}${KEY_SEPARATOR}${CLASS_A.iri}${KEY_SEPARATOR}${CLASS_SHARED.iri}`,
  );
  expect(underThing?.key).not.toBe(underA?.key);
  wrapper.unmount();
});

test("locate expands ancestors along the first path", async () => {
  const { wrapper, api, requests } = await mountTree();
  const targetKey = await api.locate(CLASS_B.iri);
  await flushPromises();

  expect(targetKey).toBe(
    [OWL_THING.iri, CLASS_A.iri, CLASS_B.iri].join(KEY_SEPARATOR),
  );
  expect(api.expandedKeys.value).toEqual(
    expect.arrayContaining([
      OWL_THING.iri,
      [OWL_THING.iri, CLASS_A.iri].join(KEY_SEPARATOR),
    ]),
  );
  expect(
    requests.some(
      (request) =>
        request.path.endsWith("/paths") &&
        request.query.get("entity") === CLASS_B.iri,
    ),
  ).toBe(true);
  wrapper.unmount();
});

test("refetched children add new nodes and drop removed ones", async () => {
  const childrenState = { current: [CLASS_B, CLASS_SHARED] as EntityNode[] };
  const holder: { api?: UseHierarchyTreeResult } = {};
  const Probe = defineComponent({
    name: "HierarchyRefreshProbe",
    setup() {
      holder.api = useHierarchyTree(ref("p1"), ref("class"));
      return () => h("div");
    },
  });

  const { wrapper, queryClient } = await renderWithProviders(Probe, {
    handlers: {
      "GET /api/projects/{id}/hierarchies/{kind}/roots": () => ({
        json: [OWL_THING],
      }),
      "GET /api/projects/{id}/hierarchies/{kind}/children": (req) => {
        const parent = req.query.get("parent");
        if (parent === OWL_THING.iri) {
          return { json: [CLASS_A] };
        }
        if (parent === CLASS_A.iri) {
          return { json: childrenState.current };
        }
        return { json: [] };
      },
      "GET /api/projects/{id}/hierarchies/{kind}/paths": () => ({ json: [] }),
    },
  });
  await flushPromises();
  const api = holder.api;
  if (api === undefined) {
    throw new Error("missing api");
  }

  await api.expand(OWL_THING.iri);
  await flushPromises();
  const classAKey = [OWL_THING.iri, CLASS_A.iri].join(KEY_SEPARATOR);
  await api.expand(classAKey);
  await flushPromises();

  expect(
    api.treeData.value[0]?.children?.[0]?.children?.map((node) => node.iri),
  ).toEqual([CLASS_B.iri, CLASS_SHARED.iri]);

  childrenState.current = [CLASS_SHARED, CLASS_C];
  await queryClient.refetchQueries({
    queryKey: queryKeys.hierarchyChildren("p1", "class", CLASS_A.iri),
  });
  await flushPromises();

  expect(
    api.treeData.value[0]?.children?.[0]?.children?.map((node) => node.iri),
  ).toEqual([CLASS_SHARED.iri, CLASS_C.iri]);
  wrapper.unmount();
});
