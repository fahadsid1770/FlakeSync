package flakesync.filter;

import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class AliasResolver {

    private AliasResolver() {
    }

    public static Map<String, Set<String>> resolveAliases(MethodDeclaration method) {
        Map<String, Set<String>> aliasMap = new HashMap<>();

        for (VariableDeclarator declarator : method.findAll(VariableDeclarator.class)) {
            if (declarator.getInitializer().isPresent()) {
                Expression initializer = declarator.getInitializer().get();
                if (isSimpleInitializer(initializer)) {
                    Set<String> ids = DependencyFilter.identifiersIn(initializer);
                    aliasMap.computeIfAbsent(declarator.getNameAsString(), k -> new HashSet<>()).addAll(ids);
                }
            }
        }

        for (AssignExpr assign : method.findAll(AssignExpr.class)) {
            Expression target = assign.getTarget();
            if (target instanceof NameExpr) {
                Expression value = assign.getValue();
                if (isSimpleInitializer(value)) {
                    Set<String> ids = DependencyFilter.identifiersIn(value);
                    aliasMap.computeIfAbsent(((NameExpr) target).getNameAsString(), k -> new HashSet<>()).addAll(ids);
                }
            }
        }

        return aliasMap;
    }

    private static boolean isSimpleInitializer(Expression expr) {
        return expr instanceof NameExpr || expr instanceof FieldAccessExpr || expr instanceof ThisExpr;
    }
}
