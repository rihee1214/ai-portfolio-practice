# 통합 검증 하네스 성능 개선

## 추가 개선 — 최종 로컬 수용·커밋 인계

**최신 판정:** 기준2와 final2는 새 무선택 전체 통과·보존 통과다. 본체66.353초(2.58%), 준비 포함61.973초(2.35%) 단축했으나 **31분 목표는 미달**이다. 동일 소스 독립 최종 검토는 로컬 하네스 범위를 수용했고 필수 잔여0이다. 로컬 커밋의 실제 해시·포함 파일·stage/commit 동일성은 아래 최종 감사 묶음에 기록한다. 예기치 않은 검사/증거 리더 실패는18회다(개발 전체2·개발 보조14·독립 검토 보조2). 독립 필수 보완 요청은1라운드1건이며 증거 리더 오류와 구분한다. 아래 시간순 기록의 “현재”와 당시 횟수는 이 최신 판정으로 대체 해석하며 원이력을 지우지 않는다.

### 최종 측정·수용 경계

[Astra 최종 독립 검토](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/final-review.md)·[기계 판정](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/final-review.json)은 `accepted-local-harness-performance-target-not-met`이며 PERF2-IR1은 코드·집중·실제 최종 전체 근거로 해결됐다(1라운드1건, 필수 잔여0). [최종 문서 확인](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/final-confirmation.json)이 후보 Markdown13개의 최종 해시를 결합한다. 실제 whole의269개 부모/자식 PowerShell 기록은7.6.9/Core다. 별도 검토 증거 해시 작업에서 도구 shell 옵션이7.7로 실행된 환경 제약은 제품 검사와 구분해 보존하고, 후속 문서 확인은 명시 exe 호출을 사용한다.

실패 집계는 선행 작업과 분리한다. 이번 개발 전체 실패2회는 구매 소비 완료 경계와 Docker ingress 바인딩이며, 개발 보조14회는 수집/실행 준비/감사 parser 오류다. 독립 검토의 증거 리더가 lifecycle stack의 `.target` 중첩을 누락한 오류1회는 실패 스크립트를 보존하고 실제 구조로 수정해 통과했다. 검토 산출물 해시 결합 작업1회도 도구의 다른 PowerShell 선택/Get-FileHash 미지원으로 빈 proofs를 남겼고, 부모 확인 후 원초안을 보존하고 명시7.6.9에서5개 실제 증거 해시를 결합했다. 두 검토 보조 오류는 제품 필수 지적 추가가 아니다. 계획된 음성 RED, 상태 observer 권한 오류1회, 기준/최종 disk 샘플 timeout 각1회, 문서 패치 쓰기 전 거부2회 등 도구 준비 기록은 검사 실패와 별도로 보존한다. 필수 보완은 PERF2-IR1 정확한 Node5 assertion 등록/누락·skip 차단1건이며 추가 실패를 통과로 덮지 않았다.

현재 세션 구현/감사 helper 담당은 `gpt-6.1-sol` high(`/root/perf2_execution`, `/root/perf2_node_gate`), 설계 판단·독립 검토 담당은 별도 `gpt-6-astra` medium(`/root/perf2_independent`)으로 지정했다. 부모가 검토자 역할을 겸했다고 기록하지 않으며 역할 명칭만으로 부모 실행 모델이 전환됐다고 주장하지 않는다. 제거된 공용 스킬이나 과거 검토 세션 실행을 이번 검토로 대신하지 않았다.

[최종 동등성 감사](../../.local-tools/perf2-browser-audit-repair-bf12331ca71f4b049abdcf61443912ed/comparison-85addab1712d4e1abbd80a92aae0e517/comparison.json)는 기존936 JUnit의 정확한 ID/호출/결과 보존+등록된8개 추가, 기존121guard 보존+정확한14개 추가, 브라우저196·Vitest427·계층 Node101의 동일 ID/결과를 확인했다. 각 실행의 새 DB63개와42worker 의존·배타·cap2·소유 종료·compiled admission,1115입력 drift0·기준 대비16경로 차이, readiness3 및 Java 비포트 부분 불변을 대조했다. 실제 새 포트 관측1회에서16개 대상 포트의 유일성·허용범위,21개 명령의 고정 대상,17304개 정확한 후보를 확인했다. H129/R0와 기존 사용자13은 그대로다. 이 감사는 새 제품 검사 실행이 아니며 기존 실패 감사는 보존한다.

| 동일 경계 | 새 기준2 | 최종2 | 관측 차이 |
|---|---:|---:|---:|
| 하네스 본체 | 2566.9262411초 / 42:46.926 | 2500.5732339초 / 41:40.573 | 66.353초·2.5849% 단축 |
| 준비·emulator 포함 launch | 2633.5792825초 / 43:53.579 | 2571.6063308초 / 42:51.606 | 61.973초·2.3532% 단축 |
| DB scheduler | 2229.602초 | 2162.190초 | 67.412초 단축 |
| 공통 빌드 / production | 77.814 / 5.199초 | 77.323 / 5.260초 | 실행당 재사용 유지 |
| 산출물 admission / 전후 보존 | 20.813 / 24.522초 | 19.989 / 24.499초 | 해시·보존 범위 유지 |

[최종2 결과](../../.local-tools/perf2-final2-6643f2c1c5bd4114a638cad9523c3dcb/result.json)·[측정](../../.local-tools/perf2-final2-measurement.json)·[단계 분석](../../.local-tools/perf2-analysis-final2-6643f2c1/analysis.md)·[원증거/소스 보존 감사](../../.local-tools/perf2-final2-preservation-audit.json)를 사용한다. 최종1115입력 manifest SHA256은 `5e8494543c1e5c22ca2021eafb732539395dae2a091c81c0d0618d2c3ae68b62`, 현재 소스 drift0·기존 원증거20972 및 진입 사본105 drift0·사용자13 변경0이다. 전체 결과는 JUnit944·통합 브라우저196(내장 release-L/P2 포함)·Vitest427·DB42묶음 통과다. 정확한 기존 검사 ID/결과와 새 guard/Node 귀속도 최종 독립 검토에서 확인했다. 단순 개수만으로 동등성을 판단하지 않는다.

비교는 같은 PC·명시 PowerShell7.6.9·도구/캐시 경로·기존 PG endpoint·Offline·무선택·역순·동시2 조건이며 각 실행의 DB/media/evidence는 새 UUID다. H129/R0 역사 산출물은 그대로 재사용하고 현재 소스의 빌드는 실행마다 새로 만든 뒤 해당 실행 안에서만 공유한다. warm cache 내용/OS cache와 호스트 부하는 통제 실험처럼 동일하지 않으므로 관측된 전후1쌍의 차이를 장기 안정성이나 전부 순서 정책의 인과 효과로 단정하지 않는다. 앞선 실패2회는 성능 비교에서 제외한다.

최종 호스트166샘플은 CPU평균32.96%·최대50.97%, 가용 메모리 최소578804KiB, disk queue p95=1이다. disk 조회 timeout1은 누락으로 보존하며0으로 치환하지 않는다(기준170샘플에도 timeout1). CPU만으로 병렬도3을 정당화하지 않고2를 유지했다. scheduler 밖338.383초, DB 배타 및 마지막 릴리스 복구의 직렬 비용이 남는다. 분석의 `no-pending-work-tail`524.963 capacity초는 마지막 배타 복구와 겹치므로 전부 제거 가능한 병렬 여유로 해석하지 않는다. 중첩된 단계 시간을 단순 합산하지 않는다.

현재 최종 whole은1회이며, 추가 동일 조건 반복은 하지 않는다. final1 뒤 final2의 목적은 포트 위험 보완 소스의 전체 근거 확보였다. 최종2 종료 후 비교 helper가 통합196개에 이미 포함된 release-L/P2개를 중복 가산해198개를 기대한 감사 오류1회와 존재하지 않는 집계 파일을 가정한 조회 오류1회를 추가 보존했다. 실제 `verify.ps1`은194개+내장2개를 합친196개를 보고한다. 원보고서의 정확한 ID/결과를 유지하고 감사 분류를 수정하며, 제품 검사나 원결과를 변경하지 않는다. 선행 작업의 실패·검토 횟수는 아래 역사 기록과 별개이며, 모델 호출/비캐시 토큰/압축 총계는 수집 가능한 근거가 없어 절감률을 추정하지 않는다.

사용자는 현재 수용 소스를 새 격리 자원에서 무선택 전체1회로 측정한 뒤, 실제 병목만 최소 수정하고 최종 전체·독립 검토·필수 보완 후 관련 수용 변경을 로컬 커밋하도록 승인했다. 목표는 가능하면31분 이하이며 필수 범위·공유 업무10초·유한 준비/정리·자원 보존을 유지한다. 푸시·배포·외부 자원 생성·실결제/송금·다음 제품 단계는 승인 범위가 아니다. 아래 선행 기록의 미커밋 인계는 당시 사실이며 이번 커밋 승인을 대신하지 않는다.

[새 진입 감사](../../.local-tools/perf2-entry-2752177b-7309-48a5-885a-be9cb4abf30e/entry.json)는 HEAD `dbaef2ddedfa7f2393e93bea6b648cf832c5cbf9`·빈 index·105미커밋 파일·직전 수용1105입력 불일치0을 확인했다. 진입 파일 사본과 선행 전체2회/독립 검토 원증거20972파일 해시를 새 경로에 보존했다. 사용자 deliverables13은 제외하며 안전한 E 파생 문서 이미지와 실행 미디어/원캡처는 구분한다. 실제 PowerShell7.6.9 경로·버전을 재확인했고 기존 실행 helper를 재사용한다.

기준 소스는 고정한다. 기존 scheduler 이벤트·group/contract timing·공통 build·준비·전후 보존과 launch 경계 및 읽기 전용 자원 observer로 새 기준을 측정하며 runtime 계측 추가는 하지 않는다. 원인별 대기는 registry와 이벤트로 재생하고, 개별 DB 연결·Maven/JVM 기동/후처리의 미분리 비용은 추측하지 않는다. 새 기준 뒤 긴 묶음/후속 소비 경로 우선 배치를 검토하며 실제 자원 포화 근거 없이 동시 수를 늘리지 않는다.

| 업무 결과 | 현재 상태·완료 조건 |
|---|---|
| 새 동일 소스 전체 기준 | 보정 소스 baseline2 전체 통과. root2566.9262411초·launch2633.5792825초. 무선택 Offline·ReverseGroups·동시2·기존 명시 도구/캐시/PG endpoint, 새 DB/media/evidence. 첫 실패는 시간 비교 기준에서 제외 |
| 실측 병목 최소 개선 | 고정 측정 시간 힌트·안정 위상 정렬 구현/집중 검증 완료. 기존 의존·배타·필수ID·assertion·deadline 불변 |
| 최종 영향 회귀·전체·독립 검토 | final2 전체·보존·동등성 감사 통과, 소스 drift0. 독립 최종 수용·필수 잔여0. final1 과거 경쟁 소켓 주체는 미확정 유지 |
| 관련 수용 변경 로컬 커밋 | 수용103개만 대상. 실제 stage/commit blob 대조·해시·잔여13개는 최종 감사 묶음에 기록. 사용자/환경/원증거 제외 |

커밋 범위는 선행 수용 P12-E·실행 환경 개선·성능 개선92개와 이번 명시 추가11개를 합친103개다. 겹치는 공통 하네스의 검증되지 않은 중간 상태를 재구성하지 않고 최종 수용 통합 소스를 한 논리적 커밋으로 묶는다. 안전한 파생 문서 이미지8개와 manifest는 기존 수용 문서의 의존성이며, 원캡처·미디어·도구·DB·원증거·사용자 `deliverables/`13개는 제외한다. [최종 커밋 감사 묶음](../../.local-tools/perf2-final-98b4d2e9a31c4f8c9e180b825d761f2e/)에 후보·검토 해시·stage 전후 blob·커밋 해시/메시지/파일·잔여 작업 트리를 연결한다. 커밋 후 해시는 이 로컬 감사에 남겨 문서 자기참조를 위한 amend나 이력 재작성을 하지 않는다. 실제 커밋 전에는 수용·stage 대조가 선행돼야 한다.

추가 개선의 첫 기준 실행 [명령](../../.local-tools/perf2-launch-d348078400614856b173d59117382434/command.json)·[결과](../../.local-tools/perf2-baseline-d348078400614856b173d59117382434/result.json)는 실패이며 보존은 통과했다. `release-recovery/browser-P`의 공개 모드 비회원 요청 전후 DB 무효과 assertion에서 `job_claim_fact`가11→12행으로 달라졌다. 소스는 직전 수용1105입력과 같지만 새 실패는 별도로 보존하며, 배타 묶음 실패 뒤 미실행/취소된 범위를 통과로 표시하지 않는다. 이번 자동 검사 실패1회(선행 작업7회와 별개)로 기록한다. 아직 성공한 새 기준이 없어 개선율을 계산하지 않는다.

[정확한 원행 추적](../../.local-tools/perf2-public-boundary-diagnosis-02653310373548f4ae770393bb219a26/claim-trace.json)·[before/after 연결](../../.local-tools/perf2-public-boundary-diagnosis-02653310373548f4ae770393bb219a26/pair-trace.json)에서 해당 실제 구매→배송의 `SHIPMENT_CREATED` 작업이 READY/attempt0→SUCCEEDED/attempt1로 진행했고 추가 claim의 canonical SHA가 일치함을 확인했다. `claimed_at`은 commit 시각이 아니므로 비회원 명령이 원인이라고 시간만으로 단정하지 않는다. 새 소유 DB는 종료·보존 상태라 읽기 시도1회가 거부됐고 재시작하지 않았다. 원 스냅샷을 근거로 사용한다. [Astra 계약 판단](../../.local-tools/perf2-baseline-failure-design-assessment.md)에 따라 실제 구매 업무의 기존 공유10초 안에서 정확한 배송 job/claim 소비 완료를 추가 확인하고 worker·guest 명령·AFTER 전체 business equality를 유지한다. 별도60초 준비로 이동·sleep·AFTER 재시도·기준 재설정·테이블 제외·timeout 확대는 하지 않는다.

서버 재시작 후 HEAD/index와 종료된 기준 실행을 재확인했다. 구현 중이던4파일과 이전5회귀 GREEN은 보존했고, 종료된 실행을 다시 이어붙이지 않는다. 이전 GREEN에는 소스 hash 결합이 없어 작은 회귀를 새 증거에서 현재 hash와 결합하는 목적을 기록한다. 이전 서브 에이전트가 복구되지 않아 같은 실제 Sol High 구현/Astra Medium 검토 역할을 새 세션 도구로 이어가며 수행하지 않은 검토를 주장하지 않는다.

[현재 소스 집중 인계](../../.local-tools/perf2-readiness-focused-19a65c9613e04331a259a0fa7a2c230c/handoff.md)는 Java 집중 컴파일·타입·lint·공개 브라우저 수집과 새 회귀5개 통과를4파일 해시에 결합했다. 기존96개 Node 검사 소스는 보존했으며 예상101개를 이때 모두 실행했다고 기록하지 않는다. 보조 수집기가 중첩 검사를 제외해58개를96개와 비교한 실패1회는 원본을 보존하고 중첩 수집을 수정했다. 현재 자동 확인 실패는 전체1회·보조 수집1회로 총2회이며 계획된 RED와 읽기 전용 조사 환경 제한은 별도다.

[Astra 독립 준비 검토](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/readiness-review.md)·[Git 추가 확인](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/git-followup.md)은 필수 지적0으로 보정 소스의 새 전체 기준 실행을 허용했다. 최종 성능 수용은 아니다. [새 기준 명령](../../.local-tools/perf2-launch-fc14033b6dce4c70a2ab55326e593f6a/command.json)은 같은 검사 조건·새 자원에서 실제 SQL/worker/브라우저 소비 및 전체 범위를 검증한다. 진행 중 소스는 고정하고 병목 개선은 성공한 전체 측정 후 판단한다.

[기준2 전체](../../.local-tools/perf2-baseline2-fc14033b6dce4c70a2ab55326e593f6a/result.json)·[측정](../../.local-tools/perf2-baseline2-measurement.json)·[소스 대조](../../.local-tools/perf2-baseline-source-audit.json)는936JUnit·196root브라우저·427프런트·42DB묶음·보존 통과,1107입력 drift0을 확인했다. 선행1105와 차이는 위 readiness4파일뿐이며 삭제0이다. frontend Node는 이번에 실제101개 pass/fail0/skip0이다. 상태 observer는 root 컨테이너 증거 읽기의 샌드박스 권한 오류로 종료됐으나 본체는 정상 완료했다. 재실행하지 않고 읽기 전용 승인 경로로 진행 상태와 종료를 확인했다. 이는 검사 본체 실패2회와 별도의 관측 실패1회다. 자원170샘플 중 disk CIM timeout1은 누락으로 공개하며0으로 치환하지 않는다.

[기준2 병목 분석](../../.local-tools/perf2-analysis-baseline2-fc14033b/analysis.md): scheduler2229.602초·공통빌드77.814초·production5.199초·산출물 admission20.813초·전후보존24.522초다. 중첩 비용을 합산하지 않는다. 마지막 빈 슬롯166.938 capacity초, 전역배타665.976초, drain18.045 capacity초를 구분했다. 고정 시간 모델은 현재2229.098초(실제와−0.504초), longest-ready2145.120초, downstream-critical-path2417.570초이며 실제 개선율이 아니다. 동시2 구간 가용 메모리 최소674372KiB·CPU평균38.679%·disk queue p95=1이므로 CPU만 보고 병렬도를 높이지 않는다. 측정 비용 고정 시 순서 조정의 scheduler 산술 하한2124.923초+밖337.324초도 목표1860초를 넘는다. 실제 적용 정책의 정확한 topology와 barrier를 재생한 뒤 최소 변경을 선택한다.

[Astra 순서 설계](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/scheduling-design.md)·[정확한 PS 순서 재생](../../.local-tools/perf2-order-candidate-prep-c94641a0374c455c9763cba12b353898/replay-af2ffbcb9d034852bf0e143858dae76a/model.md)·[설계 확인](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/scheduling-design-confirmation.md)에 따라 `executionPriority`의 고정 측정 시간 힌트→기존 안정 위상 정렬→그대로인 Node ready 판단을 선택했다. 정순/역순의 실제 반환 순서 모델 모두2145.120초로 약83.978초의 모델 이득이 유지됐다. `ReverseGroups`는 동률 순서만 뒤집고 힌트 부재는 과거 동작 그대로다. 힌트는 선택·timeout·통과 근거가 아니며 과거 증거를 실행 시 읽지 않는다. 모델 helper의 누락 prerequisite를 null 배열로 바꾼 준비 오류1회는 원본을 보존하고 실제 빈 목록 의미로 수정했다. 제품 소스나 검사를 실패했다고 바꾸지 않으며 자동 확인 실패 원장에는 포함한다.

독립 필수 보완 [PERF2-IR1](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/readiness-registration-finding.md)은 frontend Node glob의 성공만으로 새 readiness5개 누락/skip을 자동 차단하지 못한다는 점이다. 기존 실제101개 통과는 유효하지만 미래 실행 강제 계약이 부족하다. 같은 Node 실행에서 native JUnit 보고서를 함께 생성하고 정확한 파일·이름·1회 실행과 전체 nonpass 부재를 확인하는 필수 게이트로 보완한다. Vitest427 집계와 구분되는 `frontend-node-tests.xml`을 사용한다. 별도 Node 재실행이나 공통 Gate 변경은 하지 않는다. CLI 인수 위치 프로브에서 출력 보고서 부재로 실패1회가 추가됐고, 현재 개발 자동 확인 실패는 전체1·수집1·모델 adapter1·CLI probe1로 총4회다. 계획 RED·관측 권한 오류·disk 샘플 누락은 각각 별도 보존한다. 독립 필수 요청은 현재1라운드1건이며 최종 보완 검토 전까지 미해결로 둔다.

[순서 구현 인계](../../.local-tools/perf2-order-implementation-6294a9483f284e688a5c5fa4ac2d8d94/README.md)는3파일 동결·20종 잘못된 힌트·정/역순·선택·실제 배타 상호작용·기존 Node 순수3검사 통과를 연결한다. 힌트 외 등록부는 기준과 완전히 같아42클래스·587메서드·592호출·전달 계약·실행 배타를 보존한다. 이후 로컬 metadata writer의 중첩 schema 오인 실패1회, 다른 담당의 실제 PowerShell 구버전 선택을 런타임 assertion이 실행 전 차단한 준비 실패1회를 더해 **현재 개발 자동 확인 실패6회**다. 전자는 쓰기 전 거부됐고 후자는 제품 검사를 실행하지 않았으며, 수정 후 소스/명령/원실패를 각 인계에 보존했다. 셸 진단 명령의 인용 오류는 검사 실행이 아닌 도구 준비 오류로 별도 구분한다.

[Node 게이트 집중 인계](../../.local-tools/perf2-node-focused-b6c4245a4e89427087c0374300f01dda/handoff.md)는 새 guard7·실제 readiness5/native report·필수5 coverage 통과와 기존121+새7 등록을 확인했다. [독립 보완 검토](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/node-fix-review.md)는 코드·집중 범위를 해결로 판정했다. [통합 전체 진입 검토](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/prewhole-clearance.md)는 변경12파일 해시와 불변 Node scheduler를 확인했다. 최종 성능 수용은 아니며, final1의 실제128guard·101Node 및 필수5 통과가 남아 있다. [최종 전체 명령](../../.local-tools/perf2-launch-b60b623a1f8845c99cc942d052fda482/command.json)은 새 UUID 자원·동일 Offline/역순/동시2 조건으로 실행하고 준비/보고 비용까지 측정한다. 상태 observer만 컨테이너 파일 읽기가 가능한 권한으로 호출하며 업무 검증 조건을 바꾸지 않는다.

최종 실행 중 준비한 읽기 전용 동등성 감사의 inspection 구문 오류1회와 XML 부모 suite 구분 adapter의 음성 대조 실패1회를 추가 보존했다. 현재 예기치 않은 개발 자동/보조 확인 실패는 **8회(전체 업무 검증1·로컬 준비/수집/감사 보조7)**다. 실행 중 소스는 바꾸지 않았으며 Node native JUnit의97leaf+4부모 suite와 실제101개를 계층으로 대조한다. 단순 leaf 개수를101개와 비교하거나 부모4개를 검사 누락으로 처리하지 않는다.

final1은 `release-recovery/browser-P`의 checkpoint 뒤 원본을 다시 여는 `Activate/open-loopback-ingress`에서 실패했다. 초기 요약의 “복원 후”는 실제 spec60과 명령 원로그 확인으로 정정하며, 이 시점에는 Restore를 시작하지 않았다. 이는 앞선 구매 소비/비회원 무효과 assertion과 다른 지점이며, 원인 확인 전 재통과만으로 해결 처리하지 않는다. [실패 측정](../../.local-tools/perf2-final1-failed-measurement.json)은 root2234.4407525초·launch2304.6960644초를 원시간으로 보존하되 비교 적격=false·개선율/목표 판정=null로 둔다. 보존은 통과했고 전체 후속 Node101/컨테이너 검증은 완료 근거가 없다. **현재 개발 자동/보조 실패9회(전체2·보조7)**, 독립 필수 요청1건이다. 원 실패 소스1110입력 SHA `78141f6b31675e18044ed193b1dfbb06463733dc34581c2d667622fd5a6c581c`와 새 자원을 보존하며 커밋하지 않는다. Sol은 원인 추적, Astra는 계약·수정 범위를 별도로 판단한다.

[원행·명령 수집](../../.local-tools/perf2-ingress-diagnosis-a68cfa193eeb470fb4db4cb054454d1b/collected.json)과 [독립 바인딩 판단](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/final1-bind-cause-assessment.md)은 Docker의 `127.0.0.1:37858 → 10.240.16.68:8443` 재바인딩 거부를 확인했다. 기존 소유 proxy는 정지 상태였고 명령은 약536ms에 실패하여 timeout 확대 근거가 없다. 실패 전 공개 desktop/mobile 구매와 비회원 exact state equality·503·제공자0회는 통과했다. 당시 경쟁 소켓 소유자는 기록에 없어 **미확정으로 유지**한다. Windows와 Docker VM 시계·네임스페이스를 혼동하거나 현재 비점유를 과거 무점유 근거로 쓰지 않는다.

[한정된 현재 VM 관측](../../.local-tools/perf2-ingress-diagnosis-a68cfa193eeb470fb4db4cb054454d1b/current-docker-host-range-81b09e641c834dff8dcb33892da4dc78.json)은 이미 실행 중인 docker-desktop·유일한 dockerd PID와 실제 net namespace 일치를 확인하고 동적 TCP32768~60999를 읽었다. 현재 Windows IPv4/IPv6는1024~15000이다. 기존 테스트 선택20000~65535는 Docker 호스트 동적 범위와 겹치고37858도 그 안에 있다. 이는 현재 할당 위험의 증거이며 역사적 경쟁 소켓의 인과 증명은 아니다.

[Astra 격리 설계](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/port-isolation-implementation-decision.md)에 따라 제품/운영 Activate 동작은 바꾸지 않고 테스트 시작 시 실제 범위를 한 번 관측해 합집합을 제외한다. 기존 포트 고정·allocated set·실제 bind·100회 제한을 유지한다. 이미 실행 중인 VM 전제·명시 WSL/netsh/nsenter·유한 준비/명령 기한과 실패 증거를 등록하며, 기본 범위 추정·VM 명시 시작·설정 변경·충돌 무시·명령 재시도·proxy 유지로 quiescence 완화는 하지 않는다. WSL running 확인과 exec 사이의 TOCTOU까지 원자적으로 막았다고 주장하지 않는다. 새 집중 검사와 동일 소스 독립 확인 후 추가 전체 실행의 목적은 이 위험 완화와 최종 통합 근거 확보이며, final1을 재통과로 해결 처리하지 않는다.

[포트 보완 집중 인계](../../.local-tools/perf2-port-implementation-4412d9c1b44e4cc1a4d728f27ddf190d/handoff.json)와 [독립 검토](../../.local-tools/perf2-independent-review-4c71f5e9-7a32-4f4e-a212-73dd06fb146d/port-fix-review.md)는 실제 범위 관측·Java 후보 소비·새 JUnit8/Node guard7을 확인했다. 필수 guard는135개이며 기존 항목 삭제는 없다. 환경 객체 prototype 비교의 보조 assertion 오류, Maven 사용자 캐시 미지정에 따른 실행 전 거부, 직접 Java bridge 실행 오류를 각각 보존했다. 마지막 오류는33095자 인수 위험을 확인했으나 정확한 원인을 단정하지 않으며, 기존 컴파일과 관측 JSON을 재사용한 `@argfile` 실행으로 검증했다. 추가 전체 실행은 [final2 명령](../../.local-tools/perf2-launch-6643f2c1c5bd4114a638cad9523c3dcb/command.json)의 새 자원에서 수행한다. 독립 필수 요청1건의 최종 전체 증거 결합과 최종 수용은 아직 남아 있다.

[final2 전용 동등성 감사 준비](../../.local-tools/perf2-equivalence2-audit-7559fd43a2b84cb9abf612510fd67ad9/README.md)는 종료 후에만 실행하며 최종1115입력·기존936→944 JUnit의 정확한8추가·121→135 guard의 정확한14추가·나머지 동일 ID/결과·실제 포트 관측·격리/의존/소유 종료를 대조한다. 준비 중 존재하지 않는 preflight 속성 조회1회와 PowerShell 문자열의 `$'`가 JavaScript 치환 토큰으로 해석되어 쓰기 전 거부된 helper 생성1회를 별도 보존했다. 실제 검사나 실행 소스 변경은 없고, 합계14회 중 전체2·보조12로 구분한다.

## 선행 성능 개선 수용 상태

- 사용자 승인: 기존 필수 범위·신뢰성을 유지하는 분석·구현·최대2개 묶음의 제한적 병렬화·검증·별도 독립 검토·미커밋 인계. 앞선 병렬화 제외 조건은 이번 작업에서만 해제한다. 제품 단계 P13/P14에는 착수하지 않는다.
- 상태: 구현·독립 필수 수정 완료, **동일 소스의 새 무선택 전체2회 통과·절반 성능 목표2회 모두 미달**. [Astra 최종 독립 리뷰](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/final-review.md)는 로컬 하네스 구현 범위를 수용했다. 최종 문서·소스 동일성은 [독립 확인 기록](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/final-confirmation.md)을 따른다. 전체 통과·범위 수용·성능 목표 달성을 구분한다.
- 최신 근거: [첫 전체 측정](../../.local-tools/perf-full1-measurement.json)·[두 번째 전체 측정](../../.local-tools/perf-full2-measurement.json)·[동일 소스 비교](../../.local-tools/perf-final-comparison.json). 두 실행 모두936JUnit·196브라우저·427프런트·42DB그룹·컨테이너 재현·보존 통과다. 독립1라운드의 필수 보완1건 PERF-IR1은 [수정 검토](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/round1-fix-review.md)와 [전체 진입 대조](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/prewhole-clearance.md)에서 해결됐고, [최종 판정 JSON](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/final-review.json)의 새 필수 보완·미해결은0이다. 독립 검토는 유효한 원근거를 재사용했으며 새 테스트/DB/전체를 반복하지 않았다.
- 최종 실행 소스: 두 입력 manifest 모두1105파일·SHA-256 `64838da09924a439ac600fcff11a81c65345c570da5f18ad7331564c2950be5f`. [전체2 후 감사](../../.local-tools/perf-after-full2-audit.json)에서 각 실행 입력의 현재 drift0·HEAD 동일·index 비어 있음, 기존원증거9812·진입사본79·사용자13파일 변경0을 확인했다. 감사 시 미커밋105파일은 기존 수용분66(이번 작업 변경13/그대로53)·사용자13·진입 목록 밖 현재/신규26으로 구분한다. 기존 수용분과 이번 성능 delta를 한 변경으로 소유하지 않으며, 진입 사본은 덮어쓰지 않았다. 아래 원장에는 당시 실패·미완료 상태도 시간순 사실로 남긴다.
- 기준 HEAD `dbaef2ddedfa7f2393e93bea6b648cf832c5cbf9`, 빈 index. [진입 감사](../../.local-tools/perf-entry-fe8ba85a-ed35-4cf7-a2b8-9a1302649326/entry.json): 수용1090입력 불일치0, 기존 미커밋79파일 사본/소유와 기준 원증거9812파일 해시 보존. 사용자 `deliverables/`·`.codex` 등 무관한 변경을 포함/삭제하지 않는다.
- 기준은 [직전 실행 개선 수용](P12-DE-execution-hardening.md)과 [full3](../../.local-tools/pwsh769-full3-1e9bfbe0bcd94ecea17068e56412200a/result.json)다. 필수935 JUnit·196브라우저·427프런트,42DB그룹·root 컨테이너 재현·보존을 유지한다. 새 guard는 추가하며 기존 ID/파라미터 횟수를 줄이지 않는다.
- 정확한 시간: root **3785.0445586초(63:05.045)**, 사전 준비를 포함한 launch 시작→exit **3841.4221501초(64:01.422)**. 목표는 각각 **1892.5222793초(31:32.522)**와 **1920.71107505초(32:00.711)** 이하. 두 경계를 함께 보고하고 이전 측정 안의 비용을 밖으로 옮겨 절감으로 계산하지 않는다.
- 실제 지정 exe7.6.9/Core 경로를 재확인했다. 기존 Sol High 두 담당을 재사용하고 별도 Astra Medium이 설계 위험 판단·독립 검토한다. 부모 모델의 자동 변경을 주장하지 않는다. 커밋·푸시·배포·외부 자원 생성·실결제/송금 금지.

## 기준 비용·선택 근거

[기준 분석](../../.local-tools/perf-baseline-4015e6e3-fea3-4bfb-81d7-37ce97f99386/analysis.json)의 원 JSON/XML/contract timing을 재사용한다. 그룹 wall 합3450.0713초, 그룹 밖334.9732초(공통 build79.8931초·보존23.3418초 포함)다. 두 병렬 슬롯에 그룹 시간을 완벽히 나눠도 기존 그룹 밖 비용을 유지하는 산술 하한은2060.0089초다. 이는 실제 병렬 성능의 예측/측정값이 아니며 배타 검사·경합 때문에 더 길어질 수 있다. 병렬화만으로 절반 달성을 보장하지 않는다.

큰 그룹은 release-recovery529.4초, browser-api501.5초, return-recovery235.5초, return-finance207.9초, process-recovery201.0초다. 기존 계측의 DB/context 기동39회280.088초·준비432.663초·상태 비교259.166초는 단계 의미와 중첩 범위를 유지한다. 미계측 DB 연결 비용을 전체 준비 시간으로 단정하지 않는다. Vite 핵심 빌드는 수백 ms 수준이고 동일 production 출력/이미지 해시가 확인돼 재사용은 가능하지만 큰 절감의 근거로 과장하지 않는다.

기존 권한·금액·동시성·멱등성·복구·후속 소비 assertion을 바꾸지 않는다. 실제 업무 명령을 fixture/SQL로 대체하지 않는다. UI 제품 변경은 해당 없음이며 하네스가 기존 UI→API→DB→worker 소비를 빠짐없이 실행하는 계약이 변경 대상이다. 새 제품 migration·전역 도구 설정·자동 설치는 범위 밖이다.

## 구현 계약·완료표

[잔여 비용 진단](../../.local-tools/perf-remaining-costs-c5fb8c7257c74a1e83e50aa072d53d8d/diagnosis.md)은 기존 계측만 재사용했다. 기존 시간 고정·전역배타3그룹658.934초 조건의 산술 하한은2389.476초이고, 승인된 max2 스케줄 모델은2412.972초다. 모두 변경 후 실측이 아니다. browser wait407.444초와 실제 Playwright406.863초는 중첩되므로 제거 가능한 idle로 보지 않는다. 기존 snapshot3종148.242초도 절감량 자체가 아니다. 작업별 compiled복사 제거는 새 병렬 구현의 추가 비용을 줄인 것이므로 과거 직렬 기준에서 다시 차감하지 않는다. 연결·원격·정리의 독립 비용은 미계측이며 필수 업무/복구 assertion은 삭제 대상이 아니다.

| 업무 결과 | 생산자→소비자 및 제약 | 검사/증거 계획 | 상태 |
|---|---|---|---|
| 계측 기반 범위 결정 | 기준 원시간→단계별 비용·추가 계측 대상. 추정과 실제 분리 | 원 JSON/XML/timing·경계 정의·캐시 조건 | 분석 완료 |
| 실행당 산출물 재사용 | 공통 compiled/production 산출물→동일 실행의 소비자. 소스·모드·설정·tool/lockfile·정확한 파일 목록/해시 확인, 역사/dev/prod 분리 | 변조·소스/모드 불일치·누락/추가 파일 거부·실제 소비·전후 해시 | 구현·전체2회 통과 |
| 전체 상태 비교 왕복 감소 | 기존 전체테이블 JSON projection/order/null 표현→단일 SELECT 결과 TreeMap. 업무 명령·비교 대상·assertion은 그대로 | 새 PG 기존/변경 map 동일성·각 테이블 변경 탐지·실측·등록 JUnit | 구현·전체2회 통과 |
| 최대2개 안전한 검사 실행 | registry 명시 prerequisite→준비된 그룹→최대2개의 독립 PS/JVM. 영향표를 실행 DAG로 오인하지 않음 | ready/order/cap/배타/실패 생산자 거부 및 실제2자식 격리 | 실제 max2·42그룹2회 통과 |
| 소유권·실패 복구 | 자식별 env/DB/media/포트/tmp/Failsafe/보고서→부모 귀속·전체 gate. 공유 env를 runspace에서 바꾸지 않음 | 실제 소유 자손 정리·충돌 job/무관 sentinel 보존·정리 거부/정체의 유한 실패 | PERF-IR1 해결·전체2회 통과 |
| 공유 소비 격리 | 고정 브라우저24176·공유 Firebase는 자원 exclusion. Docker 전역 관측/복구·장애 주입은 위험에 따라 직렬/배타 | 등록 누락·충돌 거부, 실제 전후 보존 | 구현·전체2회 통과 |
| 범위·deadline 보존 | 기존 필수ID·parameter invocation·수집→같은 실제 assertion. 업무 공유10초·준비/기동/요청/정리 유한 제한 유지 | 기존 필수 범위 포함·936JUnit·196브라우저·427프런트·42DB, 실패/skip0. 총/시나리오 제한 미추가 | 전체2회 통과 |
| 성능·안정성 | 동일 최종 소스의 새 전체2회→원시간·자원 경합·각 실행의 목표 여부 | 사전 계획대로2회 모두 공개, 느린 결과 제외/평균 은폐 없음 | 2회 측정 완료·절반 목표 미달, 장기 안정성 미확인 |
| 독립 검토·인계 | 같은 소스·실행 근거→별도 Astra의 의존/격리/누락/귀속/성능 비교 판정 | 독립1라운드 필수1건 해결·최종 해시/보존 | 로컬 구현 범위 수용·문서/소스 동일성은 독립 확인 기록 참조 |

확정 실행 정책은 명시 PowerShell7.6.9, 최대2개(1개 진단은 허용,2초과 거부), source/산출물 불일치 시 fail closed다. 새 group child에 개별 환경을 전달하고 비밀값을 요청/로그에 저장하지 않는다. 현재 제품 Java package는 이미 공통1회이므로 재구현하지 않는다. 역사 R0/H129 산출물과 현재 산출물을 섞지 않는다. 기존 산출물 소유·검증을 재사용하며 통과 보고서를 cache로 재사용하지 않는다.

[Astra 설계 위험 판단](../../.local-tools/perf-design-risk-4a177dd2-5225-41d4-9ad5-8337d45bef84.md): parent env와 공통 java-tmp/Failsafe 임시파일을 공유하면 안 된다. 고정 포트·공유 인증 제공자·Docker 관측·실제 kill/restart의 구체적 소비를 검사하여 배타 등록한다. `public-demo-runtime→release-recovery`, `package-runtime→container-reproduction`의 실제 생산 관계는 유지한다. 내부 helper마다 별도 승인 게이트를 만들지 않는다.

추가 소스 판단: process-recovery/dashboard/fulfillment/questions/exchange-recovery/legacy의 실제 종료 대상은 반환된 소유 Process handle·검증된 D2 DB/UUID 경로이고, 관측 쿼리도 현재 DB로 제한된다. 이6개는 서로 `fault-injection` key로 배타 실행하면서 일반 전용DB 묶음과 짝지을 수 있다. 실제 자식 격리·소유 취소 검증이 선행하며 Docker package-runtime/release-recovery와 소유 범위가 더 넓은 release-upgrade는 전역 배타를 유지한다. 이는 설계 조건이며 실행 통과 선언이 아니다.

DB 상태 비교의 실측은 orderParticipantState1847회71.956초 등이다. 애플리케이션 JDBC는 이미 Hikari를 쓰므로 새 연결을 만든다고 가정하지 않는다. 전체테이블 SELECT를 UNION ALL 한 문장으로 묶는 것은 기존 projection/order/null과 모든 테이블을 유지하고 동시성 진행 관측을 바꾸지 않는 조건으로 Astra가 허용했다. deliberate 외부 연결·wait/blocking/transition 관측은 그대로 둔다. 구현 시 실제 새 PostgreSQL의 동일성·각 테이블 변경 탐지와 전후 시간을 확인하고 최종 전체에 포함한다.

## 사전 확정한 검증·측정 계획

1. 작고 결정적인 회귀로 scheduler cap/DAG/배타·실패·실제2자식 격리/취소·sentinel 보존, 산출물 identity/변조 거부를 확인한다. 새 검사를 필수 등록하고 기존 ID 목록의 포함 관계를 확인한다.
2. 변경 연결의 집중 실행이 필요한 경우 목적/대상/새 자원을 원장에 먼저 적는다. 공통 실행 변경이므로 이후 무선택 `./verify.ps1 -Offline` 전체는 필수다.
   - 첫 실제 연결 검사는 `-DbGroups 'database-runtime,job-contracts' -GroupConcurrency 2`로 계획한다. 구현 소스가 고정된 뒤 새 DB 두 묶음에서 실제 worker/Failsafe/tmp/env/증거 귀속과 snapshot 회귀, root 프런트 산출물 소비를 확인한다. 이는 전체 전 준비 검증이며 `selected-passed/certificationEligible=false`를 전체 근거로 바꾸지 않는다. 기존 사설 PG와 호스트 resource 관측을 같이 기록하고 메모리 압력이 확인될 때만 추가 조치를 판단한다. 사전 근거 없이 JVM heap 제한을 새로 줄이지 않는다.
3. **같은 최종 실행 소스에서 새 전체2회**를 계획한다. 1회는 전체 범위·실제 경합과 성능 개선 확인,2회는 새 자원에서 반복 변동·누락/격리 문제 확인이다. 성공 결과만 골라 평균내지 않으며 두 실행 각각의 시간과 목표 여부를 공개한다. 실패 수정 시 실패/영향 검사를 먼저 하고 어떤 근거가 무효인지 기록한 뒤 최종 측정 횟수를 재정한다.
4. 기준과 같은 호스트·명시 도구·Offline 캐시·선택 없는 필수 검사·테스트 PG endpoint를 사용하고 DB/media/evidence는 매회 새로 만든다. 기준의 역순 priority도 유지하되 병렬 dispatch 실제 순서는 기록한다. build cache는 실행당 새 경로이며 과거 실행 test output을 재사용하지 않는다.
5. root timer와 launch 전체 wall을 모두 비교한다. 단계 wall·시작/종료·동시 수·자원 key·CPU/메모리/디스크 관측을 기록한다. 과거 CPU/디스크의 정확한 원계측이 없으면 미측정으로 남기고 현재 경합 관측과 동일하다고 가정하지 않는다. 현재 호스트 부하·의존 캐시·이미지·기존 보존 대상 증가 등의 차이를 공개한다.
6. 독립 검토는 유효한2회 원근거를 재사용한다. 새 위험/실패/소스 변경 없이 세 번째 전체를 관성적으로 반복하지 않는다. 목표 미달이면 실제 개선율·남은 병목·범위 수용과 성능 목표 미달을 구분한다.

## 최종 구현·실행 근거

| 변경 계약 | 구현·등록된 소비자 | assertion·실행 근거 |
|---|---|---|
| 동일 실행 production 재사용 | [ProductionBundle](../../verification/ProductionBundle.cjs), [SharedProductionBundle](../../backend/src/test/java/com/example/marketplace/dbtest/SharedProductionBundle.java), PackageRuntimeIT·ReleaseRecoveryIT·root frontend preview | source/config/tool/mode·전체 byte inventory 확인, 새 복사본 독립성·변조/누락/추가 거부. [5guard·실제 소비 인계](../../.local-tools/perf-reuse-handoff.json), 전체2회 실제 소비·전후 해시 통과. 과거 test 결과·역사/dev bundle 재사용 없음 |
| 공통 compiled 읽기 소비 | [CompiledBuild](../../verification/CompiledBuild.cjs), [CompiledClasspath](../../verification/CompiledClasspath.ps1), backend/verify-db.ps1·각 worker | 생산 직후/42회 admission/최종 source·전체 inventory 대조, 실제 공통 classpath·private scratch/tmp/report와 bootstrap 출력 귀속. [집중 인계](../../.local-tools/perf-compiled-handoff-68de4a495de043898af9a5acbc474a35.json), 전체2회 최종 manifest 통과 |
| 전체 상태 비교3종 왕복 감소 | [ParticipantSnapshots](../../backend/src/test/java/com/example/marketplace/dbtest/ParticipantSnapshots.java), PostgresContractFixture의 order22·aftercare23·return31테이블, DatabaseRuntimeIT의 새 등록 method1 | 동일 projection/order/null·각 set의 모든 테이블 map 보존, 대표 typed schema76테이블의 기존/변경 동등성·테이블별 mutation 탐지 후 rollback. [새 PG 원측정](../../.local-tools/perf-snapshot-pg-3c388a11492e4359a5e6ce1446a0eccc/):20교대 측정쌍 평균848.208→211.267ms는 helper 한정이며 전체 절감률이 아님. 최종 전체가 실제 업무 흐름을 별도로 검증 |
| 현재 helper의 역사 소비 전달 | [HistoricalReleaseArtifacts](../../backend/src/test/java/com/example/marketplace/dbtest/HistoricalReleaseArtifacts.java) allowlist·copied-classpath | ParticipantSnapshots 전이 의존 포함·복사 byte·실제22키 linkage 검증. [별도 근거](../../.local-tools/perf-history-helper-fix-handoff.json), 기존 H129/R0 application artifact 보존 |
| max2·생산 관계·자원 배타 | [DbGroupExecution](../../verification/DbGroupExecution.cjs), [그룹 registry](../../verification/db-groups.json), backend/verify-db-group.ps1 | 기존 필수 범위·호출 수 보존, 실패 producer 소비 거부·자원 key 배타. 두 전체 이벤트 재구성에서 cap/DAG/exclusion 위반0, 각42 final job·42 compiled admission·종료 active0 |
| 자손 소유·유한 실패 | [WorkerJob](../../verification/WorkerJob.ps1), [admission wrapper](../../verification/Invoke-DbGroupWorker.ps1), [lifecycle5검사](../../verification/Test-WorkerJobLifecycle.cjs) | native 업무 전 실제 job membership/proof/admission, 비정상 부모 후 nested child/grandchild 정리·무관 sentinel/충돌 job 보존, cleanup 거부/정체의 유한 실패·새 admission 중단. [개발 인계](../../.local-tools/perf-workerjob-lifecycle-handoff.json), 독립 수정 검토·전체2회 unresolvedOwnership/unreleasedClaims0 |

두 전체는 선택 없음·제외 그룹0·`certificationEligible=true`다. 각936JUnit(공통344+DB592), frontend427, 필수 Node guard121개를 실패/error/skip0으로 실행했다. root 합산 브라우저196 외에 release 내부 L/P2사례도 원보고서에서 확인했고 unexpected/flaky0이다. 기존 assertion/정확한 method·parameter invocation·업무 공유10초/유한 준비·요청·정리 제한을 유지했으며 전체/시나리오 총시간 제한을 추가하지 않았다. 실제 PowerShell 양성 관측272/269건은 지정된 `C:/Users/rihee/.cache/codex-runtimes/codex-primary-runtime/dependencies/native/powershell/pwsh.exe`7.6.9/Core·compatibilitySessions0이다. 이는 OS의 기본 shell을 전역 변경했다는 뜻이 아니다.

## 예정된 전체2회 결과·성능 판정

| 경계 | 기준 | 전체1 | 전체2 | 각 실행 단축률 | 절반 목표 |
|---|---:|---:|---:|---:|---|
| root |63:05.045|41:27.473|42:13.892|34.28% /33.06%|31:32.522 이하, 둘 다 미달 |
| launch 준비 시작→exit |64:01.422|42:29.679|43:19.116|33.63% /32.34%|32:00.711 이하, 둘 다 미달 |

각 실제 명령은 [전체1 command.json](../../.local-tools/perf-launch-6920820db2404168b95c704b2ab3db0a/command.json)·[전체2 command.json](../../.local-tools/perf-launch-0090ae2dcd224e5db95966485b564847/command.json)에 있다. 지정7.6.9 실행에서 다음을 수행했다.

```powershell
./verify.ps1 -Offline -ReverseGroups -GroupConcurrency 2 -EvidenceDirectory .local-tools/perf-full1-6920820db2404168b95c704b2ab3db0a -TestPostgresPort 5785 -TestPostgresInstance f090f3a1-4f55-4a28-a29a-2949b8ad89be -TestPostgresContainer p12-a-pg-f090f3a1-4f55-4a28-a29a-2949b8ad89be
./verify.ps1 -Offline -ReverseGroups -GroupConcurrency 2 -EvidenceDirectory .local-tools/perf-full2-0090ae2dcd224e5db95966485b564847 -TestPostgresPort 5785 -TestPostgresInstance f090f3a1-4f55-4a28-a29a-2949b8ad89be -TestPostgresContainer p12-a-pg-f090f3a1-4f55-4a28-a29a-2949b8ad89be
```

원결과는 [전체1](../../.local-tools/perf-full1-6920820db2404168b95c704b2ab3db0a/result.json)·[전체2](../../.local-tools/perf-full2-0090ae2dcd224e5db95966485b564847/result.json) 모두 `passed`/exit0·보존 통과다. 첫 실행은 PERF-IR1 수정 후 모든 소비 계약·실제 max2 경합의 전체 근거를 확보하기 위해, 두 번째는 사전 확정한 동일 소스 반복 변동·격리 확인을 위해 새 DB/media/build/evidence로 실행했다. 기존 Offline 도구/이미지/의존 캐시·PG endpoint·ReverseGroups priority를 유지했다. 검사 결과를 재사용하거나 첫 통과 후 더 빠른 값을 고르기 위한 재실행은 없었다. launch에는 preflight·phone emulator 준비도 포함되며 관측 비용을 빼거나 build 비용을 측정 밖으로 옮기지 않았다. 원 exit의 launch 초는2549.6791329/2599.1161392이며 파생 JSON/표는 ms로 반올림한다.

root2회 차이는46.419초, launch는49.437초다. 두 번의 성공 관측만으로 장기 안정성을 단정하지 않는다. 겹쳐 실행된 그룹 elapsed 합은 기준3450.071초→3400.995/3452.576초이므로 wall 단축의 주효과는 검사들의 겹친 실행이다. 모든 개별 검사가 빨라졌다고 주장하지 않는다. 같은 실행 산출물의 준비/대조도 root 안에 포함했다: 공통 build74.883/73.852초, production5.151/5.375초, compiled 준비0.594/0.723초·입장 대조21.203/20.406초다. 중첩 scope를 합해 별도 wall 절감량으로 계산하지 않는다.

남은 큰 그룹은 release-recovery509.337/533.488초, browser-api507.115/512.320초, return-recovery231.385/234.973초다. release-recovery 등 전역 배타3그룹과 실제 브라우저 업무·worker 기동/복구·원격 호출은 assertion 유지 범위다. [기준 잔여 비용 진단](../../.local-tools/perf-remaining-costs-c5fb8c7257c74a1e83e50aa072d53d8d/diagnosis.md)의 약40분 max2 모델은 과거 고정 비용의 모형이며 새 실측과 혼동하지 않는다. 연결·원격·보존 내부 비용의 완전한 분리는 미계측이다. 목표 미달을 이유로 보안/금융/복구 assertion이나 실제 업무 명령을 줄이지 않았다.

호스트 표본165/168회의 CPU 평균28.36%/28.42%, 관측 최대49.94%/61.43%, disk queue p95=1·최대4/3이다. **여유 메모리 최저491584KiB(480.1MiB)/395796KiB(386.5MiB)**까지 내려갔다. baseline의 같은 호스트 시계열이 없고 현재 표본에는 무관 작업도 포함되므로 JVM/개별 검사 메모리나 성능 차이의 원인으로 귀속할 수 없다. 표본 사이 peak도 미관측이다. 전체1 disk 조회 timeout1회는 그대로 남기고 전체2 표본 조회 오류는0이다. 두 launch observer의60초 간격 bounded progress 조회는44회씩·변화 요약26회씩(합88조회/52요약)이며, 정확한 모델 API호출·비캐시 token·압축 횟수는 도구에 노출되지 않아 추산하지 않는다.

동일 소스 최종 확인과 독립 수용은 위1105입력·[최종 리뷰](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/final-review.md)를 기준으로 한다. 최종 문서·소스 동일성은 [독립 확인 기록](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/final-confirmation.md)을 따르며, [최종 인계 감사](../../.local-tools/perf-final-handoff-audit.json)에 소스·보존 대조를 남긴다. 실행 동작 변경 없는 기록 정리에 테스트를 다시 실행하지 않는다. 실제 Toss·송금·실기기·공개 배포 및 미확정 역사 실패는 이번 근거에 포함되지 않으며, 추가 제품 작업·커밋·푸시는 하지 않았다.

## 실패·미검증·보존 원장

선택2 [원결과](../../.local-tools/perf-selected2-48f9496e17094a05a2ed94556a815904/result.json)는 `failed`·보존 통과다. [실제 scheduler](../../.local-tools/perf-selected2-48f9496e17094a05a2ed94556a815904/db/scheduler-result.json)의 새 두 DB·서로 다른 PS/JVM·tmp/build/media/port는 최대2 동시로30.799초에 통과했지만, 프런트 Node95 중 cache 격리1검사가 Fetch `bad port`로 실패했다. 원래 임의 포트 번호는 로그에 없어 미관측으로 남긴다. [프런트 수정 인계](../../.local-tools/perf-proxy-port-fix-handoff.json)는 기존2초 native HTTP helper를 재사용해 동시 cache·HTML200/root assertion을 유지하고 실제 포트를 기록한다. Fetch6000 거부/무요청과 소유 서버로 라우팅한 native HTTP의 성공을 별도 helper 회귀로 확인했으며, 이는 원래 포트나 브라우저6000허용의 증거가 아니다. lint·영향32·전체프런트Node96이 통과했다. 이 프런트 수정만을 이유로 이미 통과한 DB 묶음을 반복하지 않는다.

선택2의 [진단 계측v2](../../.local-tools/perf-selected2-measurement-v2.json)는 전체 성능 비교에서 제외한다. root245.280초/launch284.897초는 선택·실패 실행 시간이며 절감률/목표 판정은 null이다. 초기 파생 요약v1은 비교부적격 표시와 함께 계산값도 포함했으므로 원본을 보존하고 v2로 대체했으며 실제 검사를 다시 실행하지 않았다. 호스트18샘플은 CPU평균27.3%/최고관측70.2%, 최소 여유메모리약4.29GiB·디스크 queue 최고관측5를 기록했다. 샘플 사이 peak나 프로세스별 자원 귀속을 단정하지 않는다. [현재 환경](../../.local-tools/perf-environment-32784a7d-64e9-41fa-bea8-cd9c485f718f.json)은 Windows11/16논리CPU·약31.22GiB, Docker28.0.1 WSL2/16CPU·약15.23GiB다. 기준 당시의 같은 세부 자원 계측은 없다.

추가로 선택2에서 build 복사2.88초/묶음을 측정했다. 로컬 Failsafe3.5.6 descriptor로 지원되는 classesDirectory/testClassesDirectory를 확인해 공통 compiled 산출물은 읽기 전용으로 공유하고 private Maven/tmp/report 경로는 유지한다. 클래스/테스트/JAR의 정확한 manifest를 생산·worker 진입·종료에서 대조하고 변조·누락·추가 파일은 소비 전에 거부한다. CodeSource.parent에 쓰던 기존 bootstrap 임시 출력은 A3_EVIDENCE/java-tmp로 이동하고 실제 경로 assertion을 유지한다. 이 새 실행 경로 변경 때문에 선택3은 `database-runtime,job-contracts,identity`를 새 DB로 검증한다. identity가 기존 bootstrap 경로의 실제 소비자다. 새로운 JUnit engine·hardlink·공유 테스트 결과 재사용은 도입하지 않는다.

공통 compiled 소스는 [동결 인계](../../.local-tools/perf-compiled-handoff-68de4a495de043898af9a5acbc474a35.json)에서4guard·구문/POM/115필수ID(기존111포함)를 확인했다. 실제 모든 worker admission에서 전체 source/artifact inventory 해시를 비교하고, 실패 종료에서도 최종 검사를 수행한다. 역사 보조 클래스 전달의 누락은 [별도 인계](../../.local-tools/perf-history-helper-fix-handoff.json)에서 기존 H129 JAR을 보존한 채 현재 helper allowlist/복사 바이트 assertion만 보완했다. 새 copied-classpath의 계획 RED→GREEN은 실제 fixture→helper22키 연결 검사이며 과거 API 업무 검증을 대체하지 않는다. 선택3 [실행 명령](../../.local-tools/perf-launch-94eb84f8da9844e1840cd8cce0c00f2c/command.json)은 이 변경 때문에 실행하며 전체 성능 비교에서는 제외한다.

선택1은 [명령](../../.local-tools/perf-launch-425efbbdd34840fdbe648e55451d601f/command.json)의 새 경로에서 실행했고 [preflight 결과](../../.local-tools/perf-selected1-425efbbdd34840fdbe648e55451d601f-preflight/result.json)에서 DB 이전 린트 실패로 종료됐다. `frontend/tests/proxyViteConfig.mjs`의 새 `process` 참조에 명시 import가 없어 `no-undef`2진단이 발생했다. 기존111개 guard·구문·타입 통과는 보존하되 실제 두 DB 검사·전체 검증은 미실행이다. lint 규칙을 끄지 않고 명시 Node import를 보완하고 해당 린트를 재검증한 뒤 새 선택2를 실행한다. 전체 측정2회에는 이 준비 실패를 성공 실행으로 넣지 않는다.

현재 개발 자동 검사 실패는7실행이다: 산출물 copy EEXIST1, 오류 메시지 대소문자 기대값1, 직렬 실행 형태를 찾던 기존 AST 검사1, 선택1 lint1, 선택2 Fetch bad port1, 선택3 클래스패스 후처리1, 새Job소유권 회귀의 과도한 기대값1. 여러 assertion/진단/그룹을 각각 실행 횟수로 중복 집계하지 않는다. 마지막 [실패 원로그](../../.local-tools/perf-workerjob-lifecycle-tests-4e01b2d0e3524bc3a8d62957b0ac93da/tests.log)는5개 중4개통과·1개실패다. 안전하게 attach가 거부된 뒤 독립 wrapper조차 시작하면 안 된다는 부가 기대값이 실제 계약보다 넓었다. 실제 native workload 미허가와 충돌 대상/무관 프로세스 보존 assertion은 유지하고 해당 사례만 재검증한다. [산출물 실행 원장](../../.local-tools/perf-reuse-analysis-04883b8428204eda92a93bce53fd6797/execution-ledger.json)의 앞2회는 생성 책임 수정·의미를 유지한 메시지 검사 수정 후5guard·실제 소비/프리뷰45검사를 통과했다. AST 검사는 새 dispatch/priority 연결과 실제 DAG 행동 검사를 유지하는 형태로 수정했다. 미구현 helper RED와 의도한 거부 검사는 별도 계획 검사다. 첫 EEXIST의 원콘솔·당시 helper hash가 별도 파일로 수집되지 않았으며 AST 실패의 임시 텍스트 로그가 후속 성공 출력으로 덮인 보존 한계가 있다. 원 도구 대화와 이후 증거를 구분하고 역사 해시/실패 XML을 만들어내지 않는다. 이후 새 GUID 경로를 사용한다.

선택3 [원결과](../../.local-tools/perf-selected3-94eb84f8da9844e1840cd8cce0c00f2c/result.json)는 실패·보존 통과다. [원인 확인](../../.local-tools/perf-classpath-failure-analysis-d26e7220c5d94602847e69bdda46c667.json): 실제Java13/8/37cases는 실패/skip0이지만 XML클래스패스222항목 중 마지막221번 빈 항목을 새후처리에서 `GetFullPath`에 전달해 worker3개가 실패했다. 끝 구분자의 빈 항목은 Java의 cwd 의미로 기록하고, 명시된 경로만 정규화하여 필수 공통2경로와 private복사 부재를 계속 검사한다. 원XML재처리·작은회귀로 수정 영향을 확인하며 이것을 root통과로 소급하지 않는다. [계측](../../.local-tools/perf-selected3-measurement.json)은 선택·실패이므로 성능 판정에서 제외하고 disk 표본1회의5초 조회 timeout도 그대로 남긴다. [독립 검토 round1](../../.local-tools/perf-independent-review-8f1e0fcd-ba32-47ec-897b-c5fec0d42ef0/round1.md)은 필수 보완1건 PERF-IR1: 종료 명령 실패 시 무한 completion 대기를 막고 비정상 부모 종료 후에도 소유 하위 프로세스 정리/증거를 확보해야 한다. 기존42그룹·586메서드/호출 수·DB 수 보존과 snapshot1추가는 확인했으나 전체·성능·최종 수용은 보류다. 설계 위험 판단과 선택3 개발 실패는 독립 반려 횟수에 중복 합산하지 않는다.

준비 읽기에서 큰 출력 잘림과 inline PowerShell regex 인용 오류를 관측했으며 제품 검사 실행은 없었다. 이후 명시exe의 파일 script와 범위를 제한한 읽기를 사용한다. 이전 작업의 실패 횟수를 이번 작업에 합산하지 않는다.

성능 계측 준비의 환경 실패1회: `.local-tools/perf-metrics-smoke-a42fea41b127479895117bd5c2c64e39/resource-samples.ndjson`에서 Win32 CPU 성능 카운터의5초 CIM timeout을 관측했다(내부 지연 원인은 미확정). timeout을 늘리지 않고 CPU만 `GetSystemTimes`의 실제 누적 counter 차이로 변경했다. 새 [계측 smoke](../../.local-tools/perf-metrics-smoke-1e690207c21143aaaa6141d4d66fc88d/resource-samples.ndjson)는 CPU15.4%·메모리31.22GiB/여유7.48GiB·디스크queue0을0.455초에 관측했고 smoke assertion을 통과했다. 이는 순간 호스트 관측이며 작업 전반 부하나 제품 테스트 통과를 뜻하지 않는다. 전체 실행은15초 간격(조회 시간 별도)의 읽기 전용 CPU/메모리/디스크 원장을 새 launch 경로에 남기고 현재 공유 호스트의 다른 작업 부하까지 포함함을 명시한다.

기존 서버·DB·미디어·실패/수용 증거를 보존한다. 새 소유 작업은 실패 시 종료를 확인하되 실패한 새 자원과 원증거는 삭제하지 않는다. 실제 Toss·실송금·실기기·공개 배포 및 역사 원인 미확정 실패는 이번 성능 검증으로 해결되지 않는다. 결과는 미커밋 인계하고 다음 제품 단계에 착수하지 않는다.
