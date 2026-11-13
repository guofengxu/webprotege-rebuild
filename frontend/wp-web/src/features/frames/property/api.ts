import type {
  AnnotationPropertyFrame,
  AnnotationPropertyFrameUpdateResult,
  DataPropertyFrame,
  DataPropertyFrameUpdateResult,
  ObjectPropertyFrame,
  ObjectPropertyFrameUpdateResult,
  UpdateAnnotationPropertyFrameRequest,
  UpdateDataPropertyFrameRequest,
  UpdateObjectPropertyFrameRequest,
} from "@wp/api-types";
import type { ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";

export async function loadObjectPropertyFrame(
  client: ApiClient,
  projectId: string,
  iri: string,
): Promise<ObjectPropertyFrame> {
  return unwrap(
    await client.GET("/api/projects/{id}/frames/object-property", {
      params: {
        path: { id: projectId },
        query: { iri },
      },
    }),
  );
}

export async function saveObjectPropertyFrame(
  client: ApiClient,
  projectId: string,
  body: UpdateObjectPropertyFrameRequest,
): Promise<ObjectPropertyFrameUpdateResult> {
  return unwrap(
    await client.PUT("/api/projects/{id}/frames/object-property", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

export async function loadDataPropertyFrame(
  client: ApiClient,
  projectId: string,
  iri: string,
): Promise<DataPropertyFrame> {
  return unwrap(
    await client.GET("/api/projects/{id}/frames/data-property", {
      params: {
        path: { id: projectId },
        query: { iri },
      },
    }),
  );
}

export async function saveDataPropertyFrame(
  client: ApiClient,
  projectId: string,
  body: UpdateDataPropertyFrameRequest,
): Promise<DataPropertyFrameUpdateResult> {
  return unwrap(
    await client.PUT("/api/projects/{id}/frames/data-property", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

export async function loadAnnotationPropertyFrame(
  client: ApiClient,
  projectId: string,
  iri: string,
): Promise<AnnotationPropertyFrame> {
  return unwrap(
    await client.GET("/api/projects/{id}/frames/annotation-property", {
      params: {
        path: { id: projectId },
        query: { iri },
      },
    }),
  );
}

export async function saveAnnotationPropertyFrame(
  client: ApiClient,
  projectId: string,
  body: UpdateAnnotationPropertyFrameRequest,
): Promise<AnnotationPropertyFrameUpdateResult> {
  return unwrap(
    await client.PUT("/api/projects/{id}/frames/annotation-property", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}
