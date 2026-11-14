package wprebuild.app.events;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import wprebuild.app.access.AccessService;
import wprebuild.app.access.Action;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * Closes project event subscriptions for users who can no longer view the project.
 *
 * <p>Does not listen to Spring events itself. Callers invoke {@link #revoke(ProjectId)} after
 * publishing {@code PermissionsChanged}, without holding the generation lock or the hub lock.
 * {@link AccessService#can} may query the database, so it must stay outside those locks.
 *
 * <p>Events already queued for a subscriber (including the {@code PermissionsChanged} that
 * triggered revocation) may still be delivered before the disconnect runs on the delivery thread.
 * That short window is intentional.
 */
@Component
public final class SubscriptionRevoker {

    private final ProjectEventHub hub;

    private final AccessService access;

    public SubscriptionRevoker(ProjectEventHub hub, AccessService access) {
        this.hub = Objects.requireNonNull(hub, "hub");
        this.access = Objects.requireNonNull(access, "access");
    }

    public void revoke(ProjectId projectId) {
        Objects.requireNonNull(projectId, "project id");
        Set<UserId> subscribers = hub.subscribers(projectId);
        if (subscribers.isEmpty()) {
            return;
        }
        Set<UserId> revoked = new LinkedHashSet<>();
        for (UserId userId : subscribers) {
            if (!access.can(userId, projectId, Action.VIEW_PROJECT)) {
                revoked.add(userId);
            }
        }
        hub.disconnect(projectId, revoked);
    }
}
