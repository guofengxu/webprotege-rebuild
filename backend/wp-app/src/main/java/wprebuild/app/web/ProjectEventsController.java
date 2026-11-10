package wprebuild.app.web;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.json.JsonMapper;
import wprebuild.app.bus.CommandBus;
import wprebuild.app.events.GetProjectEvents;
import wprebuild.app.events.ProjectEvent;
import wprebuild.app.events.ProjectEventHub;
import wprebuild.app.events.ResyncSignal;
import wprebuild.kernel.id.ProjectId;
import wprebuild.kernel.id.UserId;

/**
 * SSE and long-poll access to one project's high-level events (ADR-007, BS-11).
 *
 * <p>{@link CommandBus} checks {@code VIEW_PROJECT}. A non-positive SSE timeout means the servlet
 * container keeps the stream open; the hub writes a comment line every 25 seconds.
 */
@RestController
@RequestMapping("/api/projects/{id}/events")
public class ProjectEventsController {

    private static final Logger log = LoggerFactory.getLogger(ProjectEventsController.class);

    private static final int DEFAULT_TIMEOUT_SECONDS = 25;

    private static final int MAX_TIMEOUT_SECONDS = 30;

    private final CommandBus bus;

    private final ProjectEventHub hub;

    private final JsonMapper json;

    public ProjectEventsController(CommandBus bus, ProjectEventHub hub, JsonMapper json) {
        this.bus = Objects.requireNonNull(bus, "bus");
        this.hub = Objects.requireNonNull(hub, "hub");
        this.json = Objects.requireNonNull(json, "json");
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Object stream(
            @PathVariable String id, @RequestHeader(name = "Last-Event-ID", required = false) String lastEventId) {
        return withProject(id, (user, projectId) -> open(user, projectId, cursor(lastEventId)));
    }

    @GetMapping(path = "/poll", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object poll(
            @PathVariable String id,
            @RequestParam("after") long after,
            @RequestParam(name = "timeout", defaultValue = "" + DEFAULT_TIMEOUT_SECONDS) int timeout) {
        return withProject(id, (user, projectId) -> poll(user, projectId, after, timeout));
    }

    private SseEmitter open(UserId user, ProjectId projectId, OptionalLong after) {
        bus.execute(user, new GetProjectEvents(projectId));
        SseEmitter emitter = new SseEmitter(0L);
        SseSession session = new SseSession(emitter);
        ProjectEventHub.Subscription subscription = hub.subscribe(projectId, after, session);
        session.attach(subscription);
        emitter.onCompletion(session::close);
        emitter.onTimeout(session::close);
        emitter.onError(error -> session.close());
        try {
            session.start(subscription.backlog());
        } catch (RuntimeException ex) {
            session.close();
            throw ex;
        }
        return emitter;
    }

    private Object poll(UserId user, ProjectId projectId, long after, int timeout) {
        if (after < 0) {
            throw new IllegalArgumentException("after must not be negative");
        }
        int seconds = timeoutSeconds(timeout);
        bus.execute(user, new GetProjectEvents(projectId));
        if (seconds == 0) {
            return ResponseEntity.ok(body(hub.catchup(projectId, after)));
        }
        DeferredResult<List<Object>> result = new DeferredResult<>(seconds * 1000L, List.of());
        ProjectEventHub.PollTicket ticket = hub.park(projectId, after, delivery -> result.setResult(body(delivery)));
        if (ticket != null) {
            result.onTimeout(() -> hub.cancel(ticket));
            result.onCompletion(() -> hub.cancel(ticket));
        }
        return result;
    }

    private List<Object> body(ProjectEventHub.Delivery delivery) {
        if (delivery.resync()) {
            return List.<Object>of(ResyncSignal.INSTANCE);
        }
        List<Object> events = new ArrayList<>();
        events.addAll(delivery.events());
        return events;
    }

    private Object withProject(String id, ProjectAction action) {
        UserId user = currentUser();
        if (user == null) {
            return ApiProblems.of(ApiProblems.authenticationRequired());
        }
        final ProjectId projectId;
        try {
            projectId = ProjectId.of(id);
        } catch (IllegalArgumentException ex) {
            return invalid("project id is not a UUID");
        }
        try {
            return action.run(user, projectId);
        } catch (IllegalArgumentException ex) {
            return invalid(ex.getMessage());
        }
    }

    private static int timeoutSeconds(int timeout) {
        if (timeout < 0) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        return Math.min(timeout, MAX_TIMEOUT_SECONDS);
    }

    private static OptionalLong cursor(String header) {
        if (header == null || header.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long value = Long.parseLong(header.strip());
            if (value < 0) {
                throw new IllegalArgumentException("Last-Event-ID must be a revision number");
            }
            return OptionalLong.of(value);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("Last-Event-ID must be a revision number");
        }
    }

    private static UserId currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return null;
        }
        try {
            return new UserId(authentication.getName());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static ResponseEntity<ProblemDetail> invalid(String detail) {
        ProblemDetail problem = ApiProblems.badRequest(detail);
        problem.setProperty("code", ProblemDetailsAdvice.VALIDATION_FAILED);
        return ApiProblems.of(problem);
    }

    @FunctionalInterface
    private interface ProjectAction {
        Object run(UserId user, ProjectId projectId);
    }

    private final class SseSession implements ProjectEventHub.LiveSink {

        private final SseEmitter emitter;

        private final Deque<ProjectEventHub.Delivery> queued = new ArrayDeque<>();

        private final Object writeLock = new Object();

        private final AtomicBoolean open = new AtomicBoolean(true);

        private boolean live;

        private ProjectEventHub.Subscription subscription;

        private SseSession(SseEmitter emitter) {
            this.emitter = emitter;
        }

        private void attach(ProjectEventHub.Subscription subscription) {
            this.subscription = subscription;
        }

        private void start(ProjectEventHub.Delivery backlog) {
            emit(backlog);
            while (true) {
                ProjectEventHub.Delivery next;
                synchronized (queued) {
                    next = queued.poll();
                    if (next == null) {
                        live = true;
                        return;
                    }
                }
                emit(next);
            }
        }

        @Override
        public void onEvents(ProjectEventHub.Delivery delivery) {
            synchronized (queued) {
                if (!live) {
                    queued.add(delivery);
                    return;
                }
            }
            emit(delivery);
        }

        @Override
        public void onShutdown() {
            close();
        }

        @Override
        public void onHeartbeat() {
            synchronized (writeLock) {
                if (!open.get()) {
                    return;
                }
                try {
                    emitter.send(SseEmitter.event().comment("heartbeat"));
                } catch (Exception ex) {
                    log.debug("sse heartbeat stopped because the client is gone");
                    close();
                }
            }
        }

        private void emit(ProjectEventHub.Delivery delivery) {
            if (delivery == null || (!delivery.resync() && delivery.events().isEmpty())) {
                return;
            }
            synchronized (writeLock) {
                if (!open.get()) {
                    return;
                }
                try {
                    if (delivery.resync()) {
                        emitter.send(
                                SseEmitter.event().name("resync").data(json.writeValueAsString(ResyncSignal.INSTANCE)));
                        return;
                    }
                    for (ProjectEvent event : delivery.events()) {
                        emitter.send(SseEmitter.event()
                                .id(Long.toString(event.revNo()))
                                .name(event.type())
                                .data(json.writeValueAsString(event)));
                    }
                } catch (Exception ex) {
                    log.warn("sse send failed", ex);
                    close();
                }
            }
        }

        private void close() {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            if (subscription != null) {
                subscription.close();
            }
            try {
                emitter.complete();
            } catch (RuntimeException ex) {
                log.debug("sse emitter already completed", ex);
            }
        }
    }
}
