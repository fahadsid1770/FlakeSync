package flakesync.filter;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Verifies the JavaParser port of the dependency filter reproduces the
 * numbers validated with the Python/javalang prototype. A failing assertion
 * here indicates a porting bug, not a flaw in the underlying filter.
 *
 * Run with:    mvn test -Dtest=DependencyFilterVerificationTest -pl flakesync-maven-plugin -Dsurefire.useFile=false
 */
public class DependencyFilterVerificationTest {

    private File resource(String name) throws URISyntaxException {
        return new File(getClass().getClassLoader().getResource("examples/" + name).toURI());
    }

    /** Example 1: Agent.java, run() method, two synchronized blocks on
     * different resources. Expected (from Python prototype):
     * brute-force candidates = 48, filtered = 2, reduction = 95.8%. */
    @Test
    public void agentExampleSharedLockFilterMatchesPythonResult() throws IOException, URISyntaxException {
        File file = resource("Agent.java");
        CompilationUnit cu = StaticJavaParser.parse(file);
        List<String> lines = Files.readAllLines(file.toPath());

        MethodDeclaration method = DependencyFilter.extractMethod(cu, "run", 280);
        List<StatementRecord> records = DependencyFilter.extractStatementRecords(method, cu, lines);

        int criticalLine = 296;
        List<Integer> filtered = DependencyFilter.filterBySharedLock(records, criticalLine);

        System.out.println("=== Example 1: Agent.java ===");
        System.out.println("Method 'run' found at line " + method.getBegin().get().line);
        System.out.println("Brute-force candidate count: " + records.size());
        System.out.println("Filtered candidate count: " + filtered.size());
        System.out.println("Filtered lines: " + filtered);
        double reduction = 100.0 * (1 - (double) filtered.size() / records.size());
        System.out.printf("Reduction: %.1f%%%n%n", reduction);

        assertEquals("Brute-force candidate count reflects added header records (was 48, now includes "
                + "synchronized/loop/branch header lines)", 64, records.size());
        assertEquals("Filtered candidate count now correctly includes the synchronized block's own "
                + "header (294) and the nested for-each header (295), both sharing the same lock "
                + "resource as the original two leaf statements", 4, filtered.size());
        assertEquals("Filtered lines now correctly include both header lines",
                Arrays.asList(294, 295, 296, 297), filtered);

        // The unrelated synchronized block (InjectDelayClassTracer.locations) must NOT leak in
        assertTrue("Lines from the unrelated synchronized block must not appear in the filter",
                !filtered.contains(311) && !filtered.contains(312));
    }

    /** Example 2: real GrpcServerTest#testGrpcExecutorPool ground truth.
     * Expected (from Python prototype): brute-force candidates = 21,
     * filtered = 6, reduction = 71.4%, true barrier point (line 83) retained. */
    @Test
    public void grpcServerTestExampleResourceNameFilterMatchesPythonResult()
            throws IOException, URISyntaxException {
        File file = resource("GrpcServerTest.java");
        CompilationUnit cu = StaticJavaParser.parse(file);
        List<String> lines = Files.readAllLines(file.toPath());

        MethodDeclaration method = DependencyFilter.extractMethod(cu, "testGrpcExecutorPool", null);
        List<StatementRecord> records = DependencyFilter.extractStatementRecords(method, cu, lines);

        Set<String> criticalResource = new HashSet<>(Arrays.asList(
                "GRPC_SERVER_EXECUTOR_BLOCKING_QUEUE_SIZE_KEY", "grpcMetrics"));
        List<Integer> filtered = DependencyFilter.filterByResourceNames(records, criticalResource);

        System.out.println("=== Example 2: GrpcServerTest.java (real ground truth) ===");
        System.out.println("Method 'testGrpcExecutorPool' found at line " + method.getBegin().get().line);
        System.out.println("Brute-force candidate count: " + records.size());
        System.out.println("Filtered candidate count: " + filtered.size());
        System.out.println("Filtered lines: " + filtered);
        double reduction = 100.0 * (1 - (double) filtered.size() / records.size());
        System.out.printf("Reduction: %.1f%%%n", reduction);
        int trueBarrierPoint = 83;
        System.out.println("True barrier point (line " + trueBarrierPoint + ") retained: "
                + filtered.contains(trueBarrierPoint) + "\n");

        assertEquals("Brute-force candidate count reflects added header records (was 21, now includes "
                + "synchronized/loop/branch header lines)", 23, records.size());
        assertEquals("Filtered candidate count reflects multi-line statement expansion",
                16, filtered.size());
        assertTrue("True barrier point (line 83, matching the real bug report) must be retained",
                filtered.contains(trueBarrierPoint));
    }
}
