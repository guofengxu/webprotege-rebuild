package wprebuild.app.access;

/**
 * How an unbound external identity may be resolved. Callers set flags per identity provider; both
 * default to {@code false} so a successful login does not create or link accounts unless configured.
 */
public record ExternalLoginPolicy(boolean linkByVerifiedEmail, boolean autoProvision) {}
