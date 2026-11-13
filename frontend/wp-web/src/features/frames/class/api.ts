import type {
  ClassFrame,
  ClassFrameUpdateResult,
  UpdateClassFrameRequest,
} from "@wp/api-types";
import type { ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";

export async function loadClassFrame(
  client: ApiClient,
  projectId: string,
  iri: string,
): Promise<ClassFrame> {
  return unwrap(
    await client.GET("/api/projects/{id}/frames/class", {
      params: {
        path: { id: projectId },
        query: { iri },
      },
    }),
  );
}

export async function saveClassFrame(
  client: ApiClient,
  projectId: string,
  body: UpdateClassFrameRequest,
): Promise<ClassFrameUpdateResult> {
  return unwrap(
    await client.PUT("/api/projects/{id}/frames/class", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}
