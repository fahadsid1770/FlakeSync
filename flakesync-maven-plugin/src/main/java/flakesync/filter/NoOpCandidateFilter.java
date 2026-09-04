package flakesync.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Baseline A for the evaluation: no filtering at all. Reports the entire
 * fullRange as "priority" (i.e. 0% reduction, everything tried up front in
 * original order) so BarrierPointMojo's search behaves byte-for-byte like
 * unmodified FlakeSync. Use `-Dflakesync.filterMode=none` to select this.
 */
public final class NoOpCandidateFilter implements CandidateFilter {

    @Override
    public List<Integer> priorityCandidates(File criticalFile, int criticalLine,
                                             File candidateFile, List<Integer> fullRange) {
        return new ArrayList<>(fullRange);
    }
}
