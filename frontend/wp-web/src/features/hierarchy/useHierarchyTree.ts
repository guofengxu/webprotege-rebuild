import { useQuery, useQueryClient } from "@tanstack/vue-query";
import {
  computed,
  onScopeDispose,
  shallowRef,
  watch,
  type Ref,
  type ShallowRef,
} from "vue";
import type { HierarchyKind } from "@/features/workspace/hierarchyKinds";
import { useApiClient } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queryKeys";
import { fetchChildren, fetchPaths, fetchRoots } from "./api";
import {
  asEntityNodes,
  collectKeys,
  findNodeByKey,
  isLoaded,
  pathKey,
  replaceChildrenForParentIri,
  replaceRoots,
  type TreeNodeData,
} from "./treeNodes";

export type UseHierarchyTreeResult = {
  readonly treeData: ShallowRef<TreeNodeData[]>;
  readonly expandedKeys: ShallowRef<string[]>;
  readonly isPending: Ref<boolean>;
  readonly isError: Ref<boolean>;
  readonly error: Ref<unknown>;
  readonly isEmpty: Ref<boolean>;
  refetch: () => Promise<unknown>;
  expand: (nodeKey: string) => Promise<void>;
  collapse: (nodeKey: string) => void;
  locate: (entityIri: string) => Promise<string | null>;
};

export function useHierarchyTree(
  projectId: Ref<string>,
  kind: Ref<HierarchyKind>,
): UseHierarchyTreeResult {
  const client = useApiClient();
  const queryClient = useQueryClient();
  const treeData = shallowRef<TreeNodeData[]>([]);
  const expandedKeys = shallowRef<string[]>([]);

  const rootsQuery = useQuery({
    queryKey: computed(() =>
      queryKeys.hierarchyRoots(projectId.value, kind.value),
    ),
    queryFn: () => fetchRoots(client, projectId.value, kind.value),
    enabled: computed(() => projectId.value.length > 0),
  });

  watch(
    () => rootsQuery.data.value,
    (roots) => {
      if (roots === undefined) {
        return;
      }
      treeData.value = replaceRoots(roots, treeData.value);
      pruneExpandedKeys();
    },
    { immediate: true },
  );

  watch([projectId, kind], () => {
    treeData.value = [];
    expandedKeys.value = [];
  });

  const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
    if (event.type !== "updated") {
      return;
    }
    const query = event.query;
    if (query.state.data === undefined) {
      return;
    }
    const prefix = queryKeys.hierarchy(projectId.value, kind.value);
    if (!hasPrefix(query.queryKey, prefix)) {
      return;
    }
    const childrenPrefix = [...prefix, "children"] as const;
    if (!hasPrefix(query.queryKey, childrenPrefix)) {
      return;
    }
    const parentIri = query.queryKey[childrenPrefix.length];
    if (typeof parentIri !== "string") {
      return;
    }
    const entities = asEntityNodes(query.state.data);
    if (entities === null) {
      return;
    }
    treeData.value = replaceChildrenForParentIri(
      treeData.value,
      parentIri,
      entities,
    );
    pruneExpandedKeys();
  });
  onScopeDispose(() => {
    unsubscribe();
  });

  async function expand(nodeKey: string): Promise<void> {
    const node = findNodeByKey(treeData.value, nodeKey);
    if (node === null || node.node === null || node.node.hasChildren !== true) {
      return;
    }
    if (!isLoaded(node)) {
      const entities = await queryClient.fetchQuery({
        queryKey: queryKeys.hierarchyChildren(
          projectId.value,
          kind.value,
          node.iri,
        ),
        queryFn: () =>
          fetchChildren(client, projectId.value, kind.value, node.iri),
      });
      treeData.value = replaceChildrenForParentIri(
        treeData.value,
        node.iri,
        entities,
      );
      pruneExpandedKeys();
    }
    ensureExpanded(nodeKey);
  }

  function collapse(nodeKey: string): void {
    if (!expandedKeys.value.includes(nodeKey)) {
      return;
    }
    expandedKeys.value = expandedKeys.value.filter((key) => key !== nodeKey);
  }

  async function locate(entityIri: string): Promise<string | null> {
    const paths = await queryClient.fetchQuery({
      queryKey: queryKeys.hierarchyPaths(projectId.value, kind.value, entityIri),
      queryFn: () => fetchPaths(client, projectId.value, kind.value, entityIri),
    });
    const path = paths[0];
    if (path === undefined || path.length === 0) {
      return null;
    }
    if (treeData.value.length === 0) {
      const roots = await queryClient.fetchQuery({
        queryKey: queryKeys.hierarchyRoots(projectId.value, kind.value),
        queryFn: () => fetchRoots(client, projectId.value, kind.value),
      });
      treeData.value = replaceRoots(roots, treeData.value);
    }

    for (let index = 0; index < path.length - 1; index += 1) {
      const ancestorPath = path.slice(0, index + 1).map((entity) => entity.iri);
      const ancestorKey = pathKey(ancestorPath);
      await expand(ancestorKey);
    }
    const targetKey = pathKey(path.map((entity) => entity.iri));
    return findNodeByKey(treeData.value, targetKey) === null ? null : targetKey;
  }

  function ensureExpanded(nodeKey: string): void {
    if (expandedKeys.value.includes(nodeKey)) {
      return;
    }
    expandedKeys.value = [...expandedKeys.value, nodeKey];
  }

  function pruneExpandedKeys(): void {
    const existing = collectKeys(treeData.value);
    const next = expandedKeys.value.filter((key) => existing.has(key));
    if (next.length !== expandedKeys.value.length) {
      expandedKeys.value = next;
    }
  }

  return {
    treeData,
    expandedKeys,
    isPending: computed(() => rootsQuery.isPending.value),
    isError: computed(() => rootsQuery.isError.value),
    error: computed(() => rootsQuery.error.value),
    isEmpty: computed(
      () =>
        rootsQuery.isSuccess.value &&
        (rootsQuery.data.value?.length ?? 0) === 0,
    ),
    refetch: () => rootsQuery.refetch(),
    expand,
    collapse,
    locate,
  };
}

function hasPrefix(
  queryKey: readonly unknown[],
  prefix: readonly unknown[],
): boolean {
  if (queryKey.length < prefix.length) {
    return false;
  }
  return prefix.every((part, index) => queryKey[index] === part);
}
