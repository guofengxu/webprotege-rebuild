import type zhCN from "./zh-CN";

const enUS = {
  workspace: {
    hierarchyPanel: "Hierarchy",
    detailPanel: "Entity details",
    detailTab: "Details",
    projectNav: "Project navigation",
    revisionHistory: "Revision history",
    projectSettings: "Project settings",
    backToProjects: "Back to projects",
    loading: "Loading project…",
    notImplemented: "Not implemented yet",
    noEntitySelected: "Select an entity",
    entityIri: "Entity IRI",
    kinds: {
      class: "Classes",
      "object-property": "Object properties",
      "data-property": "Data properties",
      "annotation-property": "Annotation properties",
      individual: "Individuals",
    },
    errors: {
      trashed: "This project is in the trash and cannot be opened",
      forbidden: "You do not have permission to open this project",
      notFound: "Project not found",
    },
  },
} satisfies typeof zhCN;

export default enUS;
