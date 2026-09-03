package flakesync.filter;

import java.io.File;
import java.util.List;

/**
 * Produces an ORDERED list of candidate barrier-point line numbers to try,
 * given the critical point's location and the full brute-force candidate
 * range that BarrierPointMojo would otherwise try in order.
 *
 * Design note: this is a REORDERING interface, not a hard-exclusion one.
 * BarrierPointMojo tries the returned list first, then falls back to any
 * candidates from fullRange not already tried, in their original order,
 * if nothing in the filtered list produces a valid passing repair. That
 * fallback is what guarantees a filter can only change SPEED, never
 * correctness -- directly addressing the advisor's remark that filtering
 * should not risk missing a valid repair. Implementations are still
 * expected to return a (typically much smaller) prioritized subset first;
 * returning fullRange unchanged is always a valid, safe implementation
 * (see NoOpCandidateFilter, used as the unfiltered baseline).
 */
public interface CandidateFilter {

    /**
     * Orders candidate barrier-point lines by priority using static analysis.
     *
     * @param criticalFile  file containing the critical point
     * @param criticalLine  1-based line number of the critical point
     * @param candidateFile file containing the candidate barrier-point range
     * @param fullRange     the full brute-force candidate line numbers, in
     *                      the order BarrierPointMojo would normally try them
     * @return the lines to try first (order matters), followed internally
     *         by BarrierPointMojo falling back to whatever remains
     */
    List<Integer> orderCandidates(File criticalFile, int criticalLine,
                                   File candidateFile, List<Integer> fullRange);
}
