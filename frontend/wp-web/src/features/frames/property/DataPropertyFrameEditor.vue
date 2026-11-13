<script setup lang="ts">
import { useQueryClient } from "@tanstack/vue-query";
import type {
  AnnotationValue,
  DataPropertyFrame,
  EntityNode,
} from "@wp/api-types";
import { ElMessage } from "element-plus";
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useApiClient } from "@/shared/api/client";
import { queryKeys } from "@/shared/api/queryKeys";
import AnnotationsEditor from "../AnnotationsEditor.vue";
import { BUILTIN_DATATYPES } from "../builtins";
import EntityRefList from "../EntityRefList.vue";
import FrameHeader from "../FrameHeader.vue";
import FrameSaveBar from "../FrameSaveBar.vue";
import { normalizeDataPropertyFrame } from "../frameModel";
import OtherAxiomsView from "../OtherAxiomsView.vue";
import { useFrameEditor } from "../useFrameEditor";
import { useUnsavedGuard } from "../useUnsavedGuard";
import { loadDataPropertyFrame, saveDataPropertyFrame } from "./api";

const props = defineProps<{
  projectId: string;
  iri: string;
}>();

const { t } = useI18n();
const client = useApiClient();
const queryClient = useQueryClient();

const frameKey = computed(() =>
  queryKeys.frame(props.projectId, "DATA_PROPERTY", props.iri),
);

const editor = useFrameEditor<DataPropertyFrame>({
  queryKey: frameKey,
  load: () => loadDataPropertyFrame(client, props.projectId, props.iri),
  save: async ({ from, to }) => {
    const result = await saveDataPropertyFrame(client, props.projectId, {
      from,
      to,
    });
    return { frame: result.frame, revision: result.revision };
  },
  normalize: normalizeDataPropertyFrame,
  onSaved: async (result) => {
    ElMessage.success(
      t("framesProperty.saveSuccess", { revision: String(result.revision) }),
    );
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: queryKeys.hierarchy(props.projectId, "data-property"),
      }),
      queryClient.invalidateQueries({
        queryKey: queryKeys.searchAll(props.projectId),
      }),
    ]);
  },
});

useUnsavedGuard(editor.isDirty);

const {
  draft,
  isDirty,
  isSaving,
  conflict,
  serverChanged,
  readOnly,
  save,
  reset,
  reloadFromServer,
} = editor;

const openSections = ref([
  "annotations",
  "parents",
  "domains",
  "ranges",
  "functional",
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

function setFunctional(value: boolean | string | number): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, functional: value === true };
}
</script>

<template>
  <section
    class="property-frame-editor"
    data-testid="data-property-frame-editor"
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
              :types="['data-property']"
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
              :types="['datatype']"
              :builtins="BUILTIN_DATATYPES"
              :model-value="draft.ranges ?? []"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesProperty.ranges') }"
              @update:model-value="setRanges"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesProperty.functional')"
            name="functional"
          >
            <div class="property-frame-editor__functional">
              <el-switch
                data-testid="data-property-functional"
                :model-value="draft.functional === true"
                :disabled="readOnly"
                :aria-label="t('framesProperty.functional')"
                aria-describedby="data-property-functional-hint"
                @update:model-value="setFunctional"
              />
              <span
                id="data-property-functional-hint"
                class="property-frame-editor__hint"
              >
                {{ t("framesProperty.functionalHint") }}
              </span>
            </div>
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

.property-frame-editor__functional {
  display: flex;
  align-items: center;
  gap: var(--wp-space-2, 0.5rem);
}

.property-frame-editor__hint {
  color: var(--el-text-color-secondary);
  font-size: var(--el-font-size-small);
}

.property-frame-editor :deep(.el-button:focus-visible),
.property-frame-editor :deep(.el-switch__input:focus-visible + .el-switch__core),
.property-frame-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
