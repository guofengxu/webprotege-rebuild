package wprebuild.kernel.form;

/**
 * How a form field maps onto the ontology (BS-18 first slice).
 *
 * <p>Only {@link OwlPropertyBinding} is writable for TEXT/NUMBER. Other binding kinds are carried as
 * {@link UnsupportedOwlBinding} so equal pristine/edited values can be ignored.
 */
public sealed interface OwlBinding permits OwlPropertyBinding, UnsupportedOwlBinding {}
