import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import type {
  ApiKeyInfo,
  CreateApiKeyRequest,
  DeviceSession,
  IssuedApiKeyResponse,
} from "@wp/api-types";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export function useApiKeys() {
  const client = useApiClient();
  return useQuery({
    queryKey: queryKeys.apiKeys(),
    queryFn: () => listApiKeys(client),
  });
}

export function useCreateApiKey() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateApiKeyRequest) => createApiKey(client, body),
    // Do not retain IssuedApiKeyResponse (plaintext apiKey) in the mutation cache.
    gcTime: 0,
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.apiKeys() });
    },
  });
}

export function useRevokeApiKey() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (keyId: string) => revokeApiKey(client, keyId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.apiKeys() });
    },
  });
}

export function useDevices() {
  const client = useApiClient();
  return useQuery({
    queryKey: queryKeys.devices(),
    queryFn: () => listDevices(client),
  });
}

export function useRevokeDevice() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (tokenId: string) => revokeDevice(client, tokenId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: queryKeys.devices() });
    },
  });
}

async function listApiKeys(client: ApiClient): Promise<ApiKeyInfo[]> {
  return unwrap(await client.GET("/api/me/api-keys"));
}

async function createApiKey(
  client: ApiClient,
  body: CreateApiKeyRequest,
): Promise<IssuedApiKeyResponse> {
  return unwrap(await client.POST("/api/me/api-keys", { body }));
}

async function revokeApiKey(client: ApiClient, keyId: string): Promise<void> {
  const result = await client.DELETE("/api/me/api-keys/{keyId}", {
    params: { path: { keyId } },
  });
  if (!result.response.ok) {
    unwrap(result);
  }
}

async function listDevices(client: ApiClient): Promise<DeviceSession[]> {
  return unwrap(await client.GET("/api/me/devices"));
}

async function revokeDevice(client: ApiClient, tokenId: string): Promise<void> {
  const result = await client.DELETE("/api/me/devices/{tokenId}", {
    params: { path: { tokenId } },
  });
  if (!result.response.ok) {
    unwrap(result);
  }
}
