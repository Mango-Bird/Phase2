package sa.uet.digitalwallet_system.platform.api.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(LocalDateTime timestamp, int status, String message, T data) {
    public static <T> ApiResponse<T> success(T data) { return success("Success", data); }
    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(LocalDateTime.now(), 200, message, data);
    }
    public static <T> ApiResponse<T> created(String message, T data) {
        return new ApiResponse<>(LocalDateTime.now(), 201, message, data);
    }
}
