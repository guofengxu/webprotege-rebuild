import { useMutation, useQuery, useQueryClient } from "@tanstack/vue-query";
import type { CreateProjectRequest, ProjectResponse } from "@wp/api-types";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export function useProjects() {
  const client = useApiClient();
  return useQuery({
    queryKey: queryKeys.projects(),
    queryFn: () => listProjects(client),
  });
}

export function useTrashedProjects() {
  const client = useApiClient();
  return useQuery({
    queryKey: queryKeys.trashedProjects(),
    queryFn: () => listTrashedProjects(client),
  });
}

export function useCreateProject() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateProjectRequest) => createProject(client, body),
    onSuccess: async () => {
      await invalidateProjectLists(queryClient);
    },
  });
}

export function useTrashProject() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (projectId: string) => trashProject(client, projectId),
    onSuccess: async () => {
      await invalidateProjectLists(queryClient);
    },
  });
}

export function useRestoreProject() {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (projectId: string) => restoreProject(client, projectId),
    onSuccess: async () => {
      await invalidateProjectLists(queryClient);
    },
  });
}

async function listProjects(client: ApiClient): Promise<ProjectResponse[]> {
  return unwrap(await client.GET("/api/projects"));
}

async function listTrashedProjects(client: ApiClient): Promise<ProjectResponse[]> {
  return unwrap(await client.GET("/api/projects/trash"));
}

async function createProject(
  client: ApiClient,
  body: CreateProjectRequest,
): Promise<ProjectResponse> {
  return unwrap(await client.POST("/api/projects", { body }));
}

async function trashProject(
  client: ApiClient,
  projectId: string,
): Promise<ProjectResponse> {
  return unwrap(
    await client.POST("/api/projects/{id}/trash", {
      params: { path: { id: projectId } },
    }),
  );
}

async function restoreProject(
  client: ApiClient,
  projectId: string,
): Promise<ProjectResponse> {
  return unwrap(
    await client.DELETE("/api/projects/{id}/trash", {
      params: { path: { id: projectId } },
    }),
  );
}

async function invalidateProjectLists(
  queryClient: ReturnType<typeof useQueryClient>,
): Promise<void> {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: queryKeys.projects() }),
    queryClient.invalidateQueries({ queryKey: queryKeys.trashedProjects() }),
  ]);
}
