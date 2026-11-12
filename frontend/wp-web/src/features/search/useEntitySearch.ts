import { useQuery } from "@tanstack/vue-query";
import type { EntityNode } from "@wp/api-types";
import {
  computed,
  onScopeDispose,
  ref,
  toValue,
  watch,
  type ComputedRef,
  type MaybeRefOrGetter,
  type Ref,
} from "vue";
import {
  joinSearchTypes,
  type SearchType,
} from "@/features/workspace/hierarchyKinds";
import { useApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export const SEARCH_LIMIT = 50;

export const GLOBAL_SEARCH_TYPES = [
  "class",
  "object-property",
  "data-property",
  "annotation-property",
  "named-individual",
  "datatype",
] as const satisfies readonly SearchType[];

export type SearchableEntityType =
  | "CLASS"
  | "OBJECT_PROPERTY"
  | "DATA_PROPERTY"
  | "ANNOTATION_PROPERTY"
  | "NAMED_INDIVIDUAL"
  | "DATATYPE";

const GROUP_ORDER: readonly SearchableEntityType[] = [
  "CLASS",
  "OBJECT_PROPERTY",
  "DATA_PROPERTY",
  "ANNOTATION_PROPERTY",
  "NAMED_INDIVIDUAL",
  "DATATYPE",
] as const;

const DEBOUNCE_MS = 250;

export type SearchResultGroup = {
  readonly entityType: SearchableEntityType;
  readonly items: readonly EntityNode[];
};

export type UseEntitySearchResult = {
  readonly groups: ComputedRef<readonly SearchResultGroup[]>;
  readonly flatItems: ComputedRef<readonly EntityNode[]>;
  readonly debouncedQuery: ComputedRef<string>;
  readonly isPending: Ref<boolean>;
  readonly isFetching: Ref<boolean>;
  readonly isError: Ref<boolean>;
  readonly error: Ref<unknown>;
  readonly hasQuery: ComputedRef<boolean>;
};

export function groupSearchResults(
  nodes: readonly EntityNode[],
): SearchResultGroup[] {
  const buckets = new Map<SearchableEntityType, EntityNode[]>();
  for (const type of GROUP_ORDER) {
    buckets.set(type, []);
  }
  for (const node of nodes) {
    if (!isSearchableEntityType(node.entityType)) {
      continue;
    }
    const bucket = buckets.get(node.entityType);
    if (bucket === undefined) {
      continue;
    }
    bucket.push(node);
  }
  return GROUP_ORDER.flatMap((entityType) => {
    const items = buckets.get(entityType) ?? [];
    if (items.length === 0) {
      return [];
    }
    return [{ entityType, items }];
  });
}

function isSearchableEntityType(value: string): value is SearchableEntityType {
  return (GROUP_ORDER as readonly string[]).includes(value);
}

export function useEntitySearch(
  projectId: MaybeRefOrGetter<string>,
  query: MaybeRefOrGetter<string>,
): UseEntitySearchResult {
  const client = useApiClient();
  const debouncedQuery = ref("");
  let timer: ReturnType<typeof setTimeout> | undefined;

  watch(
    () => toValue(query),
    (raw) => {
      if (timer !== undefined) {
        clearTimeout(timer);
        timer = undefined;
      }
      const trimmed = raw.trim();
      if (trimmed.length === 0) {
        debouncedQuery.value = "";
        return;
      }
      timer = setTimeout(() => {
        debouncedQuery.value = trimmed;
        timer = undefined;
      }, DEBOUNCE_MS);
    },
    { immediate: true },
  );

  onScopeDispose(() => {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  });

  const hasQuery = computed(() => debouncedQuery.value.length > 0);
  const projectIdValue = computed(() => toValue(projectId));

  const searchQuery = useQuery({
    queryKey: computed(() =>
      queryKeys.search(
        projectIdValue.value,
        debouncedQuery.value,
        [...GLOBAL_SEARCH_TYPES],
        SEARCH_LIMIT,
      ),
    ),
    enabled: computed(
      () => projectIdValue.value.length > 0 && hasQuery.value,
    ),
    queryFn: async () => {
      const id = projectIdValue.value;
      const q = debouncedQuery.value;
      return unwrap(
        await client.GET("/api/projects/{id}/search", {
          params: {
            path: { id },
            query: {
              q,
              types: joinSearchTypes(GLOBAL_SEARCH_TYPES),
              limit: SEARCH_LIMIT,
            },
          },
        }),
      );
    },
  });

  const groups = computed(() =>
    groupSearchResults(searchQuery.data.value ?? []),
  );

  const flatItems = computed(() =>
    groups.value.flatMap((group) => [...group.items]),
  );

  return {
    groups,
    flatItems,
    debouncedQuery: computed(() => debouncedQuery.value),
    isPending: searchQuery.isPending,
    isFetching: searchQuery.isFetching,
    isError: searchQuery.isError,
    error: searchQuery.error,
    hasQuery,
  };
}
