package sa.uet.digitalwallet_system.platform.outbox;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxArchitectureTest {
    @Test
    void storageAdapterHasNoPaymentInternalHttpOrProviderDependency() {
        var classes = new ClassFileImporter()
                .importPackages("sa.uet.digitalwallet_system.platform.adapter.outbox");

        var forbidden = classes.stream()
                .flatMap(type -> type.getDirectDependenciesFromSelf().stream())
                .filter(dependency -> {
                    String target = dependency.getTargetClass().getPackageName();
                    return target.startsWith("sa.uet.digitalwallet_system.payment")
                            || target.startsWith("org.springframework.web")
                            || target.startsWith("java.net")
                            || target.startsWith("java.net.http");
                })
                .toList();

        assertThat(forbidden).isEmpty();
    }
}
