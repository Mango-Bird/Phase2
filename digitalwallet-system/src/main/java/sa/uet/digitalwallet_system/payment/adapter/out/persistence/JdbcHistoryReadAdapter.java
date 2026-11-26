package sa.uet.digitalwallet_system.payment.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import sa.uet.digitalwallet_system.payment.application.port.out.HistoryReadPort;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.mapper.TransactionEntityMapper;
import sa.uet.digitalwallet_system.payment.adapter.out.persistence.repository.TransactionRepository;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.time.LocalDateTime;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.*;
import java.util.stream.Collectors;

@Component @RequiredArgsConstructor
public class JdbcHistoryReadAdapter implements HistoryReadPort {
    private final JdbcTemplate jdbc;
    private final TransactionRepository repository;
    private final TransactionEntityMapper mapper;
    record Query(String sql,List<Object> parameters) {}
    record Key(long id,String storedTime) {}

    @Override public List<Row> seek(long wallet,String type,LocalDateTime from,LocalDateTime to,Position after,int fetch) {
        if(fetch<1||fetch>101) throw new IllegalArgumentException("Invalid history fetch size");
        Query q=statement(wallet,type,from,to,after,fetch,true);
        var keys=jdbc.query(q.sql(),ps -> bind(ps,q.parameters()),(rs,n) -> new Key(rs.getLong("id"),rs.getString("stored_time")));
        var entities=repository.findAllById(keys.stream().map(Key::id).toList()).stream()
                .collect(Collectors.toMap(e -> e.getId(),mapper::toDomain));
        return keys.stream().map(key -> new Row(Objects.requireNonNull(entities.get(key.id())),new Position(key.storedTime(),key.id()))).toList();
    }
    @Override public Optional<Transaction> owned(long wallet,String reference) {
        return repository.findOwnedReference(wallet,reference).map(mapper::toDomain);
    }
    // Package-private so the performance evidence test runs both candidates with identical predicates/bindings.
    static Query statement(long wallet,String type,LocalDateTime from,LocalDateTime to,Position after,int fetch,boolean union) {
        var parameters=new ArrayList<Object>();
        if(!union) return new Query(branch("(source_wallet_id=? OR target_wallet_id=?)",wallet,type,from,to,after,fetch,parameters,true),List.copyOf(parameters));
        String source=branch("source_wallet_id=?",wallet,type,from,to,after,fetch,parameters,false);
        String target=branch("target_wallet_id=?",wallet,type,from,to,after,fetch,parameters,false);
        parameters.add(fetch);
        return new Query("("+source+") UNION DISTINCT ("+target+") ORDER BY created_at DESC,id DESC LIMIT ?",List.copyOf(parameters));
    }
    private static String branch(String ownership,long wallet,String type,LocalDateTime from,LocalDateTime to,Position after,int fetch,List<Object> args,boolean or) {
        var sql=new StringBuilder("SELECT id,DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') AS stored_time,created_at FROM transactions WHERE ").append(ownership);
        args.add(wallet); if(or) args.add(wallet);
        if(type!=null) { sql.append(" AND type=?");args.add(type); }
        if(from!=null) { sql.append(" AND created_at>=?");args.add(from); }
        if(to!=null) { sql.append(" AND created_at<?");args.add(to); }
        if(after!=null) { sql.append(" AND (created_at<? OR (created_at=? AND id<?))");args.add(after.storedTime());args.add(after.storedTime());args.add(after.id()); }
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT ?");args.add(fetch);return sql.toString();
    }
    static void bind(PreparedStatement ps,List<Object> args) throws SQLException {
        for(int i=0;i<args.size();i++) {
            Object value=args.get(i);
            // Match the unchanged Hibernate Timestamp mapping for from/to history wall times.
            if(value instanceof LocalDateTime time) ps.setTimestamp(i+1,Timestamp.valueOf(time),Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            else ps.setObject(i+1,value);
        }
    }
}
