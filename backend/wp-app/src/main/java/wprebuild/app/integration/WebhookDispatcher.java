package wprebuild.app.integration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import wprebuild.app.integration.WebhookUrlPolicy.Result;

/**
 * Polls and sends claimed webhook deliveries with per-webhook ordering, timeouts, HMAC, and
 * exponential backoff (P6-21). HTTP runs outside DB transactions.
 */
@Component
@ConditionalOnProperty(prefix = "wp.webhooks", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WebhookDispatcher implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private static final String USER_AGENT = "webprotege-rebuild-webhook";

    private static final int MAX_ERROR_BODY_BYTES = 1024;

    private final WebhookProperties properties;

    private final JdbcWebhookRepository webhooks;

    private final WebhookUrlPolicy urlPolicy;

    private final TransactionTemplate transactions;

    private final HttpClient http;

    private final ExecutorService httpExecutor;

    private final ScheduledExecutorService scheduler;

    private final ExecutorService workers;

    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean wakeScheduled = new AtomicBoolean(false);

    private final AtomicBoolean wakeAgain = new AtomicBoolean(false);

    private final AtomicBoolean stopped = new AtomicBoolean(false);

    private final AtomicLong lastPurgeMillis = new AtomicLong(0);

    private volatile ScheduledFuture<?> periodic;

    @Autowired
    WebhookDispatcher(
            WebhookProperties properties,
            JdbcWebhookRepository webhooks,
            WebhookUrlPolicy urlPolicy,
            PlatformTransactionManager transactionManager) {
        this(properties, webhooks, urlPolicy, transactionManager, true);
    }

    /** Test constructor: optional auto-start of the poller. */
    WebhookDispatcher(
            WebhookProperties properties,
            JdbcWebhookRepository webhooks,
            WebhookUrlPolicy urlPolicy,
            PlatformTransactionManager transactionManager,
            boolean start) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.webhooks = Objects.requireNonNull(webhooks, "webhooks");
        this.urlPolicy = Objects.requireNonNull(urlPolicy, "url policy");
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transaction manager"));
        this.httpExecutor = Executors.newVirtualThreadPerTaskExecutor();
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .executor(httpExecutor)
                .build();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().daemon(true).name("wp-webhook-dispatcher").factory());
        this.workers = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("wp-webhook-send-", 0).factory());
        if (start && properties.enabled()) {
            long period = properties.pollInterval().toMillis();
            periodic = scheduler.scheduleWithFixedDelay(this::safePoll, period, period, TimeUnit.MILLISECONDS);
        }
    }

    /** Non-blocking wake; concurrent calls coalesce. */
    public void wake() {
        if (!properties.enabled() || stopped.get()) {
            return;
        }
        if (wakeScheduled.compareAndSet(false, true)) {
            try {
                scheduler.execute(this::runWake);
            } catch (RuntimeException ex) {
                wakeScheduled.set(false);
                log.warn("could not schedule webhook wake", ex);
            }
        } else {
            wakeAgain.set(true);
        }
    }

    private void runWake() {
        try {
            do {
                wakeAgain.set(false);
                safePoll();
            } while (wakeAgain.compareAndSet(true, false));
        } finally {
            wakeScheduled.set(false);
            if (wakeAgain.get() && wakeScheduled.compareAndSet(false, true)) {
                try {
                    scheduler.execute(this::runWake);
                } catch (RuntimeException ex) {
                    wakeScheduled.set(false);
                    log.warn("could not reschedule webhook wake", ex);
                }
            }
        }
    }

    private void safePoll() {
        if (stopped.get()) {
            return;
        }
        try {
            pollOnce();
            maybePurge();
        } catch (RuntimeException ex) {
            log.warn("webhook dispatcher poll failed", ex);
        }
    }

    private void pollOnce() {
        int slots = properties.maxConcurrency() - inFlight.size();
        if (slots < 1) {
            return;
        }
        Instant leaseUntil = Instant.now().plus(properties.lease());
        List<ClaimedWebhookDelivery> claimed =
                transactions.execute(status -> webhooks.claimDue(slots, Set.copyOf(inFlight), leaseUntil));
        if (claimed == null || claimed.isEmpty()) {
            return;
        }
        for (ClaimedWebhookDelivery delivery : claimed) {
            if (!inFlight.add(delivery.webhookId())) {
                continue;
            }
            try {
                workers.execute(() -> {
                    try {
                        deliver(delivery);
                    } finally {
                        inFlight.remove(delivery.webhookId());
                        wake();
                    }
                });
            } catch (RuntimeException ex) {
                inFlight.remove(delivery.webhookId());
                log.warn(
                        "could not submit webhook send deliveryId={} webhookId={}",
                        delivery.deliveryId(),
                        delivery.webhookId(),
                        ex);
            }
        }
    }

    private void deliver(ClaimedWebhookDelivery delivery) {
        Result policy = urlPolicy.check(delivery.payloadUrl());
        if (policy != Result.ALLOWED) {
            recordFailure(delivery, null, "TARGET_NOT_ALLOWED", true);
            return;
        }
        byte[] body = delivery.payloadJson().getBytes(StandardCharsets.UTF_8);
        int status;
        byte[] responseBody;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(delivery.payloadUrl()))
                    .timeout(properties.requestTimeout())
                    .header("Content-Type", "application/json")
                    .header("User-Agent", USER_AGENT)
                    .header("X-WP-Event", WebhookEventType.PROJECT_CHANGED.name())
                    .header("X-WP-Delivery", Long.toString(delivery.deliveryId()))
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (delivery.secret() != null && !delivery.secret().isBlank()) {
                builder.header("X-WP-Signature-256", WebhookSigner.headerValue(delivery.secret(), body));
            }
            // ofByteArray is covered by request-timeout (unlike ofInputStream after headers).
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            status = response.statusCode();
            responseBody = response.body() == null ? new byte[0] : response.body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn(
                    "webhook delivery interrupted deliveryId={} webhookId={} host={}",
                    delivery.deliveryId(),
                    delivery.webhookId(),
                    hostOnly(delivery.payloadUrl()));
            return;
        } catch (Exception ex) {
            recordFailure(delivery, null, ex.getClass().getSimpleName() + ": " + safeErrorMessage(ex), false);
            return;
        }
        if (status >= 200 && status < 300) {
            try {
                boolean updated = Boolean.TRUE.equals(transactions.execute(
                        tx -> webhooks.completeSuccess(delivery.deliveryId(), delivery.claimToken(), status)));
                if (updated) {
                    log.info(
                            "webhook delivery succeeded deliveryId={} webhookId={} status={} host={}",
                            delivery.deliveryId(),
                            delivery.webhookId(),
                            status,
                            hostOnly(delivery.payloadUrl()));
                }
            } catch (RuntimeException ex) {
                log.warn(
                        "webhook delivery HTTP ok but DB update failed deliveryId={} webhookId={} host={}",
                        delivery.deliveryId(),
                        delivery.webhookId(),
                        hostOnly(delivery.payloadUrl()),
                        ex);
            }
            return;
        }
        String errorSnippet = responseSnippet(responseBody);
        recordFailure(delivery, status, "HTTP_" + status + (errorSnippet.isEmpty() ? "" : ": " + errorSnippet), false);
    }

    private void recordFailure(
            ClaimedWebhookDelivery delivery, Integer statusCode, String error, boolean finalFailure) {
        int nextAttempts = delivery.attempts() + 1;
        boolean giveUp = finalFailure || nextAttempts >= properties.maxAttempts();
        Instant nextAt = giveUp ? Instant.now() : Instant.now().plus(backoff(nextAttempts));
        boolean updated = Boolean.TRUE.equals(transactions.execute(status -> webhooks.completeFailure(
                delivery.deliveryId(), delivery.claimToken(), statusCode, error, giveUp, nextAt)));
        if (updated) {
            // Log only safe fields — never echo response body (may contain the callback URL).
            log.warn(
                    "webhook delivery {} deliveryId={} webhookId={} attempts={} host={} statusCode={} errorType={}",
                    giveUp ? "failed" : "retrying",
                    delivery.deliveryId(),
                    delivery.webhookId(),
                    nextAttempts,
                    hostOnly(delivery.payloadUrl()),
                    statusCode,
                    errorType(error));
        }
    }

    private Duration backoff(int attemptsAfterIncrement) {
        int exp = Math.max(0, attemptsAfterIncrement - 1);
        Duration base = properties.retryBase();
        long millis;
        try {
            millis = Math.multiplyExact(base.toMillis(), 1L << Math.min(exp, 30));
        } catch (ArithmeticException ex) {
            millis = properties.retryMax().toMillis();
        }
        return Duration.ofMillis(Math.min(millis, properties.retryMax().toMillis()));
    }

    private void maybePurge() {
        long now = System.currentTimeMillis();
        long previous = lastPurgeMillis.get();
        if (now - previous < Duration.ofHours(1).toMillis()) {
            return;
        }
        if (!lastPurgeMillis.compareAndSet(previous, now)) {
            return;
        }
        Instant cutoff = Instant.now().minus(properties.retention());
        try {
            Integer deleted = transactions.execute(status -> webhooks.deleteFinishedBefore(cutoff));
            if (deleted != null && deleted > 0) {
                log.info("purged {} finished webhook deliveries older than {}", deleted, cutoff);
            }
        } catch (RuntimeException ex) {
            log.warn("webhook delivery purge failed", ex);
        }
    }

    private static String responseSnippet(byte[] body) {
        if (body.length == 0) {
            return "";
        }
        byte[] clipped = body.length <= MAX_ERROR_BODY_BYTES ? body : Arrays.copyOf(body, MAX_ERROR_BODY_BYTES);
        return new String(clipped, StandardCharsets.UTF_8);
    }

    private static String hostOnly(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost() == null ? "(unknown)" : uri.getHost();
        } catch (Exception ex) {
            return "(invalid)";
        }
    }

    private static String safeErrorMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return "";
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private static String errorType(String error) {
        if (error == null || error.isBlank()) {
            return "unknown";
        }
        int colon = error.indexOf(':');
        return colon < 0 ? error : error.substring(0, colon);
    }

    @Override
    public void destroy() {
        stopped.set(true);
        ScheduledFuture<?> future = periodic;
        if (future != null) {
            future.cancel(false);
        }
        scheduler.shutdownNow();
        workers.shutdownNow();
        httpExecutor.shutdownNow();
    }
}
