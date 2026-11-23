package sa.uet.digitalwallet_system.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;

import java.util.*;

final class ModuleArchitectureVerifier {
    private static final String PRODUCTION_ROOT = "sa.uet.digitalwallet_system";
    private static final Set<String> PRODUCTION_MODULES = Set.of(
            "identity", "onboarding", "wallet", "banking", "payment", "platform");
    private static final Map<String, Set<String>> PRODUCTION_GRAPH = Map.of(
            "identity", Set.of("platform"),
            "onboarding", Set.of("identity", "wallet", "platform"),
            "wallet", Set.of("platform"),
            "banking", Set.of("wallet", "platform"),
            "payment", Set.of("wallet", "banking", "platform"),
            "platform", Set.of());

    private static final Policy PRODUCTION_POLICY = new Policy(
            PRODUCTION_ROOT,
            PRODUCTION_MODULES,
            PRODUCTION_GRAPH,
            Map.of(
                    "identity.api.IdentityRegistrationApi", Set.of("identity", "onboarding"),
                    "wallet.api.WalletCreationApi", Set.of("wallet", "onboarding"),
                    "wallet.api.WalletPostingApi", Set.of("wallet", "payment"),
                    "banking.api.BankEligibilityApi", Set.of("banking", "payment"),
                    "platform.api.outbox.OutboxAppendPort", Set.of("platform", "payment")
            ),
            Map.of(), Map.of());

    static void verifyProduction(JavaClasses classes) {
        verifyProduction(classes, PRODUCTION_POLICY);
    }

    /** Same rule engine used by production and deliberately invalid compiled fixtures. */
    static void verifyProduction(JavaClasses classes, Policy policy) {
        List<String> errors = new ArrayList<>();
        Map<String, Set<String>> graph = emptyGraph(policy.modules());

        for (JavaClass source : classes) {
            if (!insideRoot(source, policy.root())) continue;
            String sourceModule = module(source.getPackageName(), policy);
            if (sourceModule == null && !isInfrastructure(source, policy)) {
                errors.add(source.getName() + " is an unmodularized legacy business type");
            }
            if (sourceModule != null) verifyLayer(source, sourceModule, policy, errors);

            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                if (!insideRoot(target, policy.root())) continue;
                String targetModule = module(target.getPackageName(), policy);

                if (sourceModule == null) {
                    verifyLegacyCaller(source, target, targetModule, policy, errors);
                    continue;
                }

                if (targetModule == null) {
                    if (!matchesException(source.getName(), target.getName(), policy.moduleToLegacyExceptions())) {
                        errors.add(source.getName() + " imports legacy type " + target.getName());
                    }
                    continue;
                }

                if (!sourceModule.equals(targetModule)) {
                    graph.get(sourceModule).add(targetModule);
                    if (!policy.allowedGraph().getOrDefault(sourceModule, Set.of()).contains(targetModule)) {
                        errors.add(source.getName() + " has forbidden module dependency on " + target.getName());
                    }
                    if (!isPublicApi(target, targetModule, policy)) {
                        errors.add(source.getName() + " imports internal type " + target.getName());
                    }
                }
                verifyRestrictedCaller(source, sourceModule, target, policy, errors);
            }
        }

        findCycles(graph, errors);
        if (!errors.isEmpty()) throw new AssertionError(String.join("\n", new LinkedHashSet<>(errors)));
    }

    static Policy fixturePolicy(String root, Set<String> modules, Map<String, Set<String>> graph) {
        return new Policy(root, modules, graph,
                Map.of("identity.api.IdentityRegistrationApi", Set.of("identity", "onboarding"),
                       "wallet.api.WalletCreationApi", Set.of("wallet", "onboarding"),
                       "wallet.api.WalletPostingApi", Set.of("wallet", "payment"),
                       "banking.api.BankEligibilityApi", Set.of("banking", "payment"),
                       "platform.api.outbox.OutboxAppendPort", Set.of("platform", "payment")),
                Map.of(), Map.of());
    }

    private static void verifyLegacyCaller(JavaClass source, JavaClass target, String targetModule,
                                           Policy policy, List<String> errors) {
        if (targetModule == null) return;
        if (!isPublicApi(target, targetModule, policy)) {
            errors.add(source.getName() + " imports internal type " + target.getName());
            return;
        }
        if (!matchesException(source.getName(), target.getName(), policy.legacyToModuleApiExceptions())) {
            errors.add(source.getName() + " imports module API without an explicit legacy exception " + target.getName());
        }
    }

    private static void verifyRestrictedCaller(JavaClass source, String sourceModule, JavaClass target,
                                                Policy policy, List<String> errors) {
        String relativeTarget = relativeName(target.getName(), policy.root());
        Set<String> allowedCallers = policy.restrictedApiCallers().get(relativeTarget);
        if (allowedCallers != null && !allowedCallers.contains(sourceModule)) {
            errors.add(source.getName() + " is not an allowed caller of " + target.getName());
        }
    }

    private static void verifyLayer(JavaClass source, String sourceModule, Policy policy, List<String> errors) {
        String pkg = source.getPackageName();
        String modulePrefix = policy.root() + "." + sourceModule;
        if (pkg.startsWith(modulePrefix + ".domain")) {
            source.getDirectDependenciesFromSelf().stream().map(d -> d.getTargetClass().getPackageName())
                    .filter(p -> p.startsWith("org.springframework") || p.startsWith("jakarta.persistence"))
                    .forEach(p -> errors.add(source.getName() + " domain depends on framework " + p));
        }
        if (pkg.startsWith(modulePrefix + ".api")) {
            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String targetModule = module(target.getPackageName(), policy);
                if (sourceModule.equals(targetModule) && !isPublicApi(target, sourceModule, policy)) {
                    errors.add(source.getName() + " public API exposes module internal type " + target.getName());
                }
                String targetPackage = target.getPackageName();
                if (targetPackage.contains(".adapter") || targetPackage.startsWith("jakarta.persistence")
                        || targetPackage.startsWith("org.springframework.data")) {
                    errors.add(source.getName() + " public API depends on adapter/persistence " + target.getName());
                }
            }
        }
    }

    private static boolean isPublicApi(JavaClass type, String module, Policy policy) {
        return type.getPackageName().startsWith(policy.root() + "." + module + ".api");
    }

    private static boolean matchesException(String source, String target, Map<String, Set<String>> exceptions) {
        return exceptions.getOrDefault(source, Set.of()).stream()
                .anyMatch(allowed -> target.equals(allowed) || (allowed.endsWith("$") && target.startsWith(allowed)));
    }

    private static boolean insideRoot(JavaClass type, String root) {
        return type.getPackageName().equals(root) || type.getPackageName().startsWith(root + ".");
    }

    /** Only the exact Spring entry point and explicit infrastructure namespaces may live outside modules. */
    private static boolean isInfrastructure(JavaClass type, Policy policy) {
        if (type.getName().equals(policy.root() + ".DigitalwalletSystemApplication")) return true;
        return Set.of("bootstrap", "migration").stream().anyMatch(namespace ->
                type.getPackageName().equals(policy.root() + "." + namespace)
                        || type.getPackageName().startsWith(policy.root() + "." + namespace + "."));
    }

    private static String relativeName(String className, String root) {
        return className.startsWith(root + ".") ? className.substring(root.length() + 1) : className;
    }

    private static String module(String pkg, Policy policy) {
        if (!pkg.startsWith(policy.root() + ".")) return null;
        String rest = pkg.substring(policy.root().length() + 1);
        String first = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
        return policy.modules().contains(first) ? first : null;
    }

    private static Map<String, Set<String>> emptyGraph(Set<String> modules) {
        Map<String, Set<String>> graph = new HashMap<>();
        modules.forEach(module -> graph.put(module, new HashSet<>()));
        return graph;
    }

    private static void findCycles(Map<String, Set<String>> graph, List<String> errors) {
        for (String start : graph.keySet()) visit(start, start, graph, new LinkedHashSet<>(), errors);
    }

    private static void visit(String start, String current, Map<String, Set<String>> graph,
                              LinkedHashSet<String> path, List<String> errors) {
        if (!path.add(current)) return;
        for (String next : graph.getOrDefault(current, Set.of())) {
            if (next.equals(start)) errors.add("module cycle: " + String.join(" -> ", path) + " -> " + start);
            else visit(start, next, graph, new LinkedHashSet<>(path), errors);
        }
    }

    record Policy(String root, Set<String> modules, Map<String, Set<String>> allowedGraph,
                  Map<String, Set<String>> restrictedApiCallers,
                  Map<String, Set<String>> legacyToModuleApiExceptions,
                  Map<String, Set<String>> moduleToLegacyExceptions) {
    }
}
