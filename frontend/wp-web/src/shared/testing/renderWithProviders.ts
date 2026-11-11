import { QueryClient, VueQueryPlugin } from "@tanstack/vue-query";
import { mount, type VueWrapper } from "@vue/test-utils";
import ElementPlus from "element-plus";
import { createPinia } from "pinia";
import { defineComponent, h, type App, type Component } from "vue";
import {
  createMemoryHistory,
  createRouter,
  RouterView,
  type RouteRecordRaw,
  type Router,
} from "vue-router";
import { createAppI18n, type AppLocale } from "@/app/i18n";
import { createApiClient, provideApiClient } from "@/shared/api/client";
import { createMockFetch, type MockHandler, type RecordedRequest } from "./mockFetch";

export type RenderWithProvidersOptions = {
  props?: Record<string, unknown>;
  handlers?: Record<string, MockHandler>;
  routes?: RouteRecordRaw[];
  route?: string;
  locale?: AppLocale;
};

const ProviderShell = defineComponent({
  name: "ProviderShell",
  setup() {
    return () => h(RouterView);
  },
});

export async function renderWithProviders(
  component: Component,
  options: RenderWithProvidersOptions = {},
): Promise<{
  wrapper: VueWrapper;
  router: Router;
  queryClient: QueryClient;
  requests: RecordedRequest[];
}> {
  const { fetch, requests } = createMockFetch(options.handlers ?? {});
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false },
      mutations: { retry: false },
    },
  });
  const apiClient = createApiClient({ fetch, baseUrl: testBaseUrl() });
  const router = createRouter({
    history: createMemoryHistory(),
    routes: options.routes ?? [defaultRoute(component, options.props)],
  });
  await router.push(options.route ?? "/");
  const wrapper = mount(ProviderShell, {
    global: {
      plugins: [
        createPinia(),
        router,
        createAppI18n(options.locale ?? "zh-CN"),
        ElementPlus,
        [VueQueryPlugin, { queryClient }],
        {
          install(app: App) {
            provideApiClient(app, apiClient);
          },
        },
      ],
    },
  });
  await router.isReady();
  return { wrapper, router, queryClient, requests };
}

function testBaseUrl(): string {
  const origin = window.location.origin;
  // Node's Request rejects a path-only URL. The browser still uses a relative baseUrl.
  return origin && origin !== "null" ? origin : "http://localhost";
}

function defaultRoute(
  component: Component,
  props: Record<string, unknown> | undefined,
): RouteRecordRaw {
  return {
    path: "/:pathMatch(.*)*",
    component,
    props: props ?? {},
  };
}
