package sa.uet.digitalwallet_system.payment.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import sa.uet.digitalwallet_system.payment.api.*;
import sa.uet.digitalwallet_system.payment.api.error.*;
import sa.uet.digitalwallet_system.payment.application.port.out.HistoryReadPort;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import sa.uet.digitalwallet_system.wallet.api.WalletQueryApi;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class PaymentHistoryQueryService implements PaymentQueryApi {
    private final WalletQueryApi wallets;
    private final HistoryReadPort history;
    @Override public PaymentView getOwned(long actor,String reference) {
        try { if(reference==null||!UUID.fromString(reference).toString().equalsIgnoreCase(reference)) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException ex) { throw invalid("Invalid reference code"); }
        long wallet=wallets.getOwnedWallet(actor).id();
        return view(history.owned(wallet,reference.toLowerCase(Locale.ROOT)).orElseThrow(PaymentNotFoundException::new));
    }
    @Override public PaymentSlice history(long actor,HistoryQuery query) {
        if(query==null) throw invalid("History query is required");
        int limit=query.limit()==null?20:query.limit();
        if(limit<1||limit>100) throw invalid("Limit must be between 1 and 100");
        if(query.type()!=null&&!Set.of("TOPUP","WITHDRAW","TRANSFER").contains(query.type())) throw invalid("Invalid payment type");
        LocalDateTime from=parse(query.from()),to=parse(query.to());
        if(from!=null&&to!=null&&!from.isBefore(to)) throw invalid("from must be before to");
        long wallet=wallets.getOwnedWallet(actor).id();
        String filters=Arrays.asList(query.type(),from,to).toString();
        var after=query.cursor()==null?null:HistoryCursor.decode(query.cursor(),wallet,filters);
        var rows=history.seek(wallet,query.type(),from,to,after,limit+1);
        boolean more=rows.size()>limit; var page=rows.subList(0,Math.min(limit,rows.size()));
        String next=more?HistoryCursor.encode(wallet,filters,page.getLast().position()):null;
        return new PaymentSlice(page.stream().map(row -> view(row.transaction())).toList(),next,more);
    }
    private static LocalDateTime parse(String value) {
        if(value==null) return null;
        try {
            if(value.length()>32) throw new IllegalArgumentException();
            var date=LocalDateTime.parse(value);
            int storedYear=date.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(java.time.ZoneOffset.UTC).getYear();
            if(date.getYear()<1000||date.getYear()>9999||storedYear<1000||storedYear>9999||date.getNano()%1_000_000!=0) throw new IllegalArgumentException();
            return date;
        }
        catch(Exception ex) { throw invalid("Dates must be ISO local date-time with millisecond precision"); }
    }
    private static PaymentFailure invalid(String message) { return new PaymentFailure("INVALID_REQUEST",message,false); }
    private static PaymentView view(Transaction t) {
        return new PaymentView(t.getId(),t.getReferenceCode(),t.getType(),t.getSourceWalletId(),t.getTargetWalletId(),t.getLinkedBankAccountId(),t.getAmount(),t.getDescription(),t.getCreatedAt());
    }
}
