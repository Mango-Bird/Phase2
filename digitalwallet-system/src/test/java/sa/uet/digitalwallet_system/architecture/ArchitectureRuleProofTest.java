package sa.uet.digitalwallet_system.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

class ArchitectureRuleProofTest {
    @Test void productionEngineRejectsForbiddenCrossModuleInternalImport() {
        assertRejected("architecture.fixtures.internalimport", Set.of("consumer", "identity"),
                Map.of("consumer", Set.of("identity"), "identity", Set.of()), "imports internal type");
    }

    @Test void productionEngineRejectsModuleCycle() {
        assertRejected("architecture.fixtures.cycle", Set.of("alpha", "beta"),
                Map.of("alpha", Set.of("beta"), "beta", Set.of("alpha")), "module cycle");
    }

    @Test void productionEngineRejectsPublicApiThatExposesOwnDomainType() {
        assertRejected("architecture.fixtures.leakyapi", Set.of("identity"),
                Map.of("identity", Set.of()), "public API exposes module internal type");
    }

    @Test void productionEngineRejectsLegacyCallerImportingIdentityPersistenceInternal() {
        assertRejected("architecture.fixtures.legacyinternal", Set.of("identity"),
                Map.of("identity", Set.of()), "imports internal type");
    }

    @Test void productionEngineRejectsPaymentCallingWalletCreationWriteApi() {
        assertRejected("architecture.fixtures.restrictedcaller", Set.of("payment", "wallet", "onboarding"),
                Map.of("payment", Set.of("wallet"), "wallet", Set.of(), "onboarding", Set.of("wallet")),
                "is not an allowed caller");
    }

    private void assertRejected(String root, Set<String> modules, Map<String, Set<String>> graph,
                                String expectedMessage) {
        var classes = new ClassFileImporter().importPackages(root);
        var policy = ModuleArchitectureVerifier.fixturePolicy(root, modules, graph);
        assertThatThrownBy(() -> ModuleArchitectureVerifier.verifyProduction(classes, policy))
                .isInstanceOf(AssertionError.class).hasMessageContaining(expectedMessage);
    }

    @Test void productionEngineRejectsBankingCallingWalletPostingEvenThroughPublicApi() {
        assertRejected("architecture.fixtures.postingcaller", Set.of("wallet", "banking", "payment"),
                Map.of("banking", Set.of("wallet"), "wallet", Set.of(), "payment", Set.of("wallet")),
                "is not an allowed caller");
    }

    @Test void productionEngineRejectsReintroducedLegacyBusinessClassWithoutDependencies() {
        assertRejected("architecture.fixtures.legacybusiness", Set.of("wallet"),
                Map.of("wallet", Set.of()), "unmodularized legacy business type");
    }

    @Test void productionEngineRejectsBusinessClassDirectlyInRootPackage() {
        assertRejected("architecture.fixtures.rootbusiness", Set.of("wallet"),
                Map.of("wallet", Set.of()), "unmodularized legacy business type");
    }

    @Test void productionEngineRejectsUnrecognizedServicePackageWithoutDependencies() {
        assertRejected("architecture.fixtures.servicebusiness", Set.of("wallet"),
                Map.of("wallet", Set.of()), "unmodularized legacy business type");
    }

    @Test void productionEngineAllowsExplicitEntryPointBootstrapAndMigrationInfrastructure() {
        var classes = new ClassFileImporter().importPackages("architecture.fixtures.infrastructure");
        var policy = ModuleArchitectureVerifier.fixturePolicy("architecture.fixtures.infrastructure",
                Set.of("wallet"), Map.of("wallet", Set.of()));
        assertThatCode(() -> ModuleArchitectureVerifier.verifyProduction(classes, policy)).doesNotThrowAnyException();
    }
}
