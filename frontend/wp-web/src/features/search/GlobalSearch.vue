<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { useI18n } from "vue-i18n";
import { useRoute, useRouter } from "vue-router";
import { iriLocalName } from "@/features/entities/iriLocalName";
import {
  entityTypeToKind,
  type EntityTypeName,
} from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { problemMessage } from "@/shared/api/problem";
import {
  useEntitySearch,
  type SearchableEntityType,
} from "./useEntitySearch";

const { t } = useI18n();
const route = useRoute();
const router = useRouter();
const { linkTo } = useWorkspaceRoute();

const projectId = computed(() => {
  const raw = route.params.projectId;
  return typeof raw === "string" && raw.length > 0 ? raw : "";
});

const visible = computed(() => projectId.value.length > 0);

const query = ref("");
const open = ref(false);
const activeIndex = ref(-1);
const inputRef = ref<HTMLInputElement | null>(null);
const listboxId = "wp-global-search-listbox";
let blurCloseTimer: ReturnType<typeof setTimeout> | undefined;

const {
  groups,
  flatItems,
  hasQuery,
  isFetching,
  isError,
  error,
} = useEntitySearch(projectId, query);

const showPanel = computed(
  () => open.value && hasQuery.value && visible.value,
);

const hasOptions = computed(
  () => showPanel.value && !isError.value && flatItems.value.length > 0,
);

const activeOptionId = computed(() => {
  if (!hasOptions.value || activeIndex.value < 0) {
    return undefined;
  }
  return optionId(activeIndex.value);
});

const itemIndexByIri = computed(() => {
  const map = new Map<string, number>();
  flatItems.value.forEach((item, index) => {
    map.set(item.iri, index);
  });
  return map;
});

const GROUP_LABEL_KEY: Record<SearchableEntityType, string> = {
  CLASS: "search.groupClass",
  OBJECT_PROPERTY: "search.groupObjectProperty",
  DATA_PROPERTY: "search.groupDataProperty",
  ANNOTATION_PROPERTY: "search.groupAnnotationProperty",
  NAMED_INDIVIDUAL: "search.groupIndividual",
  DATATYPE: "search.groupDatatype",
};

const errorText = computed(() => {
  if (!isError.value) {
    return "";
  }
  return problemMessage(error.value, t);
});

const shortcutLabel = computed(() =>
  prefersMetaShortcut() ? t("search.shortcutMeta") : t("search.shortcutCtrl"),
);

watch(flatItems, () => {
  activeIndex.value = -1;
});

watch(query, (value) => {
  if (value.trim().length > 0) {
    open.value = true;
  }
});

watch(activeIndex, async (index) => {
  if (index < 0) {
    return;
  }
  await nextTick();
  document
    .getElementById(optionId(index))
    ?.scrollIntoView({ block: "nearest" });
});

onMounted(() => {
  window.addEventListener("keydown", onGlobalShortcut);
});

onBeforeUnmount(() => {
  window.removeEventListener("keydown", onGlobalShortcut);
  clearBlurCloseTimer();
});

function clearBlurCloseTimer(): void {
  if (blurCloseTimer !== undefined) {
    clearTimeout(blurCloseTimer);
    blurCloseTimer = undefined;
  }
}

function onGlobalShortcut(event: KeyboardEvent): void {
  if (!visible.value) {
    return;
  }
  const isMod = event.metaKey || event.ctrlKey;
  if (!isMod || event.key.toLowerCase() !== "k") {
    return;
  }
  event.preventDefault();
  clearBlurCloseTimer();
  inputRef.value?.focus();
  open.value = query.value.trim().length > 0;
}

function onFocus(): void {
  clearBlurCloseTimer();
  if (query.value.trim().length > 0) {
    open.value = true;
  }
}

function onBlur(): void {
  clearBlurCloseTimer();
  blurCloseTimer = setTimeout(() => {
    blurCloseTimer = undefined;
    if (document.activeElement === inputRef.value) {
      return;
    }
    open.value = false;
  }, 150);
}

function onInputKeydown(event: KeyboardEvent): void {
  if (event.key === "Escape") {
    event.preventDefault();
    open.value = false;
    activeIndex.value = -1;
    return;
  }

  if (!showPanel.value) {
    if (event.key === "ArrowDown" && hasQuery.value) {
      open.value = true;
    }
    return;
  }

  if (isError.value || flatItems.value.length === 0) {
    return;
  }

  if (event.key === "ArrowDown") {
    event.preventDefault();
    moveActive(1);
    return;
  }
  if (event.key === "ArrowUp") {
    event.preventDefault();
    moveActive(-1);
    return;
  }
  if (event.key === "Enter") {
    event.preventDefault();
    const index = activeIndex.value >= 0 ? activeIndex.value : firstNavigableIndex();
    const item = index === null ? undefined : flatItems.value[index];
    if (item !== undefined && isNavigable(item.entityType)) {
      void selectItem(item);
    }
  }
}

function moveActive(delta: 1 | -1): void {
  const items = flatItems.value;
  if (items.length === 0) {
    return;
  }
  let next = activeIndex.value;
  for (let step = 0; step < items.length; step += 1) {
    next += delta;
    if (next < 0 || next >= items.length) {
      if (delta < 0) {
        activeIndex.value = -1;
      }
      return;
    }
    const candidate = items[next];
    if (candidate !== undefined && isNavigable(candidate.entityType)) {
      activeIndex.value = next;
      return;
    }
  }
}

function firstNavigableIndex(): number | null {
  const index = flatItems.value.findIndex((item) =>
    isNavigable(item.entityType),
  );
  return index >= 0 ? index : null;
}

async function selectItem(node: EntityNode): Promise<void> {
  const target = linkTo(node.entityType, node.iri);
  if (target === null) {
    return;
  }
  open.value = false;
  activeIndex.value = -1;
  await router.push(target);
  await nextTick();
}

function isNavigable(entityType: EntityTypeName): boolean {
  return entityTypeToKind(entityType) !== null;
}

function optionId(index: number): string {
  return `${listboxId}-option-${index}`;
}

function groupLabel(entityType: SearchableEntityType): string {
  return t(GROUP_LABEL_KEY[entityType]);
}

function prefersMetaShortcut(): boolean {
  if (typeof navigator === "undefined") {
    return false;
  }
  const platform = navigator.platform.toLowerCase();
  return platform.includes("mac") || platform.includes("iphone") || platform.includes("ipad");
}
</script>

<template>
  <div
    v-if="visible"
    class="global-search"
  >
    <div class="global-search__field">
      <input
        ref="inputRef"
        v-model="query"
        type="search"
        class="global-search__input"
        role="combobox"
        :aria-label="t('search.inputLabel')"
        :aria-expanded="showPanel"
        :aria-controls="hasOptions ? listboxId : undefined"
        aria-haspopup="listbox"
        aria-autocomplete="list"
        :aria-activedescendant="activeOptionId"
        :placeholder="t('search.placeholder')"
        autocomplete="off"
        @focus="onFocus"
        @blur="onBlur"
        @keydown="onInputKeydown"
      >
      <span
        class="global-search__shortcut"
        aria-hidden="true"
      >{{ shortcutLabel }}</span>
    </div>

    <div
      v-if="showPanel"
      class="global-search__panel"
    >
      <p
        v-if="isError"
        class="global-search__error"
        role="alert"
      >
        {{ errorText }}
      </p>
      <p
        v-else-if="isFetching && flatItems.length === 0"
        class="global-search__status"
        role="status"
        aria-live="polite"
      >
        {{ t("search.loading") }}
      </p>
      <div
        v-else-if="flatItems.length === 0"
        class="global-search__empty-block"
        role="status"
        aria-live="polite"
      >
        <p class="global-search__empty">
          {{ t("search.noResults") }}
        </p>
        <p class="global-search__hint">
          {{ t("search.displayNameHint") }}
        </p>
      </div>
      <div
        v-else
        :id="listboxId"
        role="listbox"
        :aria-label="t('search.inputLabel')"
      >
        <div
          v-for="group in groups"
          :key="group.entityType"
          class="global-search__group"
          role="group"
          :aria-label="groupLabel(group.entityType)"
        >
          <div
            class="global-search__group-title"
            aria-hidden="true"
          >
            {{ groupLabel(group.entityType) }}
          </div>
          <button
            v-for="item in group.items"
            :id="optionId(itemIndexByIri.get(item.iri) ?? 0)"
            :key="item.iri"
            type="button"
            class="global-search__option"
            :class="{
              'is-active': (itemIndexByIri.get(item.iri) ?? -1) === activeIndex,
              'is-deprecated': item.deprecated,
              'is-disabled': !isNavigable(item.entityType),
            }"
            role="option"
            :aria-selected="(itemIndexByIri.get(item.iri) ?? -1) === activeIndex"
            :aria-disabled="!isNavigable(item.entityType)"
            :tabindex="-1"
            @mousedown.prevent
            @click="void selectItem(item)"
          >
            <span class="global-search__name">{{ item.displayName }}</span>
            <span class="global-search__local">{{ iriLocalName(item.iri) }}</span>
            <span
              v-if="item.deprecated"
              class="global-search__badge"
            >{{ t("search.deprecated") }}</span>
            <span
              v-if="!isNavigable(item.entityType)"
              class="global-search__badge"
            >{{ t("search.notNavigable") }}</span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.global-search {
  position: relative;
  width: 100%;
}

.global-search__field {
  position: relative;
  display: flex;
  align-items: center;
}

.global-search__input {
  width: 100%;
  min-height: 32px;
  padding: var(--wp-space-1) 3.5rem var(--wp-space-1) var(--wp-space-3);
  border: 1px solid var(--wp-color-border);
  border-radius: var(--wp-radius-md);
  background: var(--wp-color-bg-muted);
  color: var(--wp-color-text-primary);
  font-size: var(--wp-font-size-sm);
}

.global-search__input:focus-visible {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: 1px;
}

.global-search__shortcut {
  position: absolute;
  right: var(--wp-space-2);
  color: var(--wp-color-text-tertiary);
  font-size: var(--wp-font-size-xs);
  pointer-events: none;
}

.global-search__panel {
  position: absolute;
  z-index: 20;
  top: calc(100% + var(--wp-space-1));
  right: 0;
  left: 0;
  max-height: 22rem;
  overflow: auto;
  border: 1px solid var(--wp-color-border);
  border-radius: var(--wp-radius-md);
  background: var(--wp-color-bg);
  box-shadow: 0 8px 24px rgb(0 0 0 / 8%);
}

.global-search__group-title {
  padding: var(--wp-space-2) var(--wp-space-3) var(--wp-space-1);
  color: var(--wp-color-text-secondary);
  font-size: var(--wp-font-size-xs);
  font-weight: 600;
}

.global-search__option {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: var(--wp-space-2);
  align-items: center;
  width: 100%;
  padding: var(--wp-space-2) var(--wp-space-3);
  border: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.global-search__option.is-active,
.global-search__option:hover:not(.is-disabled) {
  background: var(--wp-color-bg-muted);
}

.global-search__option.is-disabled {
  cursor: default;
  color: var(--wp-color-text-secondary);
}

.global-search__option.is-deprecated .global-search__name {
  text-decoration: line-through;
  color: var(--wp-color-text-secondary);
}

.global-search__name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.global-search__local {
  overflow: hidden;
  color: var(--wp-color-text-secondary);
  font-size: var(--wp-font-size-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.global-search__badge {
  color: var(--wp-color-text-secondary);
  font-size: var(--wp-font-size-xs);
}

.global-search__empty,
.global-search__hint,
.global-search__status,
.global-search__error {
  margin: 0;
  padding: var(--wp-space-3);
  color: var(--wp-color-text-secondary);
  font-size: var(--wp-font-size-sm);
}

.global-search__error {
  color: var(--wp-color-danger);
}

.global-search__hint {
  padding-top: 0;
  font-size: var(--wp-font-size-xs);
}

.global-search__empty-block {
  display: flex;
  flex-direction: column;
}
</style>
