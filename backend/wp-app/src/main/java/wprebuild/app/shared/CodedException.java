package wprebuild.app.shared;

/** Runtime failure that carries a stable ProblemDetail {@code code}. */
abstract sealed class CodedException extends RuntimeException
        permits ConflictException, NotFoundException, PermissionDeniedException {

    private final String code;

    CodedException(String code, String message) {
        super(requireText(message, "message"));
        this.code = requireText(code, "error code");
    }

    public final String code() {
        return code;
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
