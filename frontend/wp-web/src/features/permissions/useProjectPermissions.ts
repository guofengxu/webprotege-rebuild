import { useQuery } from "@tanstack/vue-query";
import type { components } from "@wp/api-types";
import {
  computed,
  toValue,
  type ComputedRef,
  type MaybeRefOrGetter,
} from "vue";
import { useApiClient } from "@/shared/api/client";
import { ApiError, unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";

type MyProjectPermissionsResponse =
  components["schemas"]["MyProjectPermissionsResponse"];

export type ProjectAction = NonNullable<
  MyProjectPermissionsResponse["actions"]
>[number];

export type PermissionState = "allowed" | "denied" | "pending";

export type UseProjectPermissionsResult = {
  readonly permission: (
    action: ProjectAction,
  ) => ComputedRef<PermissionState>;
};

export function useProjectPermissions(
  projectId: MaybeRefOrGetter<string>,
): UseProjectPermissionsResult {
  const client = useApiClient();
  const projectIdValue = computed(() => toValue(projectId));

  const query = useQuery({
    queryKey: computed(() => queryKeys.myPermissions(projectIdValue.value)),
    enabled: computed(() => projectIdValue.value.length > 0),
    queryFn: async (): Promise<MyProjectPermissionsResponse> =>
      unwrap(
        await client.GET("/api/projects/{id}/permissions/me", {
          params: { path: { id: projectIdValue.value } },
        }),
      ),
  });

  function permission(action: ProjectAction): ComputedRef<PermissionState> {
    return computed(() => {
      if (query.isPending.value) {
        return "pending";
      }
      if (query.isError.value) {
        const error = query.error.value;
        if (error instanceof ApiError && error.status === 403) {
          return "denied";
        }
        return "allowed";
      }
      const actions = query.data.value?.actions ?? [];
      return actions.includes(action) ? "allowed" : "denied";
    });
  }

  return { permission };
}
