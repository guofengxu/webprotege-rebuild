import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import type { components, MemberResponse, RoleRequest } from "@wp/api-types";

type UserSummaryResponse = components["schemas"]["UserSummaryResponse"];
import {
  computed,
  onScopeDispose,
  ref,
  toValue,
  watch,
  type MaybeRefOrGetter,
} from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

const CANDIDATE_LIMIT = 10;
const CANDIDATE_DEBOUNCE_MS = 300;
const CANDIDATE_STALE_TIME_MS = 30_000;

export function useMembers(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() => queryKeys.members(toValue(projectId))),
    queryFn: () => listMembers(client, toValue(projectId)),
    enabled: computed(() => toValue(projectId).length > 0),
  });
}

export function useMemberCandidates(
  projectId: MaybeRefOrGetter<string>,
  query: MaybeRefOrGetter<string>,
) {
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
      }, CANDIDATE_DEBOUNCE_MS);
    },
    { immediate: true },
  );

  onScopeDispose(() => {
    if (timer !== undefined) {
      clearTimeout(timer);
    }
  });

  const projectIdValue = computed(() => toValue(projectId));
  const hasQuery = computed(() => debouncedQuery.value.length > 0);

  const result = useQuery({
    queryKey: computed(() =>
      queryKeys.memberCandidates(projectIdValue.value, debouncedQuery.value),
    ),
    enabled: computed(
      () => projectIdValue.value.length > 0 && hasQuery.value,
    ),
    staleTime: CANDIDATE_STALE_TIME_MS,
    queryFn: () =>
      searchMemberCandidates(
        client,
        projectIdValue.value,
        debouncedQuery.value,
      ),
  });

  return {
    ...result,
    debouncedQuery: computed(() => debouncedQuery.value),
  };
}

export function usePutMember(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { userId: string; body: RoleRequest }) =>
      putMember(client, toValue(projectId), input.userId, input.body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.members(toValue(projectId)),
      });
    },
  });
}

export function useDeleteMember(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) =>
      deleteMember(client, toValue(projectId), userId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.members(toValue(projectId)),
      });
    },
  });
}

async function listMembers(
  client: ApiClient,
  projectId: string,
): Promise<MemberResponse[]> {
  return unwrap(
    await client.GET("/api/projects/{id}/members", {
      params: { path: { id: projectId } },
    }),
  );
}

async function searchMemberCandidates(
  client: ApiClient,
  projectId: string,
  q: string,
): Promise<UserSummaryResponse[]> {
  return unwrap(
    await client.GET("/api/projects/{id}/member-candidates", {
      params: {
        path: { id: projectId },
        query: { q, limit: CANDIDATE_LIMIT },
      },
    }),
  );
}

async function putMember(
  client: ApiClient,
  projectId: string,
  userId: string,
  body: RoleRequest,
): Promise<MemberResponse> {
  return unwrap(
    await client.PUT("/api/projects/{id}/members/{userId}", {
      params: { path: { id: projectId, userId } },
      body,
    }),
  );
}

async function deleteMember(
  client: ApiClient,
  projectId: string,
  userId: string,
): Promise<void> {
  const result = await client.DELETE("/api/projects/{id}/members/{userId}", {
    params: { path: { id: projectId, userId } },
  });
  if (!result.response.ok) {
    unwrap(result);
  }
}
