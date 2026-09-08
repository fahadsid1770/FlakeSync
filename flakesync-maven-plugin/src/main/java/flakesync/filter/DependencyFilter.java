package flakesync.filter;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForEachStmt;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;
import com.github.javaparser.ast.stmt.WhileStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static dependency filter for FlakeSync's barrier-point candidate search.
 *
 * Supports synchronized methods/blocks, field aliases via local variable
 * tracking, canonical identifiers from symbol resolution, one-hop method
 * call tracing, and framework callback patterns.
 */
public final class DependencyFilter {

    private DependencyFilter() {
    }

    /** Find the MethodDeclaration named methodName. If several methods share
     * that name (e.g. nested/anonymous classes), pick the one whose starting
     * line is closest to lineHint. lineHint may be null if there's only one
     * candidate. */
    public static MethodDeclaration extractMethod(CompilationUnit cu, String methodName, Integer lineHint) {
        List<MethodDeclaration> candidates = cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(methodName))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            throw new IllegalArgumentException("Method '" + methodName + "' not found");
        }
        if (candidates.size() == 1 || lineHint == null) {
            return candidates.get(0);
        }
        MethodDeclaration best = null;
        int bestDist = Integer.MAX_VALUE;
        for (MethodDeclaration m : candidates) {
            int line = m.getBegin().map(p -> p.line).orElse(0);
            int dist = Math.abs(line - lineHint);
            if (dist < bestDist) {
                bestDist = dist;
                best = m;
            }
        }
        return best;
    }

    /** Walk a method body and produce one StatementRecord per statement,
     * descending into synchronized/try/if/for/while blocks so nested
     * statements get their own precise line + identifier set, rather than
     * being lumped into one coarse record for the whole enclosing block. */
    public static List<StatementRecord> extractStatementRecords(MethodDeclaration method,
                                                               CompilationUnit enclosingCu,
                                                               List<String> sourceLines) {
        List<StatementRecord> records = new ArrayList<>();
        Optional<BlockStmt> body = method.getBody();
        if (body.isPresent()) {
            String methodSyncResource = getMethodSyncResource(method);
            Map<String, Set<String>> aliasMap = AliasResolver.resolveAliases(method);
            walk(body.get().getStatements(), methodSyncResource, aliasMap, records,
                    sourceLines, enclosingCu);
        }
        return records;
    }

    private static String getMethodSyncResource(MethodDeclaration method) {
        boolean isSynchronized = method.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.SYNCHRONIZED);
        if (!isSynchronized) {
            return null;
        }
        boolean isStatic = method.getModifiers().stream()
                .anyMatch(m -> m.getKeyword() == com.github.javaparser.ast.Modifier.Keyword.STATIC);
        String enclosingClassName = method.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                .map(t -> t.getNameAsString())
                .orElse("UnknownClass");
        return isStatic ? "CLASS:" + enclosingClassName : "THIS:" + enclosingClassName;
    }

    /** Filter candidates to those sharing the same synchronization-lock
     * resource as the critical point. Use when the critical point is itself
     * inside a synchronized block. */
    public static List<Integer> filterBySharedLock(List<StatementRecord> records, int criticalLine) {
        StatementRecord critical = records.stream()
                .filter(r -> r.line <= criticalLine && criticalLine <= r.endLine)
                .findFirst().orElse(null);
        List<Integer> result = new ArrayList<>();
        if (critical == null || critical.syncResource == null) {
            return result; // no lock context to key off -- caller should try the other filter
        }
        for (StatementRecord r : records) {
            if (critical.syncResource.equals(r.syncResource)) {
                for (int l = r.line; l <= r.endLine; l++) {
                    result.add(l);
                }
            }
        }
        return result;
    }

    /** Filter candidates to those referencing any of the given resource
     * names (e.g. a shared field or metric-key constant). Use when the
     * critical point and candidates are in different methods/files and
     * there's no shared lock object to key off. */
    public static List<Integer> filterByResourceNames(List<StatementRecord> records, Set<String> resourceNames) {
        List<Integer> result = new ArrayList<>();
        for (StatementRecord r : records) {
            if (!Collections.disjoint(r.identifiers, resourceNames)) {
                for (int l = r.line; l <= r.endLine; l++) {
                    result.add(l);
                }
            }
        }
        return result;
    }

    /**
     * Expands identifiers in a statement by tracing one-hop into called methods.
     * Only traces calls that go through project source; calls into JDK/library
     * internals are handled separately by CallbackPatterns.
     */
    public static Set<String> transitiveIdentifiers(Statement stmt, int maxDepth) {
        return transitiveIdentifiersWithCu(stmt, null, maxDepth);
    }

    public static Set<String> transitiveIdentifiersWithCu(Statement stmt, CompilationUnit enclosingCu,
                                                         int maxDepth) {
        Set<String> ids = new HashSet<>(identifiersIn(stmt));
        if (maxDepth <= 0) {
            return ids;
        }
        Set<String> visited = new HashSet<>();
        transitiveIdentifiersRecursive(stmt, ids, visited, maxDepth);
        return ids;
    }

    private static void transitiveIdentifiersRecursive(Statement stmt, Set<String> ids,
                                                       Set<String> visited, int remainingDepth) {
        if (remainingDepth <= 0) {
            return;
        }
        for (MethodCallExpr call : stmt.findAll(MethodCallExpr.class)) {
            try {
                com.github.javaparser.resolution.declarations.ResolvedMethodDeclaration resolved =
                        call.resolve();
                String signature = resolved.getQualifiedSignature();
                if (visited.contains(signature)) {
                    continue;
                }
                visited.add(signature);
                Optional<Node> calledAst = resolved.toAst();
                if (calledAst.isPresent() && calledAst.get() instanceof MethodDeclaration) {
                    MethodDeclaration calledMethod = (MethodDeclaration) calledAst.get();
                    Optional<BlockStmt> body = calledMethod.getBody();
                    if (body.isPresent()) {
                        ids.addAll(identifiersIn(body.get()));
                        transitiveIdentifiersRecursive(body.get(), ids, visited, remainingDepth - 1);
                    }
                }
            } catch (Exception ex) {
                // Most method calls (JDK collections, logging, etc.) fail to
                // resolve to an in-project AST; this is the expected case.
                assert ex != null;
            }
        }
    }

    private static String getSynchronizedLockName(SynchronizedStmt sync, Statement stmt) {
        Node expr = sync.getExpression();
        if (expr instanceof ThisExpr) {
            String className = sync.findAncestor(com.github.javaparser.ast.body.TypeDeclaration.class)
                    .map(t -> t.getNameAsString())
                    .orElse("UnknownClass");
            return "THIS:" + className;
        }
        Set<String> lockIds = identifiersIn(expr);
        return lockIds.isEmpty() ? null : lockIds.stream().sorted().collect(Collectors.joining("/"));
    }

    // ---- internal walking / identifier extraction ----

    private static void walk(NodeList<Statement> stmts, String enclosingSync,
                              Map<String, Set<String>> aliasMap,
                              List<StatementRecord> records, List<String> sourceLines,
                              CompilationUnit enclosingCu) {
        for (Statement stmt : stmts) {
            Optional<Position> pos = stmt.getBegin();
            if (!pos.isPresent()) {
                continue;
            }
            int line = pos.get().line;

            if (stmt.isSynchronizedStmt()) {
                SynchronizedStmt sync = stmt.asSynchronizedStmt();
                String lockName = getSynchronizedLockName(sync, stmt);
                Set<String> ids = identifiersIn(sync.getExpression());
                Set<String> expanded = expandWithAliases(ids, aliasMap);
                String text = (line - 1 >= 0 && line - 1 < sourceLines.size())
                        ? sourceLines.get(line - 1).trim() : "";
                records.add(new StatementRecord(line, line, expanded, lockName, text));
                walk(sync.getBody().getStatements(), lockName, aliasMap, records,
                        sourceLines, enclosingCu);
            } else if (stmt.isTryStmt()) {
                TryStmt tryStmt = stmt.asTryStmt();
                walk(tryStmt.getTryBlock().getStatements(), enclosingSync, aliasMap,
                        records, sourceLines, enclosingCu);
                for (CatchClause c : tryStmt.getCatchClauses()) {
                    walk(c.getBody().getStatements(), enclosingSync, aliasMap,
                            records, sourceLines, enclosingCu);
                }
                tryStmt.getFinallyBlock().ifPresent(f ->
                    walk(f.getStatements(), enclosingSync, aliasMap, records,
                            sourceLines, enclosingCu));
            } else if (stmt.isIfStmt()) {
                IfStmt ifStmt = stmt.asIfStmt();
                Set<String> condIds = identifiersIn(ifStmt.getCondition());
                Set<String> expanded = expandWithAliases(condIds, aliasMap);
                String text = (line - 1 >= 0 && line - 1 < sourceLines.size())
                        ? sourceLines.get(line - 1).trim() : "";
                records.add(new StatementRecord(line, line, expanded, enclosingSync, text));
                int before = records.size();
                walkSingleOrBlock(ifStmt.getThenStmt(), enclosingSync, aliasMap,
                        records, sourceLines, enclosingCu);
                ifStmt.getElseStmt().ifPresent(e ->
                        walkSingleOrBlock(e, enclosingSync, aliasMap, records,
                                sourceLines, enclosingCu));
                for (int i = before; i < records.size(); i++) {
                    records.get(i).identifiers.addAll(condIds);
                }
            } else if (stmt.isForEachStmt()) {
                ForEachStmt forEachStmt = stmt.asForEachStmt();
                int headerLine = forEachStmt.getIterable().getBegin().map(p -> p.line).orElse(line);
                Set<String> ids = identifiersIn(forEachStmt.getIterable());
                Set<String> expanded = expandWithAliases(ids, aliasMap);
                String text = (headerLine - 1 >= 0 && headerLine - 1 < sourceLines.size())
                        ? sourceLines.get(headerLine - 1).trim() : "";
                records.add(new StatementRecord(headerLine, headerLine, expanded, enclosingSync, text));
                Statement body = forEachStmt.getBody();
                walkSingleOrBlock(body, enclosingSync, aliasMap, records,
                        sourceLines, enclosingCu);
            } else if (stmt.isForStmt()) {
                ForStmt forStmt = stmt.asForStmt();
                Set<String> ids = new HashSet<>();
                for (com.github.javaparser.ast.expr.Expression init : forStmt.getInitialization()) {
                    ids.addAll(identifiersIn(init));
                }
                if (forStmt.getCompare().isPresent()) {
                    ids.addAll(identifiersIn(forStmt.getCompare().get()));
                }
                for (com.github.javaparser.ast.expr.Expression update : forStmt.getUpdate()) {
                    ids.addAll(identifiersIn(update));
                }
                Set<String> expanded = expandWithAliases(ids, aliasMap);
                String text = (line - 1 >= 0 && line - 1 < sourceLines.size())
                        ? sourceLines.get(line - 1).trim() : "";
                records.add(new StatementRecord(line, line, expanded, enclosingSync, text));
                Statement body = forStmt.getBody();
                walkSingleOrBlock(body, enclosingSync, aliasMap, records,
                        sourceLines, enclosingCu);
            } else if (stmt.isWhileStmt()) {
                WhileStmt whileStmt = stmt.asWhileStmt();
                Set<String> ids = identifiersIn(whileStmt.getCondition());
                Set<String> expanded = expandWithAliases(ids, aliasMap);
                String text = (line - 1 >= 0 && line - 1 < sourceLines.size())
                        ? sourceLines.get(line - 1).trim() : "";
                records.add(new StatementRecord(line, line, expanded, enclosingSync, text));
                walkSingleOrBlock(whileStmt.getBody(), enclosingSync,
                        aliasMap, records, sourceLines, enclosingCu);
            } else {
                Set<String> ids = identifiersIn(stmt);
                Set<String> expanded = expandWithAliases(ids, aliasMap);
                Set<String> transitive = transitiveIdentifiers(stmt, 2);
                expanded.addAll(transitive);
                String text = (line - 1 >= 0 && line - 1 < sourceLines.size())
                        ? sourceLines.get(line - 1).trim() : "";
                int endLine = stmt.getEnd().map(p -> p.line).orElse(line);
                records.add(new StatementRecord(line, endLine, expanded, enclosingSync, text));
            }
        }
    }

    private static Set<String> expandWithAliases(Set<String> ids, Map<String, Set<String>> aliasMap) {
        Set<String> expanded = new HashSet<>(ids);
        for (String id : ids) {
            if (aliasMap.containsKey(id)) {
                expanded.addAll(aliasMap.get(id));
            }
        }
        return expanded;
    }

    private static void walkSingleOrBlock(Statement stmt, String enclosingSync,
                                           Map<String, Set<String>> aliasMap,
                                           List<StatementRecord> records, List<String> sourceLines,
                                           CompilationUnit enclosingCu) {
        if (stmt.isBlockStmt()) {
            walk(stmt.asBlockStmt().getStatements(), enclosingSync, aliasMap,
                    records, sourceLines, enclosingCu);
        } else {
            NodeList<Statement> single = new NodeList<>();
            single.add(stmt);
            walk(single, enclosingSync, aliasMap, records, sourceLines, enclosingCu);
        }
    }

    /** Collect variable/field/method-call-target names referenced under a node.
     * Uses symbol resolution to produce canonical identifiers (declaringType.fieldName)
     * when possible, while also retaining bare names for backwards compatibility. */
    static Set<String> identifiersIn(Node node) {
        Set<String> ids = new HashSet<>();
        if (node == null) {
            return ids;
        }
        for (NameExpr n : node.findAll(NameExpr.class)) {
            ids.add(n.getNameAsString());
            String canonical = canonicalizeNameExpr(n);
            if (canonical != null) {
                ids.add(canonical);
            }
        }
        for (FieldAccessExpr f : node.findAll(FieldAccessExpr.class)) {
            ids.add(f.getNameAsString());
            String canonical = canonicalizeFieldAccess(f);
            if (canonical != null) {
                ids.add(canonical);
            }
            if (f.getScope() instanceof NameExpr) {
                ids.add(((NameExpr) f.getScope()).getNameAsString());
            }
        }
        for (MethodCallExpr m : node.findAll(MethodCallExpr.class)) {
            ids.add(m.getNameAsString());
            m.getScope().ifPresent(scope -> {
                if (scope instanceof NameExpr) {
                    ids.add(((NameExpr) scope).getNameAsString());
                }
            });
        }
        return ids;
    }

    private static String canonicalizeNameExpr(NameExpr nameExpr) {
        try {
            com.github.javaparser.resolution.declarations.ResolvedValueDeclaration resolved =
                    nameExpr.resolve();
            if (resolved.isField()) {
                return resolved.asField().declaringType().getQualifiedName()
                        + "." + resolved.getName();
            }
        } catch (Exception ex) {
            // Resolution failed -- fall back to bare name only
            assert ex != null;
        }
        return null;
    }

    private static String canonicalizeFieldAccess(FieldAccessExpr fieldAccess) {
        try {
            com.github.javaparser.resolution.declarations.ResolvedValueDeclaration resolved =
                    fieldAccess.resolve();
            return resolved.asField().declaringType().getQualifiedName()
                    + "." + resolved.getName();
        } catch (Exception ex) {
            // Resolution failed -- fall back to bare name only
            return null;
        }
    }
}
