import type { FeatureRegistration } from "@/features/workspace/registry";
import NamedIndividualFrameEditor from "./NamedIndividualFrameEditor.vue";

export const registration: FeatureRegistration = {
  detailEditors: {
    NAMED_INDIVIDUAL: NamedIndividualFrameEditor,
  },
};
