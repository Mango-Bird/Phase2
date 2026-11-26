package sa.uet.digitalwallet_system.payment.support;

import jakarta.persistence.*;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import sa.uet.digitalwallet_system.payment.adapter.serialization.PaymentJsonEncoding;
import sa.uet.digitalwallet_system.payment.application.PaymentIntent;
import sa.uet.digitalwallet_system.payment.api.PaymentOperation;
import sa.uet.digitalwallet_system.payment.domain.model.Transaction;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

/** Frozen pre-C06-A history mapping from base 250d688; deliberately no direct JDBC Java-time override. */
public final class LegacyPaymentFixture {
    @Entity(name="LegacyPayment") @Table(name="transactions")
    public static class LegacyTransaction {
        @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
        @Column(name="reference_code") public String referenceCode;
        public String type;
        @Column(name="source_wallet_id") public Long sourceWalletId;
        @Column(name="target_wallet_id") public Long targetWalletId;
        @Column(name="linked_bank_account_id") public Long linkedBankAccountId;
        public Long amount;
        public String description;
        @Column(name="created_at",nullable=false,updatable=false) public LocalDateTime createdAt;
    }
    public static void main(String[] args) throws Exception {
        if(!TimeZone.getDefault().getID().equals("Asia/Bangkok")) throw new IllegalStateException("fixture requires UTC+7 JVM");
        var registry=new StandardServiceRegistryBuilder().applySettings(Map.of(
                "hibernate.connection.url",args[1],"hibernate.connection.username",args[2],
                "hibernate.connection.password",args[3],"hibernate.jdbc.time_zone","UTC",
                "hibernate.hbm2ddl.auto","none","hibernate.connection.pool_size","1")).build();
        try(var factory=new MetadataSources(registry).addAnnotatedClass(LegacyTransaction.class).buildMetadata().buildSessionFactory();
            var session=factory.openSession()) {
            var tx=session.beginTransaction();
            var legacy=new LegacyTransaction();
            legacy.referenceCode="11111111-2222-3333-4444-555555555555"; legacy.type="TRANSFER";
            legacy.sourceWalletId=11L; legacy.targetWalletId=22L; legacy.amount=100L;
            legacy.createdAt=LocalDateTime.parse("2026-11-20T10:15:30.123");
            session.persist(legacy); session.flush();
            session.createNativeMutationQuery("UPDATE wallets SET balance=CASE id WHEN 11 THEN 900 ELSE 600 END,version=1 WHERE id IN (11,22)").executeUpdate();
            var encoding=new PaymentJsonEncoding();
            var model=Transaction.builder().id(legacy.id).referenceCode(legacy.referenceCode).type(legacy.type)
                    .sourceWalletId(11L).targetWalletId(22L).amount(100L).createdAt(legacy.createdAt).build();
            String snapshot=encoding.successSnapshot(model);
            String hash=encoding.requestHash(new PaymentIntent(101,PaymentOperation.TRANSFER,11,null,"TARGET",100,null));
            session.doWork(connection -> {
                try(var statement=connection.prepareStatement("""
                        INSERT INTO idempotency_requests(actor_id,operation,idempotency_key,request_hash,status,
                          transaction_id,reference_code,response_version,response_status,response_body,created_at,completed_at)
                        VALUES (101,'TRANSFER','legacy-upgrade',?,'COMPLETED',?,?,1,200,CAST(? AS JSON),UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))
                        """)) {
                    statement.setString(1,hash); statement.setLong(2,legacy.id); statement.setString(3,legacy.referenceCode);
                    statement.setString(4,snapshot); statement.executeUpdate();
                }
            });
            tx.commit(); session.clear();
            LocalDateTime oldRead=session.find(LegacyTransaction.class,legacy.id).createdAt;
            String raw=session.createNativeQuery("SELECT DATE_FORMAT(created_at,'%Y-%m-%dT%H:%i:%s.%f') FROM transactions WHERE id="+legacy.id,String.class).getSingleResult();
            Files.writeString(Path.of(args[0]),legacy.id+"\n"+oldRead+"\n"+raw);
        } finally { StandardServiceRegistryBuilder.destroy(registry); }
    }
}
