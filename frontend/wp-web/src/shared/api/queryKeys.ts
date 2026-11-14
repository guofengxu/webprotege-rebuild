export const queryKeys = {
  me() {
    return ["auth", "me"] as const;
  },
  projects() {
    return ["projects", "active"] as const;
  },
  trashedProjects() {
    return ["projects", "trash"] as const;
  },
  project(id: string) {
    return ["project", id] as const;
  },
  projectDetails(id: string) {
    return [...queryKeys.project(id), "details"] as const;
  },
  hierarchy(id: string, kind: string) {
    return [...queryKeys.project(id), "hierarchy", kind] as const;
  },
  hierarchyRoots(id: string, kind: string) {
    return [...queryKeys.hierarchy(id, kind), "roots"] as const;
  },
  hierarchyChildren(id: string, kind: string, parentIri: string) {
    return [...queryKeys.hierarchy(id, kind), "children", parentIri] as const;
  },
  hierarchyPathsAll(id: string, kind: string) {
    return [...queryKeys.hierarchy(id, kind), "paths"] as const;
  },
  hierarchyPaths(id: string, kind: string, entityIri: string) {
    return [...queryKeys.hierarchyPathsAll(id, kind), entityIri] as const;
  },
  frames(id: string) {
    return [...queryKeys.project(id), "frames"] as const;
  },
  frame(id: string, entityType: string, iri: string) {
    return [...queryKeys.frames(id), entityType, iri] as const;
  },
  individualsAll(id: string) {
    return [...queryKeys.project(id), "individuals"] as const;
  },
  individuals(id: string, typeIri: string, page: number, size: number) {
    return [...queryKeys.individualsAll(id), typeIri, page, size] as const;
  },
  searchAll(id: string) {
    return [...queryKeys.project(id), "search"] as const;
  },
  search(id: string, q: string, types: readonly string[], limit: number) {
    return [...queryKeys.searchAll(id), q, [...types], limit] as const;
  },
  revisionsAll(id: string) {
    return [...queryKeys.project(id), "revisions"] as const;
  },
  revisions(id: string, page: number, size: number) {
    return [...queryKeys.revisionsAll(id), page, size] as const;
  },
  revisionDiff(id: string, rev: number, limit: number) {
    return [...queryKeys.revisionsAll(id), "diff", rev, limit] as const;
  },
  entityHistoryAll(id: string, iri: string) {
    return [...queryKeys.project(id), "history", iri] as const;
  },
  entityHistory(id: string, iri: string, page: number, size: number) {
    return [...queryKeys.entityHistoryAll(id, iri), page, size] as const;
  },
  settings(id: string, key: string) {
    return [...queryKeys.project(id), "settings", key] as const;
  },
  members(id: string) {
    return [...queryKeys.project(id), "members"] as const;
  },
  myPermissions(id: string) {
    return [...queryKeys.project(id), "permissions", "me"] as const;
  },
  apiKeys() {
    return ["account", "apiKeys"] as const;
  },
  devices() {
    return ["account", "devices"] as const;
  },
  adminUsersAll() {
    return ["admin", "users"] as const;
  },
  adminUsers(page: number, size: number) {
    return [...queryKeys.adminUsersAll(), page, size] as const;
  },
};
