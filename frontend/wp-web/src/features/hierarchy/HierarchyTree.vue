<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  toRef,
  watch,
} from "vue";
import { useI18n } from "vue-i18n";
import type { HierarchyKind } from "@/features/workspace/hierarchyKinds";
import { problemMessage } from "@/shared/api/problem";
import {
  findNodeByIri,
  isEntityNode,
  type TreeNodeData,
} from "./treeNodes";
import { useHierarchyTree } from "./useHierarchyTree";

const props = defineProps<{
  projectId: string;
  kind: HierarchyKind;
  selectedIri?: string | null;
  /** Test override; production uses ResizeObserver on the container. */
  height?: number;
}>();

const emit = defineEmits<{
  select: [node: EntityNode];
  contextmenu: [node: EntityNode, event: MouseEvent];
}>();

const { t } = useI18n();
const projectId = toRef(props, "projectId");
const kind = toRef(props, "kind");
const {
  treeData,
  expandedKeys,
  isPending,
  isError,
  error,
  isEmpty,
  refetch,
  expand,
  collapse,
  locate,
} = useHierarchyTree(projectId, kind);

const TREE_PROPS = {
  value: "key",
  label: "label",
  children: "children",
} as const;

const ITEM_SIZE = 32;

const containerRef = ref<HTMLElement | null>(null);
const treeRef = ref<{
  setCurrentKey: (key: string | undefined) => void;
  setExpandedKeys: (keys: string[]) => void;
  scrollToNode: (key: string, strategy?: string) => void;
} | null>(null);
const measuredHeight = ref(props.height ?? 200);
const activeKey = ref<string | null>(null);
let resizeObserver: ResizeObserver | null = null;
let selectionSeq = 0;

const treeHeight = computed(() => props.height ?? measuredHeight.value);

const errorText = computed(() => {
  if (!isError.value) {
    return "";
  }
  return problemMessage(error.value, t);
});

const visibleNodes = computed(() =>
  flattenVisible(treeData.value, new Set(expandedKeys.value)),
);

watch(
  expandedKeys,
  (keys) => {
    treeRef.value?.setExpandedKeys([...keys]);
  },
  { flush: "post" },
);

watch(
  () => props.selectedIri,
  (iri) => {
    void syncSelection(iri ?? null);
  },
  { immediate: true },
);

watch(treeData, () => {
  void syncSelection(props.selectedIri ?? null);
});

onMounted(() => {
  if (props.height !== undefined) {
    return;
  }
  const el = containerRef.value;
  if (el === null || typeof ResizeObserver === "undefined") {
    return;
  }
  resizeObserver = new ResizeObserver((entries) => {
    const entry = entries[0];
    if (entry === undefined) {
      return;
    }
    const next = Math.floor(entry.contentRect.height);
    if (next > 0) {
      measuredHeight.value = next;
    }
  });
  resizeObserver.observe(el);
  measuredHeight.value = Math.max(Math.floor(el.clientHeight), 1);
});

onBeforeUnmount(() => {
  resizeObserver?.disconnect();
  resizeObserver = null;
});

async function syncSelection(iri: string | null): Promise<void> {
  const seq = ++selectionSeq;
  if (iri === null || iri === "") {
    activeKey.value = null;
    treeRef.value?.setCurrentKey(undefined);
    return;
  }
  const existing = findNodeByIri(treeData.value, iri)?.key ?? null;
  const key = existing ?? (await locate(iri));
  if (seq !== selectionSeq) {
    return;
  }
  if (key === null) {
    return;
  }
  activeKey.value = key;
  await nextTick();
  if (seq !== selectionSeq) {
    return;
  }
  treeRef.value?.setCurrentKey(key);
  treeRef.value?.scrollToNode(key, "center");
}

function asTreeNode(data: unknown): TreeNodeData | null {
  if (typeof data !== "object" || data === null) {
    return null;
  }
  const record = data as Partial<TreeNodeData>;
  if (typeof record.key !== "string" || typeof record.iri !== "string") {
    return null;
  }
  if (record.placeholder === true) {
    return data as TreeNodeData;
  }
  if (!isEntityNode(record.node)) {
    return null;
  }
  return data as TreeNodeData;
}

function onNodeExpand(data: unknown): void {
  const node = asTreeNode(data);
  if (node === null || node.placeholder === true) {
    return;
  }
  void expand(node.key);
}

function onNodeCollapse(data: unknown): void {
  const node = asTreeNode(data);
  if (node === null) {
    return;
  }
  collapse(node.key);
}

function onNodeClick(data: unknown): void {
  const node = asTreeNode(data);
  if (node === null || node.placeholder === true || node.node === null) {
    return;
  }
  activeKey.value = node.key;
  emit("select", node.node);
}

function onNodeContextMenu(data: unknown, event: MouseEvent): void {
  const node = asTreeNode(data);
  if (node === null || node.placeholder === true || node.node === null) {
    return;
  }
  event.preventDefault();
  emit("contextmenu", node.node, event);
}

function nodeAccessibleName(data: TreeNodeData): string {
  if (data.placeholder === true) {
    return t("hierarchy.loadingNode");
  }
  const deprecated = data.deprecated
    ? ` (${t("hierarchy.deprecated")})`
    : "";
  return `${data.label}${deprecated}. ${data.iri}`;
}

async function onRetry(): Promise<void> {
  await refetch();
}

function flattenVisible(
  nodes: readonly TreeNodeData[],
  expanded: ReadonlySet<string>,
): TreeNodeData[] {
  const out: TreeNodeData[] = [];
  for (const node of nodes) {
    if (node.placeholder === true) {
      continue;
    }
    out.push(node);
    if (expanded.has(node.key) && node.children !== undefined) {
      out.push(...flattenVisible(node.children, expanded));
    }
  }
  return out;
}

function onTreeKeydown(event: KeyboardEvent): void {
  const nodes = visibleNodes.value;
  if (nodes.length === 0) {
    return;
  }
  const currentIndex = Math.max(
    0,
    nodes.findIndex((node) => node.key === activeKey.value),
  );
  const current = nodes[currentIndex] ?? nodes[0];
  if (current === undefined) {
    return;
  }

  if (event.key === "ArrowDown") {
    event.preventDefault();
    focusNode(nodes[Math.min(currentIndex + 1, nodes.length - 1)]!);
    return;
  }
  if (event.key === "ArrowUp") {
    event.preventDefault();
    focusNode(nodes[Math.max(currentIndex - 1, 0)]!);
    return;
  }
  if (event.key === "Home") {
    event.preventDefault();
    focusNode(nodes[0]!);
    return;
  }
  if (event.key === "End") {
    event.preventDefault();
    focusNode(nodes[nodes.length - 1]!);
    return;
  }
  if (event.key === "ArrowRight") {
    event.preventDefault();
    if (current.node?.hasChildren === true) {
      void expand(current.key).then(() => {
        focusNode(current);
      });
    }
    return;
  }
  if (event.key === "ArrowLeft") {
    event.preventDefault();
    if (expandedKeys.value.includes(current.key)) {
      collapse(current.key);
    }
    return;
  }
  if (event.key === "Enter" || event.key === " ") {
    event.preventDefault();
    if (current.node !== null) {
      activeKey.value = current.key;
      emit("select", current.node);
    }
  }
}

function focusNode(node: TreeNodeData): void {
  activeKey.value = node.key;
  treeRef.value?.setCurrentKey(node.key);
  treeRef.value?.scrollToNode(node.key, "center");
}
</script>

<template>
  <div
    ref="containerRef"
    class="hierarchy-tree"
    data-testid="hierarchy-tree"
  >
    <div
      v-if="isPending && treeData.length === 0"
      class="hierarchy-tree__status"
      role="status"
      aria-live="polite"
      aria-busy="true"
    >
      <span class="hierarchy-tree__sr-only">{{ t("hierarchy.loadingTree") }}</span>
      <el-skeleton
        :rows="4"
        animated
        aria-hidden="true"
      />
    </div>
    <div
      v-else-if="isError && treeData.length === 0"
      class="hierarchy-tree__status"
      role="alert"
    >
      <p>{{ errorText || t("hierarchy.loadError") }}</p>
      <el-button
        type="primary"
        @click="onRetry"
      >
        {{ t("hierarchy.retry") }}
      </el-button>
    </div>
    <el-empty
      v-else-if="isEmpty"
      :description="t('hierarchy.empty')"
    />
    <div
      v-else
      class="hierarchy-tree__focus"
      tabindex="0"
      role="presentation"
      :aria-label="t('hierarchy.treeLabel')"
      @keydown="onTreeKeydown"
    >
      <el-tree-v2
        ref="treeRef"
        :data="treeData"
        :props="TREE_PROPS"
        :height="treeHeight"
        :item-size="ITEM_SIZE"
        :default-expanded-keys="expandedKeys"
        :expand-on-click-node="false"
        :highlight-current="true"
        @node-expand="onNodeExpand"
        @node-collapse="onNodeCollapse"
        @node-click="onNodeClick"
      >
        <template #default="{ data }">
          <span
            v-if="data.placeholder"
            class="hierarchy-tree__placeholder"
            aria-hidden="true"
          >
            <span class="hierarchy-tree__spinner" />
          </span>
          <span
            v-else
            class="hierarchy-tree__label"
            :class="{ 'is-deprecated': data.deprecated }"
            :title="data.iri"
            :aria-label="nodeAccessibleName(data)"
            @contextmenu="onNodeContextMenu(data, $event)"
          >
            {{ data.label }}
          </span>
        </template>
      </el-tree-v2>
    </div>
  </div>
</template>

<style scoped>
.hierarchy-tree {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.hierarchy-tree__focus {
  height: 100%;
  min-height: 0;
  outline: none;
}

.hierarchy-tree__focus:focus-visible {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: 2px;
}

.hierarchy-tree__status {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
  padding: 0.5rem;
}

.hierarchy-tree__label.is-deprecated {
  color: var(--wp-color-text-secondary);
  text-decoration: line-through;
}

.hierarchy-tree__placeholder {
  display: inline-flex;
  align-items: center;
  gap: 0.35rem;
  color: var(--wp-color-text-secondary);
}

.hierarchy-tree__spinner {
  display: inline-block;
  width: 0.75rem;
  height: 0.75rem;
  border: 2px solid var(--wp-color-text-tertiary);
  border-top-color: transparent;
  border-radius: 50%;
  animation: hierarchy-tree-spin 0.8s linear infinite;
}

.hierarchy-tree__sr-only {
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

:deep(.el-tree-node:focus-visible > .el-tree-node__content) {
  outline: 2px solid var(--wp-color-brand);
  outline-offset: -2px;
}

@keyframes hierarchy-tree-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .hierarchy-tree__spinner {
    animation: none;
  }
}
</style>
