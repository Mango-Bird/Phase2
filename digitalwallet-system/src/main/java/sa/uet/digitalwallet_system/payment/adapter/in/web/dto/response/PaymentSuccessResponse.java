package sa.uet.digitalwallet_system.payment.adapter.in.web.dto.response;

import java.time.LocalDateTime;

/** OpenAPI shape of the durable JSON snapshot returned verbatim by the adapter. */
public record PaymentSuccessResponse(LocalDateTime timestamp, int status, String message, TransactionResponse data) {}
