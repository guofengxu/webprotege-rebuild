<script setup lang="ts">
import type { EntityNode } from "@wp/api-types";
import { ElMessage } from "element-plus";
import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from "vue";
import { useI18n } from "vue-i18n";
import {
  kindToEntityType,
  type WorkspaceKind,
} from "@/features/workspace/hierarchyKinds";
import { useWorkspaceRoute } from "@/features/workspace/useWorkspaceRoute";
import { ApiError, problemMessage } from "@/shared/api/problem";
import { useDeleteEntities } from "./api";
import CreateEntitiesDialog from "./CreateEntitiesDialog.vue";
import { confirmDelete } from "./DeleteEntitiesConfirm";

export type ContextMenuTarget = {
  readonly node: EntityNode;
  readonly clientX: number;
  readonly clientY: number;
  readonly source: "tree" | "individual-row";
};

const props = defineProps<{
  projectId: string;
  kind: WorkspaceKind;
  target: ContextMenuTarget | null;
}>();

const emit = defineEmits<{
  close: [];
}>();

const { t } = useI18n();
const { entityIri, selectEntity } = useWorkspaceRoute();
const deleteEntities = useDeleteEntities(() => props.projectId);

const dropdownRef = ref<{
  handleOpen: () => void;
  handleClose: () => void;
} | null>(null);

const virtualRef = ref<{
  getBoundingClientRect: () => DOMRect;
} | null>(null);

const createOpen = ref(false);
const createParent = ref<{ iri: string; label: string } | undefined>(
  undefined,
);
const createEntityType = ref(kindToEntityType(props.kind));

const menuItems = computed(() => {
  const target = props.target;
  if (target === null) {
    return [] as const;
  }
  if (props.kind === "individual" && target.source === "tree") {
    return [{ command: "create", label: t("entities.createIndividual") }] as const;
  }
  if (props.kind === "individual" && target.source === "individual-row") {
    return [{ command: "delete", label: t("entities.delete") }] as const;
  }
  if (props.kind === "class") {
    return [
      { command: "create", label: t("entities.createChildClass") },
      { command: "delete", label: t("entities.delete") },
    ] as const;
  }
  return [
    { command: "create", label: t("entities.createChildProperty") },
    { command: "delete", label: t("entities.delete") },
  ] as const;
});

watch(
  () => props.target,
  async (target) => {
    if (target === null) {
      dropdownRef.value?.handleClose();
      return;
    }
    const { clientX, clientY } = target;
    virtualRef.value = {
      getBoundingClientRect: () =>
        new DOMRect(clientX, clientY, 0, 0),
    };
    await nextTick();
    dropdownRef.value?.handleOpen();
    await nextTick();
    focusMenuItem(0);
  },
);

onMounted(() => {
  document.addEventListener("keydown", onDocumentKeydown);
});

onBeforeUnmount(() => {
  document.removeEventListener("keydown", onDocumentKeydown);
});

function menuItemElements(): HTMLElement[] {
  return Array.from(
    document.body.querySelectorAll<HTMLElement>(".el-dropdown-menu__item"),
  );
}

function focusMenuItem(index: number): void {
  const items = menuItemElements();
  if (items.length === 0) {
    return;
  }
  const next = ((index % items.length) + items.length) % items.length;
  items[next]?.focus();
}

function focusedMenuItemIndex(): number {
  const items = menuItemElements();
  const active = document.activeElement;
  if (!(active instanceof HTMLElement)) {
    return -1;
  }
  return items.indexOf(active);
}

function onDocumentKeydown(event: KeyboardEvent): void {
  if (props.target === null) {
    return;
  }
  if (event.key === "Escape" || event.code === "Escape") {
    event.preventDefault();
    dropdownRef.value?.handleClose();
    emit("close");
    return;
  }
  const items = menuItemElements();
  if (items.length === 0) {
    return;
  }
  if (event.key === "ArrowDown") {
    event.preventDefault();
    const current = focusedMenuItemIndex();
    focusMenuItem(current < 0 ? 0 : current + 1);
    return;
  }
  if (event.key === "ArrowUp") {
    event.preventDefault();
    const current = focusedMenuItemIndex();
    focusMenuItem(current < 0 ? items.length - 1 : current - 1);
    return;
  }
  if (event.key === "Enter") {
    const current = focusedMenuItemIndex();
    if (current < 0) {
      return;
    }
    event.preventDefault();
    items[current]?.click();
  }
}

function onVisibleChange(open: boolean): void {
  if (!open) {
    emit("close");
  }
}

async function onCommand(command: string | number | object): Promise<void> {
  const target = props.target;
  if (target === null || typeof command !== "string") {
    return;
  }
  if (command === "create") {
    openCreate(target);
    return;
  }
  if (command === "delete") {
    await runDelete(target.node);
  }
}

function openCreate(target: ContextMenuTarget): void {
  if (props.kind === "individual" && target.source === "tree") {
    createEntityType.value = "NAMED_INDIVIDUAL";
  } else {
    createEntityType.value = kindToEntityType(props.kind);
  }
  createParent.value = {
    iri: target.node.iri,
    label: target.node.displayName,
  };
  createOpen.value = true;
}

async function runDelete(node: EntityNode): Promise<void> {
  const items = [{ iri: node.iri, label: node.displayName }];
  const confirmed = await confirmDelete(items, t);
  if (!confirmed) {
    return;
  }
  try {
    await deleteEntities.mutateAsync({ iris: [node.iri] });
    if (entityIri.value === node.iri) {
      selectEntity(null);
    }
  } catch (error) {
    if (error instanceof ApiError && error.code === "PERMISSION_DENIED") {
      ElMessage.error(problemMessage(error, t));
      return;
    }
    ElMessage.error(problemMessage(error, t));
  }
}
</script>

<template>
  <el-dropdown
    ref="dropdownRef"
    :virtual-ref="virtualRef ?? undefined"
    virtual-triggering
    trigger="contextmenu"
    placement="bottom-start"
    :show-arrow="false"
    :tabindex="-1"
    role="menu"
    :aria-label="t('entities.menuLabel')"
    @command="onCommand"
    @visible-change="onVisibleChange"
  >
    <span class="entity-context-menu__anchor" />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="item in menuItems"
          :key="item.command"
          :command="item.command"
        >
          {{ item.label }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>

  <CreateEntitiesDialog
    v-model="createOpen"
    :project-id="projectId"
    :entity-type="createEntityType"
    :parent="createParent"
  />
</template>

<style scoped>
.entity-context-menu__anchor {
  position: fixed;
  width: 0;
  height: 0;
  overflow: hidden;
  pointer-events: none;
}
</style>
