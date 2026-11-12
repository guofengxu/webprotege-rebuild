import type zhCN from "./zh-CN";

const enUS = {
  search: {
    inputLabel: "Search entities",
    shortcutCtrl: "Ctrl+K",
    shortcutMeta: "⌘K",
    placeholder: "Search classes, properties, individuals…",
    loading: "Searching…",
    noResults: "No matching entities",
    displayNameHint:
      "By default only language-tag-free rdfs:label values are indexed. Labels in other languages are searchable only after you add them in the project's display-name settings.",
    groupClass: "Classes",
    groupObjectProperty: "Object properties",
    groupDataProperty: "Data properties",
    groupAnnotationProperty: "Annotation properties",
    groupIndividual: "Individuals",
    groupDatatype: "Datatypes",
    deprecated: "deprecated",
    notNavigable: "not navigable",
  },
} satisfies typeof zhCN;

export default enUS;
