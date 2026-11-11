import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { expect, test } from "vitest";
import { queryKeys } from "@/shared/api/queryKeys";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { isSystemAdmin, useCurrentUser, useLogout } from "./useAuth";

const user = {
  userId: "u1",
  displayName: "Ada",
  email: "ada@example.org",
  appRoles: ["PROJECT_CREATOR"],
};

test("isSystemAdmin reads the SYSTEM_ADMIN role", () => {
  expect(isSystemAdmin(undefined)).toBe(false);
  expect(isSystemAdmin(null)).toBe(false);
  expect(isSystemAdmin(user)).toBe(false);
  expect(isSystemAdmin({ ...user, appRoles: ["SYSTEM_ADMIN", "PROJECT_CREATOR"] })).toBe(
    true,
  );
});

test("the current user is null on 401 and the query stays successful", async () => {
  const Probe = defineComponent({
    name: "CurrentUserProbe",
    setup() {
      const current = useCurrentUser();
      return () =>
        h(
          "p",
          current.isError.value
            ? "error"
            : current.data.value === null
              ? "anon"
              : (current.data.value?.displayName ?? "pending"),
        );
    },
  });

  const { wrapper } = await renderWithProviders(Probe, {
    handlers: {
      "GET /api/auth/me": () => ({
        status: 401,
        problem: { status: 401, title: "Unauthorized" },
      }),
    },
  });
  await flushPromises();

  expect(wrapper.text()).toBe("anon");
  wrapper.unmount();
});

test("logout clears the query cache and opens the login page", async () => {
  const Probe = defineComponent({
    name: "LogoutProbe",
    setup() {
      const logout = useLogout();
      return () => h("button", { onClick: () => void logout.mutate() }, "out");
    },
  });
  const { wrapper, queryClient, requests, router } = await renderWithProviders(Probe, {
    routes: [
      { path: "/", component: Probe },
      { path: "/login", component: Probe },
    ],
    handlers: {
      "POST /api/auth/logout": () => ({ status: 204 }),
    },
  });
  queryClient.setQueryData(queryKeys.me(), user);
  queryClient.setQueryData(queryKeys.projects(), []);

  await wrapper.get("button").trigger("click");
  await flushPromises();

  expect(requests.map((request) => `${request.method} ${request.path}`)).toEqual([
    "POST /api/auth/logout",
  ]);
  expect(queryClient.getQueryCache().getAll()).toHaveLength(0);
  expect(router.currentRoute.value.path).toBe("/login");
  wrapper.unmount();
});
