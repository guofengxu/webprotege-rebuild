import type { EntityNode } from "@wp/api-types";
import type { HierarchyKind } from "@/features/workspace/hierarchyKinds";
import type { ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";

export async function fetchRoots(
  client: ApiClient,
  projectId: string,
  kind: HierarchyKind,
): Promise<EntityNode[]> {
  return unwrap(
    await client.GET("/api/projects/{id}/hierarchies/{kind}/roots", {
      params: { path: { id: projectId, kind } },
    }),
  );
}

export async function fetchChildren(
  client: ApiClient,
  projectId: string,
  kind: HierarchyKind,
  parentIri: string,
): Promise<EntityNode[]> {
  return unwrap(
    await client.GET("/api/projects/{id}/hierarchies/{kind}/children", {
      params: {
        path: { id: projectId, kind },
        query: { parent: parentIri },
      },
    }),
  );
}

export async function fetchPaths(
  client: ApiClient,
  projectId: string,
  kind: HierarchyKind,
  entityIri: string,
): Promise<EntityNode[][]> {
  return unwrap(
    await client.GET("/api/projects/{id}/hierarchies/{kind}/paths", {
      params: {
        path: { id: projectId, kind },
        query: { entity: entityIri },
      },
    }),
  );
}
