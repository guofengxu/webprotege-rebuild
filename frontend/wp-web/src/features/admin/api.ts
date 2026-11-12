import { keepPreviousData, useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import type {
  AdminUserPage,
  AdminUserResponse,
  CreateUserRequest,
  ResetPasswordRequest,
  SetAppRolesRequest,
} from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export const ADMIN_USERS_PAGE_SIZE = 50;

export function useAdminUsers(
  page: MaybeRefOrGetter<number>,
  size: MaybeRefOrGetter<number> = ADMIN_USERS_PAGE_SIZE,
) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() => queryKeys.adminUsers(toValue(page), toValue(size))),
    queryFn: () => listUsers(client, toValue(page), toValue(size)),
    placeholderData: keepPreviousData,
  });
}

export function useCreateUser() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateUserRequest) => createUser(client, body),
    // Do not retain CreateUserRequest.initialPassword in the mutation cache.
    gcTime: 0,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.adminUsersAll() });
    },
  });
}

export function useSetUserAppRoles() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { userId: string; body: SetAppRolesRequest }) =>
      setUserAppRoles(client, input.userId, input.body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.adminUsersAll() });
    },
  });
}

export function useResetUserPassword() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { userId: string; body: ResetPasswordRequest }) =>
      resetUserPassword(client, input.userId, input.body),
    // Do not retain ResetPasswordRequest.newPassword in the mutation cache.
    gcTime: 0,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.adminUsersAll() });
    },
  });
}

async function listUsers(
  client: ApiClient,
  page: number,
  size: number,
): Promise<AdminUserPage> {
  return unwrap(
    await client.GET("/api/admin/users", {
      params: { query: { page, size } },
    }),
  );
}

async function createUser(
  client: ApiClient,
  body: CreateUserRequest,
): Promise<AdminUserResponse> {
  return unwrap(await client.POST("/api/admin/users", { body }));
}

async function setUserAppRoles(
  client: ApiClient,
  userId: string,
  body: SetAppRolesRequest,
): Promise<AdminUserResponse> {
  return unwrap(
    await client.PUT("/api/admin/users/{userId}/app-roles", {
      params: { path: { userId } },
      body,
    }),
  );
}

async function resetUserPassword(
  client: ApiClient,
  userId: string,
  body: ResetPasswordRequest,
): Promise<void> {
  const result = await client.PUT("/api/admin/users/{userId}/password", {
    params: { path: { userId } },
    body,
  });
  if (!result.response.ok) {
    unwrap(result);
  }
}
