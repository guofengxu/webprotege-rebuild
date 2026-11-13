import { ElMessageBox } from "element-plus";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { useI18n } from "vue-i18n";
import type { Ref } from "vue";

export function useUnsavedGuard(isDirty: Ref<boolean>): void {
  const { t } = useI18n();

  async function confirmLeave(): Promise<boolean> {
    if (!isDirty.value) {
      return true;
    }
    try {
      await ElMessageBox.confirm(
        t("framesCommon.unsavedMessage"),
        t("framesCommon.unsavedTitle"),
        {
          confirmButtonText: t("framesCommon.unsavedConfirm"),
          cancelButtonText: t("framesCommon.unsavedCancel"),
          type: "warning",
        },
      );
      return true;
    } catch {
      return false;
    }
  }

  onBeforeRouteLeave(() => confirmLeave());
  onBeforeRouteUpdate(() => confirmLeave());
}
