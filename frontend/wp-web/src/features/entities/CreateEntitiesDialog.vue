<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { problemMessage } from "@/shared/api/problem";
import { useCreateEntities, useNewEntitySettings } from "./api";
import { parseNames } from "./parseNames";

const props = defineProps<{
  projectId: string;
  entityType: EntityNode["entityType"];
  parent?: { iri: string; label: string };
}>();

const visible = defineModel<boolean>({ default: false });

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { selectEntity } = useWorkspaceRoute();
const createEntities = useCreateEntities(() => props.projectId);
const settingsQuery = useNewEntitySettings(() => props.projectId);

const namesText = ref("");
const lang = ref("");
const namesError = ref("");
const submitError = ref("");
let submitLock = false;

const pending = computed(() => createEntities.isPending.value);

const parentSummary = computed(() => {
  if (props.parent === undefined) {
    return t("entities.parentRoot");
  }
  return t("entities.parentLabel", { label: props.parent.label });
});

watch(visible, (open) => {
  if (!open) {
    return;
  }
  namesText.value = "";
  namesError.value = "";
  submitError.value = "";
  const defaultLang = settingsQuery.data.value?.defaultLang;
  lang.value =
    typeof defaultLang === "string" && defaultLang.length > 0
      ? defaultLang
      : "";
});

watch(
  () => settingsQuery.data.value?.defaultLang,
  (defaultLang) => {
    if (!visible.value) {
      return;
    }
    if (lang.value.trim().length > 0) {
      return;
    }
    if (typeof defaultLang === "string" && defaultLang.length > 0) {
      lang.value = defaultLang;
    }
  },
);

watch(namesText, () => {
  namesError.value = "";
});

async function submit(): Promise<void> {
  if (submitLock || createEntities.isPending.value) {
    return;
  }
  submitLock = true;
  submitError.value = "";
  try {
    const parsed = parseNames(namesText.value);
    if (!parsed.ok) {
      namesError.value =
        parsed.reason === "tooMany"
          ? t("entities.namesTooMany")
          : t("entities.namesRequired");
      return;
    }
    const body = {
      entityType: props.entityType,
      names: parsed.names,
      ...(lang.value.trim().length > 0 ? { lang: lang.value.trim() } : {}),
      ...(props.parent !== undefined ? { parents: [props.parent.iri] } : {}),
    };
    try {
      const created = await createEntities.mutateAsync(body);
      const first = created.entities[0];
      visible.value = false;
      if (first === undefined) {
        return;
      }
      if (
        props.entityType === "NAMED_INDIVIDUAL" &&
        props.parent !== undefined
      ) {
        void router.replace({
          query: {
            ...route.query,
            type: props.parent.iri,
            entity: first.iri,
          },
        });
        return;
      }
      selectEntity(first.iri);
    } catch (error) {
      submitError.value = problemMessage(error, t);
    }
  } finally {
    submitLock = false;
  }
}

function onCancel(): void {
  visible.value = false;
}
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="t('entities.createTitle')"
    width="28rem"
    destroy-on-close
    :append-to-body="true"
  >
    <el-alert
      v-if="submitError"
      class="create-entities-alert"
      :title="submitError"
      type="error"
      show-icon
      :closable="false"
    />
    <p class="create-entities-parent">
      {{ parentSummary }}
    </p>
    <el-form
      label-position="top"
      @submit.prevent="submit"
    >
      <el-form-item :label="t('entities.namesLabel')">
        <el-input
          v-model="namesText"
          name="names"
          type="textarea"
          :rows="6"
          :disabled="pending"
          :aria-invalid="namesError ? 'true' : undefined"
          :aria-describedby="
            namesError ? 'create-entities-names-error' : 'create-entities-names-hint'
          "
        />
        <p
          id="create-entities-names-hint"
          class="create-entities-hint"
        >
          {{ t("entities.namesHint") }}
        </p>
        <p
          v-if="namesError"
          id="create-entities-names-error"
          class="field-error"
          role="alert"
        >
          {{ namesError }}
        </p>
      </el-form-item>
      <el-form-item :label="t('entities.langLabel')">
        <el-input
          v-model="lang"
          name="lang"
          :disabled="pending"
        />
      </el-form-item>
      <div class="create-entities-actions">
        <el-button
          :disabled="pending"
          @click="onCancel"
        >
          {{ t("entities.cancel") }}
        </el-button>
        <el-button
          type="primary"
          native-type="submit"
          :loading="pending"
        >
          {{ t("entities.submit") }}
        </el-button>
      </div>
    </el-form>
  </el-dialog>
</template>

<style scoped>
.create-entities-alert {
  margin-bottom: 0.75rem;
}

.create-entities-parent {
  margin: 0 0 0.75rem;
  color: var(--wp-color-text-secondary);
}

.create-entities-hint {
  margin: 0.35rem 0 0;
  color: var(--wp-color-text-secondary);
  font-size: 0.85rem;
}

.field-error {
  margin: 0.35rem 0 0;
  color: var(--el-color-danger);
  font-size: 0.85rem;
}

.create-entities-actions {
  display: flex;
  justify-content: flex-end;
  gap: 0.5rem;
}
</style>
