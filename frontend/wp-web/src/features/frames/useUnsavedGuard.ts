import { ElMessageBox } from "element-plus";
import { onBeforeRouteLeave, onBeforeRouteUpdate } from "vue-router";
import { useI18n } from "vue-i18n";
import { toValue, type MaybeRefOrGetter } from "vue";

export function useUnsavedGuard(
  isDirty: MaybeRefOrGetter<boolean>,
  readOnly: MaybeRefOrGetter<boolean> = false,
): void {
  const { t } = useI18n();

  async function confirmLeave(): Promise<boolean> {
    if (toValue(readOnly) || !toValue(isDirty)) {
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
