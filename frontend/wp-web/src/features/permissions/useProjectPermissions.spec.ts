import { flushPromises } from "@vue/test-utils";
import { defineComponent, h, ref } from "vue";
import { afterEach, expect, test } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import type { MockResponse } from "@/shared/testing/mockFetch";
import {
  useProjectPermissions,
  type UseProjectPermissionsResult,
} from "./useProjectPermissions";

const VIEW_ACTIONS = [
  "VIEW_PROJECT",
  "VIEW_CHANGES",
  "DOWNLOAD_PROJECT",
] as const;

const EDIT_ACTIONS = [
  ...VIEW_ACTIONS,
  "EDIT_ONTOLOGY",
  "EDIT_ONTOLOGY_ANNOTATIONS",
  "CREATE_CLASS",
  "DELETE_CLASS",
  "CREATE_PROPERTY",
  "DELETE_PROPERTY",
  "CREATE_INDIVIDUAL",
  "DELETE_INDIVIDUAL",
  "REVERT_CHANGES",
] as const;

afterEach(() => {
  localStorage.removeItem("wp.locale");
});

test("pending while the permissions request is in flight", async () => {
  let resolveResponse: ((value: MockResponse) => void) | undefined;
  const { api, wrapper } = await mountPermissions({
    "GET /api/projects/{id}/permissions/me": () =>
      new Promise<MockResponse>((resolve) => {
        resolveResponse = resolve;
      }),
  });

  expect(api.permission("CREATE_CLASS").value).toBe("pending");
  expect(api.permission("REVERT_CHANGES").value).toBe("pending");

  for (let attempt = 0; attempt < 20 && resolveResponse === undefined; attempt += 1) {
    await flushPromises();
  }
  expect(resolveResponse).toBeTypeOf("function");
  resolveResponse!({
    json: {
      projectId: "p1",
      role: "CAN_EDIT",
      systemAdmin: false,
      actions: [...EDIT_ACTIONS],
    },
  });
  await flushPromises();

  expect(api.permission("CREATE_CLASS").value).toBe("allowed");
  wrapper.unmount();
});

test("allowed when actions include the operation, denied when they do not", async () => {
  const { api, wrapper } = await mountPermissions({
    "GET /api/projects/{id}/permissions/me": () => ({
      json: {
        projectId: "p1",
        role: "CAN_VIEW",
        systemAdmin: false,
        actions: [...VIEW_ACTIONS],
      },
    }),
  });
  await flushPromises();

  expect(api.permission("VIEW_PROJECT").value).toBe("allowed");
  expect(api.permission("CREATE_CLASS").value).toBe("denied");
  expect(api.permission("DELETE_INDIVIDUAL").value).toBe("denied");
  expect(api.permission("REVERT_CHANGES").value).toBe("denied");
  wrapper.unmount();
});

test("403 responses deny every action", async () => {
  const { api, wrapper } = await mountPermissions({
    "GET /api/projects/{id}/permissions/me": () => ({
      status: 403,
      problem: {
        status: 403,
        code: "PERMISSION_DENIED",
        title: "Forbidden",
      },
    }),
  });
  await flushPromises();

  expect(api.permission("VIEW_PROJECT").value).toBe("denied");
  expect(api.permission("CREATE_CLASS").value).toBe("denied");
  expect(api.permission("REVERT_CHANGES").value).toBe("denied");
  wrapper.unmount();
});

test("non-403 errors fall back to allowed so the backend can still reject", async () => {
  const { api, wrapper } = await mountPermissions({
    "GET /api/projects/{id}/permissions/me": () => ({
      status: 500,
      problem: {
        status: 500,
        code: "INTERNAL_ERROR",
        title: "Error",
      },
    }),
  });
  await flushPromises();

  expect(api.permission("CREATE_CLASS").value).toBe("allowed");
  expect(api.permission("REVERT_CHANGES").value).toBe("allowed");
  wrapper.unmount();
});

test("invalidating project(id) refetches my permissions", async () => {
  let calls = 0;
  const { api, wrapper, queryClient, requests } = await mountPermissions({
    "GET /api/projects/{id}/permissions/me": () => {
      calls += 1;
      return {
        json: {
          projectId: "p1",
          role: calls === 1 ? "CAN_VIEW" : "CAN_EDIT",
          systemAdmin: false,
          actions: calls === 1 ? [...VIEW_ACTIONS] : [...EDIT_ACTIONS],
        },
      };
    },
  });
  await flushPromises();

  expect(api.permission("CREATE_CLASS").value).toBe("denied");
  expect(
    requests.filter((entry) => entry.path === "/api/projects/p1/permissions/me"),
  ).toHaveLength(1);

  await queryClient.invalidateQueries({ queryKey: queryKeys.project("p1") });
  await flushPromises();

  expect(
    requests.filter((entry) => entry.path === "/api/projects/p1/permissions/me"),
  ).toHaveLength(2);
  expect(api.permission("CREATE_CLASS").value).toBe("allowed");
  wrapper.unmount();
});

async function mountPermissions(
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"] = {},
) {
  const api = ref<UseProjectPermissionsResult | null>(null);
  const Probe = defineComponent({
    name: "ProjectPermissionsProbe",
    setup() {
      api.value = useProjectPermissions("p1");
      return () => h("div");
    },
  });
  const rendered = await renderWithProviders(Probe, { handlers });
  if (api.value === null) {
    throw new Error("useProjectPermissions did not mount");
  }
  return { ...rendered, api: api.value };
}
