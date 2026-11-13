const zhCN = {
  members: {
    tab: "成员",
    userId: "用户 ID",
    role: "角色",
    actions: "操作",
    add: "添加",
    addMember: "添加成员",
    remove: "移除",
    empty: "暂无成员",
    tableLabel: "项目成员",
    loading: "加载中…",
    loadError: "无法加载成员列表",
    retry: "重试",
    forbidden: "需要共享权限才能查看成员",
    userIdHint: "请输入用户 ID（系统不提供按名称搜索）",
    userIdRequired: "请输入用户 ID",
    alreadyMember: "该用户已在成员列表中，请直接修改其角色",
    userNotFound: "用户 ID 不存在",
    lastManager: "项目至少需要一位管理者",
    addSuccess: "已添加成员",
    removeSuccess: "已移除成员",
    roleUpdated: "角色已更新",
    removeTitle: "移除成员",
    removeMessage: "确定将 {userId} 移出本项目吗？",
    removeConfirm: "移除",
    cancel: "取消",
    selfManageTitle: "修改自己的管理权限",
    selfManageRoleMessage:
      "你正在降低或取消自己的管理者角色，之后可能无法管理本项目成员。是否继续？",
    selfManageRemoveMessage:
      "你正在移除自己。若你是管理者，之后可能无法再管理本项目。是否继续？",
    selfManageConfirm: "继续",
    roles: {
      CAN_VIEW: {
        name: "查看",
        description: "可以查看项目内容",
      },
      CAN_COMMENT: {
        name: "评论",
        description: "可以查看；评论功能预留",
      },
      CAN_EDIT: {
        name: "编辑",
        description: "可以编辑本体内容",
      },
      CAN_MANAGE: {
        name: "管理",
        description: "可以修改项目设置与成员",
      },
    },
  },
};

export default zhCN;
