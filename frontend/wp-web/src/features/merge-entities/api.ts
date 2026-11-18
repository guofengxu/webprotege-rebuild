import { useMutation } from "@tanstack/vue-query";
import type { components } from "@wp/api-types";
import { toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";

export type MergeEntitiesRequest = components["schemas"]["MergeEntitiesRequest"];
export type MergeEntitiesResult = components["schemas"]["MergeEntitiesResult"];

export type MergedEntityTreatment =
  | "DEPRECATE_MERGED_ENTITY"
  | "DELETE_MERGED_ENTITY";

export function useMergeEntities(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  return useMutation({
    mutationFn: (body: MergeEntitiesRequest) =>
      mergeEntities(client, toValue(projectId), body),
  });
}

async function mergeEntities(
  client: ApiClient,
  projectId: string,
  body: MergeEntitiesRequest,
): Promise<MergeEntitiesResult> {
  return unwrap(
    await client.POST("/api/projects/{id}/entities/merge", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}
