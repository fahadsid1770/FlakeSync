package flakesync.filter;

import com.github.javaparser.ast.body.MethodDeclaration;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

public final class CallbackPatterns {

    private static final Map<String, Set<String>> KNOWN_CALLBACKS = Map.of(
            "afterExecute", Set.of("execute", "submit", "invokeAll", "invokeAny"),
            "beforeExecute", Set.of("execute", "submit", "invokeAll", "invokeAny")
    );

    private CallbackPatterns() {
    }

    public static boolean isKnownCallback(MethodDeclaration method) {
        if (!KNOWN_CALLBACKS.containsKey(method.getNameAsString())) {
            return false;
        }
        return method.getAnnotationByName("Override").isPresent();
    }

    public static Set<String> triggeringCallNames(MethodDeclaration method) {
        return KNOWN_CALLBACKS.getOrDefault(method.getNameAsString(), Collections.emptySet());
    }
}
