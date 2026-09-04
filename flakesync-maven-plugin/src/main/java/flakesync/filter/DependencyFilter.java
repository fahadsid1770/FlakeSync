package flakesync.filter;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SynchronizedStmt;
import com.github.javaparser.ast.stmt.TryStmt;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Static dependency filter for FlakeSync's barrier-point candidate search.
 *
 * This is a direct port of the Python/javalang prototype validated on two
 * real examples (FlakeSync's own Agent.java, and the real Apache Uniffle
 * GrpcServerTest ground truth) -- see RESULTS.md in the POC package for
 * those numbers. Nothing here has been run yet against this Maven module
 * (JavaParser can't be exercised from the sandbox this was written in --
 * no Maven Central access), so treat this as "should be correct by
 * construction, needs a real `mvn test` pass to confirm."
 *
 * KNOWN LIMITATIONS (see advisor's remarks -- these are the next hardening
 * pass, not yet implemented here):
 *   - Matching is by identifier/lock-expression NAME, not full symbol
 *     resolution. Field aliases (`Foo x = this.bar;`), heap-object identity
 *     (two unrelated objects both having a field called `count`), and
 *     interprocedural reach (a called method that internally touches the
 *     resource, e.g. afterExecute()) are NOT yet handled. See
 *     DependencyCandidateFilter's class comment for the planned extension
 *     points.
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
    public static List<StatementRecord> extractStatementRecords(MethodDeclaration method, List<String> sourceLines) {
        List<StatementRecord> records = new ArrayList<>();
        Optional<BlockStmt> body = method.getBody();
        if (body.isPresent()) {
            walk(body.get().getStatements(), null, records, sourceLines);
        }
        return records;
    }

    /** Filter candidates to those sharing the same synchronization-lock
     * resource as the critical point. Use when the critical point is itself
     * inside a synchronized block -- the sharpest signal we have for
     * lock/monitor-based concurrency bugs (validated on Agent.java: 95.8%
     * reduction, correctly excluding an unrelated synchronized block). */
    public static List<Integer> filterBySharedLock(List<StatementRecord> records, int criticalLine) {
        StatementRecord critical = records.stream()
                .filter(r -> r.line == criticalLine).findFirst().orElse(null);
        List<Integer> result = new ArrayList<>();
        if (critical == null || critical.syncResource == null) {
            return result; // no lock context to key off -- caller should try the other filter
        }
        for (StatementRecord r : records) {
            if (critical.syncResource.equals(r.syncResource)) {
                result.add(r.line);
            }
        }
        return result;
    }

    /** Filter candidates to those referencing any of the given resource
     * names (e.g. a shared field or metric-key constant). Use when the
     * critical point and candidates are in different methods/files and
     * there's no shared lock object to key off -- validated on the real
     * GrpcServerTest example: 71.4% reduction, true barrier point
     * retained. */
    public static List<Integer> filterByResourceNames(List<StatementRecord> records, Set<String> resourceNames) {
        List<Integer> result = new ArrayList<>();
        for (StatementRecord r : records) {
            if (!Collections.disjoint(r.identifiers, resourceNames)) {
                result.add(r.line);
            }
        }
        return result;
    }

    // ---- internal walking / identifier extraction ----

    private static void walk(NodeList<Statement> stmts, String enclosingSync,
                              List<StatementRecord> records, List<String> sourceLines) {
        for (Statement stmt : stmts) {
            Optional<Position> pos = stmt.getBegin();
            if (!pos.isPresent()) {
                continue;
            }
            int line = pos.get().line;

            if (stmt.isSynchronizedStmt()) {
                SynchronizedStmt sync = stmt.asSynchronizedStmt();
                Set<String> lockIds = identifiersIn(sync.getExpression());
                String lockName = lockIds.isEmpty() ? null
                        : lockIds.stream().sorted().collect(Collectors.joining("/"));
                walk(sync.getBody().getStatements(), lockName, records, sourceLines);
            } else if (stmt.isTryStmt()) {
                TryStmt tryStmt = stmt.asTryStmt();
                walk(tryStmt.getTryBlock().getStatements(), enclosingSync, records, sourceLines);
                for (CatchClause c : tryStmt.getCatchClauses()) {
                    walk(c.getBody().getStatements(), enclosingSync, records, sourceLines);
                }
                tryStmt.getFinallyBlock().ifPresent(f -> walk(f.getStatements(), enclosingSync, records, sourceLines));
            } else if (stmt.isIfStmt()) {
                IfStmt ifStmt = stmt.asIfStmt();
                Set<String> condIds = identifiersIn(ifStmt.getCondition());
                int before = records.size();
                walkSingleOrBlock(ifStmt.getThenStmt(), enclosingSync, records, sourceLines);
                ifStmt.getElseStmt().ifPresent(e -> walkSingleOrBlock(e, enclosingSync, records, sourceLines));
                // lines under this branch are control-dependent on the condition's identifiers
                for (int i = before; i < records.size(); i++) {
                    records.get(i).identifiers.addAll(condIds);
                }
            } else if (stmt.isForStmt() || stmt.isForEachStmt()) {
                Statement body = stmt.isForStmt()
                        ? stmt.asForStmt().getBody()
                        : stmt.asForEachStmt().getBody();
                walkSingleOrBlock(body, enclosingSync, records, sourceLines);
            } else if (stmt.isWhileStmt()) {
                walkSingleOrBlock(stmt.asWhileStmt().getBody(), enclosingSync, records, sourceLines);
            } else {
                Set<String> ids = identifiersIn(stmt);
                String text = (line - 1 >= 0 && line - 1 < sourceLines.size())
                        ? sourceLines.get(line - 1).trim() : "";
                records.add(new StatementRecord(line, ids, enclosingSync, text));
            }
        }
    }

    private static void walkSingleOrBlock(Statement stmt, String enclosingSync,
                                           List<StatementRecord> records, List<String> sourceLines) {
        if (stmt.isBlockStmt()) {
            walk(stmt.asBlockStmt().getStatements(), enclosingSync, records, sourceLines);
        } else {
            NodeList<Statement> single = new NodeList<>();
            single.add(stmt);
            walk(single, enclosingSync, records, sourceLines);
        }
    }

    /** Collect variable/field/method-call-target names referenced under a node.
     * Name-based only -- see class comment for known limitations. */
    private static Set<String> identifiersIn(Node node) {
        Set<String> ids = new HashSet<>();
        if (node == null) {
            return ids;
        }
        for (NameExpr n : node.findAll(NameExpr.class)) {
            ids.add(n.getNameAsString());
        }
        for (FieldAccessExpr f : node.findAll(FieldAccessExpr.class)) {
            ids.add(f.getNameAsString());
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
}
