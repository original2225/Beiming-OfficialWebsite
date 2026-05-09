package games.beiming.website.common.web.exception;

import games.beiming.website.common.core.response.ApiResponse;
import games.beiming.website.common.core.result.ErrorCode;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ApiResponse<Void> handleIllegalArgumentException(IllegalArgumentException exception) {
        return ApiResponse.failure(ErrorCode.BAD_REQUEST.getCode(), exception.getMessage());
    }

    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleException(Exception exception) {
        return ApiResponse.failure(ErrorCode.INTERNAL_SERVER_ERROR);
    }
}
