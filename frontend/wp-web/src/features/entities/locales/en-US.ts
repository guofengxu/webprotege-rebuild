import type zhCN from "./zh-CN";

const enUS = {
  entities: {
    create: "Create",
    delete: "Delete",
    createChildClass: "Create subclass",
    createChildProperty: "Create sub-property",
    createIndividual: "Create individual",
    createTitle: "Create entities",
    namesLabel: "Names (one per line)",
    namesHint:
      "Up to 100 names after trimming blanks and empty lines; duplicates are removed.",
    langLabel: "Language tag",
    parentLabel: "Create under: {label}",
    parentRoot: "Create under: top level",
    submit: "Create",
    cancel: "Cancel",
    namesRequired: "Enter at least one name",
    namesTooMany: "At most 100 entities can be created at once",
    deleteConfirmTitle: "Delete entities",
    deleteConfirmMessage:
      "Delete the following entities? This cannot be undone.\n{list}",
    deleteConfirmMore: "…{total} in total",
    deleteConfirmButton: "Delete",
    menuLabel: "Entity actions",
  },
} satisfies typeof zhCN;

export default enUS;
