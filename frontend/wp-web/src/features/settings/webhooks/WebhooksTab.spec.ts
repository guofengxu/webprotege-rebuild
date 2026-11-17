import { flushPromises } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { afterEach, expect, test, vi } from "vitest";
import type { components } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import WebhooksTab from "./WebhooksTab.vue";

type WebhookResponse = components["schemas"]["WebhookResponse"];

const PROJECT_ID = "p1";

const WEBHOOKS: WebhookResponse[] = [
  {
    webhookId: "wh-1",
    payloadUrl: "https://hooks.example.com/path/to/hook",
    eventTypes: ["PROJECT_CHANGED"],
    enabled: true,
    hasSecret: true,
    lastDelivery: {
      status: "DELIVERED",
      statusCode: 200,
      finishedAt: "2026-01-02T03:04:05Z",
      error: "",
    },
  },
  {
    webhookId: "wh-2",
    payloadUrl: "https://other.example.org/cb",
    eventTypes: ["PROJECT_CHANGED"],
    enabled: false,
    hasSecret: false,
  },
];

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("renders webhook list with host-only URLs", async () => {
  const { wrapper, requests } = await mountTab();
  await flushPromises();

  expect(wrapper.text()).toContain("https://hooks.example.com");
  expect(wrapper.text()).not.toContain("/path/to/hook");
  expect(wrapper.text()).toContain("PROJECT_CHANGED");
  expect(wrapper.text()).toContain("已设置");
  expect(wrapper.text()).toContain("DELIVERED");
  expect(
    requests.some(
      (entry) =>
        entry.method === "GET" &&
        entry.path === `/api/projects/${PROJECT_ID}/webhooks`,
    ),
  ).toBe(true);
  wrapper.unmount();
});

test("without EDIT_PROJECT_SETTINGS does not request webhooks", async () => {
  const { wrapper, requests } = await mountTab({
    actions: ["VIEW_PROJECT"],
  });
  await flushPromises();

  expect(wrapper.text()).toContain("只有项目管理者可以查看和管理 webhook");
  expect(
    requests.filter((entry) => entry.path.includes("/webhooks")),
  ).toHaveLength(0);
  expect(wrapper.find('[data-testid="webhooks-table"]').exists()).toBe(false);
  wrapper.unmount();
});

test("delete asks for confirmation before DELETE", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);

  let listed = [...WEBHOOKS];
  const { wrapper, requests } = await mountTab({
    handlers: {
      "GET /api/projects/{projectId}/webhooks": () => ({ json: listed }),
      "DELETE /api/projects/{projectId}/webhooks/{webhookId}": (req) => {
        listed = listed.filter(
          (webhook) => webhook.webhookId !== req.params.webhookId,
        );
        return { status: 204 };
      },
    },
  });
  await flushPromises();

  await wrapper.get('[data-testid="webhook-delete-wh-1"]').trigger("click");
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  expect(
    requests.some(
      (entry) =>
        entry.method === "DELETE" &&
        entry.path === `/api/projects/${PROJECT_ID}/webhooks/wh-1`,
    ),
  ).toBe(true);
  wrapper.unmount();
});

test("enable / disable sends update with secretChange KEEP", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountTab({
    handlers: {
      "PUT /api/projects/{projectId}/webhooks/{webhookId}": (req) => ({
        json: {
          ...WEBHOOKS[0],
          enabled: (req.body as { enabled?: boolean }).enabled,
        },
      }),
    },
  });
  await flushPromises();

  await wrapper.get('[data-testid="webhook-toggle-wh-1"]').trigger("click");
  await flushPromises();

  const put = requests.find(
    (entry) =>
      entry.method === "PUT" &&
      entry.path === `/api/projects/${PROJECT_ID}/webhooks/wh-1`,
  );
  expect(put?.body).toMatchObject({
    enabled: false,
    secretChange: "KEEP",
  });
  wrapper.unmount();
});

async function mountTab({
  actions = ["VIEW_PROJECT", "EDIT_PROJECT_SETTINGS"] as const,
  handlers = {},
}: {
  actions?: readonly string[];
  handlers?: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"];
} = {}) {
  return renderWithProviders(WebhooksTab, {
    props: { projectId: PROJECT_ID },
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: { actions: [...actions] },
      }),
      "GET /api/projects/{projectId}/webhooks": () => ({ json: WEBHOOKS }),
      ...handlers,
    },
  });
}
