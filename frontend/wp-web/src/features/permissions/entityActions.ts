import type { WorkspaceKind } from "@/features/workspace/hierarchyKinds";
import type { ProjectAction } from "./useProjectPermissions";

export function createActionForKind(kind: WorkspaceKind): ProjectAction {
  if (kind === "class") {
    return "CREATE_CLASS";
  }
  if (kind === "individual") {
    return "CREATE_INDIVIDUAL";
  }
  return "CREATE_PROPERTY";
}

export function deleteActionForKind(kind: WorkspaceKind): ProjectAction {
  if (kind === "class") {
    return "DELETE_CLASS";
  }
  if (kind === "individual") {
    return "DELETE_INDIVIDUAL";
  }
  return "DELETE_PROPERTY";
}
