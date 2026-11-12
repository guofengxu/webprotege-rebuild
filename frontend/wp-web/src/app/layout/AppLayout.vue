<script setup lang="ts">
import GlobalSearch from "@/features/search/GlobalSearch.vue";
import AppTopBar from "./AppTopBar.vue";
</script>

<template>
  <div class="app-layout">
    <AppTopBar>
      <template #search>
        <GlobalSearch />
      </template>
    </AppTopBar>
    <main class="app-layout__main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.app-layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
  background: var(--wp-color-bg-muted);
  color: var(--wp-color-text-primary);
  font-size: var(--wp-font-size-md);
}

.app-layout__main {
  flex: 1;
  min-height: 0;
}
</style>
