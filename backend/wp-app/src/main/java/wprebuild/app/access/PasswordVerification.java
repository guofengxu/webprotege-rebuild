package wprebuild.app.access;

import java.util.Objects;

/** Result of checking a password. Missing accounts and wrong passwords share {@link Rejected}. */
public sealed interface PasswordVerification
        permits PasswordVerification.Accepted, PasswordVerification.Rejected, PasswordVerification.ResetRequired {

    record Accepted(UserAccount account) implements PasswordVerification {

        public Accepted {
            Objects.requireNonNull(account, "account");
        }
    }

    record Rejected() implements PasswordVerification {}

    /** The account exists and {@code password_hash} is empty. */
    record ResetRequired() implements PasswordVerification {}
}
