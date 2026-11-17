package wprebuild.app.integration;

import java.util.Objects;

/**
 * Client error for webhook registration (bad URL, unknown event type, short secret). Mapped to HTTP
 * 400 with a stable {@code code} by {@code ProblemDetailsAdvice}.
 */
public final class WebhookRejectedException extends RuntimeException {

    public static final String INVALID_WEBHOOK_URL = "INVALID_WEBHOOK_URL";

    public static final String WEBHOOK_URL_UNRESOLVABLE = "WEBHOOK_URL_UNRESOLVABLE";

    public static final String WEBHOOK_TARGET_NOT_ALLOWED = "WEBHOOK_TARGET_NOT_ALLOWED";

    private final String code;

    public WebhookRejectedException(String code, String message) {
        super(requireText(message, "message"));
        this.code = requireText(code, "error code");
    }

    public String code() {
        return code;
    }

    public static WebhookRejectedException fromUrlPolicy(WebhookUrlPolicy.Result result) {
        Objects.requireNonNull(result, "result");
        return switch (result) {
            case INVALID -> new WebhookRejectedException(INVALID_WEBHOOK_URL, "webhook URL is invalid");
            case UNRESOLVABLE ->
                new WebhookRejectedException(WEBHOOK_URL_UNRESOLVABLE, "webhook URL host cannot be resolved");
            case NOT_ALLOWED ->
                new WebhookRejectedException(WEBHOOK_TARGET_NOT_ALLOWED, "webhook URL target is not allowed");
            case ALLOWED -> throw new IllegalArgumentException("ALLOWED is not a rejection");
        };
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
