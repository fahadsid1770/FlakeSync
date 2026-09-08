package flakesync;

import flakesync.common.ConfigurationDefaults;
import flakesync.common.Level;
import flakesync.common.Logger;
import flakesync.filter.CandidateFilter;
import flakesync.filter.DependencyCandidateFilter;
import flakesync.filter.NoOpCandidateFilter;
import flakesync.filter.ProximityCandidateFilter;
import flakesync.filter.SourceFileResolver;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.PluginExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;


@Mojo(name = "barrierpointsearch", defaultPhase = LifecyclePhase.TEST,
        requiresDependencyResolution = ResolutionScope.TEST)
public class BarrierPointMojo extends FlakeSyncAbstractMojo {

    // "dependency" (default) = static filter; "none" = unfiltered baseline;
    // "proximity" = same-file line-distance baseline.
    @Parameter(property = "flakesync.filterMode", defaultValue = "dependency")
    private String filterMode;

    @Parameter(property = "flakesync.proximityWindow", defaultValue = "15")
    private int proximityWindow;

    private CandidateFilter createCandidateFilter() {
        if ("none".equalsIgnoreCase(filterMode)) {
            return new NoOpCandidateFilter();
        }
        if ("proximity".equalsIgnoreCase(filterMode)) {
            return new ProximityCandidateFilter(proximityWindow);
        }
        return new DependencyCandidateFilter(this.mavenProject);
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        super.execute();
        Logger.getGlobal().log(Level.INFO, ("Running BarrierPointSearchMojo"));
        CandidateFilter candidateFilter = createCandidateFilter();

        //Setup
        try {
            File barrierResFile = new File(String.valueOf(Constants.getBarrierPointsResultsFilepath(
                    String.valueOf(this.mavenProject.getBasedir()), testName)));
            FileWriter barrierResults = new FileWriter(barrierResFile);
            BufferedWriter bw = new BufferedWriter(barrierResults);

            bw.write("#Test-Name,Boundary-Point,Barrier-Point,Threshold,FoundViaPriority");
            bw.newLine();
            bw.flush();

            FileReader boundaryResults = new FileReader(String.valueOf(Constants.getCritPointsResultsFilepath(
                    String.valueOf(this.mavenProject.getBasedir()), testName)));
            BufferedReader br = new BufferedReader(boundaryResults);

            String line = br.readLine();
            while (line != null) {
                String firstLoc = line.split("-")[0];
                String endLoc = line.split("-")[1].split("\\[")[0];
                int delay = Integer.parseInt(line.split("\\[")[1].substring(0,
                        line.split("\\[")[1].length() - 1));

                if (firstLoc.contains("Test")) { //Boundary exists in the test code
                    SurefireExecution execution = SurefireExecution.SurefireFactory.createDownwardMvnExec(
                            this.surefire, this.originalArgLine, this.mavenProject, this.mavenSession,
                            this.pluginManager, Paths.get(this.baseDir.getAbsolutePath(),
                                    ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                            this.localRepository, this.testName, delay,
                            firstLoc.replace("/", "."));

                    executeSurefireExecution(null, execution);

                    File endLineFile = new File(String.valueOf(Constants.getSearchMethodEndLineFilepath(
                            String.valueOf(this.mavenProject.getBasedir()), testName)));
                    BufferedReader reader = new BufferedReader(new FileReader(endLineFile));

                    String yieldPoint = reader.readLine().split("=")[1];

                    // Build the brute-force range, then let the candidate
                    // filter reorder it (priority first, with fallback to
                    // the rest of the range unchanged).
                    List<Integer> fullRange1 = new ArrayList<>();
                    for (int ln = Integer.parseInt(endLoc.split("#")[1]);
                         ln < Integer.parseInt(yieldPoint.split("#")[1]); ln++) {
                        fullRange1.add(ln);
                    }
                    File criticalFile1 = SourceFileResolver.resolve(firstLoc.split("#")[0], this.mavenProject);
                    File candidateFile1 = SourceFileResolver.resolve(
                            yieldPoint.split("#")[0], this.mavenProject);
                    List<Integer> orderedCandidates1 = (criticalFile1 != null && candidateFile1 != null)
                            ? candidateFilter.orderCandidates(criticalFile1,
                                    Integer.parseInt(firstLoc.split("#")[1]), candidateFile1, fullRange1)
                            : fullRange1; // couldn't resolve source files -- fall back to unfiltered

                    List<Integer> priorityCandidates1 = (criticalFile1 != null && candidateFile1 != null)
                            ? candidateFilter.priorityCandidates(criticalFile1,
                                    Integer.parseInt(firstLoc.split("#")[1]), candidateFile1, fullRange1)
                            : Collections.emptyList();
                    System.out.println("FLAKESYNC_FILTER_STATS mode=" + filterMode
                            + " loop=sameFile"
                            + " criticalLine=" + firstLoc.split("#")[1]
                            + " fullRangeSize=" + fullRange1.size()
                            + " prioritySize=" + priorityCandidates1.size()
                            + " priorityLines=" + priorityCandidates1);

                    Set<Integer> prioritySet = new HashSet<>(priorityCandidates1);
                    for (int ln : orderedCandidates1) {
                        String yieldingPoint = yieldPoint.split("#")[0] + "#" + ln;
                        System.out.println("TRYING TO YIELD AT: " + yieldingPoint);

                        // Execute test with the delay and the new potential yield point
                        // (no need to output starting line anymore)
                        execution = SurefireExecution.SurefireFactory.createYieldExec1(this.surefire,
                                this.originalArgLine, this.mavenProject, this.mavenSession, this.pluginManager,
                                Paths.get(this.baseDir.getAbsolutePath(),
                                        ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                this.localRepository, this.testName, delay, endLoc.replace("/", "."),
                                yieldingPoint, 1);
                        boolean fail = executeSurefireExecution(null, execution);

                        // If the test now passes, and it was valid, add this point as the barrier point
                        if (!fail && checkValidPass()) {
                            boolean foundViaPriority = prioritySet.contains(ln);
                            System.out.println("FLAKESYNC_BARRIER_FOUND line=" + ln
                                    + " foundViaPriority=" + foundViaPriority);
                            addBarrierPointToResults(bw, line, yieldingPoint, 1, foundViaPriority);
                            break;
                        } else {
                            // If test still fails, maybe critical point needs to execute more often
                            // Count how often it was executed
                            // TODO: Refactor createExecMon to just never use delay explicitly
                            SurefireExecution execMon = SurefireExecution.SurefireFactory.createExecMon(
                                    this.surefire, this.originalArgLine, this.mavenProject, this.mavenSession,
                                    this.pluginManager, Paths.get(this.baseDir.getAbsolutePath(),
                                            ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                    this.localRepository, this.testName, delay, endLoc);
                            executeSurefireExecution(null, execMon);

                            // Parse threshold file for new threshold and store in numExecutions
                            File execsFile = new File(String.valueOf(Constants.getThresholdFilepath(
                                    String.valueOf(this.mavenProject.getBasedir()), testName)));
                            reader = new BufferedReader(new FileReader(execsFile));
                            int numExecutions = Integer.parseInt(reader.readLine().split("=")[1]);

                            // Run again with delay and potential yield point, but now check for threshold
                            execution = SurefireExecution.SurefireFactory.createYieldExec1(this.surefire,
                                    this.originalArgLine, this.mavenProject, this.mavenSession, this.pluginManager,
                                    Paths.get(this.baseDir.getAbsolutePath(),
                                            ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                    this.localRepository, this.testName, delay, endLoc.replace("/", "."),
                                    yieldingPoint, 1);
                            fail = executeSurefireExecution(null, execution);

                            if (!fail && checkValidPass()) {
                                // If test passed, barrier point worked, and add to results file
                                boolean foundViaPriority = prioritySet.contains(ln);
                                System.out.println("FLAKESYNC_BARRIER_FOUND line=" + ln
                                        + " foundViaPriority=" + foundViaPriority);
                                addBarrierPointToResults(bw, line, yieldingPoint, numExecutions, foundViaPriority);
                                break;
                            }
                        }
                    }
                } else {
                    SurefireExecution stackTraceExec = SurefireExecution.SurefireFactory.createBarrierSTExec(this.surefire,
                            this.originalArgLine, this.mavenProject, this.mavenSession, this.pluginManager,
                            Paths.get(this.baseDir.getAbsolutePath(),
                                    ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                            this.localRepository, this.testName, delay, endLoc.replace("/", "."));
                    executeSurefireExecution(null, stackTraceExec);

                    // Need to programmatically parse stack trace
                    File directory = new File(String.valueOf(Constants.getExecutionDir(
                            String.valueOf(this.mavenProject.getBasedir()), stackTraceExec.getExecutionId())));
                    System.out.println(directory.getAbsolutePath());
                    File stackTraceFile = findSTFile(directory);
                    if (stackTraceFile == null) {
                        System.out.println("It appears that the stacktrace does not exist");
                        // Skip this critical point and move on to the next one in
                        // CriticalPoints.csv. A bare `return` here used to exit the
                        // whole mojo, silently dropping any remaining critical points
                        // and risking stale BarrierPoints.csv reads downstream.
                        line = br.readLine();
                        continue;
                    }

                    // Parse the stack trace
                    Map<String, String> classes = new HashMap<>();
                    parseStackTrace(classes, stackTraceFile);

                    ArrayList<String> reversedClasses = new ArrayList<String>(classes.keySet());
                    Collections.reverse(reversedClasses);

                    HashSet<String> visited = new HashSet<String>();
                    for (String classN : reversedClasses) {
                        if (classN.contains(",")) {
                            classN = classN.substring(0, classN.lastIndexOf(','));
                        }
                        String yieldPoint = classN + "#" + classes.get(classN);
                        endLoc = endLoc.replace("/", ".");
                        System.out.println(endLoc + "\n" + yieldPoint);
                        if (!visited.contains(classN + "#" + classes.get(classN))) {
                            visited.add(yieldPoint);

                            // Execute test with the delay and the potential yield point
                            // Also generates a file that contains the first line of the method
                            // (since we want to iterate line-by-line up towards the start of the method)
                            SurefireExecution barrierPoint = SurefireExecution.SurefireFactory.createYieldExec2(
                                    this.surefire, this.originalArgLine, this.mavenProject, this.mavenSession,
                                    this.pluginManager, Paths.get(this.baseDir.getAbsolutePath(),
                                    ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                    this.localRepository, this.testName, delay, endLoc, yieldPoint);
                            executeSurefireExecution(null, barrierPoint);

                            // Get the line number representing start of the method
                            BufferedReader reader;
                            File startLineFile = new File(String.valueOf(Constants.getSearchMethodAndLineFilepath(
                                    String.valueOf(this.mavenProject.getBasedir()), testName)));
                            reader = new BufferedReader(new FileReader(startLineFile));
                            String beginLine = reader.readLine();
                            int beginning = Integer.parseInt(beginLine.split("#")[1]); // Parse from file

                            // Iterate upwards towards the start of the method, line-by-line --
                            // build the range as before, then reorder via the candidate filter
                            // (same filter-first-with-fallback design as the other loop above).
                            List<Integer> fullRange2 = new ArrayList<>();
                            for (int ln = Integer.parseInt(yieldPoint.split("#")[1]); ln >= beginning; ln--) {
                                fullRange2.add(ln);
                            }
                            File criticalFile2 = SourceFileResolver.resolve(
                                    firstLoc.split("#")[0], this.mavenProject);
                            File candidateFile2 = SourceFileResolver.resolve(classN, this.mavenProject);
                            int criticalLineForIdentifiers = Integer.parseInt(firstLoc.split("#")[1]);

                            File effectiveCriticalFile = criticalFile2;
                            if (effectiveCriticalFile == null && candidateFile2 != null) {
                                // Couldn't resolve the TRUE critical point's source file (e.g. it
                                // lives in a different module or a dependency, outside this
                                // module's own source roots) -- fall back to the test file itself
                                // at the yield point's line rather than crashing. This is a
                                // degraded signal (it loses the real critical-point resource
                                // information), so it's logged explicitly for later inspection --
                                // any subject that hits this should be reviewed by hand rather
                                // than trusted at face value.
                                effectiveCriticalFile = SourceFileResolver.resolve(
                                        testName.split("#")[0], this.mavenProject);
                                if (effectiveCriticalFile != null) {
                                    criticalLineForIdentifiers = Integer.parseInt(yieldPoint.split("#")[1]);
                                    System.out.println("FLAKESYNC_FILTER_FALLBACK loop=crossFile "
                                            + "reason=criticalFileUnresolved firstLoc=" + firstLoc);
                                }
                            }
                            List<Integer> orderedCandidates2 = (effectiveCriticalFile != null && candidateFile2 != null)
                                    ? candidateFilter.orderCandidates(effectiveCriticalFile,
                                            criticalLineForIdentifiers, candidateFile2, fullRange2)
                                    : fullRange2;

                            List<Integer> priorityCandidates2 = (effectiveCriticalFile != null && candidateFile2 != null)
                                    ? candidateFilter.priorityCandidates(effectiveCriticalFile,
                                            criticalLineForIdentifiers, candidateFile2, fullRange2)
                                    : Collections.emptyList();
                            System.out.println("FLAKESYNC_FILTER_STATS mode=" + filterMode
                                    + " loop=crossFile"
                                    + " criticalLine=" + criticalLineForIdentifiers
                                    + " fullRangeSize=" + fullRange2.size()
                                    + " prioritySize=" + priorityCandidates2.size()
                                    + " priorityLines=" + priorityCandidates2);

                            Set<Integer> prioritySet2 = new HashSet<>(priorityCandidates2);
                            for (int ln : orderedCandidates2) {
                                String yieldingPoint = classN + "#" + ln;
                                System.out.println("TRYING TO YIELD AT: " + yieldingPoint);

                                // Execute test with the delay and the new potential yield point
                                // (no need to output starting line anymore)
                                barrierPoint = SurefireExecution.SurefireFactory.createYieldExec1(this.surefire,
                                        this.originalArgLine, this.mavenProject, this.mavenSession, this.pluginManager,
                                        Paths.get(this.baseDir.getAbsolutePath(),
                                                ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                        this.localRepository, this.testName, delay, endLoc, yieldingPoint, 1);
                                boolean fail = executeSurefireExecution(null, barrierPoint);

                                // If the test now passes and it was valid, add this point as the barrier point
                                if (!fail && checkValidPass()) {
                                    boolean foundViaPriority = prioritySet2.contains(ln);
                                    System.out.println("FLAKESYNC_BARRIER_FOUND line=" + ln
                                            + " foundViaPriority=" + foundViaPriority);
                                    addBarrierPointToResults(bw, line, yieldingPoint, 1, foundViaPriority);
                                    break;
                                } else {
                                    // If test still fails, maybe critical point needs to execute more often
                                    // Count how often it was executed
                                    // TODO: Refactor createExecMon to just never use delay explicitly
                                    SurefireExecution execMon = SurefireExecution.SurefireFactory.createExecMon(
                                            this.surefire, this.originalArgLine, this.mavenProject, this.mavenSession,
                                            this.pluginManager, Paths.get(this.baseDir.getAbsolutePath(),
                                            ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                            this.localRepository, this.testName, delay, endLoc);
                                    executeSurefireExecution(null, execMon);

                                    // Parse threshold file for new threshold and store in numExecutions
                                    File execsFile = new File(String.valueOf(Constants.getThresholdFilepath(
                                            String.valueOf(this.mavenProject.getBasedir()), testName)));
                                    reader = new BufferedReader(new FileReader(execsFile));
                                    int numExecutions = Integer.parseInt(reader.readLine().split("=")[1]);

                                    // Run again with delay and potential yield point, but now check for threshold
                                    barrierPoint = SurefireExecution.SurefireFactory.createYieldExec1(this.surefire,
                                            this.originalArgLine, this.mavenProject, this.mavenSession, this.pluginManager,
                                            Paths.get(this.baseDir.getAbsolutePath(),
                                                    ConfigurationDefaults.DEFAULT_FLAKESYNC_DIR).toString(),
                                            this.localRepository, this.testName, delay, endLoc, yieldingPoint,
                                            numExecutions);
                                    fail = executeSurefireExecution(null, barrierPoint);

                                    if (!fail && checkValidPass()) {
                                        // If test passed, barrier point worked, and add to results file
                                        boolean foundViaPriority = prioritySet2.contains(ln);
                                        System.out.println("FLAKESYNC_BARRIER_FOUND line=" + ln
                                                + " foundViaPriority=" + foundViaPriority);
                                        addBarrierPointToResults(bw, line, yieldingPoint, numExecutions, foundViaPriority);
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
                line = br.readLine();
            }
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        } catch (Throwable exception) {
            System.out.println(exception);
            throw new RuntimeException(exception);
        }

    }

    private File findSTFile(File directory) {
        for (File f : directory.listFiles()) {
            System.out.println(f.getName());
            if (f.getName().contains("TEST-")) {
                return f;
            }
        }
        return null;
    }

    // Parse the stack trace file corresponding to the failure
    private void parseStackTrace(Map<String, String> classes, File stackTraceFile) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(stackTraceFile));
        String trace = reader.readLine();
        while (trace != null) {
            if (trace.contains(".java:")) {
                // Extract class name and line number from the stack trace element String format
                String className = trace.split("at ")[1].split("\\(")[0];
                className = className.substring(0, className.lastIndexOf("."));
                String lineNum = trace.split("\\(")[1].split(":")[1].split("\\)")[0];
                System.out.println(className + " " + lineNum);
                // Only keep track of those not in black list, e.g., from JUnit or Maven
                if (!inBlackList(className)) {
                    if (classes.containsKey(className)) {
                        classes.put(className + ",", lineNum);
                    } else {
                        classes.put(className, lineNum);
                    }
                }

            }
            trace = reader.readLine();
        }
    }

    private boolean inBlackList(String className) throws IOException {
        InputStream blacklist = this.getClass().getResourceAsStream("/blacklist.txt");
        Scanner scanner = new Scanner(blacklist);
        while (scanner.hasNextLine()) {
            String line = scanner.nextLine();
            if (className.contains(line)) {
                return true;
            } else if (line.contains("*") && className.contains(line.substring(0, line.indexOf("*")))) {
                return true;
            }
        }
        return false;
    }

    private void addBarrierPointToResults(BufferedWriter bw, String bop, String bap, int threshold,
                                          boolean foundViaPriority) throws IOException {
        bw.write(this.testName + "," + bop + "," + bap + "," + threshold + "," + foundViaPriority);
        bw.newLine();
        bw.flush();
    }

    // Check whether the passing execution was valid, i.e., the injected delay got executed and yield point was added
    private boolean checkValidPass() throws IOException {
        // Read the generated file to see that all valid conditions are met
        File barrierResultFile = new File(String.valueOf(Constants.getYieldResultFilepath(String.valueOf(
                this.mavenProject.getBasedir()), testName)));
        BufferedReader reader = new BufferedReader(new FileReader(barrierResultFile));

        // File being read should have three lines for booleans representing:
        //   delay happened, yield happened, test passed
        String flagLine = reader.readLine();
        boolean hitscriteria = true;
        while (flagLine != null) {
            System.out.println(Boolean.parseBoolean(flagLine.split("=")[1]));
            hitscriteria &= Boolean.parseBoolean(flagLine.split("=")[1]);
            flagLine = reader.readLine();
        }
        // As long as all three criteria hold, the passing result was valid
        if (hitscriteria) {
            return true;
        }
        return false;
    }


    private boolean executeSurefireExecution(MojoExecutionException allExceptions,
                                             SurefireExecution execution) throws Throwable {
        try {
            execution.run();
        } catch (MojoExecutionException exception) {
            return true;
        }
        return false;
    }

    public void setTestName(String testName) {
        this.testName = testName;
    }
}