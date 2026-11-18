const OWL = "http://www.w3.org/2002/07/owl#";
const RDFS = "http://www.w3.org/2000/01/rdf-schema#";

/** OWL / RDFS entities that OWL API treats as built-in (cannot be merge sources/targets). */
const BUILT_IN_ENTITY_IRIS = new Set<string>([
  `${OWL}Thing`,
  `${OWL}Nothing`,
  `${OWL}topObjectProperty`,
  `${OWL}bottomObjectProperty`,
  `${OWL}topDataProperty`,
  `${OWL}bottomDataProperty`,
  `${OWL}versionInfo`,
  `${OWL}backwardCompatibleWith`,
  `${OWL}priorVersion`,
  `${OWL}incompatibleWith`,
  `${OWL}deprecated`,
  `${RDFS}label`,
  `${RDFS}comment`,
  `${RDFS}seeAlso`,
  `${RDFS}isDefinedBy`,
]);

export function isBuiltInEntityIri(iri: string): boolean {
  return BUILT_IN_ENTITY_IRIS.has(iri);
}
