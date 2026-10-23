package com.example.marketplace.architecture;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.core.domain.JavaClass;
import java.util.List;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

final class ModuleRules {
    private ModuleRules() {
    }

    static List<ArchRule> rules(String root) {
        return List.of(classes().should(new ArchCondition<JavaClass>("respect module API and platform boundaries") {
            @Override
            public void check(JavaClass source, ConditionEvents events) {
                String[] origin = segments(source, root);
                for (var dependency : source.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    String[] destination = segments(target, root);
                    boolean violation = false;
                    if (origin.length > 0 && destination.length > 0) {
                        boolean sameModule = origin[0].equals(destination[0]);
                        boolean targetApi = layer(destination, "api");
                        boolean targetPlatform = destination[0].equals("platform");
                        violation = origin[0].equals("platform") && !targetPlatform;
                        violation |= !sameModule && !targetPlatform && !targetApi;
                        violation |= layer(origin, "api") && !targetApi;
                        violation |= layer(origin, "domain")
                                && (layer(destination, "web") || layer(destination, "infrastructure"));
                    }
                    if (layer(origin, "api") || layer(origin, "domain")) {
                        violation |= technical(target.getPackageName()) || target.getName().equals("java.security.Principal");
                    }
                    if (violation) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        }), slices().matching(root + ".(*)..").should().beFreeOfCycles());
    }

    private static String[] segments(JavaClass type, String root) {
        String name = type.getPackageName();
        return name.startsWith(root + ".") ? name.substring(root.length() + 1).split("\\.") : new String[0];
    }

    private static boolean layer(String[] segments, String layer) {
        return segments.length > 1 && segments[1].equals(layer);
    }

    private static boolean technical(String name) {
        return List.of("java.sql", "javax.sql", "jakarta.servlet", "javax.servlet", "org.springframework.web",
                "org.springframework.jdbc", "org.springframework.security", "org.springframework.transaction")
                .stream().anyMatch(prefix -> name.equals(prefix) || name.startsWith(prefix + "."));
    }
}
