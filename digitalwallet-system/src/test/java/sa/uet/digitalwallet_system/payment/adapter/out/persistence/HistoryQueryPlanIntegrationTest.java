package sa.uet.digitalwallet_system.payment.adapter.out.persistence;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.mysql.MySQLContainer;
import sa.uet.digitalwallet_system.payment.application.port.out.HistoryReadPort.Position;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Comparative read evidence, not a production latency SLA or the C11 workload benchmark. */
@Testcontainers @SpringBootTest
class HistoryQueryPlanIntegrationTest {
    @Container static final MySQLContainer MYSQL=new MySQLContainer("mysql:8.4").withDatabaseName("c08_plans").withUsername("test_user").withPassword("test_password");
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",MYSQL::getJdbcUrl);r.add("spring.datasource.username",MYSQL::getUsername);r.add("spring.datasource.password",MYSQL::getPassword);
    }
    @Autowired JdbcTemplate jdbc;
    List<Long> read(JdbcHistoryReadAdapter.Query q) {
        return jdbc.query(q.sql(),ps -> JdbcHistoryReadAdapter.bind(ps,q.parameters()),(rs,n) -> rs.getLong("id"));
    }
    String plan(JdbcHistoryReadAdapter.Query q) {
        return String.join("\n",jdbc.query("EXPLAIN ANALYZE "+q.sql(),ps -> JdbcHistoryReadAdapter.bind(ps,q.parameters()),(rs,n) -> rs.getString(1)));
    }
    @Test void compareOrAndBoundedUnionOnOneHundredThousandRows() throws Exception {
        jdbc.update("INSERT INTO users(id,username,password_hash,full_name,email,role,created_at) VALUES (101,'a','hash','A','a@test','ROLE_USER',UTC_TIMESTAMP()),(202,'b','hash','B','b@test','ROLE_USER',UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO wallets(id,user_id,wallet_number,balance,status,version,created_at,updated_at) VALUES (11,101,'A',1000,'ACTIVE',0,UTC_TIMESTAMP(),UTC_TIMESTAMP()),(22,202,'B',1000,'ACTIVE',0,UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO linked_bank_accounts(id,wallet_id,bank_code,bank_name,account_number_masked,holder_name,status,linked_at) VALUES (32,22,'B','Bank','******1234','B','ACTIVE',UTC_TIMESTAMP())");
        String digit="(SELECT 0 n UNION ALL SELECT 1 UNION ALL SELECT 2 UNION ALL SELECT 3 UNION ALL SELECT 4 UNION ALL SELECT 5 UNION ALL SELECT 6 UNION ALL SELECT 7 UNION ALL SELECT 8 UNION ALL SELECT 9)";
        String numbers="SELECT a.n+10*b.n+100*c.n+1000*d.n+10000*e.n+1 AS n FROM "+digit+" a CROSS JOIN "+digit+" b CROSS JOIN "+digit+" c CROSS JOIN "+digit+" d CROSS JOIN "+digit+" e";
        long start=System.nanoTime();
        jdbc.update("""
                INSERT INTO transactions(id,reference_code,type,source_wallet_id,target_wallet_id,linked_bank_account_id,amount,created_at)
                SELECT n,CONCAT('00000000-0000-0000-0000-',LPAD(n,12,'0')),IF(MOD(n,10)=0,'TRANSFER','WITHDRAW'),
                  IF(MOD(n,10)=0,11,22),IF(MOD(n,10)=0,22,NULL),IF(MOD(n,10)=0,NULL,32),1,
                  DATE_ADD('2026-11-01 00:00:00',INTERVAL FLOOR(n/100) SECOND)
                FROM (
                """+numbers+") seed_rows");
        double insertMs=(System.nanoTime()-start)/1_000_000.0;
        jdbc.execute("ANALYZE TABLE transactions");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM transactions",Long.class)).isEqualTo(100_000);
        var results=new LinkedHashMap<String,Object>();
        results.put("mysql",jdbc.queryForObject("SELECT VERSION()",String.class));results.put("rows",100_000);
        results.put("seedInsertMs",insertMs);results.put("newIndexes",0);results.put("warmups",3);results.put("samples",7);
        var cases=new ArrayList<Map<String,Object>>();
        for(String scenario:List.of("sparse-first","dense-first","sparse-deep")) {
            long wallet=scenario.equals("dense-first")?22:11;
            Position after=scenario.equals("sparse-deep")?new Position("2026-11-01T00:08:20.000000",50000):null;
            var or=JdbcHistoryReadAdapter.statement(wallet,null,null,null,after,21,false);
            var union=JdbcHistoryReadAdapter.statement(wallet,null,null,null,after,21,true);
            assertThat(read(union)).containsExactlyElementsOf(read(or));
            var row=new LinkedHashMap<String,Object>(); row.put("scenario",scenario);
            for(boolean useUnion:List.of(false,true)) {
                var q=useUnion?union:or;String name=useUnion?"union":"or";
                for(int i=0;i<3;i++) read(q);
                var elapsed=new ArrayList<Double>();
                for(int i=0;i<7;i++) { long before=System.nanoTime();assertThat(read(q)).hasSize(21);elapsed.add((System.nanoTime()-before)/1_000_000.0); }
                var sorted=new ArrayList<>(elapsed);Collections.sort(sorted);
                row.put(name+"MedianMs",sorted.get(3));row.put(name+"SamplesMs",elapsed);row.put(name+"Plan",plan(q));
            }
            cases.add(row);
        }
        results.put("cases",cases);
        Path output=Path.of("build/c08-history-query-plans.json");
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(output.toFile(),results);
        System.out.println("C08 plan evidence: "+output.toAbsolutePath());
        System.out.println(new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(results));
    }
}
