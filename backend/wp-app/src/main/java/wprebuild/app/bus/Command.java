package wprebuild.app.bus;

/**
 * A command or query dispatched by {@link CommandBus}.
 *
 * <p>Queries use this type as well and are named {@code Get*} or {@code List*} (ADR-007). The bus
 * selects the handler by the command's runtime class.
 */
public interface Command<R> {}
