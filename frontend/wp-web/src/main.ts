import { VueQueryPlugin } from "@tanstack/vue-query";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import { createApp } from "vue";
import App from "./App.vue";
import { handleUnauthorized, installAuthGuard } from "./app/authGuard";
import { createAppI18n } from "./app/i18n";
import { createAppQueryClient } from "./app/queryClient";
import { createAppRouter } from "./app/router";
import { createApiClient, provideApiClient } from "./shared/api/client";

const app = createApp(App);
const queryClient = createAppQueryClient();
const router = createAppRouter();
const apiClient = createApiClient({
  onUnauthorized() {
    handleUnauthorized(router, queryClient);
  },
});
installAuthGuard(router, queryClient, apiClient);
app.use(createPinia());
app.use(router);
app.use(createAppI18n());
app.use(ElementPlus);
app.use(VueQueryPlugin, { queryClient });
provideApiClient(app, apiClient);
app.mount("#app");
