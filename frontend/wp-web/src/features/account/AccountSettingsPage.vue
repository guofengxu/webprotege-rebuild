<script setup lang="ts">
import { computed } from "vue";
import { useI18n } from "vue-i18n";
import { useCurrentUser } from "@/features/auth/useAuth";
import ApiKeysTab from "./ApiKeysTab.vue";
import DevicesTab from "./DevicesTab.vue";

const { t } = useI18n();
const currentUser = useCurrentUser();

const user = computed(() => currentUser.data.value);
const rolesLabel = computed(() => {
  const roles = user.value?.appRoles ?? [];
  if (roles.length === 0) {
    return t("account.noRoles");
  }
  return roles.join(", ");
});
</script>

<template>
  <div class="account-page">
    <header class="account-page__header">
      <h1 class="account-page__title">
        {{ t("account.pageTitle") }}
      </h1>
      <dl
        v-if="user"
        class="account-page__profile"
      >
        <div class="account-page__field">
          <dt>{{ t("account.userId") }}</dt>
          <dd>{{ user.userId ?? "" }}</dd>
        </div>
        <div class="account-page__field">
          <dt>{{ t("account.displayName") }}</dt>
          <dd>{{ user.displayName ?? "" }}</dd>
        </div>
        <div class="account-page__field">
          <dt>{{ t("account.email") }}</dt>
          <dd>{{ user.email ?? "" }}</dd>
        </div>
        <div class="account-page__field">
          <dt>{{ t("account.appRoles") }}</dt>
          <dd>{{ rolesLabel }}</dd>
        </div>
      </dl>
      <el-skeleton
        v-else-if="currentUser.isPending.value"
        :rows="2"
        animated
      />
      <el-alert
        v-else-if="currentUser.isError.value"
        type="error"
        :closable="false"
        show-icon
        :title="t('account.loadError')"
      >
        <el-button
          text
          type="primary"
          @click="currentUser.refetch()"
        >
          {{ t("account.retry") }}
        </el-button>
      </el-alert>
    </header>

    <el-tabs class="account-page__tabs">
      <el-tab-pane :label="t('account.tabApiKeys')">
        <ApiKeysTab />
      </el-tab-pane>
      <el-tab-pane :label="t('account.tabDevices')">
        <DevicesTab />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.account-page {
  max-width: 64rem;
  margin: 0 auto;
  padding: 1.5rem 1.25rem 2.5rem;
  display: flex;
  flex-direction: column;
  gap: 1.5rem;
}

.account-page__title {
  margin: 0 0 1rem;
  font-size: 1.5rem;
  font-weight: 600;
}

.account-page__profile {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(12rem, 1fr));
  gap: 0.75rem 1.5rem;
  margin: 0;
}

.account-page__field {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.account-page__field dt {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 0.8125rem;
}

.account-page__field dd {
  margin: 0;
  font-size: 0.9375rem;
  word-break: break-word;
}
</style>
