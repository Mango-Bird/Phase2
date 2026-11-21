package sa.uet.digitalwallet_system.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;
import java.util.Set;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ArchitectureRuleProofTest {
    @Test void sameRuleRejectsForbiddenInternalImport() {
        var classes = new ClassFileImporter().importPackages("architecture.fixtures.internalimport");
        assertThatThrownBy(() -> ModuleArchitectureVerifier.verifyFixture(classes,
                "architecture.fixtures.internalimport", Set.of("consumer", "identity")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("imports internal type");
    }
    @Test void sameRuleRejectsModuleCycle() {
        var classes = new ClassFileImporter().importPackages("architecture.fixtures.cycle");
        assertThatThrownBy(() -> ModuleArchitectureVerifier.verifyFixture(classes,
                "architecture.fixtures.cycle", Set.of("alpha", "beta")))
                .isInstanceOf(AssertionError.class).hasMessageContaining("module cycle");
    }
}
