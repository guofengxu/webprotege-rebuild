package wprebuild.kernel.form;

/**
 * Control kinds recognized by the first-slice form change generator (BS-18).
 *
 * <p>{@link #OTHER} covers GRID, SUB_FORM, IMAGE, choice controls, and any binding that is not yet
 * writable. Edited values for {@code OTHER} must equal pristine or the save is rejected.
 */
public enum FormControlType {
    TEXT,
    NUMBER,
    OTHER
}
