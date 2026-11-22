package sa.uet.digitalwallet_system.payment.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import sa.uet.digitalwallet_system.payment.api.PaymentOperation;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import sa.uet.digitalwallet_system.payment.application.port.out.*;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcIdempotencyStore implements IdempotencyStore {
    private final JdbcTemplate jdbc;
    public void claim(long actor, PaymentOperation operation, String key, String hash) {
        try {
            jdbc.update("INSERT INTO idempotency_requests(actor_id,operation,idempotency_key,request_hash,status,created_at) VALUES (?,?,?,?,'PROCESSING',UTC_TIMESTAMP(6))",
                    actor, operation.name(), key, hash);
        } catch (DuplicateKeyException duplicate) { throw new ClaimConflict(duplicate); }
        catch (DataAccessException ex) {
            if (sa.uet.digitalwallet_system.platform.api.error.SqlFailures.isMySqlLockTimeout(ex))
                throw new PaymentFailure("REQUEST_IN_PROGRESS", "Request is in progress; retry with the same key", true);
            throw ex;
        }
    }
    public void complete(long actor, PaymentOperation operation, String key, long txId, String reference, String body) {
        int rows = jdbc.update("UPDATE idempotency_requests SET status='COMPLETED', transaction_id=?, reference_code=?, response_version=1, response_status=200, response_body=CAST(? AS JSON), completed_at=UTC_TIMESTAMP(6) WHERE actor_id=? AND operation=? AND idempotency_key=? AND status='PROCESSING'",
                txId, reference, body, actor, operation.name(), key);
        if (rows != 1) throw new IllegalStateException("Missing idempotency claim");
    }
    public Optional<StoredPayment> find(long actor, PaymentOperation operation, String key) {
        return jdbc.query("SELECT request_hash,status,response_status,response_version,response_body,reference_code FROM idempotency_requests WHERE actor_id=? AND operation=? AND idempotency_key=?",
                (rs, row) -> new StoredPayment(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5), rs.getString(6)),
                actor, operation.name(), key).stream().findFirst();
    }
}
