import { mount } from "@vue/test-utils";
import { expect, test } from "vitest";
import { createAppI18n } from "@/app/i18n";
import HomePage from "./HomePage.vue";

test("shows the Chinese title, then the English title after switching locale", async () => {
  const i18n = createAppI18n("zh-CN");
  const wrapper = mount(HomePage, {
    global: {
      plugins: [i18n],
    },
  });

  expect(wrapper.get("h1").text()).toBe("WebProtégé 本体编辑器");

  i18n.global.locale.value = "en-US";
  await wrapper.vm.$nextTick();
  expect(wrapper.get("h1").text()).toBe("WebProtégé Ontology Editor");
});
