package wprebuild.app.access;

import java.util.Objects;

/**
 * Claims from an external identity provider after a successful login. {@code issuer} and {@code subject}
 * identify the binding; the other fields are used only when the login policy allows linking or
 * provisioning.
 */
public record ExternalIdentity(
        String issuer,
        String subject,
        String email,
        boolean emailVerified,
        String preferredUsername,
        String displayName) {

    public ExternalIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        if (issuer.isBlank()) {
            throw new IllegalArgumentException("issuer must not be blank");
        }
        if (subject.isBlank()) {
            throw new IllegalArgumentException("subject must not be blank");
        }
        if (email != null) {
            email = email.strip();
            if (email.isEmpty()) {
                email = null;
            }
        }
        if (preferredUsername != null) {
            preferredUsername = preferredUsername.strip();
            if (preferredUsername.isEmpty()) {
                preferredUsername = null;
            }
        }
        if (displayName != null) {
            displayName = displayName.strip();
            if (displayName.isEmpty()) {
                displayName = null;
            }
        }
    }
}
