package flakesync.filter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
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
 * NOT YET IMPLEMENTED -- next hardening pass per advisor's request:
 *   - Alias tracking: resolve simple local assignment chains (`Foo x =
 *     this.bar;`) before computing identifiers, so `x` and `bar` are
 *     recognized as the same resource.
 *   - Heap-object identity: use javaparser-symbol-solver-core to resolve
 *     field accesses to a specific declared type + field, not just a bare
 *     name, so two unrelated objects with a same-named field don't
 *     false-match.
 *   - Interprocedural reach: when a candidate line calls a method whose own
 *     body touches the critical resource, count that as a match too
 *     (one-hop call reachability) -- needed for cases like afterExecute()
 *     in the GrpcServer example, where the real dependency is inside the
 *     callback body, not visible at the call/registration site.
 *   - Callback recognition: overridden framework callback methods should be
 *     treated as connected to whatever resource they touch, even when
 *     nothing at their registration site mentions it textually.
 *
 * Verified against the Python/javalang prototype's exact numbers via
 * DependencyFilterVerificationTest (Agent.java: 48-&gt;2/95.8%; GrpcServerTest:
 * 21-&gt;6/71.4%, true barrier point retained) -- confirmed passing after
 * integration and compilation.
 */
public final class DependencyCandidateFilter implements CandidateFilter {

    @Override
    public List<Integer> priorityCandidates(File criticalFile, int criticalLine,
                                             File candidateFile, List<Integer> fullRange) {
        try {
            List<StatementRecord> criticalRecords = extractAllRecords(criticalFile);
            StatementRecord critical = criticalRecords.stream()
                    .filter(r -> r.line == criticalLine).findFirst().orElse(null);

            if (critical == null) {
                // Critical line isn't inside any method body we could walk
                // (e.g. a lambda, static initializer, or field declarer) --
                // known limitation. No signal -- report as such.
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
                // No shared-lock signal available (or critical point isn't
                // itself in a synchronized block) -- fall back to
                // shared-identifier overlap, e.g. the GrpcServerTest case.
                Set<String> resource = new HashSet<>(critical.identifiers);
                for (int line : DependencyFilter.filterByResourceNames(candidateRecords, resource)) {
                    if (inRange.contains(line) && seen.add(line)) {
                        priority.add(line);
                    }
                }
            }
            return priority;
        } catch (IOException ioException) {
            // Parsing failed for some reason -- don't let a filter bug
            // break the actual search; report no signal.
            return Collections.emptyList();
        }
    }

    private List<StatementRecord> extractAllRecords(File file) throws IOException {
        CompilationUnit cu = StaticJavaParser.parse(file);
        List<String> lines = Files.readAllLines(file.toPath());
        List<StatementRecord> all = new ArrayList<>();
        for (MethodDeclaration m : cu.findAll(MethodDeclaration.class)) {
            all.addAll(DependencyFilter.extractStatementRecords(m, lines));
        }
        return all;
    }
}
