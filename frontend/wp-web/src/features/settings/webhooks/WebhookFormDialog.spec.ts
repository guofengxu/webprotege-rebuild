import { flushPromises, type VueWrapper } from "@vue/test-utils";
import { afterEach, expect, test } from "vitest";
import { defineComponent, h, ref } from "vue";
import type { components } from "@wp/api-types";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import WebhookFormDialog from "./WebhookFormDialog.vue";

type WebhookResponse = components["schemas"]["WebhookResponse"];

const PROJECT_ID = "p1";

const EXISTING: WebhookResponse = {
  webhookId: "wh-1",
  payloadUrl: "https://hooks.example.com/hook",
  eventTypes: ["PROJECT_CHANGED"],
  enabled: true,
  hasSecret: true,
};

afterEach(() => {
  document.cookie =
    "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  localStorage.removeItem("wp.locale");
  document.body.querySelectorAll(".el-overlay").forEach((node) => node.remove());
});

test("rejects non-http URLs and short secrets before request", async () => {
  const { wrapper, requests } = await mountDialog({ webhook: null });
  await flushPromises();

  await setInput(wrapper, "payloadUrl", "ftp://bad.example/x");
  await setInput(wrapper, "secret", "short");
  await submit(wrapper);
  await flushPromises();

  expect(wrapper.text()).toMatch(/绝对的 http|https/);
  expect(wrapper.text()).toContain("至少 16");
  expect(
    requests.filter(
      (entry) => entry.method === "POST" || entry.method === "PUT",
    ),
  ).toHaveLength(0);
  wrapper.unmount();
});

test("create posts payload without secretChange", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";
  const { wrapper, requests } = await mountDialog({
    webhook: null,
    handlers: {
      "POST /api/projects/{projectId}/webhooks": () => ({
        status: 201,
        json: EXISTING,
      }),
    },
  });
  await flushPromises();

  await setInput(wrapper, "payloadUrl", "https://hooks.example.com/new");
  await setInput(wrapper, "secret", "1234567890abcdef");
  await submit(wrapper);
  await flushPromises();

  const post = requests.find(
    (entry) =>
      entry.method === "POST" &&
      entry.path === `/api/projects/${PROJECT_ID}/webhooks`,
  );
  expect(post?.body).toEqual({
    payloadUrl: "https://hooks.example.com/new",
    eventTypes: ["PROJECT_CHANGED"],
    enabled: true,
    secret: "1234567890abcdef",
  });
  wrapper.unmount();
});

test("edit KEEP / REPLACE / CLEAR produce the expected bodies", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";

  for (const mode of ["KEEP", "REPLACE", "CLEAR"] as const) {
    const { wrapper, requests } = await mountDialog({
      webhook: EXISTING,
      handlers: {
        "PUT /api/projects/{projectId}/webhooks/{webhookId}": () => ({
          json: EXISTING,
        }),
      },
    });
    await flushPromises();

    if (mode !== "KEEP") {
      await selectSecretMode(wrapper, mode);
      await flushPromises();
    }
    if (mode === "REPLACE") {
      await setInput(wrapper, "secret", "abcdefghijklmnop");
    }
    await submit(wrapper);
    await flushPromises();

    const put = requests.find(
      (entry) =>
        entry.method === "PUT" &&
        entry.path === `/api/projects/${PROJECT_ID}/webhooks/wh-1`,
    );
    expect(put?.body).toMatchObject({
      secretChange: mode,
      enabled: true,
      eventTypes: ["PROJECT_CHANGED"],
    });
    if (mode === "REPLACE") {
      expect(put?.body).toMatchObject({ secret: "abcdefghijklmnop" });
    } else {
      expect(
        put?.body !== null &&
          typeof put?.body === "object" &&
          "secret" in (put.body as object),
      ).toBe(false);
    }
    wrapper.unmount();
  }
});

test("server error codes map onto the matching fields", async () => {
  document.cookie = "XSRF-TOKEN=csrf-token";

  const cases = [
    {
      code: "INVALID_WEBHOOK_URL",
      expectText: "回调地址无效",
    },
    {
      code: "WEBHOOK_TARGET_NOT_ALLOWED",
      expectText: "不允许该回调地址",
    },
    {
      code: "WEBHOOK_LIMIT_REACHED",
      expectText: "数量上限",
    },
  ] as const;

  for (const item of cases) {
    const { wrapper } = await mountDialog({
      webhook: null,
      handlers: {
        "POST /api/projects/{projectId}/webhooks": () => ({
          status: 400,
          problem: {
            status: 400,
            title: "Bad Request",
            code: item.code,
          },
        }),
      },
    });
    await flushPromises();

    await setInput(wrapper, "payloadUrl", "https://hooks.example.com/x");
    await submit(wrapper);
    await flushPromises();

    expect(wrapper.text()).toContain(item.expectText);
    wrapper.unmount();
  }
});

async function mountDialog({
  webhook,
  handlers = {},
}: {
  webhook: WebhookResponse | null;
  handlers?: NonNullable<
    Parameters<typeof renderWithProviders>[1]
  >["handlers"];
}) {
  const Host = defineComponent({
    name: "WebhookFormDialogHost",
    setup() {
      const open = ref(true);
      return () =>
        h(WebhookFormDialog, {
          modelValue: open.value,
          "onUpdate:modelValue": (value: boolean) => {
            open.value = value;
          },
          projectId: PROJECT_ID,
          webhook,
        });
    },
  });

  return renderWithProviders(Host, {
    handlers: {
      "GET /api/projects/{id}/permissions/me": () => ({
        json: { actions: ["VIEW_PROJECT", "EDIT_PROJECT_SETTINGS"] },
      }),
      ...handlers,
    },
  });
}

async function setInput(
  wrapper: VueWrapper,
  name: string,
  value: string,
): Promise<void> {
  const input = wrapper.find(`input[name="${name}"]`);
  expect(input.exists()).toBe(true);
  await input.setValue(value);
  await input.trigger("input");
}

async function submit(wrapper: VueWrapper): Promise<void> {
  await wrapper.get("form").trigger("submit");
}

async function selectSecretMode(
  wrapper: VueWrapper,
  mode: "KEEP" | "REPLACE" | "CLEAR",
): Promise<void> {
  const group = wrapper.findComponent({ name: "ElRadioGroup" });
  expect(group.exists()).toBe(true);
  await group.vm.$emit("update:modelValue", mode);
  await flushPromises();
}
