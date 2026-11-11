import type { QueryClient } from "@tanstack/vue-query";
import type { ProjectStreamMessage } from "@wp/api-types";
import { queryKeys } from "@/shared/api/queryKeys";

const HIERARCHY_KINDS = ["class", "object-property", "data-property", "annotation-property"] as const;

function invalidate(queryClient: QueryClient, queryKey: readonly unknown[]): void {
  // Refetch failures must not surface as unhandled rejections; the cache is already marked stale.
  void queryClient.invalidateQueries({ queryKey }).catch((error: unknown) => {
    console.error("Failed to invalidate queries after a project event", error);
  });
}

function invalidateHierarchies(queryClient: QueryClient, projectId: string): void {
  for (const kind of HIERARCHY_KINDS) {
    invalidate(queryClient, queryKeys.hierarchy(projectId, kind));
  }
}

function invalidateFrame(
  queryClient: QueryClient,
  projectId: string,
  event: Extract<ProjectStreamMessage, { type: "EntityFrameChanged" }>,
): void {
  const { entityType, iri } = event.entity;
  if (entityType !== undefined && iri !== undefined) {
    invalidate(queryClient, queryKeys.frame(projectId, entityType, iri));
    invalidate(queryClient, queryKeys.entityHistoryAll(projectId, iri));
  }
  if (entityType === "NAMED_INDIVIDUAL") {
    invalidate(queryClient, queryKeys.individualsAll(projectId));
  }
}

export function applyProjectEvent(
  queryClient: QueryClient,
  projectId: string,
  event: ProjectStreamMessage,
): { reconnect: boolean } {
  switch (event.type) {
    case "ProjectChanged":
      invalidate(queryClient, queryKeys.revisionsAll(projectId));
      invalidate(queryClient, queryKeys.projectDetails(projectId));
      return { reconnect: false };
    case "EntityHierarchyChanged":
      // hasChildren is stored on the parent node, in the grandparent's children query.
      invalidate(queryClient, queryKeys.hierarchy(projectId, event.hierarchy));
      invalidate(queryClient, queryKeys.frames(projectId));
      if (event.hierarchy === "class") {
        invalidate(queryClient, queryKeys.individualsAll(projectId));
      }
      return { reconnect: false };
    case "EntityFrameChanged":
      invalidateFrame(queryClient, projectId, event);
      return { reconnect: false };
    case "BrowserTextChanged":
      invalidateHierarchies(queryClient, projectId);
      invalidate(queryClient, queryKeys.individualsAll(projectId));
      invalidate(queryClient, queryKeys.searchAll(projectId));
      invalidate(queryClient, queryKeys.frames(projectId));
      return { reconnect: false };
    case "PermissionsChanged":
      invalidate(queryClient, queryKeys.project(projectId));
      invalidate(queryClient, queryKeys.projects());
      return { reconnect: true };
    case "resync":
      invalidate(queryClient, queryKeys.project(projectId));
      return { reconnect: false };
    default: {
      const unreachable: never = event;
      void unreachable;
      return { reconnect: false };
    }
  }
}
