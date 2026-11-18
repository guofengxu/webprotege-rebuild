const zhCN = {
  mergeEntities: {
    action: "合并到…",
    title: "合并实体",
    sourceLabel: "源实体",
    targetLabel: "目标实体",
    targetAriaLabel: "选择合并目标实体",
    treatmentLabel: "处理方式",
    treatmentDeprecate: "弃用源实体",
    treatmentDeprecateHint:
      "源实体保留为已弃用，其引用改写到目标；可在层级与搜索中找到源实体。",
    treatmentDelete: "删除源实体",
    treatmentDeleteHint: "源实体将从本体中消失。",
    descriptionLabel: "说明（可选）",
    revisionHint: "合并会产生一个修订，可以在修订历史中回滚。",
    submit: "合并",
    cancel: "取消",
    toolbarLabel: "合并实体",
    errors: {
      ENTITY_NOT_FOUND: "实体已不存在，请刷新",
      EMPTY_SOURCES: "未指定要合并的源实体",
      TARGET_IN_SOURCES: "目标实体不能同时作为源实体",
      BUILT_IN_ENTITY: "不能合并内置实体",
      TYPE_MISMATCH: "源与目标的实体类型不一致",
      INVALID_MERGE: "无法合并这些实体",
    },
  },
};

export default zhCN;
