package flakesync.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Baseline B for the evaluation: a deliberately "dumb" filter with no code
 * understanding at all -- pure line-number proximity to the critical point,
 * within the same file. This isolates how much of DependencyCandidateFilter's
 * benefit comes from genuine dependency information vs. just "nearby code is
 * more likely relevant."
 *
 * Only applies when the critical point and the candidate range are in the
 * SAME file (as in the Agent.java-style same-file search loop). When they're
 * in different files (as in the GrpcServerTest-style cross-file search loop),
 * comparing raw line numbers across files is meaningless, so this filter has
 * no signal and returns an empty priority list -- i.e. 0% reduction for that
 * case. That's an intentional, meaningful result to report, not a bug: it
 * shows a naive proximity baseline has literally no answer for cross-file
 * dependencies, whereas DependencyCandidateFilter does (via shared resource
 * names, as validated on the real GrpcServerTest example).
 *
 * Use `-Dflakesync.filterMode=proximity` to select this, and
 * `-Dflakesync.proximityWindow=N` to change the window size (default 15
 * lines each direction from the critical point).
 */
public final class ProximityCandidateFilter implements CandidateFilter {

    private final int windowSize;

    public ProximityCandidateFilter() {
        this(15);
    }

    public ProximityCandidateFilter(int windowSize) {
        this.windowSize = windowSize;
    }

    @Override
    public List<Integer> priorityCandidates(File criticalFile, int criticalLine,
                                             File candidateFile, List<Integer> fullRange) {
        List<Integer> priority = new ArrayList<>();
        if (criticalFile == null || candidateFile == null || !criticalFile.equals(candidateFile)) {
            return priority; // cross-file: no meaningful line-number proximity, no signal
        }
        for (int line : fullRange) {
            if (Math.abs(line - criticalLine) <= windowSize) {
                priority.add(line);
            }
        }
        // Closest lines first -- purely numeric, no parsing or understanding involved.
        priority.sort(Comparator.comparingInt(line -> Math.abs(line - criticalLine)));
        return priority;
    }
}
