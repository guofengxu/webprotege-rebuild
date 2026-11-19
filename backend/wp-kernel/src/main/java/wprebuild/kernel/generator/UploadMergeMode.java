package wprebuild.kernel.generator;

/** How an uploaded ontology set is merged into the current project (BS-14). */
public enum UploadMergeMode {
    /** Pair by ontology IRI (or the single-ontology shortcut) and replace with the full diff. */
    NEW_VERSION,
    /** Append axioms and ontology annotations that the target does not already contain. */
    ADDITIONS
}
