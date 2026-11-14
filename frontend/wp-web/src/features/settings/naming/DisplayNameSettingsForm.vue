<script setup lang="ts">
import { ElMessage } from "element-plus";
import type { DisplayNameSetting, EntityNode } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import EntityPicker from "@/features/frames/EntityPicker.vue";
import {
  BUILTIN_ANNOTATION_PROPERTIES,
  RDFS_LABEL,
} from "@/features/frames/builtins";
import { rawIriNode } from "@/features/frames/frameModel";
import {
  useProjectPermissions,
  type PermissionState,
} from "@/features/permissions/useProjectPermissions";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useProjectSetting, useSaveProjectSetting } from "../api";

const SKOS_PREF_LABEL = "http://www.w3.org/2004/02/skos/core#prefLabel";
const DC_TITLE = "http://purl.org/dc/elements/1.1/title";
const DCTERMS_TITLE = "http://purl.org/dc/terms/title";

const BUILTIN_DEFAULT_SETTINGS: readonly DisplayNameSetting[] = Object.freeze([
  { annotationProperty: SKOS_PREF_LABEL, lang: "" },
  { annotationProperty: RDFS_LABEL.iri, lang: "" },
  { annotationProperty: DC_TITLE, lang: "" },
  { annotationProperty: DCTERMS_TITLE, lang: "" },
]);

type DisplayRow = {
  readonly id: string;
  property: EntityNode | null;
  lang: string;
};

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  "update:dirty": [value: boolean];
}>();

const { t } = useI18n();
const settingsQuery = useProjectSetting(() => props.projectId, "display-names");
const saveSettings = useSaveProjectSetting(
  () => props.projectId,
  "display-names",
);
const { permission } = useProjectPermissions(() => props.projectId);
const editPermissionRaw = permission("EDIT_PROJECT_SETTINGS");
const forbiddenByServer = ref(false);

watch(editPermissionRaw, (state) => {
  if (state === "allowed") {
    forbiddenByServer.value = false;
  }
});

const editPermission = computed((): PermissionState => {
  if (forbiddenByServer.value) {
    return "denied";
  }
  return editPermissionRaw.value;
});

const readOnly = computed(
  () =>
    editPermission.value === "denied" || editPermission.value === "pending",
);
const showReadOnlyHint = computed(() => editPermission.value === "denied");

const rows = ref<DisplayRow[]>([]);
const formError = ref("");
const seeded = ref(false);
const usingBuiltInDefaults = ref(false);
let rowSeq = 0;
let submitLock = false;
let baseline = "";

const pending = computed(() => saveSettings.isPending.value);
const loading = computed(
  () => settingsQuery.isPending.value && settingsQuery.data.value === undefined,
);
const loadFailed = computed(() => settingsQuery.isError.value);
const showEmptyHint = computed(
  () => usingBuiltInDefaults.value && rows.value.length === 0,
);

const isDirty = computed(() => serializeRows(rows.value) !== baseline);

watch(
  isDirty,
  (dirty) => {
    emit("update:dirty", dirty && !readOnly.value);
  },
  { immediate: true },
);

watch(
  () => settingsQuery.data.value,
  (settings) => {
    if (settings === undefined) {
      return;
    }
    if (seeded.value && isDirty.value && !readOnly.value) {
      return;
    }
    applySettings(settings);
    baseline = serializeRows(rows.value);
    formError.value = "";
    seeded.value = true;
  },
  { immediate: true },
);

function applySettings(settings: readonly DisplayNameSetting[]): void {
  if (settings.length === 0) {
    rows.value = [];
    usingBuiltInDefaults.value = true;
    return;
  }
  usingBuiltInDefaults.value = false;
  rows.value = settings.map((setting) =>
    createRow(resolveAnnotationProperty(setting.annotationProperty), setting.lang),
  );
}

function resolveAnnotationProperty(iri: string): EntityNode {
  const builtin = BUILTIN_ANNOTATION_PROPERTIES.find((node) => node.iri === iri);
  return builtin ?? rawIriNode(iri);
}

function startFromDefaults(): void {
  if (readOnly.value) {
    return;
  }
  rows.value = BUILTIN_DEFAULT_SETTINGS.map((setting) =>
    createRow(resolveAnnotationProperty(setting.annotationProperty), setting.lang),
  );
  usingBuiltInDefaults.value = false;
  formError.value = "";
}

function addRow(): void {
  if (readOnly.value) {
    return;
  }
  usingBuiltInDefaults.value = false;
  rows.value = [...rows.value, createRow(null, "")];
}

function removeRow(id: string): void {
  if (readOnly.value) {
    return;
  }
  rows.value = rows.value.filter((row) => row.id !== id);
}

function moveUp(index: number): void {
  if (readOnly.value || index <= 0) {
    return;
  }
  const next = [...rows.value];
  const previous = next[index - 1]!;
  next[index - 1] = next[index]!;
  next[index] = previous;
  rows.value = next;
}

function moveDown(index: number): void {
  if (readOnly.value || index >= rows.value.length - 1) {
    return;
  }
  const next = [...rows.value];
  const following = next[index + 1]!;
  next[index + 1] = next[index]!;
  next[index] = following;
  rows.value = next;
}

function setProperty(id: string, property: EntityNode | null): void {
  rows.value = rows.value.map((row) =>
    row.id === id ? { ...row, property } : row,
  );
  formError.value = "";
}

function setLang(id: string, lang: string | number): void {
  const text = typeof lang === "string" ? lang : String(lang);
  rows.value = rows.value.map((row) =>
    row.id === id ? { ...row, lang: text } : row,
  );
}

async function onSubmit(): Promise<void> {
  if (readOnly.value || submitLock || saveSettings.isPending.value) {
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
    const body = toSettings(rows.value);
    try {
      const saved = await saveSettings.mutateAsync(body);
      applySettings(saved);
      baseline = serializeRows(rows.value);
      ElMessage.success(t("settingsNaming.saveSuccessResync"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        forbiddenByServer.value = true;
        emit("update:dirty", false);
        return;
      }
      formError.value = problemMessage(error, t);
    }
  } finally {
    submitLock = false;
  }
}

function validateRows(current: readonly DisplayRow[]): string | undefined {
  for (const row of current) {
    if (row.property === null) {
      return t("settingsNaming.propertyRequired");
    }
  }
  return undefined;
}

function toSettings(current: readonly DisplayRow[]): DisplayNameSetting[] {
  return current.map((row) => ({
    annotationProperty: row.property!.iri,
    lang: row.lang.trim(),
  }));
}

function serializeRows(current: readonly DisplayRow[]): string {
  return JSON.stringify(
    current.map((row) => ({
      annotationProperty: row.property?.iri ?? "",
      lang: row.lang,
    })),
  );
}

function createRow(property: EntityNode | null, lang: string): DisplayRow {
  rowSeq += 1;
  return { id: `display-${rowSeq}`, property, lang };
}
</script>

<template>
  <div class="display-name-settings">
    <p
      v-if="loading"
      role="status"
    >
      {{ t("settingsNaming.loading") }}
    </p>
    <div
      v-else-if="loadFailed"
      role="alert"
    >
      <p>{{ t("settingsNaming.loadError") }}</p>
      <el-button
        type="primary"
        @click="settingsQuery.refetch()"
      >
        {{ t("settingsNaming.retry") }}
      </el-button>
    </div>
    <form
      v-else
      class="display-name-settings__form"
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
        v-else-if="showReadOnlyHint"
        :title="t('settings.readOnlyDenied')"
        type="info"
        show-icon
        :closable="false"
        role="status"
      />

      <p class="display-name-settings__hint">
        {{ t("settingsNaming.displayNamesHint") }}
      </p>

      <div
        v-if="showEmptyHint"
        class="display-name-settings__empty"
        role="status"
      >
        <p>{{ t("settingsNaming.displayNamesEmptyHint") }}</p>
        <el-button
          data-testid="start-from-defaults"
          :disabled="readOnly || pending"
          @click="startFromDefaults"
        >
          {{ t("settingsNaming.startFromDefaults") }}
        </el-button>
      </div>

      <div
        v-for="(row, index) in rows"
        :key="row.id"
        class="display-name-settings__row"
      >
        <div class="display-name-settings__property">
          <EntityPicker
            :project-id="projectId"
            :types="['annotation-property']"
            :model-value="row.property"
            :builtins="BUILTIN_ANNOTATION_PROPERTIES"
            :allow-raw-iri="true"
            :disabled="readOnly || pending"
            v-bind="{
              ariaLabel: `${t('settingsNaming.annotationProperty')} (${index + 1})`,
            }"
            @update:model-value="setProperty(row.id, $event)"
          />
        </div>
        <label class="display-name-settings__lang">
          <span class="visually-hidden">
            {{ t("settingsNaming.lang") }} ({{ index + 1 }})
          </span>
          <el-input
            :model-value="row.lang"
            name="lang"
            :disabled="readOnly || pending"
            :placeholder="t('settingsNaming.langEmpty')"
            @update:model-value="setLang(row.id, $event)"
          />
        </label>
        <div class="display-name-settings__row-actions">
          <el-button
            text
            :data-testid="`move-up-${index}`"
            :disabled="readOnly || pending || index === 0"
            :aria-label="t('settingsNaming.moveUpNamed', { n: index + 1 })"
            @click="moveUp(index)"
          >
            {{ t("settingsNaming.moveUp") }}
          </el-button>
          <el-button
            text
            :data-testid="`move-down-${index}`"
            :disabled="readOnly || pending || index === rows.length - 1"
            :aria-label="t('settingsNaming.moveDownNamed', { n: index + 1 })"
            @click="moveDown(index)"
          >
            {{ t("settingsNaming.moveDown") }}
          </el-button>
          <el-button
            text
            type="danger"
            :disabled="readOnly || pending"
            :aria-label="t('settingsNaming.removeRowNamed', { n: index + 1 })"
            @click="removeRow(row.id)"
          >
            {{ t("settingsNaming.removeRow") }}
          </el-button>
        </div>
      </div>

      <div
        v-if="!readOnly"
        class="display-name-settings__actions"
      >
        <el-button
          data-testid="add-row"
          :disabled="pending"
          @click="addRow"
        >
          {{ t("settingsNaming.addRow") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :disabled="pending || !isDirty"
          :loading="pending"
        >
          {{
            pending ? t("settingsNaming.saving") : t("settingsNaming.save")
          }}
        </el-button>
      </div>
    </form>
  </div>
</template>

<style scoped>
.display-name-settings__form {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-3);
  max-width: 48rem;
}

.display-name-settings__hint,
.display-name-settings__empty p {
  margin: 0;
  color: var(--wp-color-text-secondary);
  font-size: var(--wp-font-size-sm);
}

.display-name-settings__empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--wp-space-2);
}

.display-name-settings__row {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(6rem, 8rem) auto;
  gap: var(--wp-space-2);
  align-items: center;
}

.display-name-settings__property,
.display-name-settings__lang {
  min-width: 0;
}

.display-name-settings__row-actions {
  display: flex;
  flex-wrap: wrap;
  gap: var(--wp-space-2);
}

.display-name-settings__row-actions :deep(.el-button) {
  min-height: 2rem;
  min-width: 2rem;
}

.display-name-settings__actions {
  display: flex;
  gap: var(--wp-space-2);
  flex-wrap: wrap;
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
