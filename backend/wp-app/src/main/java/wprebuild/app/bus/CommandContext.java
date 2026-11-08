package wprebuild.app.bus;

import java.util.Objects;
import wprebuild.kernel.id.UserId;

/** The caller already authenticated by the web layer (ADR-007). */
public record CommandContext(UserId user) {

    public CommandContext {
        Objects.requireNonNull(user, "user");
    }
}
