package sa.uet.digitalwallet_system.architecture;

import com.tngtech.archunit.core.domain.*;
import java.util.*;

final class ModuleArchitectureVerifier {
    private static final Set<String> MODULES = Set.of("identity", "onboarding", "wallet", "banking", "payment", "platform");
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "identity", Set.of("platform"),
            "onboarding", Set.of("identity", "wallet", "platform"),
            "wallet", Set.of("platform"),
            "banking", Set.of("wallet", "platform"),
            "payment", Set.of("wallet", "banking", "platform"),
            "platform", Set.of());

    static void verifyProduction(JavaClasses classes) {
        List<String> errors = new ArrayList<>();
        Map<String, Set<String>> graph = emptyGraph();
        for (JavaClass source : classes) {
            String sourceModule = module(source.getPackageName(), "sa.uet.digitalwallet_system");
            if (sourceModule == null) continue;
            verifyLayer(source, sourceModule, errors);
            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String targetModule = module(target.getPackageName(), "sa.uet.digitalwallet_system");
                if (targetModule != null && !targetModule.equals(sourceModule)) {
                    graph.get(sourceModule).add(targetModule);
                    if (!ALLOWED.get(sourceModule).contains(targetModule))
                        errors.add(source.getName() + " has forbidden module dependency on " + target.getName());
                    if (!target.getPackageName().startsWith("sa.uet.digitalwallet_system." + targetModule + ".api"))
                        errors.add(source.getName() + " imports internal type " + target.getName());
                } else if (target.getPackageName().startsWith("sa.uet.digitalwallet_system.") && targetModule == null
                        && !allowedLegacyBridge(source, target)) {
                    errors.add(source.getName() + " imports legacy type " + target.getName());
                }
            }
        }
        findCycles(graph, errors);
        if (!errors.isEmpty()) throw new AssertionError(String.join("\n", errors));
    }

    static void verifyFixture(JavaClasses classes, String root, Set<String> modules) {
        List<String> errors = new ArrayList<>();
        Map<String, Set<String>> graph = new HashMap<>();
        modules.forEach(m -> graph.put(m, new HashSet<>()));
        for (JavaClass source : classes) {
            String from = module(source.getPackageName(), root, modules);
            if (from == null) continue;
            for (Dependency dependency : source.getDirectDependenciesFromSelf()) {
                JavaClass target = dependency.getTargetClass();
                String to = module(target.getPackageName(), root, modules);
                if (to != null && !to.equals(from)) {
                    graph.get(from).add(to);
                    if (!target.getPackageName().startsWith(root + "." + to + ".api"))
                        errors.add(source.getName() + " imports internal type " + target.getName());
                }
            }
        }
        findCycles(graph, errors);
        if (!errors.isEmpty()) throw new AssertionError(String.join("\n", errors));
    }

    private static void verifyLayer(JavaClass type, String module, List<String> errors) {
        String pkg = type.getPackageName();
        if (pkg.startsWith("sa.uet.digitalwallet_system." + module + ".domain")) {
            type.getDirectDependenciesFromSelf().stream().map(d -> d.getTargetClass().getPackageName())
                    .filter(p -> p.startsWith("org.springframework") || p.startsWith("jakarta.persistence"))
                    .forEach(p -> errors.add(type.getName() + " domain depends on framework " + p));
        }
        if (pkg.startsWith("sa.uet.digitalwallet_system." + module + ".api")) {
            type.getDirectDependenciesFromSelf().stream().map(Dependency::getTargetClass).forEach(target -> {
                String targetPackage = target.getPackageName();
                if (targetPackage.contains(".adapter") || targetPackage.startsWith("jakarta.persistence")
                        || targetPackage.startsWith("org.springframework.data")) {
                    errors.add(type.getName() + " public API depends on adapter/persistence " + target.getName());
                }
            });
        }
        if (pkg.startsWith("sa.uet.digitalwallet_system." + module + ".api")) {
            type.getDirectDependenciesFromSelf().stream().map(d -> d.getTargetClass().getPackageName())
                    .filter(p -> p.contains(".adapter") || p.startsWith("jakarta.persistence") || p.startsWith("org.springframework.data"))
                    .forEach(p -> errors.add(type.getName() + " API depends on implementation " + p));
        }
    }

    private static boolean allowedLegacyBridge(JavaClass source, JavaClass target) {
        if (!source.getName().equals("sa.uet.digitalwallet_system.wallet.adapter.legacy.LegacyWalletCreationBridge")) return false;
        return target.getName().equals("sa.uet.digitalwallet_system.application.port.out.SaveWalletPort")
                || target.getName().equals("sa.uet.digitalwallet_system.domain.model.Wallet")
                || target.getName().startsWith("sa.uet.digitalwallet_system.domain.model.Wallet$");
    }
    private static Map<String, Set<String>> emptyGraph() {
        Map<String, Set<String>> graph = new HashMap<>(); MODULES.forEach(m -> graph.put(m, new HashSet<>())); return graph;
    }
    private static String module(String pkg, String root) { return module(pkg, root, MODULES); }
    private static String module(String pkg, String root, Set<String> modules) {
        if (!pkg.startsWith(root + ".")) return null;
        String rest = pkg.substring(root.length() + 1);
        String first = rest.contains(".") ? rest.substring(0, rest.indexOf('.')) : rest;
        return modules.contains(first) ? first : null;
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
}
