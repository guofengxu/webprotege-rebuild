<script setup lang="ts">
import { ElMessage } from "element-plus";
import { ref } from "vue";
import { useI18n } from "vue-i18n";
import { useApiClient } from "@/shared/api/client";
import { problemMessage } from "@/shared/api/problem";
import {
  downloadProject,
  type DownloadFormatCode,
} from "./downloadProject";
import { saveBlob } from "./saveBlob";

const props = defineProps<{
  projectId: string;
  revision?: number;
}>();

const { t } = useI18n();
const client = useApiClient();
const loading = ref(false);

const formats: readonly {
  readonly format: DownloadFormatCode;
  readonly labelKey: string;
}[] = [
  { format: "owl", labelKey: "download.formatOwl" },
  { format: "ttl", labelKey: "download.formatTurtle" },
  { format: "owx", labelKey: "download.formatOwx" },
  { format: "omn", labelKey: "download.formatManchester" },
  { format: "ofn", labelKey: "download.formatFunctional" },
];

async function onCommand(command: string | number | object): Promise<void> {
  if (typeof command !== "string" || loading.value) {
    return;
  }
  if (!isDownloadFormat(command)) {
    return;
  }
  loading.value = true;
  try {
    const options =
      props.revision === undefined
        ? { format: command }
        : { format: command, revision: props.revision };
    const result = await downloadProject(client, props.projectId, options);
    saveBlob(result.blob, result.fileName);
  } catch (error) {
    ElMessage.error(problemMessage(error, t));
  } finally {
    loading.value = false;
  }
}

function isDownloadFormat(value: string): value is DownloadFormatCode {
  return formats.some((entry) => entry.format === value);
}
</script>

<template>
  <el-dropdown
    trigger="click"
    :disabled="loading"
    @command="onCommand"
  >
    <el-button
      size="small"
      :loading="loading"
      :aria-label="t('download.menuLabel')"
      :aria-busy="loading ? 'true' : 'false'"
    >
      {{ t("download.button") }}
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="entry in formats"
          :key="entry.format"
          :command="entry.format"
          :disabled="loading"
        >
          {{ t(entry.labelKey) }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
