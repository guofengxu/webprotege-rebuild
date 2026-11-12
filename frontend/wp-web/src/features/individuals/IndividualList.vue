<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref, toRef, watch } from "vue";
import { useI18n } from "vue-i18n";
import { problemMessage } from "@/shared/api/problem";
import { useIndividuals } from "./api";
import { iriLocalName } from "./iriLocalName";

const props = defineProps<{
  projectId: string;
  typeIri: string | null;
  selectedIri?: string | null;
}>();

const emit = defineEmits<{
  select: [node: EntityNode];
  "row-contextmenu": [node: EntityNode, event: MouseEvent];
}>();

const { t } = useI18n();

const PAGE_SIZE_OPTIONS = [20, 50, 100] as const;
const currentPage = ref(1);
const pageSize = ref<(typeof PAGE_SIZE_OPTIONS)[number]>(50);

const typeIri = toRef(props, "typeIri");
const projectId = toRef(props, "projectId");
const backendPage = computed(() => Math.max(0, currentPage.value - 1));

watch(typeIri, () => {
  currentPage.value = 1;
});

const query = useIndividuals(projectId, typeIri, backendPage, pageSize);

const rows = computed(() => query.data.value?.individuals ?? []);
const total = computed(() => query.data.value?.total ?? 0);
const hasType = computed(
  () => props.typeIri !== null && props.typeIri.length > 0,
);
const showEmpty = computed(
  () =>
    hasType.value &&
    !query.isPending.value &&
    !query.isError.value &&
    rows.value.length === 0,
);
const errorText = computed(() => {
  if (!query.isError.value) {
    return "";
  }
  return problemMessage(query.error.value, t);
});

function onRowClick(row: EntityNode | Record<string, unknown>): void {
  emit("select", row as EntityNode);
}

function onRowContextMenu(
  row: EntityNode | Record<string, unknown>,
  _column: unknown,
  event: Event,
): void {
  if (!(event instanceof MouseEvent)) {
    return;
  }
  event.preventDefault();
  emit("row-contextmenu", row as EntityNode, event);
}

function onPageChange(page: number): void {
  currentPage.value = page;
}

function onSizeChange(size: number): void {
  if ((PAGE_SIZE_OPTIONS as readonly number[]).includes(size)) {
    pageSize.value = size as (typeof PAGE_SIZE_OPTIONS)[number];
    currentPage.value = 1;
  }
}

function rowClassName({ row }: { row: EntityNode }): string {
  return row.iri === props.selectedIri ? "is-selected-row" : "";
}

function isSelectedRow(row: { iri?: unknown }): boolean {
  return (
    props.selectedIri !== null &&
    props.selectedIri !== undefined &&
    row.iri === props.selectedIri
  );
}

</script>

<template>
  <div
    class="individual-list"
    role="region"
    :aria-label="t('individuals.listLabel')"
    :aria-busy="query.isFetching.value ? 'true' : 'false'"
  >
    <el-empty
      v-if="!hasType"
      :description="t('individuals.selectClassFirst')"
    />

    <div
      v-else-if="query.isError.value"
      class="individual-list__status"
      role="alert"
    >
      <p>{{ errorText || t("individuals.loadError") }}</p>
      <el-button
        type="primary"
        @click="query.refetch()"
      >
        {{ t("individuals.retry") }}
      </el-button>
    </div>

    <template v-else>
      <el-table
        v-loading="query.isPending.value"
        :data="rows"
        row-key="iri"
        highlight-current-row
        empty-text=""
        :row-class-name="rowClassName"
        :current-row-key="selectedIri ?? undefined"
        @row-click="onRowClick"
        @row-contextmenu="onRowContextMenu"
      >
        <el-table-column
          :label="t('individuals.displayName')"
          min-width="160"
        >
          <template #default="{ row }">
            <button
              type="button"
              class="individual-list__name"
              :class="{ 'is-deprecated': Boolean(row.deprecated) }"
              :title="String(row.iri)"
              :aria-label="
                row.deprecated
                  ? `${row.displayName} (${t('individuals.deprecated')})`
                  : String(row.displayName)
              "
              :aria-pressed="isSelectedRow(row) ? 'true' : 'false'"
              @click.stop="onRowClick(row)"
            >
              {{ row.displayName }}
            </button>
          </template>
        </el-table-column>
        <el-table-column
          :label="t('individuals.localName')"
          min-width="120"
        >
          <template #default="{ row }">
            {{ iriLocalName(String(row.iri)) }}
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            v-if="showEmpty"
            :description="t('individuals.empty')"
          />
        </template>
      </el-table>

      <el-pagination
        v-if="hasType && total > 0"
        class="individual-list__pager"
        background
        layout="total, sizes, prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :page-sizes="[...PAGE_SIZE_OPTIONS]"
        :current-page="currentPage"
        @current-change="onPageChange"
        @size-change="onSizeChange"
      />
    </template>
  </div>
</template>

<style scoped>
.individual-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  height: 100%;
  min-height: 0;
}

.individual-list__status {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 0.5rem;
}

.individual-list__pager {
  justify-content: flex-end;
  flex-shrink: 0;
}

.individual-list__name {
  display: inline;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.individual-list__name:focus-visible {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: 2px;
}

.individual-list__name.is-deprecated {
  color: var(--wp-color-text-secondary);
  text-decoration: line-through;
}

.individual-list :deep(.is-selected-row) > td.el-table__cell {
  background-color: var(--el-table-current-row-bg-color);
}
</style>
