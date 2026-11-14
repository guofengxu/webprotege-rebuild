<script setup lang="ts">
import { useQueryClient } from "@tanstack/vue-query";
import type {
  AnnotationValue,
  EntityNode,
  ObjectPropertyFrame,
} from "@wp/api-types";
import { ElMessage } from "element-plus";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useApiClient } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queryKeys";
import AnnotationsEditor from "../AnnotationsEditor.vue";
import EntityRefList from "../EntityRefList.vue";
import FrameHeader from "../FrameHeader.vue";
import FrameSaveBar from "../FrameSaveBar.vue";
import { normalizeObjectPropertyFrame } from "../frameModel";
import OtherAxiomsView from "../OtherAxiomsView.vue";
import { useFrameEditor } from "../useFrameEditor";
import { useUnsavedGuard } from "../useUnsavedGuard";
import { loadObjectPropertyFrame, saveObjectPropertyFrame } from "./api";

type Characteristic = NonNullable<ObjectPropertyFrame["characteristics"]>[number];

const CHARACTERISTICS: readonly Characteristic[] = [
  "FUNCTIONAL",
  "INVERSE_FUNCTIONAL",
  "TRANSITIVE",
  "SYMMETRIC",
  "ASYMMETRIC",
  "REFLEXIVE",
  "IRREFLEXIVE",
];

const props = defineProps<{
  projectId: string;
  iri: string;
}>();

const { t } = useI18n();
const client = useApiClient();
const queryClient = useQueryClient();

const frameKey = computed(() =>
  queryKeys.frame(props.projectId, "OBJECT_PROPERTY", props.iri),
);

const editor = useFrameEditor<ObjectPropertyFrame>({
  projectId: () => props.projectId,
  queryKey: frameKey,
  load: () => loadObjectPropertyFrame(client, props.projectId, props.iri),
  save: async ({ from, to }) => {
    const result = await saveObjectPropertyFrame(client, props.projectId, {
      from,
      to,
    });
    return { frame: result.frame, revision: result.revision };
  },
  normalize: normalizeObjectPropertyFrame,
  onSaved: async (result) => {
    ElMessage.success(
      t("framesProperty.saveSuccess", { revision: String(result.revision) }),
    );
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: queryKeys.hierarchy(props.projectId, "object-property"),
      }),
      queryClient.invalidateQueries({
        queryKey: queryKeys.searchAll(props.projectId),
      }),
    ]);
  },
});

useUnsavedGuard(editor.isDirty, editor.readOnly);

const {
  draft,
  isDirty,
  isSaving,
  conflict,
  serverChanged,
  readOnly,
  editPermission,
  save,
  reset,
  reloadFromServer,
} = editor;

const openSections = ref([
  "annotations",
  "parents",
  "domains",
  "ranges",
  "characteristics",
  "inverses",
  "otherAxioms",
]);

function setAnnotations(value: AnnotationValue[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, annotations: value };
}

function setParents(value: EntityNode[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, parents: value };
}

function setDomains(value: EntityNode[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, domains: value };
}

function setRanges(value: EntityNode[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, ranges: value };
}

function setCharacteristics(value: readonly (string | number)[]): void {
  if (draft.value === undefined) {
    return;
  }
  const allowed = new Set<string>(CHARACTERISTICS);
  const next = value.filter((entry): entry is Characteristic =>
    allowed.has(String(entry)),
  );
  draft.value = { ...draft.value, characteristics: next };
}

function setInverses(value: EntityNode[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, inverses: value };
}
</script>

<template>
  <section
    class="property-frame-editor"
    data-testid="object-property-frame-editor"
    :aria-busy="draft === undefined ? 'true' : undefined"
  >
    <p
      v-if="draft === undefined"
      class="property-frame-editor__loading"
    >
      {{ t("framesProperty.loading") }}
    </p>
    <template v-else>
      <FrameHeader
        v-if="draft.subject !== undefined"
        :subject="draft.subject"
      />

      <div class="property-frame-editor__body">
        <el-collapse v-model="openSections">
          <el-collapse-item
            :title="t('framesProperty.annotations')"
            name="annotations"
          >
            <AnnotationsEditor
              :project-id="projectId"
              :model-value="draft.annotations ?? []"
              :disabled="readOnly"
              @update:model-value="setAnnotations"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.parents')"
            name="parents"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['object-property']"
              :model-value="draft.parents ?? []"
              :exclude-iris="[iri]"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesProperty.parents') }"
              @update:model-value="setParents"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.domains')"
            name="domains"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['class']"
              :model-value="draft.domains ?? []"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesProperty.domains') }"
              @update:model-value="setDomains"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.ranges')"
            name="ranges"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['class']"
              :model-value="draft.ranges ?? []"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesProperty.ranges') }"
              @update:model-value="setRanges"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.characteristics')"
            name="characteristics"
          >
            <el-checkbox-group
              :model-value="draft.characteristics ?? []"
              :disabled="readOnly"
              :aria-label="t('framesProperty.characteristics')"
              @update:model-value="setCharacteristics"
            >
              <el-checkbox
                v-for="item in CHARACTERISTICS"
                :key="item"
                :value="item"
                :label="item"
                :data-testid="`characteristic-${item}`"
              >
                {{ t(`framesProperty.characteristic.${item}`) }}
              </el-checkbox>
            </el-checkbox-group>
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.inverses')"
            name="inverses"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['object-property']"
              :model-value="draft.inverses ?? []"
              :exclude-iris="[iri]"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesProperty.inverses') }"
              @update:model-value="setInverses"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.otherAxioms')"
            name="otherAxioms"
          >
            <OtherAxiomsView :axioms="draft.otherAxioms ?? []" />
          </el-collapse-item>
        </el-collapse>
      </div>

      <FrameSaveBar
        :is-dirty="isDirty"
        :is-saving="isSaving"
        :conflict="conflict"
        :server-changed="serverChanged"
        :read-only="readOnly"
        :edit-permission="editPermission"
        @save="save"
        @reset="reset"
        @reload="reloadFromServer"
      />
    </template>
  </section>
</template>

<style scoped>
.property-frame-editor {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-3, 0.75rem);
  min-height: 0;
}

.property-frame-editor__loading {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.property-frame-editor__body {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.property-frame-editor :deep(.el-checkbox-group) {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--wp-space-1, 0.25rem);
}

.property-frame-editor :deep(.el-button:focus-visible),
.property-frame-editor :deep(.el-checkbox__input.is-focus .el-checkbox__inner),
.property-frame-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
