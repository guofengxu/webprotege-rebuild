<script setup lang="ts">
import type { RevisionSummary } from "@wp/api-types";
import { computed, ref, toRef, watch } from "vue";
import { useI18n } from "vue-i18n";
import { RouterLink } from "vue-router";
import { problemMessage } from "@/shared/api/problem";
import { HISTORY_PAGE_SIZE, useEntityRevisions } from "./api";

const props = defineProps<{
  projectId: string;
  iri: string;
}>();

const { t, locale } = useI18n();

const currentPage = ref(1);
const backendPage = computed(() => Math.max(0, currentPage.value - 1));
const pageSize = HISTORY_PAGE_SIZE;

const projectId = toRef(props, "projectId");
const iri = toRef(props, "iri");
const query = useEntityRevisions(projectId, iri, backendPage, pageSize);

const rows = computed(() => query.data.value?.revisions ?? []);
const total = computed(() => query.data.value?.total ?? 0);

const dateFormatter = computed(
  () =>
    new Intl.DateTimeFormat(locale.value, {
      dateStyle: "medium",
      timeStyle: "short",
    }),
);

const errorText = computed(() => {
  if (!query.isError.value) {
    return "";
  }
  return problemMessage(query.error.value, t);
});

watch([iri, projectId], () => {
  currentPage.value = 1;
});

function formatTs(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return dateFormatter.value.format(date);
}

function onPageChange(page: number): void {
  currentPage.value = page;
}

function toRevision(row: RevisionSummary | Record<string, unknown>): RevisionSummary {
  return row as RevisionSummary;
}

function diffTo(row: RevisionSummary | Record<string, unknown>) {
  const revision = toRevision(row);
  return {
    name: "history" as const,
    params: { projectId: props.projectId },
    query: { rev: String(revision.revNo) },
  };
}
</script>

<template>
  <div
    class="entity-history"
    :aria-label="t('history.entityListLabel')"
    :aria-busy="query.isFetching.value ? 'true' : 'false'"
  >
    <div
      v-if="query.isError.value"
      class="entity-history__status"
      role="alert"
    >
      <p>{{ errorText || t("history.loadError") }}</p>
      <el-button
        type="primary"
        @click="query.refetch()"
      >
        {{ t("history.retry") }}
      </el-button>
    </div>

    <template v-else>
      <el-table
        v-loading="query.isPending.value"
        :data="rows"
        row-key="revNo"
        empty-text=""
      >
        <el-table-column
          prop="revNo"
          :label="t('history.revNo')"
          width="90"
        />
        <el-table-column
          :label="t('history.timestamp')"
          min-width="140"
        >
          <template #default="{ row }">
            {{ formatTs(row.ts) }}
          </template>
        </el-table-column>
        <el-table-column
          prop="userId"
          :label="t('history.user')"
          min-width="100"
        />
        <el-table-column
          prop="description"
          :label="t('history.description')"
          min-width="160"
          show-overflow-tooltip
        />
        <el-table-column
          prop="changeCount"
          :label="t('history.changeCount')"
          width="90"
        />
        <el-table-column
          :label="t('history.actions')"
          width="120"
        >
          <template #default="{ row }">
            <RouterLink
              class="entity-history__diff-link"
              :to="diffTo(row)"
            >
              {{ t("history.viewDiff") }}
            </RouterLink>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            v-if="!query.isPending.value"
            :description="t('history.entityEmpty')"
          />
        </template>
      </el-table>

      <el-pagination
        v-if="total > 0"
        class="entity-history__pager"
        background
        layout="total, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="onPageChange"
      />
    </template>
  </div>
</template>

<style scoped>
.entity-history {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.entity-history__status {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.entity-history__pager {
  justify-content: flex-end;
}

.entity-history__diff-link {
  color: var(--wp-color-brand, var(--el-color-primary));
  text-decoration: underline;
}

.entity-history__diff-link:focus-visible {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
