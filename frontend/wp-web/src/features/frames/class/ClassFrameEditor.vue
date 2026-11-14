<script setup lang="ts">
import { useQueryClient } from "@tanstack/vue-query";
import type {
  AnnotationValue,
  ClassFrame,
  EntityNode,
  PropertyValue,
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
import { normalizeClassFrame } from "../frameModel";
import OtherAxiomsView from "../OtherAxiomsView.vue";
import { useFrameEditor } from "../useFrameEditor";
import { useUnsavedGuard } from "../useUnsavedGuard";
import { loadClassFrame, saveClassFrame } from "./api";
import PropertyValuesEditor from "./PropertyValuesEditor.vue";

const props = defineProps<{
  projectId: string;
  iri: string;
}>();

const { t } = useI18n();
const client = useApiClient();
const queryClient = useQueryClient();

const frameKey = computed(() =>
  queryKeys.frame(props.projectId, "CLASS", props.iri),
);

const editor = useFrameEditor<ClassFrame>({
  projectId: () => props.projectId,
  queryKey: frameKey,
  load: () => loadClassFrame(client, props.projectId, props.iri),
  save: async ({ from, to }) => {
    const result = await saveClassFrame(client, props.projectId, { from, to });
    return { frame: result.frame, revision: result.revision };
  },
  normalize: normalizeClassFrame,
  onSaved: async (result) => {
    ElMessage.success(
      t("framesClass.saveSuccess", { revision: String(result.revision) }),
    );
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: queryKeys.hierarchy(props.projectId, "class"),
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

const propertyValuesValid = ref(true);
const openSections = ref([
  "annotations",
  "parents",
  "propertyValues",
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

function setPropertyValues(value: PropertyValue[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, propertyValues: value };
}
</script>

<template>
  <section
    class="class-frame-editor"
    data-testid="class-frame-editor"
    :aria-busy="draft === undefined ? 'true' : undefined"
  >
    <p
      v-if="draft === undefined"
      class="class-frame-editor__loading"
    >
      {{ t("framesClass.loading") }}
    </p>
    <template v-else>
      <FrameHeader
        v-if="draft.subject !== undefined"
        :subject="draft.subject"
      />

      <div class="class-frame-editor__body">
        <el-collapse v-model="openSections">
          <el-collapse-item
            :title="t('framesClass.annotations')"
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
            :title="t('framesClass.parents')"
            name="parents"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['class']"
              :model-value="draft.parents ?? []"
              :exclude-iris="[iri]"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesClass.parents') }"
              @update:model-value="setParents"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesClass.propertyValues')"
            name="propertyValues"
          >
            <PropertyValuesEditor
              v-model:valid="propertyValuesValid"
              :project-id="projectId"
              :model-value="draft.propertyValues ?? []"
              :disabled="readOnly"
              @update:model-value="setPropertyValues"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesClass.otherAxioms')"
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
        :invalid="!propertyValuesValid"
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
.class-frame-editor {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-3, 0.75rem);
  min-height: 0;
}

.class-frame-editor__loading {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.class-frame-editor__body {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.class-frame-editor :deep(.el-button:focus-visible),
.class-frame-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
