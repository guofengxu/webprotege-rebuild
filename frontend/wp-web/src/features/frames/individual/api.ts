import type {
  NamedIndividualFrame,
  NamedIndividualFrameUpdateResult,
  UpdateNamedIndividualFrameRequest,
} from "@wp/api-types";
import type { ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";

export async function loadNamedIndividualFrame(
  client: ApiClient,
  projectId: string,
  iri: string,
): Promise<NamedIndividualFrame> {
  return unwrap(
    await client.GET("/api/projects/{id}/frames/individual", {
      params: {
        path: { id: projectId },
        query: { iri },
      },
    }),
  );
}

export async function saveNamedIndividualFrame(
  client: ApiClient,
  projectId: string,
  body: UpdateNamedIndividualFrameRequest,
): Promise<NamedIndividualFrameUpdateResult> {
  return unwrap(
    await client.PUT("/api/projects/{id}/frames/individual", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}
