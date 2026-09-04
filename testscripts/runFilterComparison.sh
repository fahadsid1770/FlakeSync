#!/bin/bash
# runFilterComparison.sh
#
# Extends testscripts/runAll.sh into a 3-way filter-mode comparison harness,
# per the advisor's evaluation request. For each subject in the input CSV:
#
#   1. Runs the 4 filter-independent phases ONCE (concurrentfind, delaylocs,
#      deltadebug, critsearch) -- these don't depend on -Dflakesync.filterMode,
#      so running them 3x would triple wall-clock time for no reason.
#   2. For each filter mode (none, proximity, dependency):
#      a. Runs barrierpointsearch with that mode, capturing candidate-count
#         stats from the FLAKESYNC_FILTER_STATS log line.
#      b. Snapshots the BarrierPoints.csv result (it gets overwritten by the
#         next mode's run otherwise).
#      c. Runs patch -- this generates .patch files for the critical-point
#         and barrier-point files, then SELF-REVERTS the source (confirmed by
#         reading PatchingMojo.java: it copies to .orig, injects, diffs,
#         then restores from .orig). So after this step the source tree is
#         back to the checked-out state regardless of outcome.
#      d. Repeated-run validation: re-applies the snapshotted .patch files
#         with `patch -p0` (safe because SavePatch.java generates diffs with
#         absolute paths on both sides), runs the actual test REPEAT_RUNS
#         times via plain `mvn test`, records the pass count, then reverts
#         via `git checkout -- .` (safe: .flakesync/ is untracked, so this
#         only reverts the patch we just applied, never touches FlakeSync's
#         own intermediate artifacts).
#   3. Writes one row per (subject, filter mode) to results/FilterComparison.csv
#      with exactly the columns the advisor's Remark 2 asked for, plus a couple
#      of extra diagnostic columns.
#
# ASSUMPTIONS THIS SCRIPT MAKES (please sanity-check on one subject before
# trusting a full run across all subjects -- I could not execute any of this
# myself; see the accompanying README section for a suggested dry-run):
#   - `patch -p0 < file.patch` correctly re-applies SavePatch.java's diffs
#     (relies on the diff headers containing absolute paths, confirmed by
#     reading SavePatch.java's use of `diff -u originalFilePath modifiedFilePath`
#     where both paths were already absolute).
#   - `.flakesync/` is untracked (not gitignored-and-committed) in every
#     subject repo, so `git checkout -- .` never deletes FlakeSync's own
#     artifacts. Worth a quick `git status` check on the first subject.
#   - `-Dtest=<Class>#<method>` is valid surefire syntax in each subject's
#     configured surefire version (modern surefire, 2.19+, supports this;
#     FlakeSync's own -Dflakesync.testName already uses this exact format,
#     so this is very likely fine, but hasn't been independently confirmed).
#   - Each subject has exactly ONE critical point worth trying. If
#     CriticalPoints.csv has more than one line, BarrierPointMojo's outer
#     loop will try each in turn, producing multiple FLAKESYNC_FILTER_STATS
#     lines per run -- this script only uses the FIRST one for candidate-count
#     reporting and prints a warning if more than one appears, so those
#     subjects' logs are worth checking by hand.
#
# USAGE:
#   ./runFilterComparison.sh input/inputs.csv [repeat_runs]
#   (repeat_runs defaults to 10 if not given)

set -uo pipefail
# NOTE: deliberately NOT using `set -e` -- mvn commands are expected to exit
# non-zero sometimes (e.g. a barrier point search that finds nothing, or a
# repeated-validation run that fails), and we want to keep going and record
# that outcome, not abort the whole sweep.

currentDir="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
results="$currentDir/results"
logs="$results/logs"
patches="$results/patches"
barrierSnapshots="$results/barrier_snapshots"
mkdir -p "$results" "$logs" "$patches" "$barrierSnapshots"

REPEAT_RUNS="${2:-10}"
MODES=("none" "proximity" "dependency")

summaryCsv="$results/FilterComparison.csv"
echo "slug,sha,test_name,filter_mode,full_range_size,filtered_size,candidate_reduction_pct,barrier_point_retained,repair_success,repeated_pass_count,repeated_total_runs,repeated_run_validated,barrier_search_time_sec,total_time_sec,shared_phase_time_sec" > "$summaryCsv"

while IFS= read -r csvline; do
    if [[ "$csvline" =~ ^# ]] || [[ -z "$csvline" ]]; then
        continue
    fi

    slug=$(echo "$csvline" | cut -d',' -f1)
    sha=$(echo "$csvline" | cut -d',' -f2)
    module=$(echo "$csvline" | cut -d',' -f3)
    testName=$(echo "$csvline" | cut -d',' -f4)

    echo "=================================================================="
    echo "SUBJECT: $slug @ $sha  module=$module  test=$testName"
    echo "=================================================================="

    workdir="$currentDir/input/$slug"
    rm -rf "$workdir"
    git clone "https://github.com/$slug" "$workdir"
    (cd "$workdir" && git checkout "$sha")
    (cd "$workdir" && mvn clean install -pl "$module" -am -U -DskipTests)

    subjectTag=$(echo "${slug}_${testName}" | tr '/#.' '___')

    # Deterministic FlakeSync artifact paths -- see Constants.java. All relative
    # to $workdir (the repo root), matching how -pl $module resolves basedir.
    testNameDots=${testName//#/.}
    moduleDir="$workdir/$module"
    barrierResultsFile="$moduleDir/.flakesync/Results-BarrierSearch/${testNameDots}-BarrierPoints.csv"
    patchDir="$moduleDir/.flakesync/patch"

    pushd "$workdir" > /dev/null

    # ---- Shared phases (independent of filter mode) -- run ONCE ----
    sharedStart=$(date +%s.%N)
    mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:concurrentfind \
        -Dflakesync.testName="${testName}" -pl "$module" \
        > "$logs/${subjectTag}_concurrentfind.log" 2>&1
    mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:delaylocs \
        -Dflakesync.testName="${testName}" -pl "$module" \
        > "$logs/${subjectTag}_delaylocs.log" 2>&1
    mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:deltadebug \
        -Dflakesync.testName="${testName}" -pl "$module" \
        > "$logs/${subjectTag}_deltadebug.log" 2>&1
    mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:critsearch \
        -Dflakesync.testName="${testName}" -pl "$module" \
        > "$logs/${subjectTag}_critsearch.log" 2>&1
    sharedEnd=$(date +%s.%N)
    sharedTime=$(echo "scale=2; $sharedEnd - $sharedStart" | bc)
    echo "Shared-phase time: ${sharedTime}s"

    referenceBarrierLine=""   # set from Baseline A ("none"); used for retention checks

    for mode in "${MODES[@]}"; do
        echo "------------------------------------------------------------"
        echo "FILTER MODE: $mode"
        echo "------------------------------------------------------------"

        bpLog="$logs/${subjectTag}_${mode}_barrierpointsearch.log"
        bpStart=$(date +%s.%N)
        mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:barrierpointsearch \
            -Dflakesync.testName="${testName}" -Dflakesync.filterMode="${mode}" -pl "$module" \
            > "$bpLog" 2>&1
        bpEnd=$(date +%s.%N)
        barrierSearchTime=$(echo "scale=2; $bpEnd - $bpStart" | bc)

        statsCount=$(grep -c "FLAKESYNC_FILTER_STATS" "$bpLog" || true)
        if [[ "$statsCount" -gt 1 ]]; then
            echo "WARNING: $statsCount FLAKESYNC_FILTER_STATS lines in $bpLog (multiple critical points tried) -- review manually"
        fi
        statsLine=$(grep -m1 "FLAKESYNC_FILTER_STATS" "$bpLog" || true)
        fullRangeSize=$(echo "$statsLine" | grep -oE "fullRangeSize=[0-9]+" | cut -d'=' -f2)
        filteredSize=$(echo "$statsLine" | grep -oE "prioritySize=[0-9]+" | cut -d'=' -f2)
        priorityLines=$(echo "$statsLine" | grep -oE "priorityLines=\[[^]]*\]" | sed -E 's/priorityLines=\[(.*)\]/\1/' | tr -d ' ')
        fullRangeSize=${fullRangeSize:-0}
        filteredSize=${filteredSize:-0}

        if [[ "$fullRangeSize" -gt 0 ]]; then
            reductionPct=$(echo "scale=4; 100 * (1 - $filteredSize / $fullRangeSize)" | bc)
        else
            reductionPct="NA"
        fi

        # Snapshot the barrier-points result before the next mode overwrites it.
        if [[ -f "$barrierResultsFile" ]]; then
            cp "$barrierResultsFile" "$barrierSnapshots/${subjectTag}_${mode}_BarrierPoints.csv"
        fi

        foundBarrierLine=""
        repairSuccess="no"
        if [[ -f "$barrierResultsFile" ]] && grep -vE '^#|^$' "$barrierResultsFile" | grep -q .; then
            repairSuccess="yes"
            foundBarrierLine=$(grep -vE '^#|^$' "$barrierResultsFile" | tail -1 | cut -d',' -f3)
        fi

        if [[ "$mode" == "none" ]]; then
            referenceBarrierLine="$foundBarrierLine"
        fi

        refLineNum=$(echo "$referenceBarrierLine" | awk -F'#' '{print $NF}')
        barrierPointRetained="n/a"
        if [[ "$mode" != "none" ]]; then
            if [[ -n "$refLineNum" ]] && echo ",$priorityLines," | grep -q ",$refLineNum,"; then
                barrierPointRetained="yes"
            else
                barrierPointRetained="no"
            fi
        fi

        # ---- Patch phase (self-reverting -- see header comment) ----
        rm -rf "$patchDir"   # clean slate so this mode's snapshot can't pick up a stale file
        patchLog="$logs/${subjectTag}_${mode}_patch.log"
        patchStart=$(date +%s.%N)
        mvn edu.utexas.ece:flakesync-maven-plugin:1.0-SNAPSHOT:patch \
            -Dflakesync.testName="${testName}" -pl "$module" \
            > "$patchLog" 2>&1
        patchEnd=$(date +%s.%N)
        patchTime=$(echo "scale=2; $patchEnd - $patchStart" | bc)

        modePatchDir="$patches/${subjectTag}_${mode}"
        mkdir -p "$modePatchDir"
        if [[ -d "$patchDir" ]]; then
            cp "$patchDir"/*.patch "$modePatchDir/" 2>/dev/null || true
        fi

        # ---- Repeated-run validation ----
        repeatedPassCount=0
        repeatedTotal=0
        if [[ "$repairSuccess" == "yes" ]] && [[ -n "$(ls -A "$modePatchDir" 2>/dev/null)" ]]; then
            applyFailed="no"
            for patchFile in "$modePatchDir"/*.patch; do
                if ! patch -p0 < "$patchFile"; then
                    echo "WARNING: failed to apply $patchFile -- skipping repeated-run validation for this mode"
                    applyFailed="yes"
                fi
            done
            if [[ "$applyFailed" == "no" ]]; then
                for i in $(seq 1 "$REPEAT_RUNS"); do
                    if mvn test -Dtest="${testName}" -pl "$module" \
                        > "$logs/${subjectTag}_${mode}_validate_${i}.log" 2>&1; then
                        repeatedPassCount=$((repeatedPassCount + 1))
                    fi
                    repeatedTotal=$((repeatedTotal + 1))
                done
            fi
            git checkout -- .   # revert the applied patch; .flakesync/ is untracked, unaffected
        fi

        repeatedRunValidated="n/a"
        if [[ "$repeatedTotal" -gt 0 ]]; then
            if [[ "$repeatedPassCount" -eq "$repeatedTotal" ]]; then
                repeatedRunValidated="yes"
            else
                repeatedRunValidated="no"
            fi
        fi

        totalTime=$(echo "scale=2; $sharedTime + $barrierSearchTime + $patchTime" | bc)

        echo "$slug,$sha,$testName,$mode,$fullRangeSize,$filteredSize,$reductionPct,$barrierPointRetained,$repairSuccess,$repeatedPassCount,$repeatedTotal,$repeatedRunValidated,$barrierSearchTime,$totalTime,$sharedTime" >> "$summaryCsv"

        echo "RESULT mode=$mode fullRange=$fullRangeSize filtered=$filteredSize reduction=${reductionPct}% retained=$barrierPointRetained repairSuccess=$repairSuccess validated=$repeatedRunValidated ($repeatedPassCount/$repeatedTotal) barrierSearchTime=${barrierSearchTime}s totalTime=${totalTime}s"
    done

    popd > /dev/null

done < "$1"

echo
echo "Done. Results in $summaryCsv"
echo "Per-mode barrier-point snapshots in $barrierSnapshots"
echo "Per-mode patch snapshots in $patches"
echo "Full logs in $logs"
