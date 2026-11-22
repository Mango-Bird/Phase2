package sa.uet.digitalwallet_system.payment.adapter.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.payment.application.PaymentIntent;
import sa.uet.digitalwallet_system.payment.application.port.out.PaymentEncodingPort;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.HexFormat;

@Component
public class PaymentJsonEncoding implements PaymentEncodingPort {
    private final ObjectMapper json = new ObjectMapper();
    public String canonical(PaymentIntent i) {
        try {
            return json.writeValueAsString(Arrays.asList("payment-request.v1", i.actorId(), i.operation().name(),
                    i.walletId(), i.linkedBankId(), i.targetWalletNumber(), i.amount(), i.description()));
        } catch (Exception ex) { throw new IllegalStateException("Cannot encode payment request", ex); }
    }
    public String requestHash(PaymentIntent intent) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(intent).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    public String successSnapshot(Transaction tx) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("timestamp", time(LocalDateTime.now(ZoneOffset.UTC)));
        envelope.put("status", 200);
        envelope.put("message", switch (tx.getType()) { case "TOPUP" -> "Topup successful"; case "WITHDRAW" -> "Withdraw successful"; default -> "Transfer successful"; });
        ObjectNode data = envelope.putObject("data");
        data.put("id", tx.getId());
        data.put("referenceCode", tx.getReferenceCode()); data.put("type", tx.getType());
        data.put("sourceWalletId", tx.getSourceWalletId());
        data.put("targetWalletId", tx.getTargetWalletId());
        data.put("linkedBankAccountId", tx.getLinkedBankAccountId());
        data.put("amount", tx.getAmount());
        data.put("description", tx.getDescription());
        data.put("createdAt", time(tx.getCreatedAt()));
        return envelope.toString();
    }
    private String time(LocalDateTime value) { return value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME); }
}
