package sa.uet.digitalwallet_system.payment.application.port.out;

public interface PaymentRetryDelayPort { void pauseBeforeRetry(int retryAttempt); }
