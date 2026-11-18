import type zhCN from "./zh-CN";

const enUS = {
  mergeEntities: {
    action: "Merge into…",
    title: "Merge entities",
    sourceLabel: "Source entity",
    targetLabel: "Target entity",
    targetAriaLabel: "Choose merge target entity",
    treatmentLabel: "Treatment",
    treatmentDeprecate: "Deprecate source entity",
    treatmentDeprecateHint:
      "Keep the source as deprecated and rewrite references to the target; the source remains findable in the hierarchy and search.",
    treatmentDelete: "Delete source entity",
    treatmentDeleteHint: "The source entity will disappear from the ontology.",
    descriptionLabel: "Description (optional)",
    revisionHint:
      "Merging creates a revision that can be reverted in revision history.",
    submit: "Merge",
    cancel: "Cancel",
    toolbarLabel: "Merge entities",
    errors: {
      ENTITY_NOT_FOUND: "Entity no longer exists; please refresh",
      EMPTY_SOURCES: "No source entities were specified",
      TARGET_IN_SOURCES: "The target cannot also be a source entity",
      BUILT_IN_ENTITY: "Built-in entities cannot be merged",
      TYPE_MISMATCH: "Source and target entity types do not match",
      INVALID_MERGE: "These entities cannot be merged",
    },
  },
} satisfies typeof zhCN;

export default enUS;
