import { defineStore } from "pinia";
import { ref, watch } from "vue";

const LAYOUT_STORAGE_KEY = "wp.workspace.layout";

export type WorkspaceLayout = {
  readonly leftSize: number | string;
  readonly leftCollapsed: boolean;
};

const DEFAULT_LAYOUT: WorkspaceLayout = {
  leftSize: "30%",
  leftCollapsed: false,
};

export const useWorkspaceStore = defineStore("workspace", () => {
  const stored = readStoredLayout();
  const leftSize = ref(stored.leftSize);
  const leftCollapsed = ref(stored.leftCollapsed);

  watch([leftSize, leftCollapsed], () => {
    writeStoredLayout({
      leftSize: leftSize.value,
      leftCollapsed: leftCollapsed.value,
    });
  });

  function setLeftSize(size: number | string): void {
    leftSize.value = size;
  }

  function setLeftCollapsed(collapsed: boolean): void {
    leftCollapsed.value = collapsed;
  }

  return {
    leftSize,
    leftCollapsed,
    setLeftSize,
    setLeftCollapsed,
  };
});

function readStoredLayout(): WorkspaceLayout {
  try {
    if (typeof localStorage === "undefined") {
      return DEFAULT_LAYOUT;
    }
    const raw = localStorage.getItem(LAYOUT_STORAGE_KEY);
    if (raw === null) {
      return DEFAULT_LAYOUT;
    }
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
      return DEFAULT_LAYOUT;
    }
    const record = parsed as Record<string, unknown>;
    const leftSize = parseLeftSize(record.leftSize);
    const leftCollapsed =
      typeof record.leftCollapsed === "boolean"
        ? record.leftCollapsed
        : DEFAULT_LAYOUT.leftCollapsed;
    return { leftSize, leftCollapsed };
  } catch {
    return DEFAULT_LAYOUT;
  }
}

function writeStoredLayout(layout: WorkspaceLayout): void {
  try {
    if (typeof localStorage !== "undefined") {
      localStorage.setItem(LAYOUT_STORAGE_KEY, JSON.stringify(layout));
    }
  } catch {
    // Private mode / quota — keep the in-memory layout.
  }
}

function parseLeftSize(value: unknown): number | string {
  if (typeof value === "number" && Number.isFinite(value) && value > 0) {
    return value;
  }
  if (typeof value === "string" && value.trim().length > 0) {
    return value;
  }
  return DEFAULT_LAYOUT.leftSize;
}
