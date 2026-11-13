import { flushPromises } from "@vue/test-utils";
import { ElMessageBox } from "element-plus";
import { defineComponent, h, ref } from "vue";
import { afterEach, expect, test, vi } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { useUnsavedGuard } from "./useUnsavedGuard";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.removeItem("wp.locale");
});

const ENTITY_A = "http://ex.org/ont#A";
const ENTITY_B = "http://ex.org/ont#B";

test("dirty entity query change prompts and cancel keeps the route", async () => {
  vi.spyOn(ElMessageBox, "confirm").mockRejectedValue("cancel");
  const { router, wrapper } = await mountGuard(true);
  await flushPromises();

  await router.push({
    path: "/projects/p1/class",
    query: { entity: ENTITY_B },
  });
  await flushPromises();

  expect(ElMessageBox.confirm).toHaveBeenCalled();
  expect(router.currentRoute.value.query.entity).toBe(ENTITY_A);
  wrapper.unmount();
});

test("clean draft navigates without prompting", async () => {
  vi.spyOn(ElMessageBox, "confirm").mockResolvedValue("confirm" as never);
  const { router, wrapper } = await mountGuard(false);
  await flushPromises();

  await router.push({
    path: "/projects/p1/class",
    query: { entity: ENTITY_B },
  });
  await flushPromises();

  expect(ElMessageBox.confirm).not.toHaveBeenCalled();
  expect(router.currentRoute.value.query.entity).toBe(ENTITY_B);
  wrapper.unmount();
});

async function mountGuard(dirty: boolean) {
  const GuardHost = defineComponent({
    name: "UnsavedGuardHost",
    setup() {
      useUnsavedGuard(ref(dirty));
      return () => h("div", "host");
    },
  });

  return renderWithProviders(GuardHost, {
    route: `/projects/p1/class?entity=${encodeURIComponent(ENTITY_A)}`,
    routes: [
      {
        path: "/projects/:projectId/:kind",
        component: GuardHost,
      },
    ],
  });
}
