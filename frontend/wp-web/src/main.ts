import { VueQueryPlugin } from "@tanstack/vue-query";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import { createApp } from "vue";
import App from "./App.vue";
import { createAppI18n } from "./app/i18n";
import { createAppQueryClient } from "./app/queryClient";
import { createAppRouter } from "./app/router";
import { createApiClient, provideApiClient } from "./shared/api/client";

const app = createApp(App);
const queryClient = createAppQueryClient();
// P4-25 passes onUnauthorized so a 401 leaves the current page for the login screen.
const apiClient = createApiClient();
app.use(createPinia());
app.use(createAppRouter());
app.use(createAppI18n());
app.use(ElementPlus);
app.use(VueQueryPlugin, { queryClient });
provideApiClient(app, apiClient);
app.mount("#app");
