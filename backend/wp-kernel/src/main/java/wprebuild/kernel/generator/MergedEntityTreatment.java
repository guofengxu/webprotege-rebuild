package wprebuild.kernel.generator;

/** What to do with each source entity after its usages have been rewritten onto the target (BS-13). */
public enum MergedEntityTreatment {
    DELETE_MERGED_ENTITY,
    DEPRECATE_MERGED_ENTITY
}
