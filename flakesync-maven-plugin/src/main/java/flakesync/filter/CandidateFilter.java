package flakesync.filter;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Produces a prioritized subset of candidate barrier-point line numbers,
 * given the critical point's location and the full brute-force candidate
 * range that BarrierPointMojo would otherwise try in order.
 *
 * Implementations provide {@link #priorityCandidates}: the lines this
 * filter is confident about, in priority order. {@link #orderCandidates}
 * is a default method built on top of it that tries priority candidates
 * first, then falls back to the rest of fullRange in its original order.
 * That fallback guarantees a filter can only change SPEED, never
 * correctness.
 */
public interface CandidateFilter {

    /**
     * Returns the subset of fullRange this filter is confident about, in
     * priority order, using static analysis.
     *
     * @param criticalFile  file containing the critical point
     * @param criticalLine  1-based line number of the critical point
     * @param candidateFile file containing the candidate barrier-point range
     * @param fullRange     the full brute-force candidate line numbers
     * @return the subset of fullRange this filter is confident about, in
     *         priority order. Return an empty list if the filter has no
     *         useful signal for this case.
     */
    List<Integer> priorityCandidates(File criticalFile, int criticalLine,
                                      File candidateFile, List<Integer> fullRange);

    /**
     * Priority candidates first, then the rest of fullRange as fallback, in
     * its original order. This is what BarrierPointMojo actually iterates.
     */
    default List<Integer> orderCandidates(File criticalFile, int criticalLine,
                                           File candidateFile, List<Integer> fullRange) {
        List<Integer> priority = priorityCandidates(criticalFile, criticalLine, candidateFile, fullRange);
        Set<Integer> inRange = new HashSet<>(fullRange);
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (int line : priority) {
            if (inRange.contains(line) && seen.add(line)) {
                ordered.add(line);
            }
        }
        for (int line : fullRange) {
            if (seen.add(line)) {
                ordered.add(line);
            }
        }
        return ordered;
    }
}
