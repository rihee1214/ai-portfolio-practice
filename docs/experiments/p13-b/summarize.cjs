const { createHash } = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const labels = ['warmup', 'run-1', 'run-2', 'run-3'];
const spans = [
    ['fixture', 'fixture-started', 'fixture-ready', '업무 fixture 준비'],
    ['quote', 'quote-started', 'quote-returned', '견적 API'],
    ['consent', 'consent-started', 'consent-returned', '동의 API'],
    ['userCommand', 'quote-started', 'user-command-verified', '견적 시작 → 동의와 작업 등록 확인'],
    ['firstLaunch', 'first-start-requested', 'first-started', '첫 JVM 실행 요청 → PID 반환'],
    ['firstToPrepared', 'first-start-requested', 'prepared-observed', '첫 JVM 실행 요청 → PREPARED 관측'],
    ['preparedToKill', 'prepared-observed', 'kill-requested', 'PREPARED 관측 → 종료 요청'],
    ['kill', 'kill-requested', 'killed', '강제 종료 요청 → 종료 확인'],
    ['killedToRestart', 'killed', 'restart-requested', '종료 확인 → 재시작 요청'],
    ['restartLaunch', 'restart-requested', 'restart-started', '재시작 요청 → PID 반환'],
    ['restart', 'restart-requested', 'restart-exited', '재시작 요청 → worker 정상 종료'],
    ['query', 'query-started', 'query-completed', '예약 조회 작업'],
    ['resultConsumer', 'result-consumer-started', 'result-consumer-completed', '환불 결과 소비 작업'],
    ['returnConsumer', 'return-consumer-started', 'return-consumer-completed', '반품 진행 소비 작업'],
    ['afterRestartToOutcome', 'restart-exited', 'outcome-observed', '재시작 종료 → 업무 결과 관측'],
    ['consentToOutcome', 'consent-started', 'outcome-observed', '동의 시작 → 업무 결과 관측'],
];
const childCheckpoints = [
    ['first-effect-runtime-ready.json', 'first-worker', 'ready', 'firstPid'],
    ['first-effect-runtime-prepared.json', 'first-worker', 'prepared', 'firstPid'],
    ['restart-effect-runtime-ready.json', 'second-worker', 'ready', 'restartPid'],
    ['restart-effect-runtime-completed.json', 'second-worker', 'completed', 'restartPid'],
];

function ensure(condition, message) {
    if (!condition) throw new Error(message);
}

function artifactReader(root, artifacts) {
    return function read(relative) {
        const data = fs.readFileSync(path.join(root, relative));
        artifacts[relative] = { bytes: data.length, sha256: createHash('sha256').update(data).digest('hex') };
        return data.toString('utf8').replace(/^\uFEFF/, '');
    };
}

function stats(values) {
    ensure(values.length > 0 && values.every(Number.isFinite), 'Invalid distribution values');
    const sorted = [...values].sort((a, b) => a - b);
    const middle = Math.floor(sorted.length / 2);
    return {
        values,
        min: sorted[0],
        median: sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2,
        max: sorted.at(-1),
    };
}

function postgresRecords(raw) {
    ensure(raw.endsWith('\n'), 'PostgreSQL NDJSON is missing its final line terminator');
    return raw.replace(/\r?\n$/, '').split(/\r?\n/).map((line, index) => {
        try {
            return JSON.parse(line);
        } catch (cause) {
            throw new Error('Invalid standalone PostgreSQL NDJSON record at line ' + (index + 1), { cause });
        }
    });
}

function databaseSummary(read, prefix, database, phase) {
    const rows = postgresRecords(read(prefix + '/postgres-samples.ndjson'));
    const waitSamples = {};
    const peaks = { sessions: 0, active: 0, idleInTransaction: 0, grantedLocks: 0, ungrantedLocks: 0, lockWaitingBackends: 0, blockedBackends: 0 };
    let lockWaitSamples = 0;
    let lastStarted = -1;
    const gaps = [];
    for (const row of rows) {
        ensure(row.postgres.database === database && row.startedNs > lastStarted && row.finishedNs >= row.startedNs,
            'Database sampler order or attribution differs for ' + prefix);
        if (lastStarted >= 0) gaps.push((row.startedNs - lastStarted) / 1e6);
        lastStarted = row.startedNs;
        const { activity, locks } = row.postgres;
        const blocked = activity.filter(backend => backend.blockers.length > 0).length;
        const waiting = activity.filter(backend => backend.wait_event_type === 'Lock').length;
        const ungranted = locks.filter(lock => !lock.granted).reduce((sum, lock) => sum + lock.count, 0);
        const granted = locks.filter(lock => lock.granted).reduce((sum, lock) => sum + lock.count, 0);
        const perSample = {
            sessions: activity.length,
            active: activity.filter(backend => backend.state === 'active').length,
            idleInTransaction: activity.filter(backend => backend.state?.startsWith('idle in transaction')).length,
            grantedLocks: granted,
            ungrantedLocks: ungranted,
            lockWaitingBackends: waiting,
            blockedBackends: blocked,
        };
        for (const key of Object.keys(perSample)) peaks[key] = Math.max(peaks[key], perSample[key]);
        if (blocked || waiting || ungranted) lockWaitSamples++;
        for (const type of new Set(activity.filter(backend => backend.wait_event_type).map(backend => backend.wait_event_type + '/' + backend.wait_event))) {
            waitSamples[type] = (waitSamples[type] ?? 0) + 1;
        }
    }
    const withinWork = rows.filter(row => row.startedNs <= phase['outcome-observed'] && row.finishedNs >= phase['quote-started']);
    ensure(withinWork.length >= 2, 'Missing database samples overlapping work in ' + prefix);
    const first = rows[0].postgres.databaseStats;
    const last = rows.at(-1).postgres.databaseStats;
    ensure(first.statsReset === last.statsReset, 'Database stats reset during ' + prefix);
    const statsDelta = Object.fromEntries(['commits', 'rollbacks', 'blocksRead', 'blocksHit', 'deadlocks'].map(key => [key, last[key] - first[key]]));
    return { samples: rows.length, samplesOverlappingWork: withinWork.length, sampleGapMillis: stats(gaps), lockWaitSamples, peaks, waitSamples, statsDelta };
}

function checkRuntime(snapshot, name, expectedRole, expectedPhase, expectedPid, database) {
    ensure(snapshot.role === expectedRole && snapshot.phase === expectedPhase && snapshot.pid === expectedPid &&
        snapshot.database === database && snapshot.pool.database === database && snapshot.pool.available &&
        snapshot.jvm.heap.usedBytes > 0 && snapshot.jvm.threads.live > 0 && snapshot.jvm.gc.length > 0 &&
        snapshot.pool.maximum === 5 && snapshot.pool.total > 0 && Number.isFinite(Date.parse(snapshot.capturedAt)),
        'Invalid runtime checkpoint for ' + name + ' ' + expectedRole + '/' + expectedPhase);
    return snapshot;
}

function runtimeDeltas(runtime) {
    const supportedDelta = (before, after) => before >= 0 && after >= before ? after - before : null;
    return [0, 2, 4].map(index => {
        const { role, pid, jvm: before } = runtime[index];
        const { jvm: after } = runtime[index + 1];
        const gc = before.gc.map(collector => {
            const match = after.gc.find(candidate => candidate.name === collector.name);
            return {
                name: collector.name,
                count: supportedDelta(collector.count, match.count),
                millis: supportedDelta(collector.millis, match.millis),
            };
        });
        return { role, pid, gc, jitMillis: supportedDelta(before.jit.totalCompilationMillis, after.jit.totalCompilationMillis) };
    });
}

function readTrial(root, read, name, record) {
    const prefix = 'groups/' + name;
    const trial = JSON.parse(read(prefix + '/trial.json'));
    const outer = JSON.parse(read(prefix + '/phase.json'));
    const maven = JSON.parse(read(prefix + '/maven-command.json'));
    const cpuBefore = JSON.parse(read(prefix + '/container-cpu-before.json'));
    const cpuAfter = JSON.parse(read(prefix + '/container-cpu-after.json'));
    read(prefix + '/failsafe-reports/TEST-com.example.marketplace.dbtest.P13ReturnRefundExperiment.xml');
    ensure(trial.trial === name && trial.status === 'passed', 'Missing successful trial ' + name);
    const { database, media, startingScale, outcome, processRecovery } = trial;
    ensure(record.trial === name && record.status === 'passed' && record.includedInMeasurements === (name !== 'warmup') &&
        record.database === database && record.databaseProof.primary === database &&
        JSON.stringify(record.databaseProof.created) === JSON.stringify([database]) &&
        fs.realpathSync(record.media) === fs.realpathSync(media) &&
        fs.realpathSync(record.evidence) === fs.realpathSync(path.join(root, prefix)) &&
        outer.status === 'passed' && outer.processExited && maven.exitCode === 0 && record.junit.status === 'passed',
        'Runner proof differs for ' + name);
    ensure(database && media && processRecovery.firstExitCode !== 0 && processRecovery.restartExitCode === 0 &&
        processRecovery.firstPid !== processRecovery.restartPid && processRecovery.jobStatus === 'SUCCEEDED',
        'Child execution did not match the measured scenario in ' + name);
    const phase = Object.fromEntries(trial.timeline.map(mark => [mark.phase, mark.elapsedNs]));
    ensure(Object.keys(phase).length === trial.timeline.length &&
        trial.timeline.every((mark, index) => mark.elapsedNs >= (trial.timeline[index - 1]?.elapsedNs ?? 0)),
        'Invalid parent timeline for ' + name);
    const timingsMillis = Object.fromEntries(spans.map(([key, from, to]) => {
        ensure(Number.isSafeInteger(phase[from]) && Number.isSafeInteger(phase[to]) && phase[to] > phase[from],
            'Missing monotonic timing for ' + name + ' ' + key);
        return [key, (phase[to] - phase[from]) / 1e6];
    }));
    const { userCommandDeadline } = trial;
    ensure(userCommandDeadline.elapsedNs === phase['user-command-verified'] - phase['quote-started'] &&
        userCommandDeadline.elapsedNs <= userCommandDeadline.limitNs, 'User command deadline proof differs for ' + name);
    const [before, after] = trial.parentRuntime;
    const runtime = [
        checkRuntime(before, name, 'parent', 'before-quote', before.pid, database),
        checkRuntime(after, name, 'parent', 'after-outcome', before.pid, database),
        ...childCheckpoints.map(([file, role, stage, pidField]) => {
            const snapshot = JSON.parse(read(prefix + '/processes/' + file));
            ensure(snapshot.experimentRun === name, 'Unexpected worker run label in ' + file);
            return checkRuntime(snapshot, name, role, stage, processRecovery[pidField], database);
        }),
    ];
    ensure(new Set(runtime.map(snapshot => snapshot.jvm.javaVersion)).size === 1 &&
        new Set(runtime.map(snapshot => snapshot.pid)).size === 3, 'Unexpected runtime conditions for ' + name);
    const databaseSamples = databaseSummary(read, prefix, database, phase);
    let usageMicroseconds = null;
    if (cpuBefore.status === 'observed' && cpuAfter.status === 'observed') {
        ensure(cpuBefore.source === cpuAfter.source, 'Container CPU source changed for ' + name);
        usageMicroseconds = cpuBefore.source === 'cgroup-v1-cpuacct'
            ? (cpuAfter.counters.usage_nsec - cpuBefore.counters.usage_nsec) / 1000
            : cpuAfter.counters.usage_usec - cpuBefore.counters.usage_usec;
    }
    ensure(usageMicroseconds === null || usageMicroseconds >= 0, 'Container CPU counter decreased for ' + name);
    const execution = {
        mavenElapsedSeconds: maven.elapsedSeconds, outerElapsedSeconds: outer.elapsedSeconds,
        firstFixtureUtc: trial.timeline[0].observedUtc, finalParentUtc: after.capturedAt,
        containerCpuUsageMicroseconds: usageMicroseconds,
        containerCpuSource: usageMicroseconds === null ? null : cpuBefore.source,
    };
    return {
        name, database, media, startingScale, outcome, processRecovery, userCommandDeadline, timingsMillis, runtime,
        runtimeDeltas: runtimeDeltas(runtime), databaseSamples, execution,
    };
}

function comparableScale(trial) {
    const { database_bytes, ...logical } = trial.startingScale;
    return JSON.stringify(logical);
}

function comparableOutcome(trial) {
    const identifiers = ['attempt', 'quote_fingerprint', 'order_id', 'hold_id', 'assessment_id', 'quote_id'];
    return JSON.stringify(Object.fromEntries(Object.entries(trial.outcome).filter(([key]) => !identifiers.includes(key))));
}

function displaySummary(summary) {
    const format = (values, digits = 3) => values.map(value => value.toFixed(digits)).join(' / ');
    const lines = [
        '# P13-B 측정 집계', '',
        '| 구간 | run-1 / run-2 / run-3 (ms) | 최소 / 중앙 / 최대 (ms) |',
        '| --- | ---: | ---: |',
        ...spans.map(([key, _from, _to, label]) => {
            const { values, min, median, max } = summary.timingMillis[key];
            return '| ' + label + ' | ' + format(values) + ' | ' + format([min, median, max]) + ' |';
        }),
        '', '동의 시작부터 업무 결과 관측까지의 한 건당 경과시간 역수는 ' +
            format(summary.observedCaseRates.individualPerSecond) + '건/초다. 세 업무 구간을 합치면 ' +
            summary.observedCaseRates.casesPerActiveSecond.toFixed(3) + '건/초다.',
        '기술 실패 0/3회, 계획한 강제 종료 3/6 자식 JVM, 정상 재시작 종료 3/3회, 환불 UNKNOWN 3/3건.',
        '', '| DB 표본 | run-1 | run-2 | run-3 |', '| --- | ---: | ---: | ---: |',
    ];
    const db = summary.trials.map(trial => trial.databaseSamples);
    const dbRows = [
        ['표본', sample => sample.samples],
        ['업무 구간과 겹친 표본', sample => sample.samplesOverlappingWork],
        ['최대 client backend', sample => sample.peaks.sessions],
        ['최대 active client backend', sample => sample.peaks.active],
        ['최대 부여 잠금', sample => sample.peaks.grantedLocks],
        ['최대 미부여 잠금', sample => sample.peaks.ungrantedLocks],
        ['잠금 대기가 관측된 표본', sample => sample.lockWaitSamples],
        ['최대 차단된 backend', sample => sample.peaks.blockedBackends],
        ['데드락 누적 카운터 차이', sample => sample.statsDelta.deadlocks],
        ['표본 시작 간격 중앙값(ms)', sample => sample.sampleGapMillis.median.toFixed(1)],
        ['표본 시작 간격 최댓값(ms)', sample => sample.sampleGapMillis.max.toFixed(1)],
    ];
    for (const [label, metric] of dbRows) lines.push('| ' + label + ' | ' + db.map(metric).join(' | ') + ' |');
    const cpu = summary.trials.map(trial => trial.execution.containerCpuUsageMicroseconds);
    lines.push('', 'Maven 구간 전체에서 PostgreSQL 컨테이너 CPU 누적값 차이는 ' +
        cpu.map(value => value === null ? '미측정' : (value / 1e6).toFixed(3) + ' CPU초').join(' / ') + '다.');
    lines.push('', '| JVM 시점 | 힙 사용량 (MiB) | 누적 GC 횟수 / 시간 (ms) | 현재/최대 스레드 | 풀 active/idle/total/awaiting |',
        '| --- | ---: | --- | --- | --- |');
    const cell = fields => fields.join(' · ');
    for (const [index, first] of summary.trials[0].runtime.entries()) {
        const observed = summary.trials.map(trial => trial.runtime[index]);
        const gc = observed.map(({ jvm }) => jvm.gc.map(collector => collector.count + '/' + collector.millis).join(', '));
        const threads = observed.map(({ jvm }) => jvm.threads.live + '/' + jvm.threads.peak);
        const pool = observed.map(({ pool }) => [pool.active, pool.idle, pool.total, pool.awaiting].join('/'));
        lines.push('| ' + first.role + ' ' + first.phase + ' | ' + format(observed.map(({ jvm }) => jvm.heap.usedBytes / 1048576), 1)
            + ' | ' + cell(gc) + ' | ' + cell(threads) + ' | ' + cell(pool) + ' |');
    }
    lines.push('', '각 칸은 run-1, run-2, run-3 순서다. GC는 원자료의 collector 순서로 표시했다.', '');
    return lines.join('\n');
}

function main() {
    const [, , rootArg, destinationArg] = process.argv;
    ensure(rootArg && destinationArg && process.argv.length === 4, 'Usage: node summarize.cjs TRIAL_ROOT NEW_OUTPUT_DIRECTORY');
    const root = fs.realpathSync(rootArg);
    const destination = path.resolve(destinationArg);
    ensure(!fs.existsSync(destination), 'Use a fresh output directory');
    const artifacts = {};
    const read = artifactReader(root, artifacts);
    const runner = JSON.parse(read('result.json'));
    read('source.json');
    read('source-after.json');
    read('before/snapshot.json');
    read('after/snapshot.json');
    ensure(runner.status === 'passed' && runner.sourceVerification === 'passed' && runner.preservation === 'passed' &&
        runner.certificationEligible === false && JSON.stringify(runner.executionOrder) === JSON.stringify(labels) &&
        runner.trials.length === labels.length && runner.sourceSha256 === artifacts['source.json'].sha256 &&
        runner.sourceSha256 === artifacts['source-after.json'].sha256, 'Runner source, order, or preservation proof differs');
    const [warmup, ...trials] = labels.map((name, index) => readTrial(root, read, name, runner.trials[index]));
    const all = [warmup, ...trials];
    ensure(new Set(all.map(trial => trial.database)).size === labels.length &&
        new Set(all.map(trial => fs.realpathSync(trial.media))).size === labels.length,
        'Warmup and measured trials must have isolated DB and media');
    ensure(all.every(trial => comparableScale(trial) === comparableScale(warmup)), 'Logical starting scale differed between trials');
    ensure(all.every(trial => comparableOutcome(trial) === comparableOutcome(warmup)), 'Business result differed between trials');
    ensure(all.every((trial, index) => index === 0 ||
        Date.parse(all[index - 1].execution.finalParentUtc) <= Date.parse(trial.execution.firstFixtureUtc)), 'Trial activity intervals overlap');
    const java = all.flatMap(trial => trial.runtime.map(snapshot => JSON.stringify([
        snapshot.jvm.javaVersion, snapshot.jvm.vmName, snapshot.jvm.vmVersion, snapshot.jvm.availableProcessors,
        snapshot.jvm.heap.maxBytes, snapshot.jvm.gc.map(collector => collector.name).sort(),
        snapshot.pool.maximum, snapshot.pool.connectionTimeoutMillis,
    ])));
    ensure(new Set(java).size === 1, 'JVM or Hikari conditions differed between trials');
    const timingMillis = Object.fromEntries(spans.map(([key]) => [key, stats(trials.map(trial => trial.timingsMillis[key]))]));
    const businessObserved = trials.filter(trial => trial.outcome.refund_state === 'UNKNOWN' && trial.outcome.return_state === 'REVIEW_REQUIRED').length;
    ensure(businessObserved === trials.length, 'Business outcome differed across measured trials');
    const summary = {
        sourceRoot: root,
        source: { head: runner.head, sourceSha256: runner.sourceSha256, testId: runner.testId },
        analysis: { node: process.version, scriptSha256: createHash('sha256').update(fs.readFileSync(__filename)).digest('hex') },
        sampleCount: trials.length,
        warmupExcluded: { name: warmup.name, database: warmup.database, media: warmup.media },
        timingMillis,
        observedCaseRates: {
            individualPerSecond: trials.map(trial => 1000 / trial.timingsMillis.consentToOutcome),
            casesPerActiveSecond: 1000 * trials.length / timingMillis.consentToOutcome.values.reduce((a, b) => a + b, 0),
            note: 'Same serial one-case path timed from consent start to observed business outcome. This is a service-time rate.',
        },
        outcomes: {
            technicalFailures: { count: 0, denominator: trials.length, unit: 'measured trials' },
            expectedForcedExits: { count: trials.length, denominator: 2 * trials.length, unit: 'child processes' },
            restartSuccesses: { count: trials.length, denominator: trials.length, unit: 'restart child processes' },
            unknownRefunds: { count: businessObserved, denominator: trials.length, unit: 'refund attempts' },
        },
        trials,
        artifacts,
    };
    fs.mkdirSync(destination);
    fs.writeFileSync(path.join(destination, 'summary.json'), JSON.stringify(summary, null, 2) + '\n', { flag: 'wx' });
    fs.writeFileSync(path.join(destination, 'tables.md'), displaySummary(summary), { flag: 'wx' });
}

main();
