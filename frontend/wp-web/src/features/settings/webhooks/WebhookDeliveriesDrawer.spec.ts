import { flushPromises } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { defineComponent, h, ref } from "vue";
import type { components } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import WebhookDeliveriesDrawer from "./WebhookDeliveriesDrawer.vue";

type WebhookDeliveryResponse =
  components["schemas"]["WebhookDeliveryResponse"];

const PROJECT_ID = "p1";
const WEBHOOK_ID = "wh-1";

const DELIVERIES: WebhookDeliveryResponse[] = [
  {
    deliveryId: 11,
    status: "DELIVERED",
    attempts: 1,
    lastStatusCode: 200,
    lastError: "",
    createdAt: "2026-01-02T03:04:05Z",
    finishedAt: "2026-01-02T03:04:06Z",
  },
  {
    deliveryId: 12,
    status: "FAILED",
    attempts: 3,
    lastStatusCode: 500,
    lastError: "upstream",
    createdAt: "2026-01-01T01:00:00Z",
    finishedAt: "2026-01-01T01:05:00Z",
  },
];

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("loads deliveries when opened and refresh refetches", async () => {
  let listCalls = 0;
  const { wrapper, requests } = await mountDrawer({
    handlers: {
      "GET /api/projects/{projectId}/webhooks/{webhookId}/deliveries": () => {
        listCalls += 1;
        return { json: DELIVERIES };
      },
    },
  });
  await flushPromises();

  expect(wrapper.text()).toContain("DELIVERED");
  expect(wrapper.text()).toContain("FAILED");
  expect(wrapper.text()).toContain("upstream");
  expect(
    requests.filter(
      (entry) =>
        entry.method === "GET" &&
        entry.path ===
          `/api/projects/${PROJECT_ID}/webhooks/${WEBHOOK_ID}/deliveries`,
    ),
  ).toHaveLength(1);
  expect(listCalls).toBe(1);

  await wrapper
    .get('[data-testid="webhook-deliveries-refresh"]')
    .trigger("click");
  await flushPromises();

  expect(listCalls).toBe(2);
  wrapper.unmount();
});

test("does not fetch until the drawer is open", async () => {
  const Host = defineComponent({
    name: "ClosedDeliveriesHost",
    setup() {
      const open = ref(false);
      return () =>
        h("div", [
          h(
            "button",
            {
              "data-testid": "open-drawer",
              onClick: () => {
                open.value = true;
              },
            },
            "open",
          ),
          h(WebhookDeliveriesDrawer, {
            modelValue: open.value,
            "onUpdate:modelValue": (value: boolean) => {
              open.value = value;
            },
            projectId: PROJECT_ID,
            webhookId: WEBHOOK_ID,
          }),
        ]);
    },
  });

  const { wrapper, requests } = await renderWithProviders(Host, {
    handlers: {
      "GET /api/projects/{projectId}/webhooks/{webhookId}/deliveries": () => ({
        json: DELIVERIES,
      }),
    },
  });
  await flushPromises();

  expect(
    requests.filter((entry) => entry.path.includes("/deliveries")),
  ).toHaveLength(0);

  await wrapper.get('[data-testid="open-drawer"]').trigger("click");
  await flushPromises();

  expect(
    requests.filter(
      (entry) =>
        entry.method === "GET" &&
        entry.path ===
          `/api/projects/${PROJECT_ID}/webhooks/${WEBHOOK_ID}/deliveries`,
    ),
  ).toHaveLength(1);
  wrapper.unmount();
});

async function mountDrawer({
  handlers = {},
}: {
  handlers?: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"];
} = {}) {
  const Host = defineComponent({
    name: "WebhookDeliveriesHost",
    setup() {
      const open = ref(true);
      return () =>
        h(WebhookDeliveriesDrawer, {
          modelValue: open.value,
          "onUpdate:modelValue": (value: boolean) => {
            open.value = value;
          },
          projectId: PROJECT_ID,
          webhookId: WEBHOOK_ID,
        });
    },
  });

  return renderWithProviders(Host, { handlers });
}
