import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { expect, test } from "vitest";
import { useApiClient } from "@/shared/api/client";
import { renderWithProviders } from "./renderWithProviders";

test("records the component request headers and body", async () => {
  document.cookie = "XSRF-TOKEN=token-1";
  const Probe = defineComponent({
    name: "RequestProbe",
    setup() {
      const api = useApiClient();
      void api.POST("/api/projects", { body: { name: "Demo" } });
      return () => h("p", "ok");
    },
  });

  const { requests, wrapper } = await renderWithProviders(Probe, {
    handlers: {
      "POST /api/projects": () => ({
        status: 201,
        json: { projectId: "p1", displayName: "Demo" },
      }),
    },
  });
  await flushPromises();

  expect(requests).toHaveLength(1);
  expect(requests[0]?.method).toBe("POST");
  expect(requests[0]?.path).toBe("/api/projects");
  expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBe("token-1");
  expect(requests[0]?.headers.get("content-type")).toContain("application/json");
  expect(requests[0]?.body).toEqual({ name: "Demo" });
  wrapper.unmount();
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

test("uses the supplied routes and initial route", async () => {
  const Child = defineComponent({
    name: "RoutedChild",
    setup() {
      return () => h("p", "child");
    },
  });

  const { router, wrapper } = await renderWithProviders(Child, {
    routes: [{ path: "/projects/:projectId", component: Child }],
    route: "/projects/abc",
  });

  expect(router.currentRoute.value.path).toBe("/projects/abc");
  expect(router.currentRoute.value.params.projectId).toBe("abc");
  expect(wrapper.text()).toContain("child");
  wrapper.unmount();
});

test("matches an OpenAPI path template and records the query", async () => {
  const Probe = defineComponent({
    name: "TemplateProbe",
    setup() {
      const api = useApiClient();
      void api.GET("/api/projects/{id}/search", {
        params: {
          path: { id: "abc" },
          query: { q: "owl", limit: 20 },
        },
      });
      return () => h("p", "search");
    },
  });

  const { requests, wrapper } = await renderWithProviders(Probe, {
    handlers: {
      "GET /api/projects/{id}/search": (req) => {
        expect(req.params.id).toBe("abc");
        expect(req.query.get("q")).toBe("owl");
        return { json: {} };
      },
    },
  });
  await flushPromises();

  expect(requests[0]?.path).toBe("/api/projects/abc/search");
  expect(requests[0]?.query.get("limit")).toBe("20");
  wrapper.unmount();
});

test("an unmatched request rejects", async () => {
  const Probe = defineComponent({
    name: "UnmatchedProbe",
    setup() {
      const api = useApiClient();
      const pending = api.GET("/api/auth/me");
      return { pending };
    },
    render() {
      return h("div");
    },
  });

  const { wrapper } = await renderWithProviders(Probe);
  const pending = wrapper.getComponent(Probe).vm.pending;
  await expect(pending).rejects.toThrow(/No mock handler for GET \/api\/auth\/me/);
  wrapper.unmount();
});
