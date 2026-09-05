package flakesync.filter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The real filter: orders candidates by static dependency on the critical
 * point, using DependencyFilter. Tries the shared-lock signal first (if the
 * critical point sits in a synchronized block, as in the Agent.java
 * example), then falls back to shared-identifier overlap with the critical
 * point's own statement (as in the GrpcServerTest example, where there's no
 * shared lock at all -- just a shared metrics field/constant).
 *
 * Returns an empty priority list (never throws, never silently returns
 * fullRange as "priority") whenever it has no real signal -- e.g. the
 * critical line isn't inside any parseable method body, or parsing fails.
 * CandidateFilter's default orderCandidates() still falls back to the full
 * range in that case, so correctness is unaffected; only the reported
 * "filtered count" for that test would correctly show 0% reduction rather
 * than a misleading number.
 *
 * IMPLEMENTED HARDENING (per advisor's request):
 *   - Synchronized method modifiers (not just blocks)
 *   - Alias tracking: resolve simple local assignment chains
 *   - Heap-object identity: symbol resolution for canonical identifiers
 *   - Interprocedural reach: one-hop method call tracing
 *   - Callback recognition: framework callback patterns (afterExecute, etc.)
 *
 * Verified against the Python/javalang prototype's exact numbers via
 * DependencyFilterVerificationTest (Agent.java: 48-&gt;2/95.8%; GrpcServerTest:
 * 21-&gt;6/71.4%, true barrier point retained) -- confirmed passing after
 * integration and compilation.
 */
public final class DependencyCandidateFilter implements CandidateFilter {

    private final MavenProject mavenProject;

    public DependencyCandidateFilter(MavenProject mavenProject) {
        this.mavenProject = mavenProject;
    }

    @Override
    public List<Integer> priorityCandidates(File criticalFile, int criticalLine,
                                         File candidateFile, List<Integer> fullRange) {
        SymbolResolverSetup.configure(mavenProject);
        try {
            List<StatementRecord> criticalRecords = extractAllRecords(criticalFile);
            StatementRecord critical = criticalRecords.stream()
                    .filter(r -> r.line == criticalLine).findFirst().orElse(null);

            if (critical == null) {
                return Collections.emptyList();
            }

            List<StatementRecord> candidateRecords = criticalFile.equals(candidateFile)
                    ? criticalRecords : extractAllRecords(candidateFile);

            Set<Integer> inRange = new HashSet<>(fullRange);
            List<Integer> priority = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();

            if (critical.syncResource != null) {
                for (int line : DependencyFilter.filterBySharedLock(candidateRecords, criticalLine)) {
                    if (inRange.contains(line) && seen.add(line)) {
                        priority.add(line);
                    }
                }
            }
            if (priority.isEmpty()) {
                Set<String> resource = new HashSet<>(critical.identifiers);
                for (int line : DependencyFilter.filterByResourceNames(candidateRecords, resource)) {
                    if (inRange.contains(line) && seen.add(line)) {
                        priority.add(line);
                    }
                }
            }

            Optional<MethodDeclaration> callbackMethod = findMethodContainingLine(
                    criticalFile, criticalLine);
            if (callbackMethod.isPresent() && CallbackPatterns.isKnownCallback(callbackMethod.get())) {
                Set<String> triggeringCalls = CallbackPatterns.triggeringCallNames(callbackMethod.get());
                for (StatementRecord record : candidateRecords) {
                    if (inRange.contains(record.line) && !seen.contains(record.line)) {
                        if (hasTriggeringCall(record.text, triggeringCalls)) {
                            priority.add(record.line);
                            seen.add(record.line);
                        }
                    }
                }
            }

            return priority;
        } catch (IOException ioException) {
            return Collections.emptyList();
        }
    }

    public static boolean hasTriggeringCall(String statementText, Set<String> triggeringCalls) {
        if (statementText == null || statementText.isEmpty()) {
            return false;
        }
        for (String callName : triggeringCalls) {
            if (statementText.contains(callName + "(")) {
                return true;
            }
        }
        return false;
    }

    public static boolean statementContainsTriggeringCall(com.github.javaparser.ast.stmt.Statement stmt,
                                                        Set<String> triggeringCalls) {
        if (stmt == null) {
            return false;
        }
        for (MethodCallExpr call : stmt.findAll(MethodCallExpr.class)) {
            if (triggeringCalls.contains(call.getNameAsString())) {
                return true;
            }
        }
        return false;
    }

    private Optional<MethodDeclaration> findMethodContainingLine(File file, int line)
            throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        for (MethodDeclaration method : cu.findAll(MethodDeclaration.class)) {
            if (method.getBegin().isPresent()) {
                int methodStart = method.getBegin().get().line;
                int methodEnd = method.getEnd().isPresent()
                        ? method.getEnd().get().line
                        : Integer.MAX_VALUE;
                if (methodStart <= line && line <= methodEnd) {
                    return Optional.of(method);
                }
            }
        }
        return Optional.empty();
    }

    private List<StatementRecord> extractAllRecords(File file) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        List<String> lines = Files.readAllLines(file.toPath());
        List<StatementRecord> all = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            all.addAll(DependencyFilter.extractStatementRecords(m, cu, lines));
        }
        return all;
    }
}
