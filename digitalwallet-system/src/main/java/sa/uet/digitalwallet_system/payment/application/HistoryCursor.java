package sa.uet.digitalwallet_system.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import sa.uet.digitalwallet_system.payment.application.port.out.HistoryReadPort.Position;
import sa.uet.digitalwallet_system.payment.api.error.PaymentFailure;
import java.time.LocalDateTime;
import java.util.*;

/** Position is untrusted input, never an authorization token. Every SQL query is wallet-scoped. */
final class HistoryCursor {
    private static final ObjectMapper JSON=new ObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static String encode(long wallet,String filter,Position position) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(List.of(1,wallet,filter,position.storedTime(),position.id()))); }
        catch(Exception ex) { throw new IllegalStateException("Cannot encode history cursor",ex); }
    }
    static Position decode(String cursor,long wallet,String filter) {
        try {
            if(cursor.isEmpty()||cursor.length()>1024) throw new IllegalArgumentException();
            var bytes=Base64.getUrlDecoder().decode(cursor);
            if(!Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(cursor)) throw new IllegalArgumentException();
            var n=JSON.readTree(bytes);
            if(!n.isArray()||n.size()!=5||!n.get(0).isInt()||n.get(0).intValue()!=1
                    ||!n.get(1).isIntegralNumber()||!n.get(1).canConvertToLong()||n.get(1).longValue()!=wallet
                    ||!n.get(2).isTextual()||!n.get(2).textValue().equals(filter)||!n.get(3).isTextual()
                    ||!n.get(4).isIntegralNumber()||!n.get(4).canConvertToLong()||n.get(4).longValue()<=0) throw new IllegalArgumentException();
            String time=n.get(3).textValue(); var date=LocalDateTime.parse(time);
            if(!time.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{6}")||date.getYear()<1000) throw new IllegalArgumentException();
            return new Position(time,n.get(4).longValue());
        } catch(Exception ex) { throw new PaymentFailure("INVALID_CURSOR","Invalid history cursor or filters",false); }
    }
    private HistoryCursor() {}
}
