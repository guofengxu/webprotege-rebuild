<script setup lang="ts">
import { ElMessage } from "element-plus";
import type {
  EntityCrudKitSettings,
  EntityNode,
  NewEntitySettings,
} from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import EntityPicker from "@/features/frames/EntityPicker.vue";
import {
  BUILTIN_ANNOTATION_PROPERTIES,
} from "@/features/frames/builtins";
import { rawIriNode } from "@/features/frames/frameModel";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useProjectSetting, useSaveProjectSetting } from "../api";

type KitType = EntityCrudKitSettings["type"];
type WhiteSpace = Extract<
  EntityCrudKitSettings,
  { type: "SUPPLIED_NAME" }
>["whiteSpace"];

type RangeRow = {
  readonly id: string;
  userId: string;
  start: string;
  end: string;
};

const props = defineProps<{
  projectId: string;
}>();

const emit = defineEmits<{
  "update:dirty": [value: boolean];
}>();

const { t } = useI18n();
const settingsQuery = useProjectSetting(() => props.projectId, "new-entity");
const saveSettings = useSaveProjectSetting(() => props.projectId, "new-entity");

const iriPrefix = ref("");
const kitType = ref<KitType>("UUID");
const whiteSpace = ref<WhiteSpace>("CAMEL_CASE");
const totalDigits = ref("7");
const ranges = ref<RangeRow[]>([]);
const labelProperty = ref<EntityNode | null>(null);
const defaultLang = ref("");
const targetOntology = ref("");
const formError = ref("");
const readOnly = ref(false);
const seeded = ref(false);
let rowSeq = 0;
let submitLock = false;
let baseline = "";

const pending = computed(() => saveSettings.isPending.value);
const loading = computed(
  () => settingsQuery.isPending.value && settingsQuery.data.value === undefined,
);
const loadFailed = computed(() => settingsQuery.isError.value);

const isDirty = computed(() => serializeForm() !== baseline);

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
    baseline = serializeForm();
    formError.value = "";
    seeded.value = true;
  },
  { immediate: true },
);

function applySettings(settings: NewEntitySettings): void {
  iriPrefix.value = settings.iriPrefix;
  defaultLang.value = settings.defaultLang;
  targetOntology.value = settings.targetOntology;
  labelProperty.value = resolveAnnotationProperty(settings.labelProperty);
  const kit = settings.kit;
  kitType.value = kit.type;
  if (kit.type === "SUPPLIED_NAME") {
    whiteSpace.value = kit.whiteSpace;
  } else {
    whiteSpace.value = "CAMEL_CASE";
  }
  if (kit.type === "OBO") {
    totalDigits.value = String(kit.totalDigits);
    ranges.value = kit.userRanges.map((range) =>
      createRange(range.userId, String(range.start), String(range.end)),
    );
  } else {
    totalDigits.value = "7";
    ranges.value = [];
  }
}

function resolveAnnotationProperty(iri: string): EntityNode {
  const builtin = BUILTIN_ANNOTATION_PROPERTIES.find((node) => node.iri === iri);
  return builtin ?? rawIriNode(iri);
}

function addRange(): void {
  if (readOnly.value) {
    return;
  }
  ranges.value = [...ranges.value, createRange("", "", "")];
}

function removeRange(id: string): void {
  if (readOnly.value) {
    return;
  }
  ranges.value = ranges.value.filter((row) => row.id !== id);
}

function updateRange(
  id: string,
  field: "userId" | "start" | "end",
  value: string | number,
): void {
  const text = typeof value === "string" ? value : String(value);
  ranges.value = ranges.value.map((row) =>
    row.id === id ? { ...row, [field]: text } : row,
  );
  formError.value = "";
}

function rangeLabel(row: RangeRow, index: number): string {
  const userId = row.userId.trim();
  return userId.length > 0
    ? userId
    : t("settingsNaming.newRangeRow", { n: index + 1 });
}

async function onSubmit(): Promise<void> {
  if (readOnly.value || submitLock || saveSettings.isPending.value) {
    return;
  }
  submitLock = true;
  formError.value = "";
  try {
    const validationError = validateForm();
    if (validationError !== undefined) {
      formError.value = validationError;
      return;
    }
    const body = toBody();
    try {
      const saved = await saveSettings.mutateAsync(body);
      applySettings(saved);
      baseline = serializeForm();
      ElMessage.success(t("settingsNaming.saveSuccess"));
    } catch (error) {
      if (error instanceof ApiError && error.status === 403) {
        readOnly.value = true;
        formError.value = t("settingsNaming.forbidden");
        emit("update:dirty", false);
        return;
      }
      formError.value = problemMessage(error, t);
    }
  } finally {
    submitLock = false;
  }
}

function validateForm(): string | undefined {
  if (!isAbsoluteIri(iriPrefix.value.trim())) {
    return t("settingsNaming.iriPrefixInvalid");
  }
  if (labelProperty.value === null) {
    return t("settingsNaming.labelPropertyRequired");
  }
  if (kitType.value !== "OBO") {
    return undefined;
  }
  const digits = Number(totalDigits.value);
  if (!Number.isInteger(digits) || digits < 1) {
    return t("settingsNaming.totalDigitsInvalid");
  }
  const parsed: Array<{ start: number; end: number }> = [];
  for (const row of ranges.value) {
    if (row.start.trim().length === 0 || row.end.trim().length === 0) {
      return t("settingsNaming.rangeInvalid");
    }
    const start = Number(row.start);
    const end = Number(row.end);
    if (
      !Number.isInteger(start) ||
      !Number.isInteger(end) ||
      start > end
    ) {
      return t("settingsNaming.rangeInvalid");
    }
    parsed.push({ start, end });
  }
  for (let i = 0; i < parsed.length; i += 1) {
    for (let j = i + 1; j < parsed.length; j += 1) {
      const left = parsed[i]!;
      const right = parsed[j]!;
      if (left.start <= right.end && right.start <= left.end) {
        return t("settingsNaming.rangesOverlap");
      }
    }
  }
  return undefined;
}

function toBody(): NewEntitySettings {
  return {
    iriPrefix: iriPrefix.value.trim(),
    kit: toKit(),
    labelProperty: labelProperty.value!.iri,
    defaultLang: defaultLang.value.trim(),
    targetOntology: targetOntology.value.trim(),
  };
}

function toKit(): EntityCrudKitSettings {
  if (kitType.value === "SUPPLIED_NAME") {
    return { type: "SUPPLIED_NAME", whiteSpace: whiteSpace.value };
  }
  if (kitType.value === "OBO") {
    return {
      type: "OBO",
      totalDigits: Number(totalDigits.value),
      userRanges: ranges.value.map((row) => ({
        userId: row.userId.trim(),
        start: Number(row.start),
        end: Number(row.end),
      })),
    };
  }
  return { type: "UUID" };
}

function serializeForm(): string {
  return JSON.stringify({
    iriPrefix: iriPrefix.value,
    kit: toKit(),
    labelProperty: labelProperty.value?.iri ?? "",
    defaultLang: defaultLang.value,
    targetOntology: targetOntology.value,
  });
}

function createRange(userId: string, start: string, end: string): RangeRow {
  rowSeq += 1;
  return { id: `range-${rowSeq}`, userId, start, end };
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
  <div class="new-entity-settings">
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
      class="new-entity-settings__form"
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
        :title="t('settingsNaming.forbidden')"
        type="warning"
        show-icon
        :closable="false"
      />

      <label class="new-entity-settings__field">
        <span>{{ t("settingsNaming.iriPrefix") }}</span>
        <el-input
          v-model="iriPrefix"
          name="iriPrefix"
          :disabled="readOnly || pending"
        />
      </label>

      <fieldset class="new-entity-settings__fieldset">
        <legend>{{ t("settingsNaming.kitType") }}</legend>
        <label class="new-entity-settings__radio">
          <input
            v-model="kitType"
            type="radio"
            name="kitType"
            value="UUID"
            :disabled="readOnly || pending"
          >
          {{ t("settingsNaming.kitUuid") }}
        </label>
        <label class="new-entity-settings__radio">
          <input
            v-model="kitType"
            type="radio"
            name="kitType"
            value="SUPPLIED_NAME"
            :disabled="readOnly || pending"
          >
          {{ t("settingsNaming.kitSuppliedName") }}
        </label>
        <label class="new-entity-settings__radio">
          <input
            v-model="kitType"
            type="radio"
            name="kitType"
            value="OBO"
            :disabled="readOnly || pending"
          >
          {{ t("settingsNaming.kitObo") }}
        </label>
      </fieldset>

      <fieldset
        v-if="kitType === 'SUPPLIED_NAME'"
        class="new-entity-settings__fieldset"
      >
        <legend>{{ t("settingsNaming.whiteSpace") }}</legend>
        <label class="new-entity-settings__radio">
          <input
            v-model="whiteSpace"
            type="radio"
            name="whiteSpace"
            value="CAMEL_CASE"
            :disabled="readOnly || pending"
          >
          {{ t("settingsNaming.whiteSpaceCamelCase") }}
        </label>
        <label class="new-entity-settings__radio">
          <input
            v-model="whiteSpace"
            type="radio"
            name="whiteSpace"
            value="UNDERSCORES"
            :disabled="readOnly || pending"
          >
          {{ t("settingsNaming.whiteSpaceUnderscores") }}
        </label>
      </fieldset>

      <template v-if="kitType === 'OBO'">
        <label class="new-entity-settings__field">
          <span>{{ t("settingsNaming.totalDigits") }}</span>
          <el-input
            v-model="totalDigits"
            name="totalDigits"
            type="number"
            :min="1"
            :disabled="readOnly || pending"
          />
        </label>

        <fieldset class="new-entity-settings__fieldset new-entity-settings__ranges">
          <legend>{{ t("settingsNaming.userRanges") }}</legend>
          <div
            v-for="(row, index) in ranges"
            :key="row.id"
            class="new-entity-settings__range-row"
          >
            <label class="new-entity-settings__cell">
              <span class="visually-hidden">
                {{ t("settingsNaming.userId") }} ({{ rangeLabel(row, index) }})
              </span>
              <el-input
                :model-value="row.userId"
                name="userId"
                :disabled="readOnly || pending"
                :placeholder="t('settingsNaming.userId')"
                @update:model-value="updateRange(row.id, 'userId', $event)"
              />
            </label>
            <label class="new-entity-settings__cell">
              <span class="visually-hidden">
                {{ t("settingsNaming.rangeStart") }} ({{ rangeLabel(row, index) }})
              </span>
              <el-input
                :model-value="row.start"
                name="rangeStart"
                type="number"
                :disabled="readOnly || pending"
                :placeholder="t('settingsNaming.rangeStart')"
                @update:model-value="updateRange(row.id, 'start', $event)"
              />
            </label>
            <label class="new-entity-settings__cell">
              <span class="visually-hidden">
                {{ t("settingsNaming.rangeEnd") }} ({{ rangeLabel(row, index) }})
              </span>
              <el-input
                :model-value="row.end"
                name="rangeEnd"
                type="number"
                :disabled="readOnly || pending"
                :placeholder="t('settingsNaming.rangeEnd')"
                @update:model-value="updateRange(row.id, 'end', $event)"
              />
            </label>
            <el-button
              text
              type="danger"
              :disabled="readOnly || pending"
              :aria-label="
                t('settingsNaming.removeRangeNamed', {
                  userId: rangeLabel(row, index),
                })
              "
              @click="removeRange(row.id)"
            >
              {{ t("settingsNaming.removeRange") }}
            </el-button>
          </div>
          <el-button
            data-testid="add-range"
            :disabled="readOnly || pending"
            @click="addRange"
          >
            {{ t("settingsNaming.addRange") }}
          </el-button>
        </fieldset>
      </template>

      <div class="new-entity-settings__field">
        <span>{{ t("settingsNaming.labelProperty") }}</span>
        <EntityPicker
          :project-id="projectId"
          :types="['annotation-property']"
          :model-value="labelProperty"
          :builtins="BUILTIN_ANNOTATION_PROPERTIES"
          :allow-raw-iri="true"
          :disabled="readOnly || pending"
          v-bind="{ ariaLabel: t('settingsNaming.labelProperty') }"
          @update:model-value="labelProperty = $event"
        />
      </div>

      <label class="new-entity-settings__field">
        <span>{{ t("settingsNaming.defaultLang") }}</span>
        <el-input
          v-model="defaultLang"
          name="defaultLang"
          :disabled="readOnly || pending"
        />
      </label>

      <label class="new-entity-settings__field">
        <span>{{ t("settingsNaming.targetOntology") }}</span>
        <el-input
          v-model="targetOntology"
          name="targetOntology"
          :disabled="readOnly || pending"
        />
      </label>

      <div class="new-entity-settings__actions">
        <el-button
          type="primary"
          native-type="submit"
          :disabled="readOnly || pending || !isDirty"
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
.new-entity-settings__form {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-4);
  max-width: 42rem;
}

.new-entity-settings__field {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2);
  font-size: var(--wp-font-size-sm);
  color: var(--wp-color-text-primary);
}

.new-entity-settings__fieldset {
  margin: 0;
  padding: 0;
  border: 0;
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2);
}

.new-entity-settings__fieldset legend {
  padding: 0;
  margin-bottom: var(--wp-space-2);
  font-size: var(--wp-font-size-sm);
}

.new-entity-settings__radio {
  display: flex;
  align-items: center;
  gap: var(--wp-space-2);
  font-size: var(--wp-font-size-sm);
}

.new-entity-settings__ranges {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2);
}

.new-entity-settings__range-row {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.7fr) minmax(0, 0.7fr) auto;
  gap: var(--wp-space-2);
  align-items: center;
}

.new-entity-settings__cell {
  display: block;
  min-width: 0;
}

.new-entity-settings__actions {
  display: flex;
  justify-content: flex-start;
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
