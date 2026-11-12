<script setup lang="ts">
import type { RevisionSummary } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { problemMessage } from "@/shared/api/problem";
import { HISTORY_PAGE_SIZE, useRevisions } from "./api";
import RevertRevisionButton from "./RevertRevisionButton.vue";
import RevisionDiffView from "./RevisionDiffView.vue";

const { t, locale } = useI18n();
const route = useRoute();
const router = useRouter();

const projectId = computed(() => {
  const raw = route.params.projectId;
  return typeof raw === "string" ? raw : "";
});

const currentPage = ref(1);
const backendPage = computed(() => Math.max(0, currentPage.value - 1));
const pageSize = HISTORY_PAGE_SIZE;

const query = useRevisions(projectId, backendPage, pageSize);
const rows = computed(() => query.data.value?.revisions ?? []);
const total = computed(() => query.data.value?.total ?? 0);

const selectedRev = computed((): number | null => {
  const raw = route.query.rev;
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== "string" || value.length === 0) {
    return null;
  }
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null;
});

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

watch(projectId, () => {
  currentPage.value = 1;
});

function formatTs(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return dateFormatter.value.format(date);
}

function toRevision(row: RevisionSummary | Record<string, unknown>): RevisionSummary {
  return row as RevisionSummary;
}

function selectRevision(row: RevisionSummary | Record<string, unknown>): void {
  const revision = toRevision(row);
  void router.replace({
    query: { ...route.query, rev: String(revision.revNo) },
  });
}

function onPageChange(page: number): void {
  currentPage.value = page;
}

function rowClassName({ row }: { row: RevisionSummary }): string {
  return row.revNo === selectedRev.value ? "is-selected-row" : "";
}
</script>

<template>
  <div
    class="revision-history"
    :aria-label="t('history.pageTitle')"
  >
    <h1 class="revision-history__heading">
      {{ t("history.pageTitle") }}
    </h1>

    <div class="revision-history__layout">
      <section
        class="revision-history__list"
        :aria-label="t('history.listLabel')"
        :aria-busy="query.isFetching.value ? 'true' : 'false'"
      >
        <div
          v-if="query.isError.value"
          class="revision-history__status"
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
            highlight-current-row
            empty-text=""
            :row-class-name="rowClassName"
            :current-row-key="selectedRev ?? undefined"
            @row-click="selectRevision"
          >
            <el-table-column
              :label="t('history.revNo')"
              width="110"
            >
              <template #default="{ row }">
                <button
                  type="button"
                  class="revision-history__rev-button"
                  :aria-pressed="row.revNo === selectedRev ? 'true' : 'false'"
                  :aria-label="t('history.viewRevision', { rev: row.revNo })"
                  @click.stop="selectRevision(row)"
                >
                  {{ row.revNo }}
                  <span
                    v-if="row.revNo === selectedRev"
                    class="revision-history__selected-mark"
                  >{{ t("history.selected") }}</span>
                </button>
              </template>
            </el-table-column>
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
                <RevertRevisionButton
                  :project-id="projectId"
                  :revision="toRevision(row)"
                />
              </template>
            </el-table-column>
            <template #empty>
              <el-empty
                v-if="!query.isPending.value"
                :description="t('history.empty')"
              />
            </template>
          </el-table>

          <el-pagination
            v-if="total > 0"
            class="revision-history__pager"
            background
            layout="total, prev, pager, next"
            :total="total"
            :page-size="pageSize"
            :current-page="currentPage"
            @current-change="onPageChange"
          />
        </template>
      </section>

      <aside class="revision-history__diff">
        <RevisionDiffView
          v-if="selectedRev !== null && projectId.length > 0"
          :project-id="projectId"
          :rev="selectedRev"
        />
        <el-empty
          v-else
          :description="t('history.selectRevision')"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.revision-history {
  display: flex;
  flex-direction: column;
  gap: 1rem;
  height: 100%;
  min-height: 0;
  padding: 1rem;
}

.revision-history__heading {
  margin: 0;
  font-size: 1.25rem;
  font-weight: 600;
}

.revision-history__layout {
  display: grid;
  grid-template-columns: minmax(20rem, 1fr) minmax(20rem, 1fr);
  gap: 1rem;
  min-height: 0;
  flex: 1;
}

.revision-history__list,
.revision-history__diff {
  min-height: 0;
  overflow: auto;
}

.revision-history__status {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.revision-history__pager {
  margin-top: 0.75rem;
  justify-content: flex-end;
}

.revision-history__list :deep(.is-selected-row) > td.el-table__cell {
  background-color: var(--el-table-current-row-bg-color);
}

@media (max-width: 64rem) {
  .revision-history__layout {
    grid-template-columns: 1fr;
  }
}
</style>
