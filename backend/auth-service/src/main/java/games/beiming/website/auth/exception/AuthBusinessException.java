package games.beiming.website.auth.exception;

import games.beiming.website.common.core.result.ErrorCode;

public class AuthBusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public AuthBusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
