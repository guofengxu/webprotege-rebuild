const enUS = {
  framesProperty: {
    annotations: "Annotations",
    parents: "Parents",
    domains: "Domains",
    ranges: "Ranges",
    characteristics: "Characteristics",
    inverses: "Inverses",
    otherAxioms: "Other axioms",
    functional: "Functional",
    functionalHint: "Each subject has at most one value for this property.",
    loading: "Loading…",
    saveSuccess: "Saved as revision {revision}",
    iriPlaceholder: "Enter an absolute IRI and press Enter",
    iriInvalid: "Invalid IRI: must be an absolute IRI (with a scheme)",
    iriRemove: "Remove {iri}",
    iriEmpty: "No IRIs yet",
    characteristic: {
      FUNCTIONAL: "Functional — each subject has at most one value",
      INVERSE_FUNCTIONAL:
        "Inverse functional — each value has at most one subject",
      TRANSITIVE: "Transitive — if aRb and bRc then aRc",
      SYMMETRIC: "Symmetric — if aRb then bRa",
      ASYMMETRIC: "Asymmetric — if aRb then not bRa",
      REFLEXIVE: "Reflexive — every individual relates to itself",
      IRREFLEXIVE: "Irreflexive — no individual relates to itself",
    },
  },
};

export default enUS;
