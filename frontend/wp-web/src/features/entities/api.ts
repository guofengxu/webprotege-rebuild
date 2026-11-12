import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/vue-query";
import type {
  CreateEntitiesRequest,
  CreatedEntities,
  DeleteEntitiesRequest,
  NewEntitySettings,
  RevisionResponse,
} from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import {
  entityTypeToKind,
  type WorkspaceKind,
} from "@/features/workspace/hierarchyKinds";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export function useNewEntitySettings(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() =>
      queryKeys.settings(toValue(projectId), "new-entity"),
    ),
    queryFn: () => getNewEntitySettings(client, toValue(projectId)),
    enabled: computed(() => toValue(projectId).length > 0),
  });
}

export function useCreateEntities(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateEntitiesRequest) =>
      createEntities(client, toValue(projectId), body),
    onSuccess: async (_result, body) => {
      await invalidateAfterCreate(queryClient, toValue(projectId), body);
    },
  });
}

export function useDeleteEntities(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: DeleteEntitiesRequest) =>
      deleteEntities(client, toValue(projectId), body),
    onSuccess: async (_result, body) => {
      await invalidateAfterDelete(queryClient, toValue(projectId), body);
    },
  });
}

async function getNewEntitySettings(
  client: ApiClient,
  projectId: string,
): Promise<NewEntitySettings> {
  return unwrap(
    await client.GET("/api/projects/{id}/settings/new-entity", {
      params: { path: { id: projectId } },
    }),
  );
}

async function createEntities(
  client: ApiClient,
  projectId: string,
  body: CreateEntitiesRequest,
): Promise<CreatedEntities> {
  return unwrap(
    await client.POST("/api/projects/{id}/entities", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

async function deleteEntities(
  client: ApiClient,
  projectId: string,
  body: DeleteEntitiesRequest,
): Promise<RevisionResponse> {
  return unwrap(
    await client.DELETE("/api/projects/{id}/entities", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

async function invalidateAfterCreate(
  queryClient: QueryClient,
  projectId: string,
  body: CreateEntitiesRequest,
): Promise<void> {
  const tasks: Promise<unknown>[] = [
    queryClient.invalidateQueries({ queryKey: queryKeys.searchAll(projectId) }),
  ];
  const kind = hierarchyKindForEntityType(body.entityType);
  if (kind === "individual") {
    tasks.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.individualsAll(projectId),
      }),
    );
  } else if (kind !== null) {
    tasks.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.hierarchy(projectId, kind),
      }),
    );
  }
  await Promise.all(tasks);
}

async function invalidateAfterDelete(
  queryClient: QueryClient,
  projectId: string,
  body: DeleteEntitiesRequest,
): Promise<void> {
  const iris = body.iris ?? [];
  const tasks: Promise<unknown>[] = [
    queryClient.invalidateQueries({ queryKey: queryKeys.searchAll(projectId) }),
    queryClient.invalidateQueries({
      queryKey: queryKeys.individualsAll(projectId),
    }),
  ];
  for (const kind of [
    "class",
    "object-property",
    "data-property",
    "annotation-property",
  ] as const) {
    tasks.push(
      queryClient.invalidateQueries({
        queryKey: queryKeys.hierarchy(projectId, kind),
      }),
    );
  }
  for (const iri of iris) {
    for (const entityType of [
      "CLASS",
      "OBJECT_PROPERTY",
      "DATA_PROPERTY",
      "ANNOTATION_PROPERTY",
      "NAMED_INDIVIDUAL",
    ] as const) {
      tasks.push(
        queryClient.invalidateQueries({
          queryKey: queryKeys.frame(projectId, entityType, iri),
        }),
      );
    }
  }
  await Promise.all(tasks);
}

function hierarchyKindForEntityType(
  entityType: string | undefined,
): WorkspaceKind | null {
  if (
    entityType === "CLASS" ||
    entityType === "OBJECT_PROPERTY" ||
    entityType === "DATA_PROPERTY" ||
    entityType === "ANNOTATION_PROPERTY" ||
    entityType === "NAMED_INDIVIDUAL"
  ) {
    return entityTypeToKind(entityType);
  }
  return null;
}
