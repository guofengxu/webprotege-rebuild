import type { FeatureRegistration } from "@/features/workspace/registry";
import AnnotationPropertyFrameEditor from "./AnnotationPropertyFrameEditor.vue";
import DataPropertyFrameEditor from "./DataPropertyFrameEditor.vue";
import ObjectPropertyFrameEditor from "./ObjectPropertyFrameEditor.vue";

export const registration: FeatureRegistration = {
  detailEditors: {
    OBJECT_PROPERTY: ObjectPropertyFrameEditor,
    DATA_PROPERTY: DataPropertyFrameEditor,
    ANNOTATION_PROPERTY: AnnotationPropertyFrameEditor,
  },
};
