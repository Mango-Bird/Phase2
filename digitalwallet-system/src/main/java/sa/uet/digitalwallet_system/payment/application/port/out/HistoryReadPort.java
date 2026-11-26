package sa.uet.digitalwallet_system.payment.application.port.out;

import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface HistoryReadPort {
    record Position(String storedTime, long id) {}
    record Row(Transaction transaction, Position position) {}
    List<Row> seek(long walletId, String type, LocalDateTime from, LocalDateTime to, Position after, int fetch);
    Optional<Transaction> owned(long walletId, String reference);
}
