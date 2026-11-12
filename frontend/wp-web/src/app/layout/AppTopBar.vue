<script setup lang="ts">
import { useQuery } from "@tanstack/vue-query";
import { ElMessage } from "element-plus";
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { isSystemAdmin, useCurrentUser, useLogout } from "@/features/auth/useAuth";
import { useApiClient } from "@/shared/api/client";
import { problemMessage, unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";
import LocaleSwitcher from "./LocaleSwitcher.vue";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const currentUser = useCurrentUser();
const logout = useLogout();
const client = useApiClient();

const projectId = computed(() => {
  const raw = route.params.projectId;
  if (typeof raw === "string" && raw.length > 0) {
    return raw;
  }
  return undefined;
});

const projectQuery = useQuery({
  queryKey: computed(() =>
    projectId.value === undefined
      ? (["topbar-project", null] as const)
      : queryKeys.projectDetails(projectId.value),
  ),
  enabled: computed(() => projectId.value !== undefined),
  queryFn: async () => {
    const id = projectId.value;
    if (id === undefined) {
      throw new Error("projectId is required");
    }
    return unwrap(
      await client.GET("/api/projects/{id}", {
        params: { path: { id } },
      }),
    );
  },
});

const displayName = computed(() => currentUser.data.value?.displayName ?? "");
const showAdmin = computed(() => isSystemAdmin(currentUser.data.value));
const projectName = computed(() => projectQuery.data.value?.displayName ?? "");
const userMenuLabel = computed(() => {
  const name = displayName.value;
  return name === ""
    ? t("layout.userMenu")
    : `${t("layout.userMenu")}, ${name}`;
});

function onUserCommand(command: string | number | object): void {
  if (command === "settings") {
    void router.push("/me");
    return;
  }
  if (command === "admin") {
    void router.push("/admin/users");
    return;
  }
  if (command === "logout") {
    logout.mutate(undefined, {
      onError(error) {
        ElMessage.error(problemMessage(error, t));
      },
    });
  }
}
</script>

<template>
  <header
    class="top-bar"
    role="banner"
  >
    <div class="top-bar__left">
      <RouterLink
        class="top-bar__brand"
        to="/"
      >
        {{ t("layout.productName") }}
      </RouterLink>
      <span
        v-if="projectName !== ''"
        class="top-bar__project"
      >
        {{ projectName }}
      </span>
    </div>

    <div class="top-bar__search">
      <slot name="search" />
    </div>

    <div class="top-bar__right">
      <LocaleSwitcher />
      <el-dropdown
        trigger="click"
        @command="onUserCommand"
      >
        <button
          type="button"
          class="top-bar__user"
          :aria-label="userMenuLabel"
        >
          <span class="top-bar__user-name">{{ displayName }}</span>
          <span
            class="top-bar__user-caret"
            aria-hidden="true"
          >▾</span>
        </button>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="settings">
              {{ t("layout.accountSettings") }}
            </el-dropdown-item>
            <el-dropdown-item
              v-if="showAdmin"
              command="admin"
            >
              {{ t("layout.userManagement") }}
            </el-dropdown-item>
            <el-dropdown-item
              command="logout"
              divided
            >
              {{ t("layout.logout") }}
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped>
.top-bar {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(12rem, 28rem) auto;
  align-items: center;
  gap: var(--wp-space-4);
  height: var(--wp-topbar-height);
  padding: 0 var(--wp-space-4);
  border-bottom: 1px solid var(--wp-color-border);
  background: var(--wp-color-bg);
  color: var(--wp-color-text-primary);
  font-size: var(--wp-font-size-md);
}

.top-bar__left,
.top-bar__right {
  display: flex;
  align-items: center;
  gap: var(--wp-space-3);
  min-width: 0;
}

.top-bar__right {
  justify-content: flex-end;
}

.top-bar__brand {
  color: var(--wp-color-brand);
  font-weight: 600;
  text-decoration: none;
  white-space: nowrap;
}

.top-bar__brand:focus-visible {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: 2px;
}

.top-bar__project {
  overflow: hidden;
  color: var(--wp-color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-bar__project::before {
  content: "/";
  margin-right: var(--wp-space-2);
  color: var(--wp-color-text-tertiary);
}

.top-bar__search {
  min-width: 0;
}

.top-bar__user {
  display: inline-flex;
  align-items: center;
  gap: var(--wp-space-1);
  min-height: 24px;
  padding: var(--wp-space-1) var(--wp-space-2);
  border: 1px solid var(--wp-color-border);
  border-radius: var(--wp-radius-md);
  background: var(--wp-color-bg);
  color: var(--wp-color-text-primary);
  font-size: var(--wp-font-size-sm);
  cursor: pointer;
}

.top-bar__user:focus-visible {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: 2px;
}

.top-bar__user-name {
  max-width: 10rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.top-bar__user-caret {
  font-size: var(--wp-font-size-xs);
  color: var(--wp-color-text-tertiary);
}
</style>
