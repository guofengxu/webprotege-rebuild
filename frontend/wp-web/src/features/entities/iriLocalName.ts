/** Returns the local name of an IRI (fragment or last path segment). */
export function iriLocalName(iri: string): string {
  const hash = iri.lastIndexOf("#");
  if (hash >= 0 && hash < iri.length - 1) {
    return iri.slice(hash + 1);
  }
  const slash = iri.lastIndexOf("/");
  if (slash >= 0 && slash < iri.length - 1) {
    return iri.slice(slash + 1);
  }
  return iri;
}
