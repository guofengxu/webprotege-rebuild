import { ElMessageBox } from "element-plus";
import { h } from "vue";
import type { ComposerTranslation } from "vue-i18n";

const PREVIEW_LIMIT = 10;

export type DeleteEntityItem = {
  readonly iri: string;
  readonly label: string;
};

/**
 * Shows a danger-styled confirm dialog listing entity labels.
 * Returns true when the user confirms; false when cancelled.
 */
export async function confirmDelete(
  items: ReadonlyArray<DeleteEntityItem>,
  t: ComposerTranslation,
): Promise<boolean> {
  if (items.length === 0) {
    return false;
  }
  const preview = items.slice(0, PREVIEW_LIMIT).map((item) => item.label);
  const lines = [...preview];
  if (items.length > PREVIEW_LIMIT) {
    lines.push(t("entities.deleteConfirmMore", { total: items.length }));
  }
  const message = h(
    "div",
    { style: { whiteSpace: "pre-line" } },
    t("entities.deleteConfirmMessage", { list: lines.join("\n") }),
  );
  try {
    await ElMessageBox.confirm(
      message,
      t("entities.deleteConfirmTitle"),
      {
        type: "warning",
        confirmButtonText: t("entities.deleteConfirmButton"),
        cancelButtonText: t("entities.cancel"),
        confirmButtonClass: "el-button--danger",
      },
    );
    return true;
  } catch {
    return false;
  }
}
