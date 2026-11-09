#!/usr/bin/env bash
set -euo pipefail

mx="${GITHUB_WORKSPACE}/mx/mx"
source_root="$(pwd)"
job_scratch="$(mktemp -d "${RUNNER_TEMP}/upstream-candidate-${GITHUB_RUN_ID}-${GITHUB_RUN_ATTEMPT}-XXXXXX")"
results="${RUNNER_TEMP}/candidate-results"
build_root="${job_scratch}/builds"
baseline="${job_scratch}/baseline/source"
# A self-hosted runner executes one job at a time, but its temporary directory may survive jobs.
# Retain old artifacts outside the upload path so this report cannot include stale evidence.
if [[ -L "${results}" ]]; then
    echo 'Unexpected results symlink; retain it for investigation' >&2
    exit 1
fi
if [[ -e "${results}" ]]; then
    mv "${results}" "${job_scratch}/previous-results"
fi
mkdir -p "${results}" "${build_root}" "${job_scratch}/baseline"
baseline_added=false
cleanup() {
    local status=$?
    if [[ "${baseline_added}" == true ]]; then
        if ! git worktree remove "${baseline}"; then
            echo "Retained baseline requiring inspection: ${baseline}" >&2
        fi
    fi
    rm -rf "${build_root}"
    # Previous artifacts and any unexpectedly dirty source remain available for investigation.
    if [[ ! -e "${job_scratch}/previous-results" && ! -e "${baseline}" ]]; then
        rm -f "${job_scratch}/baseline/graal"
        rmdir "${job_scratch}/baseline" "${job_scratch}"
    fi
    return "${status}"
}
trap cleanup EXIT
git merge-base --is-ancestor "${BASE_SHA}" "${HEAD_SHA}"
test "$(git rev-parse HEAD)" = "${HEAD_SHA}"
git worktree add --detach "${baseline}" "${BASE_SHA}"
baseline_added=true
{
    echo "base=${BASE_SHA}"
    echo "head=${HEAD_SHA}"
    echo "runner=${RUNNER_NAME}"
    uname -a
    "${JAVA_HOME}/bin/java" -version
    git -C "${GITHUB_WORKSPACE}/mx" rev-parse HEAD
    lscpu
} > "${results}/environment.txt" 2>&1
cp -R ci/upstream-reproducers "${results}/reproducers"
find "${results}/reproducers" -type f -print0 | sort -z | xargs -0 sha256sum > "${results}/reproducer-sha256.txt"

run_mx() {
    local tree="$1" label="$2" suite="$3"
    shift 3
    (cd "${tree}/${suite}"; export MX_ALT_OUTPUT_ROOT="${build_root}/${label}"; "${mx}" --max-cpus=8 "$@")
}
expect_failure() {
    local label="$1" diagnostic="$2" status
    shift 2
    set +e
    "$@" > "${results}/${label}.log" 2>&1
    status=$?
    set -e
    echo "${status}" > "${results}/${label}.exit"
    if [[ "${status}" == 0 ]] || ! grep -Fq "${diagnostic}" "${results}/${label}.log"; then
        cat "${results}/${label}.log"
        echo "Baseline did not produce the required defect signature" >&2
        exit 1
    fi
}
run_lcov_sample() {
    local tree="$1" label="$2" cp
    cp="$(run_mx "${tree}" "${label}" tools classpath TRUFFLE_COVERAGE_TEST TRUFFLE_RUNTIME)"
    run_mx "${tree}" "${label}" tools java --enable-native-access=ALL-UNNAMED -cp "${cp}" "${source_root}/ci/upstream-reproducers/lcov/LCOVReproducer.java"
}
build_resource_sample() {
    local tree="$1" label="$2"
    mkdir -p "${results}/${label}-sample"
    "${JAVA_HOME}/bin/javac" -d "${results}/${label}-sample" "${source_root}/ci/upstream-reproducers/resource-url/ResourceURLReproducer.java"
    run_mx "${tree}" "${label}" substratevm --native-images=false native-image --no-fallback --parallelism=8 -J-Xmx8g -cp "${results}/${label}-sample" ResourceURLReproducer -o "${results}/${label}-sample/resource-url"
}
case "${CANDIDATE_BRANCH}" in
    staging/lcov-source-identity)
        run_mx "${baseline}" baseline tools build --build-logs=silent --dependencies TRUFFLE_COVERAGE_TEST,TRUFFLE_RUNTIME > "${results}/baseline-build.log" 2>&1
        expect_failure baseline-reproducer 'LCOV lost pathless source identity' run_lcov_sample "${baseline}" baseline
        run_mx "${source_root}" candidate tools build --build-logs=silent --dependencies TRUFFLE_COVERAGE_TEST,TRUFFLE_RUNTIME > "${results}/candidate-build.log" 2>&1
        run_lcov_sample "${source_root}" candidate > "${results}/candidate-reproducer.log" 2>&1
        run_mx "${source_root}" candidate tools unittest CoverageTest > "${results}/regressions.log" 2>&1
        run_mx "${source_root}" candidate tools checkstyle --primary > "${results}/checkstyle.log" 2>&1
        ;;
    staging/resource-url-no-dns)
        run_mx "${baseline}" baseline substratevm --native-images=false build --build-logs=silent > "${results}/baseline-build.log" 2>&1
        build_resource_sample "${baseline}" baseline > "${results}/baseline-image-build.log" 2>&1
        expect_failure baseline-reproducer 'Resource URL identity used a network address' "${results}/baseline-sample/resource-url"
        run_mx "${source_root}" candidate substratevm --native-images=false build --build-logs=silent > "${results}/candidate-build.log" 2>&1
        build_resource_sample "${source_root}" candidate > "${results}/candidate-image-build.log" 2>&1
        "${results}/candidate-sample/resource-url" > "${results}/candidate-reproducer.log" 2>&1
        run_mx "${source_root}" candidate substratevm --native-images=false native-unittest com.oracle.svm.test.NativeImageResourceTest --build-args --parallelism=8 -J-Xmx8g > "${results}/regressions.log" 2>&1
        run_mx "${source_root}" candidate substratevm checkstyle --primary > "${results}/checkstyle.log" 2>&1
        ;;
    staging/using-return-completion)
        test "$(git -C "${GITHUB_WORKSPACE}/graal" rev-parse HEAD)" = 29054f702c3cd8420cdc927409b7d0f55acddec2
        ln -s "${GITHUB_WORKSPACE}/graal" "${job_scratch}/baseline/graal"
        run_mx "${baseline}" baseline graal-js build --build-logs=silent --dependencies TRUFFLE_JS_TESTS,GRAALJS_LAUNCHER,truffle:TRUFFLE_RUNTIME > "${results}/baseline-build.log" 2>&1
        expect_failure baseline-sync 'Expected 42; got undefined' run_mx "${baseline}" baseline graal-js js --experimental-options --js.explicit-resource-management=true --js.unhandled-rejections=throw "${source_root}/ci/upstream-reproducers/using-return/reproduce.js"
        expect_failure baseline-async 'Async using lost its completion' run_mx "${baseline}" baseline graal-js js --experimental-options --js.explicit-resource-management=true --js.unhandled-rejections=throw "${source_root}/ci/upstream-reproducers/using-return/reproduce-async.js"
        run_mx "${source_root}" candidate graal-js build --build-logs=silent --dependencies TRUFFLE_JS_TESTS,GRAALJS_LAUNCHER,truffle:TRUFFLE_RUNTIME > "${results}/candidate-build.log" 2>&1
        run_mx "${source_root}" candidate graal-js js --experimental-options --js.explicit-resource-management=true --js.unhandled-rejections=throw "${source_root}/ci/upstream-reproducers/using-return/reproduce.js" > "${results}/candidate-sync.log" 2>&1
        run_mx "${source_root}" candidate graal-js js --experimental-options --js.explicit-resource-management=true --js.unhandled-rejections=throw "${source_root}/ci/upstream-reproducers/using-return/reproduce-async.js" > "${results}/candidate-async.log" 2>&1
        run_mx "${source_root}" candidate graal-js unittest com.oracle.truffle.js.test.suite.JSSimpleTestSuite > "${results}/regressions.log" 2>&1
        run_mx "${source_root}" candidate graal-js checkstyle --primary > "${results}/checkstyle.log" 2>&1
        ;;
    *) echo 'Unapproved candidate branch' >&2; exit 1 ;;
esac
git diff --exit-code
echo 'Baseline reproduced the specified defect; exact candidate reproducer, regressions, and checkstyle passed.' > "${results}/result.txt"
cat "${results}/result.txt"
