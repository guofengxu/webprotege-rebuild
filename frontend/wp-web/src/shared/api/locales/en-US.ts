import type zhCN from "./zh-CN";

const enUS = {
  errors: {
    VALIDATION_FAILED: "The submitted data failed validation",
    PERMISSION_DENIED: "You do not have permission for this action",
    NOT_FOUND: "The requested item was not found",
    CONFLICT: "The action conflicts with the current state",
    PROJECT_TRASHED: "The project is in the trash",
    FRAME_CHANGED: "This entity was changed somewhere else",
    LAST_MANAGER: "The last project manager cannot be removed",
    LAST_SYSTEM_ADMIN: "The last system administrator cannot be removed",
    UPLOAD_TOO_LARGE: "The upload exceeds the size limit",
    ONTOLOGY_PARSE_FAILED: "The ontology file could not be parsed",
    PASSWORD_RESET_REQUIRED: "Reset the password before signing in",
    CONCURRENT_APPEND: "Someone else saved at the same time; try again",
    INTERNAL_ERROR: "An internal server error occurred",
    http: {
      "401": "Your session has expired; sign in again",
      "403": "You do not have permission for this action",
      "404": "The requested item was not found",
      "409": "The action conflicts with the current state",
      "413": "The upload exceeds the size limit",
      "500": "An internal server error occurred",
    },
    network: "The network request failed; try again",
    unknown: "An unknown error occurred",
  },
} satisfies typeof zhCN;

export default enUS;
