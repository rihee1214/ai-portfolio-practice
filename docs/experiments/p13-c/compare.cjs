const { spawnSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { isDeepStrictEqual } = require('node:util');

const workload = {
    paymentMode: 'DEMO', condition: 'SALEABLE', shippedUnits: 4, orderQuoteLines: 2,
    inspectedReturnRevision: 3, selectedOriginalPolicyFee: '3000', selectedOrdinal: 0, responsibility: 'BUYER',
};
const outcome = {
    attempt_amount: 7000, refund_state: 'UNKNOWN', purpose: 'ITEM', sequence: 1,
    return_state: 'REVIEW_REQUIRED', hold_state: 'ACTIVE', consent_key: 'child-prepared', consent_revision: 3,
    explicit_consent: true, assessment_state: 'BOUND', assessed_refund: 7000, assessed_increment: 3000,
    paid: 43000, reserved: 7000, succeeded: 0, reserved_increment: 3000, attempts: 1,
    provider_submissions: 0, provider_effects: 0, applied_fees: 0, completed_returns: 0,
};
const identityFields = {
    attempt: 'refundAttemptId', quote_fingerprint: 'quoteFingerprint', order_id: 'orderId',
    hold_id: 'holdId', assessment_id: 'assessmentId', quote_id: 'quoteId',
};
const timingKeys = ['userCommand', 'firstToPrepared', 'kill', 'restart', 'afterRestartToOutcome', 'consentToOutcome'];
const candidateOption = '-XX:TieredStopAtLevel=1';
const commandLimitNs = 10_000_000_000;
const improvementThresholdPercent = 10;

function ensure(condition, message) {
    if (!condition) throw new Error(message);
}

function readArtifact(root, relativePath, decode) {
    const bytes = fs.readFileSync(path.join(root, relativePath));
    const text = bytes.toString('utf8').replace(/^\uFEFF/, '');
    return { value: decode(text), ref: { path: relativePath, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') } };
}

function validatedSummary(root, destination) {
    const summarizer = path.join(__dirname, '..', 'p13-b', 'summarize.cjs');
    const result = spawnSync(process.execPath, [summarizer, root, destination], { encoding: 'utf8' });
    if (result.error) throw result.error;
    ensure(result.status === 0, `P13-B summary validation failed for ${root}\n${result.stderr}`);
    return readArtifact(destination, 'summary.json', JSON.parse);
}

function checkRecovery(trial, context) {
    const { processRecovery, identities, claims, consumers } = trial;
    ensure(processRecovery.jobAttempts === 2 && processRecovery.atPrepared.state === 'PROCESSING' &&
        processRecovery.atPrepared.mock_rows === 0, `Prepared state or recovery attempt count differs for ${context}`);
    const [first, restart] = claims;
    ensure(identities.jobId && claims.length === 2 && claims.every(claim => claim.job_id === identities.jobId && claim.eligible === true) &&
        first.eligibility_kind === 'READY' && first.attempt === 1 && first.owner_token &&
        restart.eligibility_kind === 'TAKEOVER' && restart.attempt === 2 && restart.previous_attempt === 1 &&
        restart.owner_token && restart.owner_token !== first.owner_token && restart.previous_owner === first.owner_token &&
        first.initial_lease_until === restart.required_at && restart.one_microsecond_after_lease === true,
        `Job claim or lease linkage differs for ${context}`);
    const expectedCauses = {
        'REFUND_QUERY_DUE.v1': identities.refundAttemptId,
        'REFUND_RESULT_RECORDED.v1': identities.refundAttemptId,
        'RETURN_FINANCE_RESUME.v1': identities.returnId,
    };
    ensure(identities.returnId && consumers.length === Object.keys(expectedCauses).length &&
        Object.entries(expectedCauses).every(([type, cause]) => consumers.some(
            row => row.type === type && row.cause_key === cause && row.status === 'SUCCEEDED' && row.attempts === 1)),
        `Downstream job result differs for ${context}`);
}

function readRun(root, destination, runner, mode) {
    const { value: summary, ref: summaryRef } = validatedSummary(root, destination);
    ensure(summary.artifacts[runner.ref.path].sha256 === runner.ref.sha256 && summary.sampleCount === 3,
        `Runner evidence changed or measurement count differs for ${mode}`);
    const legacy = runner.value.workerJvmMode === undefined;
    const expectedArguments = mode === 'tier1' ? [candidateOption] : [];
    const trialsByName = new Map(summary.trials.map(trial => [trial.name, trial]));
    const measurements = runner.value.trials.map(record => {
        const prefix = `groups/${record.trial}`;
        const context = `${mode}/${record.trial}`;
        const { value: trial, ref: trialRef } = readArtifact(root, `${prefix}/trial.json`, JSON.parse);
        const { selectedItem, ...logicalWorkload } = trial.workload;
        ensure(summary.artifacts[trialRef.path].sha256 === trialRef.sha256 && selectedItem &&
            isDeepStrictEqual(logicalWorkload, workload), `Trial evidence changed or workload differs for ${context}`);
        ensure(trial.workerJvmMode === (legacy ? undefined : mode), `Trial worker JVM mode differs for ${context}`);
        const { userCommandDeadline: deadline } = trial;
        ensure(deadline.limitNs === commandLimitNs && deadline.from === 'quote-started' &&
            deadline.through === 'user-command-verified' && deadline.elapsedNs <= commandLimitNs,
            `User command deadline differs for ${context}`);
        ensure(Object.entries(outcome).every(([field, expected]) => trial.outcome[field] === expected),
            `Money or business status differs for ${context}`);
        ensure(Object.entries(identityFields).every(([column, field]) =>
            trial.identities[field] && trial.outcome[column] === trial.identities[field]),
            `Business result identity differs for ${context}`);
        checkRecovery(trial, context);

        const argumentFiles = ['first', 'restart'].map(worker => {
            const { value: argumentsUsed, ref } = readArtifact(root, `${prefix}/processes/${worker}.args`,
                text => text.split(/\r?\n/).filter(line => line.startsWith('-XX:TieredStopAtLevel=')));
            ensure(isDeepStrictEqual(argumentsUsed, expectedArguments), `Worker launch option differs for ${context}/${worker}`);
            return ref;
        });
        const checkpoints = ['first-effect-runtime-ready', 'first-effect-runtime-prepared',
            'restart-effect-runtime-ready', 'restart-effect-runtime-completed'].map(name => {
            const { value, ref } = readArtifact(root, `${prefix}/processes/${name}.json`, JSON.parse);
            ensure(summary.artifacts[ref.path].sha256 === ref.sha256, `Runtime checkpoint changed for ${context}/${name}`);
            return { value, ref };
        });
        const runtime = [...trial.parentRuntime, ...checkpoints.map(checkpoint => checkpoint.value)];
        if (!legacy) {
            for (const { role, phase, jvm } of runtime) {
                const tier1Worker = mode === 'tier1' && role !== 'parent';
                ensure(isDeepStrictEqual(jvm.tieredStopAtLevelArguments, tier1Worker ? expectedArguments : []) &&
                    jvm.tieredStopAtLevel === (tier1Worker ? 1 : 4),
                    `Effective JVM option differs for ${context}/${role}/${phase}`);
            }
        }
        const { database_bytes, ...logicalScale } = trial.startingScale;
        ensure(isDeepStrictEqual(trial.startingScale, record.startingScale) && positiveScale(logicalScale),
            `Dataset size or hash evidence differs for ${context}`);
        const jvm = runtime.map(({ jvm }) => [jvm.javaVersion, jvm.vmName, jvm.vmVersion]);
        ensure(jvm[0].every(value => typeof value === 'string' && value.length > 0) &&
            jvm.every(identity => isDeepStrictEqual(identity, jvm[0])), `Runtime JDK differs for ${context}`);
        return {
            trial: record.trial, includedInMeasurements: record.includedInMeasurements, database: trial.database,
            media: fs.realpathSync(trial.media), logicalScale, databaseBytes: database_bytes, jdk: jvm[0],
            timingMillis: trialsByName.get(record.trial)?.timingsMillis ?? null, userCommandElapsedNs: deadline.elapsedNs,
            evidence: { trial: trialRef, workerArgumentFiles: argumentFiles, runtimeCheckpoints: checkpoints.map(item => item.ref) },
        };
    });
    return { summary, artifact: summaryRef, runner: runner.ref, root, mode, measurements, legacy };
}

function positiveScale(scale) {
    return Number.isSafeInteger(scale.indexes) && scale.indexes > 0 &&
        Number.isSafeInteger(scale.successful_migrations) && scale.successful_migrations > 0 &&
        /^[a-f0-9]{64}$/i.test(scale.index_sha256) && /^[a-f0-9]{64}$/i.test(scale.migration_sha256) &&
        Object.keys(scale.rows).length > 0 && Object.values(scale.rows).every(count => Number.isSafeInteger(count) && count >= 0);
}

function sharedWhenRecorded(baseline, candidate, description) {
    if (baseline == null || candidate == null) return null;
    ensure(isDeepStrictEqual(baseline, candidate), `${description} differs between baseline and candidate`);
    return baseline;
}

function compareTimings(baseline, candidate) {
    return Object.fromEntries(timingKeys.map(key => {
        const before = baseline.summary.timingMillis[key];
        const after = candidate.summary.timingMillis[key];
        return [key, { baseline: before, candidate: after, medianReductionPercent: 100 * (1 - after.median / before.median) }];
    }));
}

function runRecord(run, label) {
    return {
        source: run.summary.source, root: run.root, mode: run.mode,
        workerJvmProof: run.legacy ? 'recorded-launch-arguments' : 'recorded-launch-arguments-and-effective-level',
        runner: run.runner, summary: { ...run.artifact, path: `${label}/${run.artifact.path}` },
        trials: run.measurements.map(({ logicalScale, jdk, ...measurement }) => measurement),
    };
}

function main() {
    ensure(process.argv.length === 5, 'Usage: node compare.cjs BASELINE_TRIAL_ROOT CANDIDATE_TRIAL_ROOT NEW_OUTPUT_DIRECTORY');
    const [baselineRoot, candidateRoot] = process.argv.slice(2, 4).map(root => fs.realpathSync(root));
    const destination = path.resolve(process.argv[4]);
    ensure(baselineRoot !== candidateRoot, 'Use separate baseline and candidate evidence roots');
    const baselineRunner = readArtifact(baselineRoot, 'result.json', JSON.parse);
    const candidateRunner = readArtifact(candidateRoot, 'result.json', JSON.parse);
    const baselineMode = baselineRunner.value.workerJvmMode;
    ensure(baselineMode === undefined || baselineMode === 'baseline', 'Baseline worker JVM mode must be baseline');
    ensure(candidateRunner.value.workerJvmMode === 'tier1', 'Candidate worker JVM mode must be tier1');
    const endpoint = sharedWhenRecorded(baselineRunner.value.endpoint, candidateRunner.value.endpoint, 'PostgreSQL endpoint');
    const javaSha256 = sharedWhenRecorded(baselineRunner.value.tools?.java?.sha256, candidateRunner.value.tools?.java?.sha256,
        'Java executable hash');
    fs.mkdirSync(destination);
    const baseline = readRun(baselineRoot, path.join(destination, 'baseline'), baselineRunner, 'baseline');
    const candidate = readRun(candidateRoot, path.join(destination, 'candidate'), candidateRunner, 'tier1');
    ensure(baseline.summary.source.testId === candidate.summary.source.testId && baseline.summary.source.testId,
        'Experiment test identity differs between baseline and candidate');
    const trials = [...baseline.measurements, ...candidate.measurements];
    ensure(new Set(trials.map(trial => trial.database)).size === trials.length &&
        new Set(trials.map(trial => trial.media)).size === trials.length, 'Baseline and candidate must use separate DB and media');
    const [{ logicalScale, jdk }] = trials;
    ensure(trials.every(trial => isDeepStrictEqual(trial.logicalScale, logicalScale)), 'Logical dataset scale differs between trials');
    ensure(trials.every(trial => isDeepStrictEqual(trial.jdk, jdk)), 'Runtime JDK differs between trials');
    const timingMillis = compareTimings(baseline, candidate);
    const comparison = {
        analysis: { node: process.version, scriptSha256: createHash('sha256').update(fs.readFileSync(__filename)).digest('hex') },
        sampleCountPerVariant: baseline.summary.sampleCount, userCommandLimitNs: commandLimitNs,
        workload, logicalScale, outcome, endpoint, jdk: { version: jdk[0], vmName: jdk[1], vmVersion: jdk[2], javaSha256 },
        baseline: runRecord(baseline, 'baseline'), candidate: runRecord(candidate, 'candidate'),
        timingMillis,
        primaryMetric: {
            key: 'restart', improvementThresholdPercent,
            thresholdMet: timingMillis.restart.candidate.median * 100 <=
                timingMillis.restart.baseline.median * (100 - improvementThresholdPercent),
        },
    };
    fs.writeFileSync(path.join(destination, 'comparison.json'), JSON.stringify(comparison, null, 2) + '\n', { flag: 'wx' });
}

try {
    main();
} catch (error) {
    process.stderr.write(error.message + '\n');
    process.exitCode = 1;
}
