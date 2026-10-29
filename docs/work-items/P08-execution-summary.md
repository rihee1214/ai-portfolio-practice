# P08 순차 실행 최종 기록

2026-10-28. 사용자 요청의 A→B→C→D→E→F→F1→F2→G를 순차 진행했다. **A~D/F/F1/F2 및 G01~04 로컬·모의 범위는 개발·독립 전체 검증을 통과했다. E의 실제 PG와 G05는 외부 차단이므로 P08 전체는 미완료다.** P09 구현·커밋·배포·푸시는 하지 않았다.

## 단계별 판정

| 단계 | 개발·독립 결과 | 근거 |
|---|---|---|
| A 주문 저장·스냅샷 | 완료: Java360/브라우저68/프런트162/Node72 | [독립 검토](P08-A-review.md) |
| B 지속 Job·Outbox | 완료: Java373/68/162/73 | [독립 검토](P08-B-review.md) |
| C 실제 주문·전체 예약 | 완료: Java388/74/170/73 | [독립 검토](P08-C-review.md) |
| D 모의 결제·보상·0원 | 완료: 당시 Java407/80/175/73. 최종 교차 지적은 아래 새 전체에서 추가 검증 | [독립 검토](P08-D-review.md), [최종 보완](P08-final-fix.md) |
| E Toss 로컬 계약 | 로컬 Java421/84/179/73 통과. 실제 SANDBOX 미실행·차단 | [독립 검토](P08-E-review.md) |
| F 현재 권한의 실제 주문·결제 이력 | 완료: Java429/90/187/73 | [독립 검토](P08-F-review.md) |
| F1 판매자별 기본 모의 배송 | 완료: Java441/90/187/73 | [독립 검토](P08-F1-review.md) |
| F2 배송 화면·새 브라우저 조회 | 완료: Java443/96/206/75 | [독립 검토](P08-F2-review.md) |
| G 통합·최종 대조 | G01~04 로컬 통과: Java451/106/216/75. G05 차단 | [최종 독립 검토](P08-G-review.md) |

숫자는 각 단계 당시 실행 결과이며, 후속 결과로 과거 증거를 소급 변경하지 않는다. 제약별 assertion과 실패·재현·수정 연결은 각 작업/검토 문서 및 [누적 기록](P08-progress.md)을 따른다.

## 최종 실행과 소스

- 개발: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-g-dev2-20261028`, 22:42:46~22:52:28 KST.
- 독립: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-g-review1-20261028`, 22:55:23~23:04:52 KST.
- 두 실행 모두 선택 옵션/MAVEN_ARGS 없이 종료0, status/preservation passed. Java451(단위183+PostgreSQL267+전화 제공자1), 브라우저106, 프런트216, Node75 및 필수50묶음·typecheck/lint/build 통과. 필수 누락·실패·오류·skip·flaky0.
- HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53`, `codex/p05-d`의 미커밋 작업 트리. 입력472개 SHA256 `A20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF`. 개발/독립/현재 동일, 전후 불일치0(최종 독립 감사23:06:41).
- 개발 새 DB `d2_it_4c83a711300b497fa2a8968fa675f844`, 미디어 `.local-tools/p08-g-dev2-20261028/db/media`.
- 독립 새 DB `d2_it_1f0485ba3a1c408dbb40e0e278617477`, 미디어 `.local-tools/p08-g-review1-20261028/db/media`.
- 실행 원본과 `evidence/P08-G-dev-final-*`, `evidence/P08-G-review-final-*` 사본 보존. 기존 서버·DB·검증 DB·미디어·캐시를 삭제하거나 재사용하지 않았다. 보존 판정은 자원 존재 및 파일 해시 근거이며, 과거 DB 모든 행의 전체 덤프 동일성을 주장하지 않는다.

## 최종 교차 검토 보완

독립 교차 검토의 R-FINAL-01(결제 화면의 인증 변경 후 개인 상태 잔류)과 R-FINAL-02(예약/주문 잠금 대기 뒤 자격 자연 만료 재검사 누락)를 실제 RED로 재현했다. 하나의 수정 묶음과 단일 독립 재검토 후 새 개발·독립 전체를 확보했다. [원본 지적·재검토](P08-final-review.md), [수정·실패·assertion](P08-final-fix.md)을 따른다. 과거 D 검사가 새 경계를 이미 증명했다고 소급하지 않는다.

## 차단·미검증·보존

- **E/G05 실제 PG:** 테스트 키·MID·API 버전·공개 webhook 수신 및 일반 카드의 보안키 제공/전송시각 계약과 실제 SDK·승인·조회·전달 증거가 없다. 로컬 fake SDK/HTTP stub·서명 모델은 실제 PG 성공이 아니다. [별도 NOT RUN 인수 시나리오](P08-G-sandbox-NOT-RUN.md).
- **원인 미확정 이력:** F2 첫 전체의 전화 probe `fetch failed`, 최종 수정 첫 집중의 쿠폰 재조회 네트워크 실패. 후속 집중·개발·독립 실행은 통과했으나 근본 원인 해결을 주장하지 않는다.
- 실패32 manifest와 중복 제거65,820파일의 존재·해시·충돌 및 manifest 자체 SHA를 전후 대조해 불일치0. 컴파일/fixture/선택 오류와 실제 요구사항 RED, 선택 실행의 과거 필수 coverage 누락을 구분해 원본 보존했다. 필수 검사 삭제·목록 축소·시간 제한 확대는 하지 않았다.
- G의 추가 전화/조회 인증은 테스트 전용 실제 loopback 송신 주소를 분리했다. 제품 quota·CSRF·세션·전달 헤더 정책은 변경하지 않았고 기존 검사도 유지했다.
- P09는 인계 문서만 작성했으며 기능 구현·배포·푸시는 진행하지 않았다. 실제 택배사·분할 배송·클레임·정산은 이번 통과 범위가 아니다.

비필수 마감 서식 점검: git diff --check는 PostgresJdbcIT.java, App.tsx, verify.ps1의 EOF 빈줄3건으로 종료1이었다. 검증된 소스는 변경하지 않았으며 이 서식 항목을 남긴다. 위 필수 하네스 통과와 구분한다.

## 로컬 커밋 준비 — 2026-10-29

사용자가 P08 변경778개만 로컬 커밋하도록 승인했다. 앞 절의 미커밋·EOF 서식 실패는 당시 이력으로 보존한다. PostgresJdbcIT.java, App.tsx, verify.ps1의 끝 빈 줄만 제거하고 마지막 개행은 유지했다. 문서 끝을 제외한 내용 불변 assertion과 `git diff --check` 통과. 기능·필수 목록·시간 제한은 변경하지 않는다.

| 제약 | 검사·기대 결과 | 증거 |
| --- | --- | --- |
| EOF 정리만 수행 | 변경3파일의 마지막 CR/LF 제외 본문 동일, diff --check 종료0 | `.local-tools/p08-local-commit-20261029/files-before.json` 및 후속 감사 |
| 전체 로컬 회귀 | 새 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-commit-verify-20261029-01`, 모든 필수 검사 및 보존 통과 | 실행 대기 |
| 실패 기록 보존 | 기존32manifest·65,820파일과 최신Firebase실패 기록 SHA256 전후 동일 | `.local-tools/p08-local-commit-20261029/` 감사 |
| 승인한 커밋 범위 유지 | 스테이징 경로=사전 확정778개, 검증 소스와 커밋 내용 일치 | `commit-files.json`과 커밋 후 감사 |

실제 Toss SANDBOX E/G05는 NOT RUN/BLOCKED로 유지하며 P08 전체 완료를 선언하지 않는다. P09·배포·푸시는 수행하지 않는다.

### 새 전체 검증 결과 및 커밋 범위

2026-10-29 22:33:45~22:43:43 KST, `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-commit-verify-20261029-01`을 로컬 연결 허용 실행 권한으로 수행했다. 종료0, status/preservation passed. Java451(단위·ArchUnit183+PostgreSQL267+전화제공자1), browser106, frontend216, Node75, 필수50묶음·typecheck/lint/build 통과. 필수 실패·오류·skip·브라우저flaky0. 이번 전체 실행에서 새 실패는 없다.

검증 시작 HEAD `a9d2d1be8c300032751a549c89a84087c9808229` + 미커밋P08. 입력472개 inputs.json SHA256 `BDA5EDAA085169E7E9B80667E80A41AE28218A1EE046FFDCFA037819DD71C9FE`, 실행 후 현재 소스 불일치0. 앞선 A20ECE... 해시는 EOF 정리 전 소스이며 이번 결과로 소급 교체하지 않는다.

새 DB `d2_it_bd37df9b866d48409f6a142ade90acca`, 미디어 `.local-tools/p08-commit-verify-20261029-01/db/media`, 에뮬레이터 `.local-tools/phone-emulator-d753614082364365ba877fe216362a3b`. result.json·inputs.json·coverage.json·java-cases.json·브라우저/프런트coverage·전후스냅샷과 로그를 원본 경로에 보존한다. 기존 서버·DB·미디어는 하네스 보존 검사 통과이며 과거 DB 모든 행의 전수 해시 검증을 주장하지 않는다.

로컬 커밋 대상은 사전에 확정한778개(백엔드83·프런트48·검증6·작업문서24·안내문서3·증거614)다. 최근 모델 라우팅·Firebase기록 커밋은 별도로 유지하며 `.local-tools/` 실행 원본·DB·미디어는 추가하지 않는다. 실제 Toss SANDBOX E/G05는 미실행·외부차단이고 P08 전체는 미완료다. 이 커밋은 로컬·모의 구현과 검증된 범위의 저장이며 실제 결제 연동 완료나 운영 준비 완료를 의미하지 않는다. 푸시·배포·P09 구현은 하지 않는다.

실패 보존 최종 대조: 기존32manifest와65,820파일 및Firebase기존실패4파일, 합계65,824파일 모두 실행 전후 해시·존재 불일치0. .local-tools/p08-local-commit-20261029/failure-audit-before.json과 ailure-audit-after.json에 결과를 보존했다.

### 스테이징 후 전체 서식 검사

기존 추적 파일의 요청된 EOF3건 정리 후 `git diff --check`는 통과했다. 신규734개까지 포함하는 `git diff --cached --check`에서는 기존 신규파일의 서식86건으로 종료2였다. 증거파일77건은 원본 보존 대상이며, 나머지9건은 OrderErrors.java, PaymentApprovalProbe.java, WorkerProcessProbe.java, P08-A.md, orders-create.spec.ts, payments-mock.spec.ts, purchase-helpers.ts, OrderCreationPanel.tsx, PaymentStore.test.tsx의 EOF 빈 줄이다. 이들은 이번 요청의 EOF3건과 별개이며 파일 사전해시 대조에서 이번 수정이 없음을 확인했다. 이 비필수 서식 검사는 실패로 기록하고 전체 통과라고 표시하지 않는다. 필수 Offline 하네스·보존 검사 통과와 구분하며 승인된778개를 그대로 커밋한다. 원문 지적은 `.local-tools/p08-local-commit-20261029/staged-diff-check.txt`에 보존한다.
