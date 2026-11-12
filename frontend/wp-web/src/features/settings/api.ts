import {
  useMutation,
  useQuery,
  useQueryClient,
} from "@tanstack/vue-query";
import type {
  DisplayNameSetting,
  NewEntitySettings,
  PrefixSettings,
  ProjectResponse,
  UpdateProjectRequest,
} from "@wp/api-types";
import { computed, toValue, type MaybeRefOrGetter } from "vue";
import { useApiClient, type ApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

export type ProjectSettingKey = "prefixes" | "new-entity" | "display-names";

type SettingByKey = {
  prefixes: PrefixSettings;
  "new-entity": NewEntitySettings;
  "display-names": DisplayNameSetting[];
};

export function useProjectSetting<K extends ProjectSettingKey>(
  projectId: MaybeRefOrGetter<string>,
  key: K,
) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() =>
      queryKeys.settings(toValue(projectId), key),
    ),
    queryFn: () => getProjectSetting(client, toValue(projectId), key),
    enabled: computed(() => toValue(projectId).length > 0),
  });
}

export function useSaveProjectSetting<K extends ProjectSettingKey>(
  projectId: MaybeRefOrGetter<string>,
  key: K,
) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: SettingByKey[K]) =>
      putProjectSetting(client, toValue(projectId), key, body),
    onSuccess: (data) => {
      queryClient.setQueryData(
        queryKeys.settings(toValue(projectId), key),
        data,
      );
    },
  });
}

export function useUpdateProject(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateProjectRequest) =>
      updateProject(client, toValue(projectId), body),
    onSuccess: async (project) => {
      const id = toValue(projectId);
      queryClient.setQueryData(queryKeys.projectDetails(id), project);
      await Promise.all([
        queryClient.invalidateQueries({
          queryKey: queryKeys.projectDetails(id),
        }),
        queryClient.invalidateQueries({ queryKey: queryKeys.projects() }),
      ]);
    },
  });
}

export function useProjectDetails(projectId: MaybeRefOrGetter<string>) {
  const client = useApiClient();
  return useQuery({
    queryKey: computed(() => queryKeys.projectDetails(toValue(projectId))),
    queryFn: () => getProject(client, toValue(projectId)),
    enabled: computed(() => toValue(projectId).length > 0),
  });
}

async function getProject(
  client: ApiClient,
  projectId: string,
): Promise<ProjectResponse> {
  return unwrap(
    await client.GET("/api/projects/{id}", {
      params: { path: { id: projectId } },
    }),
  );
}

async function updateProject(
  client: ApiClient,
  projectId: string,
  body: UpdateProjectRequest,
): Promise<ProjectResponse> {
  return unwrap(
    await client.PATCH("/api/projects/{id}", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

async function getPrefixes(
  client: ApiClient,
  projectId: string,
): Promise<PrefixSettings> {
  return unwrap(
    await client.GET("/api/projects/{id}/settings/prefixes", {
      params: { path: { id: projectId } },
    }),
  );
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

async function getDisplayNameSettings(
  client: ApiClient,
  projectId: string,
): Promise<DisplayNameSetting[]> {
  return unwrap(
    await client.GET("/api/projects/{id}/settings/display-names", {
      params: { path: { id: projectId } },
    }),
  );
}

async function putPrefixes(
  client: ApiClient,
  projectId: string,
  body: PrefixSettings,
): Promise<PrefixSettings> {
  return unwrap(
    await client.PUT("/api/projects/{id}/settings/prefixes", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

async function putNewEntitySettings(
  client: ApiClient,
  projectId: string,
  body: NewEntitySettings,
): Promise<NewEntitySettings> {
  return unwrap(
    await client.PUT("/api/projects/{id}/settings/new-entity", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

async function putDisplayNameSettings(
  client: ApiClient,
  projectId: string,
  body: DisplayNameSetting[],
): Promise<DisplayNameSetting[]> {
  return unwrap(
    await client.PUT("/api/projects/{id}/settings/display-names", {
      params: { path: { id: projectId } },
      body,
    }),
  );
}

function getProjectSetting<K extends ProjectSettingKey>(
  client: ApiClient,
  projectId: string,
  key: K,
): Promise<SettingByKey[K]> {
  switch (key) {
    case "prefixes":
      return getPrefixes(client, projectId) as Promise<SettingByKey[K]>;
    case "new-entity":
      return getNewEntitySettings(client, projectId) as Promise<SettingByKey[K]>;
    case "display-names":
      return getDisplayNameSettings(client, projectId) as Promise<
        SettingByKey[K]
      >;
    default: {
      const _exhaustive: never = key;
      return _exhaustive;
    }
  }
}

function putProjectSetting<K extends ProjectSettingKey>(
  client: ApiClient,
  projectId: string,
  key: K,
  body: SettingByKey[K],
): Promise<SettingByKey[K]> {
  switch (key) {
    case "prefixes":
      return putPrefixes(
        client,
        projectId,
        body as PrefixSettings,
      ) as Promise<SettingByKey[K]>;
    case "new-entity":
      return putNewEntitySettings(
        client,
        projectId,
        body as NewEntitySettings,
      ) as Promise<SettingByKey[K]>;
    case "display-names":
      return putDisplayNameSettings(
        client,
        projectId,
        body as DisplayNameSetting[],
      ) as Promise<SettingByKey[K]>;
    default: {
      const _exhaustive: never = key;
      return _exhaustive;
    }
  }
}
