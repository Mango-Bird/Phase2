package sa.uet.digitalwallet_system.payment.application;

import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import sa.uet.digitalwallet_system.platform.api.error.ModuleFailure;
import sa.uet.digitalwallet_system.platform.api.error.SqlFailures;
import java.util.*;

public final class PaymentRetryClassifier {
    private PaymentRetryClassifier() {}
    public static boolean retryable(RuntimeException failure) {
        if (failure instanceof ModuleFailure) return false;
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        boolean optimistic = false;
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ModuleFailure) return false;
            if (cause instanceof OptimisticLockingFailureException || cause instanceof OptimisticLockException) optimistic = true;
        }
        return optimistic || SqlFailures.isMySqlDeadlock(failure);
    }
}
