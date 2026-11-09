package wprebuild.app.ontology.frame;

/** Supported class-frame restrictions. Anything else stays in {@code otherAxioms}. */
public enum PropertyValueKind {
    /** {@code SubClassOf(C ObjectSomeValuesFrom(p D))}. */
    SOME,

    /** {@code ObjectHasValue} or {@code DataHasValue}. */
    HAS_VALUE,

    /** {@code DataSomeValuesFrom(p datatype)}. */
    DATA_SOME
}
