package sa.uet.digitalwallet_system.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

class ModuleArchitectureTest {
    @Test void productionModulesHonorPublishedApisAndAcyclicGraph() {
        ModuleArchitectureVerifier.verifyProduction(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("sa.uet.digitalwallet_system"));
    }
}
