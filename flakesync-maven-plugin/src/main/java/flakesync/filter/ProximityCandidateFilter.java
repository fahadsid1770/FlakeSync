package flakesync.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Baseline B for the evaluation: a deliberately "dumb" filter with no code
 * understanding -- pure line-number proximity to the critical point within
 * the same file. This isolates how much of DependencyCandidateFilter's
 * benefit comes from genuine dependency info vs. just "nearby code is more
 * likely relevant."
 *
 * Only applies when the critical point and candidate range are in the SAME
 * file; across files, raw line numbers are meaningless and it returns an
 * empty priority list (0% reduction), an intentional result to report.
 *
 * Select with `-Dflakesync.filterMode=proximity`; adjust the window with
 * `-Dflakesync.proximityWindow=N` (default 15 lines each direction).
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
