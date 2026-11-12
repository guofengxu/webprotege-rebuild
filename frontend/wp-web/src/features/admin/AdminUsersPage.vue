<script setup lang="ts">
import type { AdminUserResponse } from "@wp/api-types";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { ADMIN_USERS_PAGE_SIZE, useAdminUsers } from "./api";
import CreateUserDialog from "./CreateUserDialog.vue";

const { t } = useI18n();

const currentPage = ref(1);
const backendPage = computed(() => Math.max(0, currentPage.value - 1));
const pageSize = ADMIN_USERS_PAGE_SIZE;
const createOpen = ref(false);

const query = useAdminUsers(backendPage, pageSize);
const rows = computed(() => query.data.value?.users ?? []);
const total = computed(() => query.data.value?.total ?? 0);

const forbidden = computed(
  () =>
    query.isError.value &&
    query.error.value instanceof ApiError &&
    query.error.value.status === 403,
);

const errorText = computed(() => {
  if (!query.isError.value || forbidden.value) {
    return "";
  }
  return problemMessage(query.error.value, t);
});

function onPageChange(page: number): void {
  currentPage.value = page;
}

function roleLabels(user: AdminUserResponse): string[] {
  return user.appRoles ?? [];
}
</script>

<template>
  <div
    class="admin-users"
    :aria-label="t('admin.pageTitle')"
  >
    <header class="admin-users__header">
      <h1 class="admin-users__title">
        {{ t("admin.pageTitle") }}
      </h1>
      <el-button
        v-if="!forbidden"
        type="primary"
        @click="createOpen = true"
      >
        {{ t("admin.createUser") }}
      </el-button>
    </header>

    <el-alert
      v-if="forbidden"
      type="error"
      show-icon
      :closable="false"
      :title="t('admin.forbidden')"
    />
    <el-alert
      v-else-if="errorText"
      type="error"
      show-icon
      :closable="false"
      :title="t('admin.loadError')"
      :description="errorText"
    >
      <el-button
        text
        type="primary"
        :aria-label="t('admin.retryLoadUsers')"
        @click="query.refetch()"
      >
        {{ t("admin.retry") }}
      </el-button>
    </el-alert>

    <template v-else>
      <div
        v-if="query.isPending.value && rows.length === 0"
        role="status"
        aria-live="polite"
      >
        <span class="sr-only">{{ t("admin.loading") }}</span>
        <el-skeleton
          :rows="6"
          animated
        />
      </div>
      <template v-else>
        <el-table
          :data="rows"
          row-key="userId"
          stripe
          :empty-text="t('admin.empty')"
          :aria-label="t('admin.usersTableLabel')"
          :aria-busy="query.isFetching.value ? 'true' : 'false'"
        >
          <el-table-column
            :label="t('admin.userId')"
            min-width="120"
          >
            <template #default="{ row }">
              {{ row.userId ?? "" }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('admin.displayName')"
            min-width="140"
          >
            <template #default="{ row }">
              {{ row.displayName ?? "" }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('admin.email')"
            min-width="180"
          >
            <template #default="{ row }">
              {{ row.email ?? "" }}
            </template>
          </el-table-column>
          <el-table-column
            :label="t('admin.appRoles')"
            min-width="180"
          >
            <template #default="{ row }">
              <template v-if="roleLabels(row).length > 0">
                <el-tag
                  v-for="role in roleLabels(row)"
                  :key="role"
                  class="admin-users__role"
                  size="small"
                >
                  {{ role }}
                </el-tag>
              </template>
              <span v-else>{{ t("admin.noRoles") }}</span>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('admin.passwordResetRequired')"
            min-width="140"
          >
            <template #default="{ row }">
              <el-tag
                v-if="row.passwordResetRequired"
                type="warning"
                size="small"
              >
                {{ t("admin.passwordResetRequired") }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            :label="t('admin.actions')"
            min-width="120"
          >
            <template #default>
              <!-- P4-28: set roles / reset password -->
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-if="total > 0"
          class="admin-users__pager"
          background
          layout="total, prev, pager, next"
          :total="total"
          :page-size="pageSize"
          :current-page="currentPage"
          @current-change="onPageChange"
        />
      </template>
    </template>

    <CreateUserDialog v-model="createOpen" />
  </div>
</template>

<style scoped>
.admin-users {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 16px 20px 24px;
}

.admin-users__header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.admin-users__title {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.admin-users__role {
  margin-right: 4px;
}

.admin-users__pager {
  display: flex;
  justify-content: flex-end;
}

.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
</style>
