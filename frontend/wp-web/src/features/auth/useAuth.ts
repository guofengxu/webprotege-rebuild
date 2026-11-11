import {
  queryOptions,
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/vue-query";
import type { AuthUserResponse, LoginRequest } from "@wp/api-types";
import { useRouter } from "vue-router";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

const SYSTEM_ADMIN_ROLE = "SYSTEM_ADMIN";

export function currentUserQuery(client: ApiClient) {
  return queryOptions({
    queryKey: queryKeys.me(),
    queryFn: () => fetchCurrentUser(client),
  });
}

export function useCurrentUser() {
  return useQuery(currentUserQuery(useApiClient()));
}

export function useLogin() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: LoginRequest) => signIn(client, body),
    onSuccess: (user) => {
      queryClient.clear();
      queryClient.setQueryData(queryKeys.me(), user);
    },
  });
}

export function useLogout() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  const router = useRouter();
  return useMutation({
    mutationFn: () => signOut(client),
    onSuccess: async () => {
      queryClient.clear();
      await router.replace("/login");
    },
  });
}

export function isSystemAdmin(
  user: Pick<AuthUserResponse, "appRoles"> | null | undefined,
): boolean {
  return user?.appRoles?.includes(SYSTEM_ADMIN_ROLE) === true;
}

async function fetchCurrentUser(client: ApiClient): Promise<AuthUserResponse | null> {
  const result = await client.GET("/api/auth/me");
  // 401 is a signed-out session. Other 401 responses go through onUnauthorized.
  if (result.response.status === 401) {
    return null;
  }
  return unwrap(result);
}

async function signIn(client: ApiClient, body: LoginRequest): Promise<AuthUserResponse> {
  return unwrap(await client.POST("/api/auth/login", { body }));
}

async function signOut(client: ApiClient): Promise<void> {
  const result = await client.POST("/api/auth/logout");
  if (!result.response.ok) {
    unwrap(result);
  }
}
