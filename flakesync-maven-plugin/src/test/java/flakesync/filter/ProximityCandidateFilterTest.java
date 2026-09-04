package flakesync.filter;

import org.junit.Test;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * ProximityCandidateFilter is pure line-number arithmetic (no parsing), so
 * this test is self-contained -- synthetic files/ranges rather than real
 * source, since the logic doesn't depend on actual code content at all.
 *
 * Run with: mvn test -Dtest=ProximityCandidateFilterTest -pl flakesync-maven-plugin
 */
public class ProximityCandidateFilterTest {

    @Test
    public void sameFileReturnsOnlyLinesWithinWindowSortedByDistance() {
        ProximityCandidateFilter filter = new ProximityCandidateFilter(5); // window = +/-5 lines
        File file = new File("SomeFile.java");
        int criticalLine = 100;
        List<Integer> fullRange = Arrays.asList(80, 94, 97, 100, 103, 108, 120);

        List<Integer> priority = filter.priorityCandidates(file, criticalLine, file, fullRange);

        // Only 97, 100, 103 are within +/-5 of 100; 80, 94, 108, 120 are not.
        assertEquals(Arrays.asList(100, 97, 103), priority);
    }

    @Test
    public void sameFileNoLinesWithinWindowReturnsEmpty() {
        ProximityCandidateFilter filter = new ProximityCandidateFilter(5);
        File file = new File("SomeFile.java");
        List<Integer> fullRange = Arrays.asList(1, 2, 3, 200, 201);

        List<Integer> priority = filter.priorityCandidates(file, 100, file, fullRange);

        assertTrue("No candidates are within the window of line 100", priority.isEmpty());
    }

    @Test
    public void crossFileHasNoSignalReturnsEmpty() {
        ProximityCandidateFilter filter = new ProximityCandidateFilter(15);
        File criticalFile = new File("GrpcServer.java");
        File candidateFile = new File("GrpcServerTest.java");
        List<Integer> fullRange = Arrays.asList(50, 51, 80, 83, 91, 93);

        List<Integer> priority = filter.priorityCandidates(criticalFile, 179, candidateFile, fullRange);

        // Different files -- line-number proximity is meaningless across files,
        // so the proximity baseline has no signal here (unlike DependencyCandidateFilter,
        // which handles this exact cross-file case via shared resource names).
        assertTrue("Cross-file case must yield no priority candidates for the proximity baseline",
                priority.isEmpty());
    }

    @Test
    public void orderCandidatesFallsBackToFullRangeWhenNoSignal() {
        // Exercises the default orderCandidates() method from CandidateFilter:
        // even with zero priority candidates, the merged order must still
        // contain every original candidate (correctness guarantee).
        ProximityCandidateFilter filter = new ProximityCandidateFilter(15);
        File criticalFile = new File("GrpcServer.java");
        File candidateFile = new File("GrpcServerTest.java");
        List<Integer> fullRange = Arrays.asList(50, 51, 80, 83, 91, 93);

        List<Integer> ordered = filter.orderCandidates(criticalFile, 179, candidateFile, fullRange);

        assertEquals("With no priority signal, merged order should equal the original full range",
                fullRange, ordered);
    }
}
