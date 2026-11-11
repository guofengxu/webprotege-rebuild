import { createApp } from "vue";
import { createPinia } from "pinia";
import ElementPlus from "element-plus";
import "element-plus/dist/index.css";
import App from "./App.vue";
import { createAppI18n } from "./app/i18n";
import { createAppRouter } from "./app/router";

const app = createApp(App);
app.use(createPinia());
app.use(createAppRouter());
app.use(createAppI18n());
app.use(ElementPlus);
app.mount("#app");
