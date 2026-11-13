import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import ProjectShell from "./ProjectShell.vue";
import { WORKSPACE_KIND_PATH } from "./routes";

const ChildPage = defineComponent({
  name: "ChildPage",
  setup() {
    return () => h("p", "child-workspace");
  },
});

const ProjectsPage = defineComponent({
  name: "ProjectsPage",
  setup() {
    return () => h("p", "projects-list");
  },
});

afterEach(() => {
  localStorage.removeItem("wp.locale");
  localStorage.removeItem("wp.workspace.layout");
});

test("PROJECT_TRASHED shows the trash message and does not render child routes", async () => {
  const { wrapper, requests } = await renderShell("/projects/p1/class", {
    "GET /api/projects/p1": () => ({
      status: 409,
      problem: {
        status: 409,
        code: "PROJECT_TRASHED",
        title: "Trashed",
        detail: "in trash",
      },
    }),
    "GET /api/projects/{id}/events": openEventStream,
  });

  await flushPromises();
  expect(wrapper.text()).toContain("项目已在回收站中");
  expect(wrapper.text()).toContain("返回项目列表");
  expect(wrapper.text()).not.toContain("child-workspace");
  expect(wrapper.get('a[href="/projects"]').text()).toContain("返回项目列表");
  expect(eventRequests(requests)).toHaveLength(0);
  wrapper.unmount();
});

test("403 shows the permission message and does not render child routes", async () => {
  const { wrapper, requests } = await renderShell("/projects/p1/class", {
    "GET /api/projects/p1": () => ({
      status: 403,
      problem: {
        status: 403,
        code: "PERMISSION_DENIED",
        title: "Forbidden",
      },
    }),
    "GET /api/projects/{id}/events": openEventStream,
  });

  await flushPromises();
  expect(wrapper.text()).toContain("没有打开此项目的权限");
  expect(wrapper.text()).not.toContain("child-workspace");
  expect(eventRequests(requests)).toHaveLength(0);
  wrapper.unmount();
});

test("successful getProject renders the child workspace route and opens events once", async () => {
  const { wrapper, requests } = await renderShell("/projects/p1/class", {
    "GET /api/projects/p1": () => ({
      json: { projectId: "p1", displayName: "Demo" },
    }),
    "GET /api/projects/{id}/events": openEventStream,
  });

  await flushPromises();
  expect(wrapper.text()).toContain("child-workspace");
  expect(eventRequests(requests)).toHaveLength(1);
  wrapper.unmount();
});

function openEventStream() {
  return {
    status: 200,
    headers: { "content-type": "text/event-stream" },
    body: new ReadableStream<Uint8Array>({
      start() {
        // Keep the SSE connection open so tests can assert a single subscribe.
      },
    }),
  };
}

function eventRequests(
  requests: Awaited<ReturnType<typeof renderWithProviders>>["requests"],
) {
  return requests.filter((request) => request.path.endsWith("/events"));
}

async function renderShell(
  route: string,
  handlers: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"],
) {
  return renderWithProviders(ProjectShell, {
    route,
    handlers,
    routes: [
      {
        path: "/projects/:projectId",
        component: ProjectShell,
        children: [
          {
            path: WORKSPACE_KIND_PATH,
            name: "workspace",
            component: ChildPage,
          },
        ],
      },
      { path: "/projects", component: ProjectsPage },
    ],
  });
}
