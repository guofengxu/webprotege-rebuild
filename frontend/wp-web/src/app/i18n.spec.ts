import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, h, nextTick } from "vue";
import { afterEach, expect, test } from "vitest";
import {
  createAppI18n,
  resolveInitialLocale,
  useAppLocale,
  type AppLocale,
} from "./i18n";

afterEach(() => {
  localStorage.removeItem("wp.locale");
  document.documentElement.lang = "";
});

test("setLocale updates copy, html lang, and localStorage", async () => {
  const i18n = createAppI18n("zh-CN");
  const Probe = defineComponent({
    setup() {
      const { locale, setLocale } = useAppLocale();
      return () =>
        h("div", [
          h("p", { id: "locale" }, locale.value),
          h("p", { id: "title" }, i18n.global.t("app.title")),
          h(
            "button",
            {
              id: "switch",
              onClick: () => {
                setLocale("en-US");
              },
            },
            "switch",
          ),
        ]);
    },
  });

  const wrapper = mount(Probe, { global: { plugins: [i18n] } });
  expect(wrapper.get("#locale").text()).toBe("zh-CN");
  expect(wrapper.get("#title").text()).toBe("WebProtégé 本体编辑器");

  await wrapper.get("#switch").trigger("click");
  await nextTick();
  await flushPromises();

  expect(wrapper.get("#locale").text()).toBe("en-US");
  expect(wrapper.get("#title").text()).toBe("WebProtégé Ontology Editor");
  expect(document.documentElement.lang).toBe("en-US");
  expect(localStorage.getItem("wp.locale")).toBe("en-US");
  wrapper.unmount();
});

test("invalid localStorage locale values are ignored", () => {
  localStorage.setItem("wp.locale", "fr-FR");
  expect(resolveInitialLocale()).not.toBe("fr-FR" as AppLocale);
  expect(["zh-CN", "en-US"]).toContain(resolveInitialLocale());
});

test("a stored locale wins over the browser language", () => {
  localStorage.setItem("wp.locale", "en-US");
  expect(resolveInitialLocale()).toBe("en-US");
  localStorage.setItem("wp.locale", "zh-CN");
  expect(resolveInitialLocale()).toBe("zh-CN");
});
