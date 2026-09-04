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
 * filter is confident about, in priority order. This is the number that
 * matters for reporting -- "filtered candidate count" in the results table
 * is priorityCandidates(...).size(), not the merged search order.
 *
 * {@link #orderCandidates} is a default method built on top of it: try the
 * priority candidates first, then fall back to whatever remains of
 * fullRange, in its original order, if nothing in the priority set
 * produces a valid passing repair. That fallback is what guarantees a
 * filter can only change SPEED, never correctness -- directly addressing
 * the advisor's remark that filtering should not risk missing a valid
 * repair. BarrierPointMojo only ever calls orderCandidates; the split
 * exists so harness/reporting code can call priorityCandidates directly to
 * get the "filtered count" number without re-deriving it from the merged
 * list.
 */
public interface CandidateFilter {

    /**
     * Returns the subset of fullRange this filter is confident about, in
     * priority order, using static analysis.
     *
     * @param criticalFile  file containing the critical point
     * @param criticalLine  1-based line number of the critical point
     * @param candidateFile file containing the candidate barrier-point range
     * @param fullRange     the full brute-force candidate line numbers, in
     *                      the order BarrierPointMojo would normally try them
     * @return the subset of fullRange this filter is confident about, in
     *         priority order. Return an empty list if the filter has no
     *         useful signal for this case (e.g. couldn't resolve/parse a
     *         file) -- that correctly reports as "0 filtered, no reduction"
     *         rather than silently pretending to have filtered.
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
