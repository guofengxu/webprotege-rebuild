import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import type { MemberResponse, RoleRequest } from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export function useMembers(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() => queryKeys.members(toValue(projectId))),
    queryFn: () => listMembers(client, toValue(projectId)),
    enabled: computed(() => toValue(projectId).length > 0),
  });
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
