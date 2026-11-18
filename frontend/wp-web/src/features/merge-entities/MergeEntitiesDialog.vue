<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { iriLocalName } from "@/features/entities/iriLocalName";
import EntityPicker from "@/features/frames/EntityPicker.vue";
import {
  kindToEntityType,
  searchTypeOf,
  type SearchType,
  type WorkspaceKind,
} from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { ApiError, problemMessage } from "@/shared/api/problem";
import {
  useMergeEntities,
  type MergeEntitiesRequest,
  type MergedEntityTreatment,
} from "./api";

const props = defineProps<{
  projectId: string;
  kind: WorkspaceKind;
  sourceIri: string;
}>();

const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const { selectEntity } = useWorkspaceRoute();
const mergeEntities = useMergeEntities(() => props.projectId);

const target = ref<EntityNode | null>(null);
const treatment = ref<MergedEntityTreatment>("DEPRECATE_MERGED_ENTITY");
const description = ref("");
const submitError = ref("");
let submitLock = false;

const pending = computed(() => mergeEntities.isPending.value);
const canSubmit = computed(() => target.value !== null && !pending.value);

const searchTypes = computed((): SearchType[] => {
  const searchType = searchTypeOf(kindToEntityType(props.kind));
  return searchType === null ? [] : [searchType];
});

const excludeIris = computed(() =>
  props.sourceIri.length > 0 ? [props.sourceIri] : [],
);

const sourceLabel = computed(() => iriLocalName(props.sourceIri));

const treatmentHint = computed(() =>
  treatment.value === "DELETE_MERGED_ENTITY"
    ? t("mergeEntities.treatmentDeleteHint")
    : t("mergeEntities.treatmentDeprecateHint"),
);

watch(visible, (open) => {
  if (!open) {
    return;
  }
  resetForm();
});

watch(
  () => props.sourceIri,
  () => {
    if (visible.value) {
      target.value = null;
      submitError.value = "";
    }
  },
);

function resetForm(): void {
  target.value = null;
  treatment.value = "DEPRECATE_MERGED_ENTITY";
  description.value = "";
  submitError.value = "";
}

async function submit(): Promise<void> {
  if (submitLock || mergeEntities.isPending.value || target.value === null) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    const targetIri = target.value.iri;
    const body = {
      sources: [props.sourceIri],
      target: targetIri,
      treatment: treatment.value,
      ...(description.value.trim().length > 0
        ? { description: description.value.trim() }
        : {}),
    } satisfies MergeEntitiesRequest;
    await mergeEntities.mutateAsync(body);
    visible.value = false;
    selectEntity(targetIri);
  } catch (error) {
    submitError.value = mapMergeError(error);
  } finally {
    submitLock = false;
  }
}

function onCancel(): void {
  visible.value = false;
}

function mapMergeError(error: unknown): string {
  if (!(error instanceof ApiError)) {
    return problemMessage(error, t);
  }
  if (error.code === "ENTITY_NOT_FOUND") {
    return t("mergeEntities.errors.ENTITY_NOT_FOUND");
  }
  if (error.code === "INVALID_MERGE") {
    const reason = problemReason(error);
    if (reason === "EMPTY_SOURCES") {
      return t("mergeEntities.errors.EMPTY_SOURCES");
    }
    if (reason === "TARGET_IN_SOURCES") {
      return t("mergeEntities.errors.TARGET_IN_SOURCES");
    }
    if (reason === "BUILT_IN_ENTITY") {
      return t("mergeEntities.errors.BUILT_IN_ENTITY");
    }
    if (reason === "TYPE_MISMATCH") {
      return t("mergeEntities.errors.TYPE_MISMATCH");
    }
    return t("mergeEntities.errors.INVALID_MERGE");
  }
  return problemMessage(error, t);
}

function problemReason(error: ApiError): string | undefined {
  const problem = error.problem;
  if (problem === undefined) {
    return undefined;
  }
  const reason = problem["reason"];
  return typeof reason === "string" && reason.length > 0 ? reason : undefined;
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('mergeEntities.title')"
    width="32rem"
    destroy-on-close
    :append-to-body="true"
  >
    <el-alert
      v-if="submitError"
      id="merge-entities-submit-error"
      class="merge-entities-alert"
      role="alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <p class="merge-entities-revision-hint">
      {{ t("mergeEntities.revisionHint") }}
    </p>
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('mergeEntities.sourceLabel')">
        <p class="merge-entities-source">
          <span class="merge-entities-source__label">{{ sourceLabel }}</span>
          <span class="merge-entities-source__iri">{{ sourceIri }}</span>
        </p>
      </el-form-item>
      <el-form-item :label="t('mergeEntities.targetLabel')">
        <EntityPicker
          v-model="target"
          :project-id="projectId"
          :types="searchTypes"
          :exclude-iris="excludeIris"
          :allow-raw-iri="false"
          :disabled="pending"
          v-bind="{ ariaLabel: t('mergeEntities.targetAriaLabel') }"
        />
      </el-form-item>
      <el-form-item :label="t('mergeEntities.treatmentLabel')">
        <el-radio-group
          v-model="treatment"
          :disabled="pending"
          aria-describedby="merge-entities-treatment-hint"
        >
          <el-radio value="DEPRECATE_MERGED_ENTITY">
            {{ t("mergeEntities.treatmentDeprecate") }}
          </el-radio>
          <el-radio value="DELETE_MERGED_ENTITY">
            {{ t("mergeEntities.treatmentDelete") }}
          </el-radio>
        </el-radio-group>
        <p
          id="merge-entities-treatment-hint"
          class="merge-entities-hint"
        >
          {{ treatmentHint }}
        </p>
      </el-form-item>
      <el-form-item :label="t('mergeEntities.descriptionLabel')">
        <el-input
          v-model="description"
          name="description"
          type="textarea"
          :rows="3"
          :disabled="pending"
        />
      </el-form-item>
      <div class="merge-entities-actions">
        <el-button
          :disabled="pending"
          @click="onCancel"
        >
          {{ t("mergeEntities.cancel") }}
        </el-button>
        <el-button
          data-testid="merge-entities-submit"
          type="primary"
          native-type="submit"
          :disabled="!canSubmit"
          :loading="pending"
        >
          {{ t("mergeEntities.submit") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.merge-entities-alert {
  margin-bottom: 0.75rem;
}

.merge-entities-revision-hint {
  margin: 0 0 0.75rem;
  color: var(--wp-color-text-secondary);
  font-size: 0.9rem;
}

.merge-entities-source {
  display: flex;
  flex-direction: column;
  gap: 0.15rem;
  margin: 0;
}

.merge-entities-source__label {
  font-weight: 600;
}

.merge-entities-source__iri {
  color: var(--wp-color-text-secondary);
  font-size: 0.85rem;
  word-break: break-all;
}

.merge-entities-hint {
  margin: 0.35rem 0 0;
  color: var(--wp-color-text-secondary);
  font-size: 0.85rem;
}

.merge-entities-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
