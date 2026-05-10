package games.beiming.website.auth.exception;

import games.beiming.website.common.core.response.ApiResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "games.beiming.website.auth")
public class AuthExceptionHandler {

    @ExceptionHandler(AuthBusinessException.class)
    public ApiResponse<Void> handleAuthBusinessException(AuthBusinessException exception) {
        return ApiResponse.failure(exception.getErrorCode().getCode(), exception.getMessage());
    }
}
