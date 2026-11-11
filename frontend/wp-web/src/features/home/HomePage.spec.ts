import { mount } from "@vue/test-utils";
import { createPinia } from "pinia";
import { expect, test } from "vitest";
import { createMemoryHistory } from "vue-router";
import App from "@/App.vue";
import { createAppI18n } from "@/app/i18n";
import { createAppRouter } from "@/app/router";

test("shows the Chinese title, then the English title after switching locale", async () => {
  const i18n = createAppI18n();
  const router = createAppRouter(createMemoryHistory());
  const wrapper = mount(App, {
    global: {
      plugins: [createPinia(), router, i18n],
    },
  });

  await router.isReady();
  expect(wrapper.get("h1").text()).toBe("WebProtégé 本体编辑器");

  i18n.global.locale.value = "en-US";
  await wrapper.vm.$nextTick();
  expect(wrapper.get("h1").text()).toBe("WebProtégé Ontology Editor");
});
