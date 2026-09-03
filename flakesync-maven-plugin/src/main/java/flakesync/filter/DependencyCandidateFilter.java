package flakesync.filter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
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
 * Any candidate not matched by either signal is still appended at the end
 * of the returned list (see CandidateFilter's class comment) -- so this is
 * a reordering, not an exclusion, and combined with BarrierPointMojo's
 * fallback loop it cannot cause FlakeSync to miss a valid repair it would
 * otherwise have found.
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
 * IMPORTANT: this has not yet been run inside an actual `mvn` build --
 * written to be correct by construction against the validated Python
 * prototype, but needs a real compile + test pass to confirm. See
 * README in the POC package for the sandbox limitation that prevented
 * testing this here.
 */
public final class DependencyCandidateFilter implements CandidateFilter {

    @Override
    public List<Integer> orderCandidates(File criticalFile, int criticalLine,
                                          File candidateFile, List<Integer> fullRange) {
        try {
            List<StatementRecord> criticalRecords = extractAllRecords(criticalFile);
            StatementRecord critical = criticalRecords.stream()
                    .filter(r -> r.line == criticalLine).findFirst().orElse(null);

            if (critical == null) {
                // Critical line isn't inside any method body we could walk
                // (e.g. a lambda, static initializer, or field declarer) --
                // known limitation. Fall back to unfiltered order rather
                // than risk silently excluding the true barrier point.
                return new ArrayList<>(fullRange);
            }

            List<StatementRecord> candidateRecords = criticalFile.equals(candidateFile)
                    ? criticalRecords : extractAllRecords(candidateFile);

            Set<Integer> inRange = new HashSet<>(fullRange);
            List<Integer> ordered = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();

            if (critical.syncResource != null) {
                for (int line : DependencyFilter.filterBySharedLock(candidateRecords, criticalLine)) {
                    if (inRange.contains(line) && seen.add(line)) {
                        ordered.add(line);
                    }
                }
            }
            if (ordered.isEmpty()) {
                // No shared-lock signal available (or critical point isn't
                // itself in a synchronized block) -- fall back to
                // shared-identifier overlap, e.g. the GrpcServerTest case.
                Set<String> resource = new HashSet<>(critical.identifiers);
                for (int line : DependencyFilter.filterByResourceNames(candidateRecords, resource)) {
                    if (inRange.contains(line) && seen.add(line)) {
                        ordered.add(line);
                    }
                }
            }
            // Append everything else in fullRange, original order, as fallback.
            for (int line : fullRange) {
                if (seen.add(line)) {
                    ordered.add(line);
                }
            }
            return ordered;
        } catch (IOException ioException) {
            // Parsing failed for some reason -- don't let a filter bug
            // break the actual search; fall back to unfiltered order.
            return new ArrayList<>(fullRange);
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
