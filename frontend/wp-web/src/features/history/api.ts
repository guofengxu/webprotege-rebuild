import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/vue-query";
import type {
  RevisionDiff,
  RevisionPage,
  RevisionResponse,
} from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export const DEFAULT_DIFF_LIMIT = 200;
export const HISTORY_PAGE_SIZE = 50;

export async function fetchRevisions(
  client: ApiClient,
  projectId: string,
  page: number,
  size: number,
): Promise<RevisionPage> {
  return unwrap(
    await client.GET("/api/projects/{id}/revisions", {
      params: {
        path: { id: projectId },
        query: { page, size },
      },
    }),
  );
}

export async function fetchRevisionDiff(
  client: ApiClient,
  projectId: string,
  rev: number,
  limit: number,
): Promise<RevisionDiff> {
  return unwrap(
    await client.GET("/api/projects/{id}/revisions/{rev}", {
      params: {
        path: { id: projectId, rev },
        query: { limit },
      },
    }),
  );
}

export async function fetchEntityRevisions(
  client: ApiClient,
  projectId: string,
  iri: string,
  page: number,
  size: number,
): Promise<RevisionPage> {
  return unwrap(
    await client.GET("/api/projects/{id}/entities/history", {
      params: {
        path: { id: projectId },
        query: { iri, page, size },
      },
    }),
  );
}

export async function revertRevision(
  client: ApiClient,
  projectId: string,
  rev: number,
): Promise<RevisionResponse> {
  return unwrap(
    await client.POST("/api/projects/{id}/revisions/{rev}/revert", {
      params: {
        path: { id: projectId, rev },
      },
    }),
  );
}

export function useRevisions(
  projectId: MaybeRefOrGetter<string>,
  page: MaybeRefOrGetter<number>,
  size: MaybeRefOrGetter<number>,
) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() =>
      queryKeys.revisions(
        toValue(projectId),
        toValue(page),
        toValue(size),
      ),
    ),
    queryFn: () =>
      fetchRevisions(
        client,
        toValue(projectId),
        toValue(page),
        toValue(size),
      ),
    enabled: computed(() => toValue(projectId).length > 0),
  });
}

export function useRevisionDiff(
  projectId: MaybeRefOrGetter<string>,
  rev: MaybeRefOrGetter<number | null>,
  limit: MaybeRefOrGetter<number> = DEFAULT_DIFF_LIMIT,
) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() => {
      const revNo = toValue(rev);
      return queryKeys.revisionDiff(
        toValue(projectId),
        revNo ?? -1,
        toValue(limit),
      );
    }),
    queryFn: () => {
      const revNo = toValue(rev);
      if (revNo === null) {
        throw new Error("rev is required");
      }
      return fetchRevisionDiff(
        client,
        toValue(projectId),
        revNo,
        toValue(limit),
      );
    },
    enabled: computed(() => {
      const revNo = toValue(rev);
      return toValue(projectId).length > 0 && revNo !== null && revNo > 0;
    }),
  });
}

export function useEntityRevisions(
  projectId: MaybeRefOrGetter<string>,
  iri: MaybeRefOrGetter<string>,
  page: MaybeRefOrGetter<number>,
  size: MaybeRefOrGetter<number>,
) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() =>
      queryKeys.entityHistory(
        toValue(projectId),
        toValue(iri),
        toValue(page),
        toValue(size),
      ),
    ),
    queryFn: () =>
      fetchEntityRevisions(
        client,
        toValue(projectId),
        toValue(iri),
        toValue(page),
        toValue(size),
      ),
    enabled: computed(
      () => toValue(projectId).length > 0 && toValue(iri).length > 0,
    ),
  });
}

export function useRevertRevision(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (rev: number) =>
      revertRevision(client, toValue(projectId), rev),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.project(toValue(projectId)),
      });
    },
  });
}
