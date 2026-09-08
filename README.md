# FlakeSync

FlakeSync locates and patches the root cause of flaky tests in Java/Maven projects. It works by injecting tiny delays at strategic points, seeing which ones flip a test from pass to fail, then generating a patch that makes the test reliably pass.

It runs four sequential phases (six Maven goals total), and every phase writes its results into `./<module>/.flakesync/` inside the project being analyzed.

## Requirements

- Java 8+
- Maven 3.3.9+
- Git

## Step: Build FlakeSync

```
mvn clean install -U
```

This builds three modules: `flakesync-utils`, `flakesync-core` (the Java agent), and `flakesync-maven-plugin`.

## Step: Clone and build a target project

We'll use Apache Uniffle as an example. Pick any Java project with a flaky test — the steps are the same.

```
git clone https://github.com/apache/incubator-uniffle.git
cd incubator-uniffle
git checkout 625377c9ae130b4e64247ac35c95d2d0bac42af9
mvn clean install -pl common -am -U -DskipTests
```

The example flaky test throughout this guide is:
`org.apache.uniffle.common.rpc.GrpcServerTest#testGrpcExecutorPool` in module `common`.

## Steps: Run the four phases

The plugin is invoked as `mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:<goal>`.

| Phase | Goal | Input | Output |
|-------|------|-------|--------|
| 1a. Find concurrent methods | `concurrentfind` | test name | `*-ResultMethods.txt` |
| 1b. Find delay locations | `delaylocs` | result methods | `*-Locations.txt` |
| 2. Minimize locations | `deltadebug` | all locations | `*-Locations_minimized.txt` |
| 3. Critical point search | `critsearch` | minimized locations | `Results-CritSearch/*-CriticalPoints.csv`, `*-RootMethods.csv` |
| 4. Barrier point search | `barrierpointsearch` | critical points | `Results-BarrierSearch/*-BarrierPoints.csv` |
| 5. Patch | `patch` | barrier points | `patch/*.patch` |

Run each goal from the root of the cloned project, passing the test name and module:

```
TESTSYNC=org.apache.uniffle.common.rpc.GrpcServerTest#testGrpcExecutorPool
MODULE=common

mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:concurrentfind -Dflakesync.testName=$TESTSYNC -pl $MODULE
mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:delaylocs -Dflakesync.testName=$TESTSYNC -pl $MODULE
mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:deltadebug -Dflakesync.testName=$TESTSYNC -pl $MODULE
mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:critsearch -Dflakesync.testName=$TESTSYNC -pl $MODULE
mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:barrierpointsearch -Dflakesync.testName=$TESTSYNC -pl $MODULE
mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:patch -Dflakesync.testName=$TESTSYNC -pl $MODULE
```

A short version of what each phase does:

- **concurrentfind** — instruments the bytecode to record which methods actually run at the same time, then runs the test once.
- **delaylocs** — injects growing delays (100 ms to 12.8 s) at the concurrent-method locations and records which ones make the test fail.
- **deltadebug** — shrinks the failing set down to the smallest subset of locations that still reproduces the failure.
- **critsearch** — analyzes the stack traces from failing runs to find root methods and critical points, grouped into contiguous regions.
- **barrierpointsearch** — for each critical point, finds the barrier point (a line where adding synchronization makes the test reliably pass) and the execution threshold at which it kicks in.
- **patch** — reads the critical and barrier points and writes `.patch` files for the modified source files, then restores the originals.

The patches live in `./<module>/.flakesync/patch/` and can be applied with `patch -p0`.

To wipe the analysis output and start over, delete `./<module>/.flakesync` and re-run the phases.

## The dependency filter and its evaluation

Barrier point search is the slowest phase: left as-is, it brute-forces every line in the range, and each trial launches a fresh JVM. The **DependencyCandidateFilter** makes it faster by reordering candidates — it tries lines that have a static dependency on the critical point first, and only falls back to the rest of the range if none of those produce a valid repair. Filtering can therefore only change speed, never correctness.

Because a filter that always worked everywhere would be boring, and a filter that never worked would be useless, FlakeSync ships three modes so the ordering logic can be compared honestly:

| Mode | What it does | Flag |
|------|--------------|------|
| `none` | No filtering (baseline A) | `-Dflakesync.filterMode=none` |
| `proximity` | Same-file line-distance only (baseline B) | `-Dflakesync.filterMode=proximity` |
| `dependency` | Static dependency analysis (default) | `-Dflakesync.filterMode=dependency` |

Set the mode the same way as any other FlakeSync property:

```
mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:barrierpointsearch \
    -Dflakesync.testName=$TESTSYNC -pl $MODULE -Dflakesync.filterMode=proximity
```

The proximity window is tunable with `-Dflakesync.proximityWindow=N` (default 15 lines).

To run the three-way comparison across a list of subjects, use the harness script:

```
cd testscripts
./runFilterComparison.sh input/dryrun-one-subject.csv 10
```

This clones each subject at the pinned commit, runs the four filter-independent phases once, then runs barrier-point search and patching under all three modes. For each (subject, mode) it records a row in `results/FilterComparison.csv` with the candidate counts, reduction percentage, whether the reference barrier point survived, whether a valid repair was found, and how long each stage took. `repeat_runs` (default 10) controls how many times the patched test is re-run to confirm the repair.

### What to look at in the results

- **Candidate reduction** — `full_range_size` vs `priority_size`, and `candidate_reduction_pct`. A high reduction means the filter pruned most candidates.
- **Barrier point retention** — did the known-good barrier point stay in the priority set? Note that a project can have several valid barrier points, so finding a different one that passes validation is still a success.
- **Repair correctness** — was a patch generated (`repair_success`), and did all `repeated_run_validated` runs pass?
- **Timing** — `barrier_search_time_sec` separately from `total_time_sec`. Reduction doesn't always translate 1:1 into speedup because JVM startup and class loading dominate.

Also run the unit tests that pin the filter's behavior:

```
mvn test -Dtest=DependencyFilterVerificationTest -pl flakesync-maven-plugin -Dsurefire.useFile=false
```