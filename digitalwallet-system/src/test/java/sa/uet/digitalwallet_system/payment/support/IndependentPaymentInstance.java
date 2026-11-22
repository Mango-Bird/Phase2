package sa.uet.digitalwallet_system.payment.support;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import sa.uet.digitalwallet_system.DigitalwalletSystemApplication;
import java.nio.file.*;
import java.util.Arrays;

/** Test-only launcher: no diagnostic HTTP endpoint is added to the application. */
public final class IndependentPaymentInstance {
    public static void main(String[] args) throws Exception {
        var context = new SpringApplicationBuilder(DigitalwalletSystemApplication.class)
                .run(Arrays.copyOfRange(args, 1, args.length));
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        int timeout = context.getBean(JdbcTemplate.class)
                .queryForObject("SELECT @@SESSION.innodb_lock_wait_timeout", Integer.class);
        Path probe = Path.of(args[0]);
        Path temporary = probe.resolveSibling(probe.getFileName() + ".tmp");
        Files.writeString(temporary, port + "\n" + timeout + "\n" + ProcessHandle.current().pid());
        Files.move(temporary, probe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
