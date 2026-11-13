const enUS = {
  members: {
    tab: "Members",
    userId: "User ID",
    role: "Role",
    actions: "Actions",
    add: "Add",
    addMember: "Add member",
    remove: "Remove",
    empty: "No members yet",
    tableLabel: "Project members",
    loading: "Loading…",
    loadError: "Could not load members",
    retry: "Retry",
    forbidden: "Sharing permission is required to view members",
    userIdHint: "Enter a user ID (name search is not available)",
    userIdRequired: "Enter a user ID",
    alreadyMember: "That user is already a member; change their role in the table",
    userNotFound: "User ID not found",
    lastManager: "The project must keep at least one manager",
    addSuccess: "Member added",
    removeSuccess: "Member removed",
    roleUpdated: "Role updated",
    removeTitle: "Remove member",
    removeMessage: "Remove {userId} from this project?",
    removeConfirm: "Remove",
    cancel: "Cancel",
    selfManageTitle: "Change your own management role",
    selfManageRoleMessage:
      "You are reducing or removing your manager role. You may lose the ability to manage members. Continue?",
    selfManageRemoveMessage:
      "You are removing yourself. If you are a manager, you may lose access to manage this project. Continue?",
    selfManageConfirm: "Continue",
    roles: {
      CAN_VIEW: {
        name: "View",
        description: "Can view project content",
      },
      CAN_COMMENT: {
        name: "Comment",
        description: "Can view; commenting is reserved for later",
      },
      CAN_EDIT: {
        name: "Edit",
        description: "Can edit ontology content",
      },
      CAN_MANAGE: {
        name: "Manage",
        description: "Can change project settings and members",
      },
    },
  },
};

export default enUS;
