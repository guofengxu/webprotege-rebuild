const zhCN = {
  errors: {
    VALIDATION_FAILED: "提交的内容没有通过校验",
    PERMISSION_DENIED: "没有执行此操作的权限",
    NOT_FOUND: "找不到请求的内容",
    CONFLICT: "操作与当前状态冲突",
    PROJECT_TRASHED: "项目已在回收站中",
    FRAME_CHANGED: "该实体已被其他人修改",
    LAST_MANAGER: "不能移除项目的最后一位管理者",
    LAST_SYSTEM_ADMIN: "不能移除最后一位系统管理员",
    UPLOAD_TOO_LARGE: "上传的文件超过大小限制",
    ONTOLOGY_PARSE_FAILED: "无法解析本体文件",
    PASSWORD_RESET_REQUIRED: "需要先重置密码才能登录",
    CONCURRENT_APPEND: "有其他人同时提交了修改，请重试",
    INTERNAL_ERROR: "服务器内部错误",
    http: {
      "401": "登录已失效，请重新登录",
      "403": "没有执行此操作的权限",
      "404": "找不到请求的内容",
      "409": "操作与当前状态冲突",
      "413": "上传的文件超过大小限制",
      "500": "服务器内部错误",
    },
    network: "网络连接失败，请稍后重试",
    unknown: "发生了未知错误",
  },
};

export default zhCN;
