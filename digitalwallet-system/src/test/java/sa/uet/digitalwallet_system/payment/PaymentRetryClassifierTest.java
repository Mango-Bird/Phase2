package sa.uet.digitalwallet_system.payment;

import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import sa.uet.digitalwallet_system.payment.application.PaymentRetryClassifier;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import sa.uet.digitalwallet_system.platform.api.error.SqlFailures;
import java.sql.*;
import static org.assertj.core.api.Assertions.*;

class PaymentRetryClassifierTest {
    @Test void retriesOnlyRecognizedOptimisticOrMysqlDeadlock() {
        assertThat(PaymentRetryClassifier.retryable(new OptimisticLockingFailureException("stale"))).isTrue();
        assertThat(PaymentRetryClassifier.retryable(new RuntimeException(new jakarta.persistence.OptimisticLockException("stale")))).isTrue();
        assertThat(PaymentRetryClassifier.retryable(new RuntimeException(new SQLException("deadlock","40001",1213)))).isTrue();
    }
    @Test void nestedJdbcBatchDeadlockIsRecognized() {
        var batch=new BatchUpdateException(); batch.setNextException(new SQLException("deadlock","40001",1213));
        assertThat(PaymentRetryClassifier.retryable(new RuntimeException(batch))).isTrue();
    }
    @Test void timeoutDuplicateConnectivityAndUnknownStateAreNotRetried() {
        for (var sql : java.util.List.of(new SQLException("wait","HY000",1205), new SQLException("connector timeout","40001",1205), new SQLException("duplicate","23000",1062),
                new SQLException("connection","08006",0), new SQLException("unknown",null,1213)))
            assertThat(PaymentRetryClassifier.retryable(new RuntimeException(sql))).isFalse();
        assertThat(SqlFailures.isMySqlLockTimeout(new RuntimeException(new SQLException("wait","HY000",1205)))).isTrue();
        assertThat(SqlFailures.isMySqlLockTimeout(new RuntimeException(new SQLException("connector timeout","40001",1205)))).isTrue();
    }
    @Test void businessFailuresAreNotRetriedEvenWhenMarkedClientRetryable() {
        var failure=new PaymentFailure("RESOURCE_BUSY","busy",true);
        failure.initCause(new SQLException("deadlock","40001",1213));
        assertThat(PaymentRetryClassifier.retryable(new RuntimeException(failure))).isFalse();
    }
    @Test void cyclicCauseChainsCannotHangClassification() {
        var a=new RuntimeException(); var b=new RuntimeException(a); a.initCause(b);
        assertThat(PaymentRetryClassifier.retryable(a)).isFalse();
    }
}
