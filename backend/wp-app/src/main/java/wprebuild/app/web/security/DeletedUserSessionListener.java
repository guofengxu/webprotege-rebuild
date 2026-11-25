package wprebuild.app.web.security;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import wprebuild.app.access.UserDeleted;

/**
 * Marks every in-process session for a soft-deleted user as expired and records the deleted flag in the
 * per-request cache so the next request returns 401 even when the registry had no entry (sessions created
 * before this process started).
 */
public class DeletedUserSessionListener {

    private static final Logger log = LoggerFactory.getLogger(DeletedUserSessionListener.class);

    private final SessionRegistry sessions;

    private final DeletedAccountFilter deletedAccounts;

    public DeletedUserSessionListener(SessionRegistry sessions, DeletedAccountFilter deletedAccounts) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.deletedAccounts = Objects.requireNonNull(deletedAccounts, "deleted accounts");
    }

    @EventListener
    public void onUserDeleted(UserDeleted event) {
        Objects.requireNonNull(event, "event");
        String userId = event.userId().value();
        int expired = 0;
        for (SessionInformation info : sessions.getAllSessions(userId, false)) {
            info.expireNow();
            expired++;
        }
        deletedAccounts.markDeleted(event.userId());
        log.info("expired {} session(s) for deleted user {}", expired, userId);
    }
}
