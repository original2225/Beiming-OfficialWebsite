package games.beiming.website.common.core.result;

public enum ErrorCode {

    SUCCESS(0, "success"),
    BAD_REQUEST(400, "request parameter error"),
    UNAUTHORIZED(401, "unauthorized"),
    FORBIDDEN(403, "forbidden"),
    NOT_FOUND(404, "resource not found"),
    CONFLICT(409, "business conflict"),
    INVITE_CODE_INVALID(410, "invite code expired or invalid"),
    FILE_TOO_LARGE(413, "file too large"),
    UNSUPPORTED_MEDIA_TYPE(415, "unsupported file type"),
    TOO_MANY_REQUESTS(429, "too many requests"),
    INTERNAL_SERVER_ERROR(500, "internal server error");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
