import type { FeatureRegistration } from "@/features/workspace/registry";
import ClassFrameEditor from "./ClassFrameEditor.vue";

export const registration: FeatureRegistration = {
  detailEditors: {
    CLASS: ClassFrameEditor,
  },
};
