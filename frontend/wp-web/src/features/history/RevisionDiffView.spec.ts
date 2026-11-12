import { flushPromises } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { afterEach, expect, test } from "vitest";
import { renderWithProviders } from "@/shared/testing/renderWithProviders";
import { revisionDiff } from "./fixtures";
import RevisionDiffView from "./RevisionDiffView.vue";

afterEach(() => {
  localStorage.removeItem("wp.locale");
});

test("groups changes by subject iri and shows truncation when total > limit", async () => {
  const base = revisionDiff(3);
  const { wrapper } = await mountDiff({
    total: 250,
    limit: 200,
    changes: base.changes,
  });
  await flushPromises();

  expect(wrapper.text()).toContain("Pizza");
  expect(wrapper.text()).toContain("ont");
  expect(wrapper.text()).toContain("共 250 条，仅显示前 200 条");
  const pizzaLink = wrapper.find('a[href*="/projects/p1/class"]');
  expect(pizzaLink.exists()).toBe(true);
  expect(pizzaLink.text()).toContain("Pizza");
  wrapper.unmount();
});

test("op is shown with both text and an icon", async () => {
  const { wrapper } = await mountDiff(revisionDiff(3));
  await flushPromises();

  const add = wrapper.find('[data-op="ADD"]');
  const remove = wrapper.find('[data-op="REMOVE"]');
  expect(add.exists()).toBe(true);
  expect(remove.exists()).toBe(true);
  expect(add.text()).toContain("添加");
  expect(add.text()).toContain("+");
  expect(remove.text()).toContain("删除");
  expect(remove.text()).toContain("−");
  wrapper.unmount();
});

async function mountDiff(diff: {
  total: number;
  limit: number;
  changes: ReturnType<typeof revisionDiff>["changes"];
}) {
  const Host = defineComponent({
    name: "RevisionDiffHost",
    setup() {
      return () =>
        h(RevisionDiffView, {
          projectId: "p1",
          rev: 3,
        });
    },
  });
  return renderWithProviders(Host, {
    route: "/projects/p1/history?rev=3",
    routes: [
      {
        path: "/projects/:projectId",
        children: [
          {
            path: "history",
            name: "history",
            component: Host,
          },
          {
            path: ":kind(class|object-property|data-property|annotation-property|individual)",
            name: "workspace",
            component: { template: "<div />" },
          },
        ],
      },
    ],
    handlers: {
      "GET /api/projects/{id}/revisions/{rev}": () => ({
        json: {
          revNo: 3,
          changes: diff.changes,
          limit: diff.limit,
          total: diff.total,
        },
      }),
    },
  });
}
