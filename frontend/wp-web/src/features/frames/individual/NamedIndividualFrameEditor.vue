<script setup lang="ts">
import { useQueryClient } from "@tanstack/vue-query";
import type {
  AnnotationValue,
  EntityNode,
  NamedIndividualFrame,
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
import { normalizeNamedIndividualFrame } from "../frameModel";
import OtherAxiomsView from "../OtherAxiomsView.vue";
import { useFrameEditor } from "../useFrameEditor";
import { useUnsavedGuard } from "../useUnsavedGuard";
import {
  loadNamedIndividualFrame,
  saveNamedIndividualFrame,
} from "./api";
import DataAssertionsEditor from "./DataAssertionsEditor.vue";
import ObjectAssertionsEditor from "./ObjectAssertionsEditor.vue";

type ObjectPropertyAssertion = NonNullable<
  NamedIndividualFrame["objectPropertyAssertions"]
>[number];
type DataPropertyAssertion = NonNullable<
  NamedIndividualFrame["dataPropertyAssertions"]
>[number];

const props = defineProps<{
  projectId: string;
  iri: string;
}>();

const { t } = useI18n();
const client = useApiClient();
const queryClient = useQueryClient();

const frameKey = computed(() =>
  queryKeys.frame(props.projectId, "NAMED_INDIVIDUAL", props.iri),
);

const editor = useFrameEditor<NamedIndividualFrame>({
  queryKey: frameKey,
  load: () => loadNamedIndividualFrame(client, props.projectId, props.iri),
  save: async ({ from, to }) => {
    const result = await saveNamedIndividualFrame(client, props.projectId, {
      from,
      to,
    });
    return { frame: result.frame, revision: result.revision };
  },
  normalize: normalizeNamedIndividualFrame,
  onSaved: async (result) => {
    ElMessage.success(
      t("framesIndividual.saveSuccess", {
        revision: String(result.revision),
      }),
    );
    await Promise.all([
      queryClient.invalidateQueries({
        queryKey: queryKeys.individualsAll(props.projectId),
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

const objectAssertionsValid = ref(true);
const dataAssertionsValid = ref(true);
const assertionsInvalid = computed(
  () => !objectAssertionsValid.value || !dataAssertionsValid.value,
);

const openSections = ref([
  "annotations",
  "types",
  "objectAssertions",
  "dataAssertions",
  "sameIndividuals",
  "otherAxioms",
]);

function setAnnotations(value: AnnotationValue[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, annotations: value };
}

function setTypes(value: EntityNode[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, types: value };
}

function setObjectAssertions(value: ObjectPropertyAssertion[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, objectPropertyAssertions: value };
}

function setDataAssertions(value: DataPropertyAssertion[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, dataPropertyAssertions: value };
}

function setSameIndividuals(value: EntityNode[]): void {
  if (draft.value === undefined) {
    return;
  }
  draft.value = { ...draft.value, sameIndividuals: value };
}
</script>

<template>
  <section
    class="named-individual-frame-editor"
    data-testid="named-individual-frame-editor"
    :aria-busy="draft === undefined ? 'true' : undefined"
  >
    <p
      v-if="draft === undefined"
      class="named-individual-frame-editor__loading"
    >
      {{ t("framesIndividual.loading") }}
    </p>
    <template v-else>
      <FrameHeader
        v-if="draft.subject !== undefined"
        :subject="draft.subject"
      />

      <div class="named-individual-frame-editor__body">
        <el-collapse v-model="openSections">
          <el-collapse-item
            :title="t('framesIndividual.annotations')"
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
            :title="t('framesIndividual.types')"
            name="types"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['class']"
              :model-value="draft.types ?? []"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesIndividual.types') }"
              @update:model-value="setTypes"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesIndividual.objectAssertions')"
            name="objectAssertions"
          >
            <ObjectAssertionsEditor
              v-model:valid="objectAssertionsValid"
              :project-id="projectId"
              :model-value="draft.objectPropertyAssertions ?? []"
              :disabled="readOnly"
              @update:model-value="setObjectAssertions"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesIndividual.dataAssertions')"
            name="dataAssertions"
          >
            <DataAssertionsEditor
              v-model:valid="dataAssertionsValid"
              :project-id="projectId"
              :model-value="draft.dataPropertyAssertions ?? []"
              :disabled="readOnly"
              @update:model-value="setDataAssertions"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesIndividual.sameIndividuals')"
            name="sameIndividuals"
          >
            <EntityRefList
              :project-id="projectId"
              :types="['named-individual']"
              :model-value="draft.sameIndividuals ?? []"
              :exclude-iris="[iri]"
              :disabled="readOnly"
              v-bind="{ ariaLabel: t('framesIndividual.sameIndividuals') }"
              @update:model-value="setSameIndividuals"
            />
          </el-collapse-item>

          <el-collapse-item
            :title="t('framesIndividual.otherAxioms')"
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
        :invalid="assertionsInvalid"
        :read-only="readOnly"
        @save="save"
        @reset="reset"
        @reload="reloadFromServer"
      />
    </template>
  </section>
</template>

<style scoped>
.named-individual-frame-editor {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-3, 0.75rem);
  min-height: 0;
}

.named-individual-frame-editor__loading {
  margin: 0;
  color: var(--el-text-color-secondary);
}

.named-individual-frame-editor__body {
  display: flex;
  flex-direction: column;
  gap: var(--wp-space-2, 0.5rem);
}

.named-individual-frame-editor :deep(.el-button:focus-visible),
.named-individual-frame-editor :deep(.el-select__wrapper.is-focused) {
  outline: 2px solid var(--wp-color-brand, var(--el-color-primary));
  outline-offset: 2px;
}
</style>
