import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import type { components } from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export type WebhookResponse = components["schemas"]["WebhookResponse"];
export type WebhookDeliveryResponse =
  components["schemas"]["WebhookDeliveryResponse"];
export type CreateWebhookRequest = components["schemas"]["CreateWebhookRequest"];
export type UpdateWebhookRequest = components["schemas"]["UpdateWebhookRequest"];

export type UseWebhooksOptions = {
  readonly enabled?: MaybeRefOrGetter<boolean>;
};

export type UseWebhookDeliveriesOptions = {
  readonly enabled?: MaybeRefOrGetter<boolean>;
  readonly limit?: number;
};

const DEFAULT_DELIVERY_LIMIT = 20;

export function useWebhooks(
  projectId: MaybeRefOrGetter<string>,
  options: UseWebhooksOptions = {},
) {
  const client = useApiClient();
  const enabledOption = options.enabled;
  return useQuery({
    queryKey: computed(() => queryKeys.webhooks.list(toValue(projectId))),
    queryFn: () => listWebhooks(client, toValue(projectId)),
    enabled: computed(() => {
      if (toValue(projectId).length === 0) {
        return false;
      }
      if (enabledOption === undefined) {
        return true;
      }
      return toValue(enabledOption);
    }),
  });
}

export function useWebhookDeliveries(
  projectId: MaybeRefOrGetter<string>,
  webhookId: MaybeRefOrGetter<string | null>,
  options: UseWebhookDeliveriesOptions = {},
) {
  const client = useApiClient();
  const limit = options.limit ?? DEFAULT_DELIVERY_LIMIT;
  const enabledOption = options.enabled;
  return useQuery({
    queryKey: computed(() => {
      const id = toValue(webhookId) ?? "";
      return queryKeys.webhooks.deliveries(toValue(projectId), id);
    }),
    queryFn: () => {
      const id = toValue(webhookId);
      if (id === null || id.length === 0) {
        return Promise.resolve([] as WebhookDeliveryResponse[]);
      }
      return listWebhookDeliveries(client, toValue(projectId), id, limit);
    },
    enabled: computed(() => {
      const id = toValue(webhookId);
      if (toValue(projectId).length === 0 || id === null || id.length === 0) {
        return false;
      }
      if (enabledOption === undefined) {
        return true;
      }
      return toValue(enabledOption);
    }),
  });
}

export function useCreateWebhook(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateWebhookRequest) =>
      createWebhook(client, toValue(projectId), body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.webhooks.list(toValue(projectId)),
      });
    },
  });
}

export function useUpdateWebhook(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: { webhookId: string; body: UpdateWebhookRequest }) =>
      updateWebhook(client, toValue(projectId), input.webhookId, input.body),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.webhooks.list(toValue(projectId)),
      });
    },
  });
}

export function useDeleteWebhook(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (webhookId: string) =>
      deleteWebhook(client, toValue(projectId), webhookId),
    onSuccess: async () => {
      await queryClient.invalidateQueries({
        queryKey: queryKeys.webhooks.list(toValue(projectId)),
      });
    },
  });
}

async function listWebhooks(
  client: ApiClient,
  projectId: string,
): Promise<WebhookResponse[]> {
  return unwrap(
    await client.GET("/api/projects/{projectId}/webhooks", {
      params: { path: { projectId } },
    }),
  );
}

async function createWebhook(
  client: ApiClient,
  projectId: string,
  body: CreateWebhookRequest,
): Promise<WebhookResponse> {
  return unwrap(
    await client.POST("/api/projects/{projectId}/webhooks", {
      params: { path: { projectId } },
      body,
    }),
  );
}

async function updateWebhook(
  client: ApiClient,
  projectId: string,
  webhookId: string,
  body: UpdateWebhookRequest,
): Promise<WebhookResponse> {
  return unwrap(
    await client.PUT("/api/projects/{projectId}/webhooks/{webhookId}", {
      params: { path: { projectId, webhookId } },
      body,
    }),
  );
}

async function deleteWebhook(
  client: ApiClient,
  projectId: string,
  webhookId: string,
): Promise<void> {
  const result = await client.DELETE(
    "/api/projects/{projectId}/webhooks/{webhookId}",
    {
      params: { path: { projectId, webhookId } },
    },
  );
  if (!result.response.ok) {
    unwrap(result);
  }
}

async function listWebhookDeliveries(
  client: ApiClient,
  projectId: string,
  webhookId: string,
  limit: number,
): Promise<WebhookDeliveryResponse[]> {
  return unwrap(
    await client.GET(
      "/api/projects/{projectId}/webhooks/{webhookId}/deliveries",
      {
        params: {
          path: { projectId, webhookId },
          query: { limit },
        },
      },
    ),
  );
}
