<script setup lang="ts">
import { useQuery } from "@tanstack/vue-query";
import type { EntityNode } from "@wp/api-types";
import {
  computed,
  onScopeDispose,
  ref,
  watch,
  type Ref,
} from "vue";
import { useI18n } from "vue-i18n";
import {
  joinSearchTypes,
  type SearchType,
} from "@/features/workspace/hierarchyKinds";
import { useApiClient } from "@/shared/api/client";
import { unwrap } from "@/shared/api/problem";
import { queryKeys } from "@/shared/api/queryKeys";
import { rawIriNode } from "./frameModel";

const RAW_OPTION_PREFIX = "__raw__:";
const SEARCH_LIMIT = 20;
const DEBOUNCE_MS = 250;

const props = withDefaults(
  defineProps<{
    projectId: string;
    types: SearchType[];
    modelValue: EntityNode | null;
    builtins?: readonly EntityNode[];
    allowRawIri?: boolean;
    excludeIris?: readonly string[];
    ariaLabel: string;
  }>(),
  {
    builtins: () => [],
    allowRawIri: false,
    excludeIris: () => [],
  },
);

const emit = defineEmits<{
  "update:modelValue": [value: EntityNode | null];
}>();

const { t } = useI18n();
const client = useApiClient();

const filterText: Ref<string> = ref("");
const debouncedQuery = ref("");
let debounceTimer: ReturnType<typeof setTimeout> | undefined;

watch(
  filterText,
  (raw) => {
    if (debounceTimer !== undefined) {
      clearTimeout(debounceTimer);
      debounceTimer = undefined;
    }
    const trimmed = raw.trim();
    if (trimmed.length === 0) {
      debouncedQuery.value = "";
      return;
    }
    debounceTimer = setTimeout(() => {
      debouncedQuery.value = trimmed;
      debounceTimer = undefined;
    }, DEBOUNCE_MS);
  },
  { immediate: true },
);

onScopeDispose(() => {
  if (debounceTimer !== undefined) {
    clearTimeout(debounceTimer);
  }
});

const searchEnabled = computed(
  () => props.projectId.length > 0 && debouncedQuery.value.length > 0,
);

const searchQuery = useQuery({
  queryKey: computed(() =>
    queryKeys.search(
      props.projectId,
      debouncedQuery.value,
      props.types,
      SEARCH_LIMIT,
    ),
  ),
  enabled: searchEnabled,
  queryFn: async () =>
    unwrap(
      await client.GET("/api/projects/{id}/search", {
        params: {
          path: { id: props.projectId },
          query: {
            q: debouncedQuery.value,
            types: joinSearchTypes(props.types),
            limit: SEARCH_LIMIT,
          },
        },
      }),
    ),
});

const excluded = computed(() => new Set(props.excludeIris));

const matchedBuiltins = computed(() => {
  const needle = filterText.value.trim().toLowerCase();
  return props.builtins.filter((node) => {
    if (excluded.value.has(node.iri)) {
      return false;
    }
    if (needle.length === 0) {
      return true;
    }
    return (
      node.displayName.toLowerCase().includes(needle) ||
      node.iri.toLowerCase().includes(needle)
    );
  });
});

const searchHits = computed(() => {
  const rows = searchQuery.data.value ?? [];
  const builtinIris = new Set(matchedBuiltins.value.map((node) => node.iri));
  return rows.filter(
    (node) => !excluded.value.has(node.iri) && !builtinIris.has(node.iri),
  );
});

type PickerOption = {
  readonly value: string;
  readonly node: EntityNode;
  readonly label: string;
  readonly isRaw: boolean;
};

const options = computed((): PickerOption[] => {
  const fromBuiltins = matchedBuiltins.value.map((node) => ({
    value: node.iri,
    node,
    label: node.displayName,
    isRaw: false,
  }));
  const fromSearch = searchHits.value.map((node) => ({
    value: node.iri,
    node,
    label: node.displayName,
    isRaw: false,
  }));
  const items = [...fromBuiltins, ...fromSearch];
  const current = props.modelValue;
  if (
    current !== null &&
    !excluded.value.has(current.iri) &&
    !items.some((item) => item.node.iri === current.iri)
  ) {
    items.unshift({
      value: current.iri,
      node: current,
      label: current.displayName,
      isRaw: current.entityType === "IRI",
    });
  }
  const typed = filterText.value.trim();
  if (
    props.allowRawIri &&
    isAbsoluteIri(typed) &&
    !excluded.value.has(typed) &&
    !items.some((item) => item.node.iri === typed)
  ) {
    const node = rawIriNode(typed);
    items.push({
      value: `${RAW_OPTION_PREFIX}${typed}`,
      node,
      label: t("framesInputs.useRawIri", { iri: typed }),
      isRaw: true,
    });
  }
  return items;
});

const selectedValue = computed({
  get(): string | undefined {
    return props.modelValue?.iri;
  },
  set(value: string | undefined) {
    if (value === undefined || value === "") {
      emit("update:modelValue", null);
      return;
    }
    const option = options.value.find((entry) => entry.value === value);
    if (option !== undefined) {
      emit("update:modelValue", option.node);
      filterText.value = "";
      return;
    }
    if (value.startsWith(RAW_OPTION_PREFIX)) {
      emit(
        "update:modelValue",
        rawIriNode(value.slice(RAW_OPTION_PREFIX.length)),
      );
      filterText.value = "";
      return;
    }
    if (props.modelValue?.iri === value) {
      return;
    }
    emit("update:modelValue", null);
  },
});

function onRemoteMethod(query: string): void {
  filterText.value = query;
}

function onSelect(value: string | number | boolean | undefined): void {
  if (value === undefined || value === null || value === "") {
    selectedValue.value = undefined;
    return;
  }
  selectedValue.value = String(value);
}

defineExpose({
  applyFilter: onRemoteMethod,
});

function entityTypeLabel(entityType: EntityNode["entityType"]): string {
  return t(`framesCommon.entityType.${entityType}`);
}

function isAbsoluteIri(value: string): boolean {
  try {
    const url = new URL(value);
    return url.protocol.length > 0 && !/\s/.test(value);
  } catch {
    return false;
  }
}
</script>

<template>
  <el-select
    class="entity-picker"
    :model-value="selectedValue"
    filterable
    remote
    clearable
    :remote-method="onRemoteMethod"
    :loading="searchQuery.isFetching.value"
    :aria-label="ariaLabel"
    :placeholder="ariaLabel"
    @update:model-value="onSelect"
  >
    <el-option
      v-for="option in options"
      :key="option.value"
      :label="option.label"
      :value="option.value"
    >
      <span class="entity-picker__option">
        <span class="entity-picker__name">{{ option.label }}</span>
        <span
          v-if="!option.isRaw"
          class="entity-picker__type"
        >
          {{ entityTypeLabel(option.node.entityType) }}
        </span>
      </span>
    </el-option>
  </el-select>
</template>

<style scoped>
.entity-picker {
  width: 100%;
}

.entity-picker__option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
}

.entity-picker__name {
  overflow: hidden;
  text-overflow: ellipsis;
}

.entity-picker__type {
  flex-shrink: 0;
  color: var(--el-text-color-secondary);
  font-size: 0.75rem;
}
</style>
