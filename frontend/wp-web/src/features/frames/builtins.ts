import type { EntityNode } from "@wp/api-types";

const XSD = "http://www.w3.org/2001/XMLSchema#";
const RDF = "http://www.w3.org/1999/02/22-rdf-syntax-ns#";
const RDFS = "http://www.w3.org/2000/01/rdf-schema#";
const OWL = "http://www.w3.org/2002/07/owl#";
const SKOS = "http://www.w3.org/2004/02/skos/core#";

export const RDF_LANG_STRING = `${RDF}langString`;

function datatype(localName: string, iri: string): EntityNode {
  return {
    iri,
    entityType: "DATATYPE",
    displayName: localName,
    deprecated: false,
    hasChildren: false,
  };
}

function annotationProperty(localName: string, iri: string): EntityNode {
  return {
    iri,
    entityType: "ANNOTATION_PROPERTY",
    displayName: localName,
    deprecated: false,
    hasChildren: false,
  };
}

export const BUILTIN_DATATYPES: readonly EntityNode[] = Object.freeze([
  datatype("xsd:string", `${XSD}string`),
  datatype("rdf:langString", RDF_LANG_STRING),
  datatype("xsd:boolean", `${XSD}boolean`),
  datatype("xsd:integer", `${XSD}integer`),
  datatype("xsd:decimal", `${XSD}decimal`),
  datatype("xsd:double", `${XSD}double`),
  datatype("xsd:dateTime", `${XSD}dateTime`),
  datatype("xsd:anyURI", `${XSD}anyURI`),
]);

export const DEFAULT_DATATYPE = BUILTIN_DATATYPES[0]!;

export const BUILTIN_ANNOTATION_PROPERTIES: readonly EntityNode[] = Object.freeze([
  annotationProperty("rdfs:label", `${RDFS}label`),
  annotationProperty("rdfs:comment", `${RDFS}comment`),
  annotationProperty("rdfs:seeAlso", `${RDFS}seeAlso`),
  annotationProperty("rdfs:isDefinedBy", `${RDFS}isDefinedBy`),
  annotationProperty("owl:deprecated", `${OWL}deprecated`),
  annotationProperty("skos:prefLabel", `${SKOS}prefLabel`),
  annotationProperty("skos:altLabel", `${SKOS}altLabel`),
  annotationProperty("skos:definition", `${SKOS}definition`),
]);

export const RDFS_LABEL = BUILTIN_ANNOTATION_PROPERTIES[0]!;
