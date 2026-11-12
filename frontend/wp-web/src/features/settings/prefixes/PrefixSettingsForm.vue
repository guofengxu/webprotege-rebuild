<script setup lang="ts">
import { ElMessage } from "element-plus";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import type { PrefixSettings } from "@wp/api-types";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useProjectSetting, useSaveProjectSetting } from "../api";

type PrefixRow = {
  readonly id: string;
  prefix: string;
  iri: string;
};

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  "update:dirty": [value: boolean];
}>();

const { t } = useI18n();
const prefixesQuery = useProjectSetting(() => props.projectId, "prefixes");
const savePrefixes = useSaveProjectSetting(() => props.projectId, "prefixes");

const rows = ref<PrefixRow[]>([]);
const formError = ref("");
const readOnly = ref(false);
const seeded = ref(false);
let rowSeq = 0;
let submitLock = false;
let baseline = "";

const pending = computed(() => savePrefixes.isPending.value);
const loading = computed(
  () => prefixesQuery.isPending.value && prefixesQuery.data.value === undefined,
);
const loadFailed = computed(() => prefixesQuery.isError.value);

const isDirty = computed(() => serializeRows(rows.value) !== baseline);

watch(
  isDirty,
  (dirty) => {
    emit("update:dirty", dirty && !readOnly.value);
  },
  { immediate: true },
);

watch(
  () => prefixesQuery.data.value,
  (settings) => {
    if (settings === undefined) {
      return;
    }
    if (seeded.value && isDirty.value && !readOnly.value) {
      return;
    }
    rows.value = toRows(settings);
    baseline = serializeRows(rows.value);
    formError.value = "";
    seeded.value = true;
  },
  { immediate: true },
);

function addRow(): void {
  if (readOnly.value) {
    return;
  }
  rows.value = [...rows.value, createRow("", "")];
}

function removeRow(id: string): void {
  if (readOnly.value) {
    return;
  }
  rows.value = rows.value.filter((row) => row.id !== id);
}

function updatePrefix(id: string, prefix: string | number): void {
  const text = typeof prefix === "string" ? prefix : String(prefix);
  rows.value = rows.value.map((row) =>
    row.id === id ? { ...row, prefix: text } : row,
  );
  formError.value = "";
}

function updateIri(id: string, iri: string | number): void {
  const text = typeof iri === "string" ? iri : String(iri);
  rows.value = rows.value.map((row) =>
    row.id === id ? { ...row, iri: text } : row,
  );
  formError.value = "";
}

function rowLabel(row: PrefixRow, index: number): string {
  const prefix = row.prefix.trim();
  return prefix.length > 0 ? prefix : t("settings.newPrefixRow", { n: index + 1 });
}

async function onSubmit(): Promise<void> {
  if (readOnly.value || submitLock || savePrefixes.isPending.value) {
    return;
  }
  submitLock = true;
  formError.value = "";
  try {
    const validationError = validateRows(rows.value);
    if (validationError !== undefined) {
      formError.value = validationError;
      return;
    }
    const body = toPrefixSettings(rows.value);
    try {
      const saved = await savePrefixes.mutateAsync(body);
      rows.value = toRows(saved);
      baseline = serializeRows(rows.value);
      ElMessage.success(t("settings.saveSuccess"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        readOnly.value = true;
        formError.value = t("settings.forbidden");
        emit("update:dirty", false);
        return;
      }
      formError.value = problemMessage(error, t);
    }
  } finally {
    submitLock = false;
  }
}

function validateRows(current: readonly PrefixRow[]): string | undefined {
  const seen = new Set<string>();
  for (const row of current) {
    const prefix = row.prefix.trim();
    const iri = row.iri.trim();
    if (prefix.length === 0) {
      return t("settings.prefixRequired");
    }
    if (seen.has(prefix)) {
      return t("settings.prefixDuplicate");
    }
    seen.add(prefix);
    if (!isAbsoluteIri(iri)) {
      return t("settings.iriInvalid");
    }
  }
  return undefined;
}

function toRows(settings: PrefixSettings): PrefixRow[] {
  return Object.entries(settings).map(([prefix, iri]) =>
    createRow(prefix, iri),
  );
}

function toPrefixSettings(current: readonly PrefixRow[]): PrefixSettings {
  const result: Record<string, string> = {};
  for (const row of current) {
    result[row.prefix.trim()] = row.iri.trim();
  }
  return result;
}

function serializeRows(current: readonly PrefixRow[]): string {
  return JSON.stringify(
    current.map((row) => ({
      prefix: row.prefix,
      iri: row.iri,
    })),
  );
}

function createRow(prefix: string, iri: string): PrefixRow {
  rowSeq += 1;
  return { id: `row-${rowSeq}`, prefix, iri };
}

function isAbsoluteIri(value: string): boolean {
  if (value.length === 0 || /\s/.test(value)) {
    return false;
  }
  try {
    const url = new URL(value);
    return url.protocol.length > 0;
  } catch {
    return false;
  }
}
</script>

<template>
  <div class="prefix-settings">
    <p
      v-if="loading"
      role="status"
    >
      {{ t("settings.loading") }}
    </p>
    <div
      v-else-if="loadFailed"
      role="alert"
    >
      <p>{{ t("settings.loadError") }}</p>
      <el-button
        type="primary"
        @click="prefixesQuery.refetch()"
      >
        {{ t("settings.retry") }}
      </el-button>
    </div>
    <form
      v-else
      class="prefix-settings__form"
      @submit.prevent="onSubmit"
    >
      <el-alert
        v-if="formError"
        :title="formError"
        type="error"
        show-icon
        :closable="false"
      />
      <el-alert
        v-else-if="readOnly"
        :title="t('settings.forbidden')"
        type="warning"
        show-icon
        :closable="false"
      />

      <p
        v-if="rows.length === 0"
        class="prefix-settings__empty"
      >
        {{ t("settings.prefixesEmpty") }}
      </p>

      <div
        v-for="(row, index) in rows"
        :key="row.id"
        class="prefix-settings__row"
      >
        <label class="prefix-settings__cell">
          <span class="visually-hidden">
            {{ t("settings.prefixName") }} ({{ rowLabel(row, index) }})
          </span>
          <el-input
            :model-value="row.prefix"
            name="prefix"
            :disabled="readOnly || pending"
            :placeholder="t('settings.prefixName')"
            @update:model-value="updatePrefix(row.id, $event)"
          />
        </label>
        <label class="prefix-settings__cell prefix-settings__cell--iri">
          <span class="visually-hidden">
            {{ t("settings.prefixIri") }} ({{ rowLabel(row, index) }})
          </span>
          <el-input
            :model-value="row.iri"
            name="iri"
            :disabled="readOnly || pending"
            :placeholder="t('settings.prefixIri')"
            @update:model-value="updateIri(row.id, $event)"
          />
        </label>
        <el-button
          text
          type="danger"
          :disabled="readOnly || pending"
          :aria-label="t('settings.removePrefixNamed', { prefix: rowLabel(row, index) })"
          @click="removeRow(row.id)"
        >
          {{ t("settings.removePrefix") }}
        </el-button>
      </div>

      <div class="prefix-settings__actions">
        <el-button
          :disabled="readOnly || pending"
          @click="addRow"
        >
          {{ t("settings.addPrefix") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :disabled="readOnly || pending || !isDirty"
          :loading="pending"
        >
          {{ pending ? t("settings.saving") : t("settings.save") }}
        </el-button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.prefix-settings__form {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-3);
  max-width: 48rem;
}

.prefix-settings__row {
  display: grid;
  grid-template-columns: minmax(6rem, 10rem) minmax(0, 1fr) auto;
  gap: var(--wp-space-2);
  align-items: center;
}

.prefix-settings__cell {
  display: block;
  min-width: 0;
}

.prefix-settings__actions {
  display: flex;
  gap: var(--wp-space-2);
  flex-wrap: wrap;
}

.prefix-settings__empty {
  margin: 0;
  color: var(--wp-color-text-secondary);
  font-size: var(--wp-font-size-sm);
}

.visually-hidden {
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
