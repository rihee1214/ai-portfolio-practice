# P08 순차 실행 기록

2026-10-27 사용자 요청: A → B → C → D → E → F → F1 → F2 → G 순차 구현·단계별 전체 검증·독립 검토. 기존 자원과 실패 이력을 보존하고 새 격리 DB/미디어만 사용한다. P09·커밋·배포·푸시는 수행하지 않는다.

## 착수 상태

- 기준 HEAD: `460aa8bb0bb22f5138849eb00c050abd18f6fb53`. 현재 `codex/p05-d` 작업 디렉터리의 기존 미커밋 README/구현계획/P08 계획 문서를 보존한다.
- P07 결과는 선행 인계이며 P08 검증 결과로 사용하지 않는다.
- A01~A04 제약/assertion 연결은 P08-A의 검사표를 기준으로 한다. 신규 검사 구현·실행 전에는 미검증이다.
- 각 단계 개발 전체 실행 후 구현자와 분리된 검토자에게 제약별 assertion 검토와 새 경로 전체 실행을 맡긴다. 두 전체 하네스는 동시에 실행하지 않는다.
- A 미완료 상태에서 B 이후 구현을 시작하지 않는다. E 외부 제공자 접근 예외는 A~D 실패에 적용하지 않는다.

## 사전 검증

1. `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-preflight-20261027-01`: 종료1. 전화 에뮬레이터 hub 포트 활성화 실패로 루트 내부 검사 진입 전 중단. `.local-tools/phone-emulator-e0c1a52692b146b3bdfbcd49e932cdf5`의 config/result/emulator 로그 보존. 새 DB 생성·JUnit·브라우저·프런트·보존 비교는 이 실행에서 미실행. 에뮬레이터가 출력한 60000ms 문구만으로 실제 경과 시간이나 원인을 단정하지 않는다.
2. 로컬 통신 접근 제한 여부를 확인하기 위해 권한을 확장한 동일 루트 명령을 새 경로 `.local-tools/p08-preflight-20261027-02`에서 실행했다. 15:29:23~15:33:55 KST, 종료0, status/preservation passed, Java348/browser68/frontend162/Node72. 필수 누락·실패·skip0, 소스 동결. 새 DB `d2_it_051870d9bae3437caa41d51bea5e312b`, 새 미디어 해당 경로의 `db/media`, 에뮬레이터 `.local-tools/phone-emulator-f0e6ae8a56af4645ba553bb30812b28c`. 첫 실패는 제한된 실행 환경에서만 관측됐으며 성공으로 과거 결과를 바꾸지 않는다.
3. `evidence/P08-preflight-prior-failure-preservation.json`: P06/P07 실패14158파일 모두 존재·SHA 일치. 원본 DB·미디어·로그 삭제 없음.

## A 집중 검사·독립 사전 검토

- `.local-tools/p08-a-unit-red-20261027.txt`: 신규 OrderRulesTest3개 assertion 실패(예상3400/실제0, 잘못된 단위 구조·금지 전이 미거부), 컴파일 성공. RED용 최소 선언체 뒤 구현했다.
- `.local-tools/p08-a-unit-green-20261027.txt`: OrderRulesTest3 + 실제 production ArchUnit1 + 불법 fixture 규칙17 = 21개 통과, 실패/오류/skip0. 이는 집중 검사이며 A 전체 완료 검증이 아니다.
- 독립 검토자 `p08_review`의 읽기 전용 계약 사전 검토: R-A01 인증/주문 시도 연결 수명, R-A02 단위 구조·정합성, R-A03 모드 저장 제약의 assertion 공백. 구현 검토·독립 실행은 아직 미실행. 구체화·검사 보완 후 다시 검토한다.
- DB 집중 첫 실행 `.local-tools/p08-a-db-red-20261027`: 새 DB는 생성됐지만 DB 스크립트 직접 호출로 `GCLOUD_PROJECT`가 없어 context 시작 오류1. 요구사항 RED가 아니며 원본 보존.
- 정상 루트에서 선택 실행 `.local-tools/p08-a-db-red2-20261027`: 새 DB `d2_it_05bf88c767564babadfd1b7b6134d918`, 단위166 통과/DB1 assertion 실패/오류0/skip0. purchase_order 미존재 assertion으로 RED 확인. status failed/preservation passed. 집중 선택 실행은 전체 통과 근거로 쓰지 않는다.
- V31 주문 migration과 저장 포트 선언체를 추가하고 `.local-tools/p08-a-store-red-20261027`에서 저장/재조회/멱등 관련 신규 DB assertion을 실행했다. DB3 중 저장/재조회2 assertion 실패, 소유자/키1 통과, 오류/skip0, 보존 passed. 저장 구현 전 RED 증거다.
- `.local-tools/p08-a-store-green1-20261027`: DB4 중2 통과/1실패/1오류. P07 역사 이행 검사에서 신규 V31까지 실행되어 예상2/실제3; 검증의 대상 V28→V30을 명시적으로 고정했다. 기존 migration과 필수 검사는 유지하고 새 V31 실행은 A 검사에 맡긴다. 다른 오류는 테스트가 issued_coupon에 존재하지 않는 EXPIRED 상태를 사용한 것으로, 원천 제목 변경으로 수정했다. 제품 상태 정책은 변경하지 않았다. 보존 passed.
- `.local-tools/p08-a-store-green2-20261027`: 단위166·선택DB7 모두 실패/오류/skip0. 선택하지 않은 필수 검사가 없어 루트 coverage는 **failed**이며 보존 passed. 집중 green과 전체 성공을 구분한다.
- `.local-tools/p08-a-dev1-20261027`: 선택 옵션 없는 개발 전체에서 단위166, DB/전화192 중1실패, 브라우저68 통과, 보존 passed. `reservationValidationAndDatabaseConstraints`의 이행 개수 예상13/실제14가 원인. 신규 주문 검사 자체는 통과. 프런트는 이 실행에서 미실행이며 전체 실패다.
- 이행 검사 보완 결정: 앞 집중 단계의 V30 고정은 채택하지 않고 제거했다. 기존 V14/V16/V17/V28 업그레이드 경로 모두 최신 V31까지 유지한다. V28→V31 기대 개수3, V17→V31 기대14로 갱신하고 기존 데이터 보존·재실행0 assertion을 그대로 유지한다. 초기 문자열 교체에 함께 포함됐던 다른 upgrade 변수의 V30 고정도 모두 제거했다. 필수 검사 삭제/범위 축소 없음.
- `.local-tools/p08-a-dev2-20261027`: 신규 필수11개(단위3/DB8), R-A05 비0 상품/쿠폰/배송할인과 parent/seller/item/unit 저장열 대조, R-A08 실제 별도 거래 barrier의 동일키 수렴/상충hash 한승자 보강 후 전체 실행 중. 하네스 범위 설명도 P08-A로 갱신했다. 최종 통과·독립 전체 전에는 A 완료가 아니다.

### 개발2 회귀 실패와 원인 조사

개발2는 status failed/preservation passed다. 신규 주문8개·단위3개는 통과했으나 기존 `couponReservationCrossSellerAndConcurrentRetries`의 동일키 동시 예약에서 오류1이 발생했다. Java166 단위 및 DB193 중1오류, 브라우저68 통과. 프런트는 미실행이다.

PostgreSQL 로그 2026-10-27 06:55:31.433 UTC의 실제 실패 SQL은 coupon_reservation_batch의 `INSERT ... ON CONFLICT(checkout_id) DO NOTHING`이며 충돌 제약은 복합 UNIQUE `coupon_batch_identity(checkout_id,member_id,request_hash)`다. 기본키만 지정한 충돌 처리 밖에서 다른 고유 인덱스 충돌이 발생해 JdbcCouponReservations가 409로 바꿨다. 재시도를 생략하거나 시간 제한을 늘리지 않는다.

최소 보완은 모든 고유 충돌을 대상으로 `ON CONFLICT DO NOTHING` 후 기존 checkout 행 잠금·member/hash 검사를 그대로 수행하는 것이다. 동일내용은 같은 예약을 반환하고 다른내용은409로 거부한다. 신규 `couponBatchConcurrentIdenticalInsertsRespectAllUniqueKeys`는8회 독립 동시 insert마다 동일 결과·batch1/감사1행을 검증한다. `.local-tools/p08-a-coupon-fix-green-20261027`에서 기존 동일키/상충배치 검사와 집중 실행 중이다. 이 선행 회귀 결함은 이번 A 최종 전체·독립 검토에 포함하며 과거 P07 성공 기록을 소급 변경하지 않는다.

### 쿠폰 집중 green·게스트 주문 경합 RED와 보완

- 쿠폰 집중 실행은 단위166/선택DB3 실패·오류·skip0. 루트는 선택하지 않은 필수 검사 누락으로 failed, 보존 passed다. 전체 성공으로 표시하지 않는다.
- 같은 다중 unique 경합이 주문 checkout의 session/key + guest_attempt_id에도 가능한지 신규 동시검사로 확인했다. `.local-tools/p08-a-guest-conflict-red-20261027`에서 실제 `order_checkout_attempt_guest_attempt_id_key` DuplicateKeyException을 재현했다. DB1 오류/보존 passed. 추정만으로 수정한 것이 아니라 새 DB 실행 증거를 확보했다.
- 주문도 전체 고유충돌 처리 후 session/key 행을 잠그고 hash/guestAttempt/owner를 검사한다. 다른키에서 이미 사용한 guestAttempt는 해당 session/key 행이 없으므로 명시적 `OrderRejected(409)`다. 동일키 동시저장은 같은 주문을 반환한다. 기존 row 중복을 무시하고 다른 자격을 성공시키지 않는다.
- `.local-tools/p08-a-dev3-20261027`에서 선택 옵션 없는 전체 재검증 중. 제품·검사·하네스 소스 동결. 독립 최종 실행은 개발 전체 결과 확인 후 수행한다.

## A 개발 최종 — 독립 검토 전

`./verify.ps1 -Offline -EvidenceDirectory .local-tools/p08-a-dev3-20261027`는 2026-10-27 16:01:15~16:05:39 KST에 종료0/status·preservation passed다. Java360(JUnit/ArchUnit/PostgreSQL/전화 제공자), 브라우저68, 프런트 단위162, Node72와 타입·린트·빌드·하네스 자체검사를 통과했다. 필수 누락·실패·skip0. 새 DB `d2_it_556cb5dc380549fab05725a1245e0624`, 새 미디어 `.local-tools/p08-a-dev3-20261027/db/media`, 에뮬레이터 `.local-tools/phone-emulator-7c67793fba4246b0acf8a9343345b977`.

기준 HEAD 위 미커밋 입력382개, inputs SHA256 `0E8E5E484453BA164126BCAD19F89407D86FEC23C4C523E69DB7A95195A51582`. 실행 전후 및 실행 후 현재 파일 해시 불일치0. `evidence/P08-A-dev-final-*`에 결과·입력·필수 coverage·DB 결과·before/after·소스 대조를 보존했다. `evidence/P08-A-failure-manifest.json`은 별도 에뮬레이터 로그를 포함한 실패4721파일의 해시다. 원본·실패 DB·미디어는 그대로 둔다.

독립 검토자는 `.local-tools/p08-a-review1-20261027`의 새 루트 전체 검증과 제약/assertion·소스/실패 보존 대조를 진행한다. 독립 결과 전에는 A 완료/B 착수를 선언하지 않는다. P08 전체는 미완료다.

## 단계별 현황

| 단계 | 상태 | 다음 진행 조건 |
| --- | --- | --- |
| A | 완료: 개발·독립 전체 및 제약 검토 통과 | P08-A-review 최종 증거 |
| B | 완료: 개발2·독립2 전체 및 제약 검토 통과 | P08-B-review 최종 증거 |
| C | 완료: 개발2·독립1 전체 및 제약 검토 통과 | P08-C-review 최종 증거 |
| D | 완료: 개발2·독립1 전체 및 제약 검토 통과 | P08-D-review 최종 증거 |
| E | 로컬 개발·독립 통과 / 실제 SANDBOX 외부차단 | DEMO 후속 허용, E전체 미완료 |
| F | DEMO 개발·독립 완료 | Java429/browser90/frontend187/Node73·보존 통과 |
| F1 | 개발·독립 완료 | Java441/browser90/frontend187/Node73·보존 통과 |
| F2 | 개발·독립 완료 | Java443/browser96/frontend206/Node75·보존 통과 |
| G | G01~04 로컬 개발·독립 통과 | G05 실제PG 차단·P08전체 미완료 |

## A 독립 완료와 B 인계

독립 실행 `.local-tools/p08-a-review1-20261027`은 16:06:56~16:11:20 KST 종료0, status/preservation passed, Java360/browser68/frontend162/Node72, 필수 누락·실패·skip0이다. 개발3과 입력382개 SHA가 동일하고 실행 후 소스 변경0, 과거 실패14158개와 A 실패4721개 누락/해시 불일치0이다. [독립 검토](P08-A-review.md)의 R-A01~08은 해소됐으며 A 저장 모델 범위를 완료한다. 기존 실패 기록은 위 시점별 결과 그대로 보존한다.

최신 migration은 V31이다. C는 trusted Orders 저장/조회 포트를 HTTP에 그대로 노출하지 않고 인증·현재 견적·guest 소비·전체 예약을 조정해야 한다. checkout/guest_attempt/identity/store의 FK와 행 잠금도 통합 잠금 표에 반영한다. D는 실제 상태 전이와 결제 조정을 맡는다.

### 남은 단계 실행 담당 분리

남은 각 단계를 하나의 응집된 작업으로 위임하고 개발 전체 종료 뒤 별도 검토자가 독립 전체를 실행한다. subagent-driven-development 실행 방식을 적용하되 사용자 순차 지시가 우선하므로 다음 단계 구현과 병렬 진행하지 않는다. 현재 feature 작업 디렉터리와 미커밋 증거를 유지하며 스킬 일반 절차의 커밋·증거 삭제는 하지 않는다. 기록과 코드의 소유자를 분리한다.

| 작업/공유 계약 | 확인 결과·실행 판정 |
| --- | --- |
| B 자체 | B01~04가 저장·lease·실제 프로세스 복구·재시도 계약과 일치. 일반 worker만 구현 |
| C 자체 | 주문 생성과 모든 예약/인증 소비의 한 거래, 실패 주입·동시성 검사가 일치 |
| D 자체 | 외부 호출과 DB 거래 분리, 불명확 결과 재조회/예약 유지 검사가 일치 |
| E 자체 | 로컬 stub 성공만으로 실제 테스트 PG 완료 판정 금지. 외부 차단은 명시 |
| F 자체 | 실제 주문 읽기와 자격/필드별 제한, 새 기기 검사가 일치 |
| F1 자체 | 하위 주문당 기본 배송과 판매자/모의 제공자 권한 분리 검사가 일치 |
| F2 자체 | F1 실제 상태 기반 화면·키보드·오래된 응답 검사, fixture 대체 금지 |
| G 자체 | 선행 증거와 실제 구매 전체 assertion 모두 필요. E 차단이면 모의 부분만 판정 |
| A→C/D/F/F1 | A 저장 모델에 HTTP 권한 책임 없음. 소비 단계에서 해당 인증·전이 검사 추가 |
| B→C/D/F1 | Outbox 거래 참여, 작업 원인키·lease token 계약을 공개 API로 넘긴다 |
| C→D/E/F | 실제 주문·예약·deadline은 서버 저장값 사용, UI값 신뢰 금지 |
| D→E/F/F1 | 결제 조정 API 재사용. terminal 재시도를 주문 실패로 자동 번역 금지 |
| E→F/F1/F2/G | 외부 접근 차단 예외만 모의 후속 허용, E/전체 완료 금지 |
| F→F1/F2/G | 소유권 제한 조회에 배송을 결합, 개인정보 노출 범위 유지 |
| F1→F2/G | 같은 배송 API·상태/이벤트 이용, 배송 완료를 클라이언트가 지정하지 않음 |
| 모든 단계 공통 검사/마이그레이션 파일 | 순차 단독 수정. 새 migration 기대개수 갱신, 과거 업그레이드 최신 대상 유지. 필수 목록 축소 금지 |

위 계약 충돌 없음. 각 단계의 구체 API는 착수 기록에 확정하고 독립 검토 지적은 다음 단계 전에 해결한다.

### B 사전 검토 판정 R-B01

기존 ModuleRules의 공개 API 검사에서 targetPlatform을 일괄 거부해 platform.api가 자기 공개 DTO를 참조하는 것도 금지한다는 독립 사전 지적을 받았다. B 계획은 Jobs/Outbox/JobHandler 공개 계약을 명시하므로 공개 platform API 참조만 최소 허용하는 방향으로 판정한다. platform→업무와 API→platform 내부 금지는 유지하고 각각의 허용/거부 fixture를 필수 검사에 추가한다. 아직 수정·실행 통과를 뜻하지 않으며 구현자에게 전달했다.

B brief는 단계 문서 전체가 단일 작업이므로 표준 Task N 추출 대신 `.local-tools/p08-execution/task-b-brief.md`에 원문을 보존했다. 구현 보고서는 같은 경로의 task-b-report.md, 공식 결과는 P08-B.md에 누적한다. Git 커밋 범위 대신 하네스 inputs와 미커밋 파일 해시로 검토 소스를 식별한다.

R-B01 범위 정정: 기존 `publicApiCannotDependOnPlatformEvenIfItsPackageIsNamedApi`는 업무 모듈 api→platform.api 금지를 의도적으로 검사한다. 이를 유지한다. 허용 추가는 platform.api→같은 platform.api 공개 타입으로 한정하고 업무 infrastructure/handler→platform.api의 기존 허용을 확인한다. 최초 전달의 업무 api 허용 제안은 채택하지 않는다. 독립 검토의 후속 근거에 따라 구현 전에 범위를 좁혔다.

B 독립 사전 검토 R-B02~06은 [P08-B-review](P08-B-review.md)에 연결했다. 이벤트/job 동시 저장, 모든 실행권 변경의 만료 판정, claim 횟수의 내구 저장, 효과 commit 후 ack 전 실제 프로세스 중단, 웹/worker 실행 분리를 요구한다. 구현자가 B 착수 계약과 assertion 표에 반영하며 아직 전체/독립 통과는 아니다.

### B 개발 전체와 독립 실행 시작

`.local-tools/p08-b-dev1-20261027`는 16:38:16~16:43:02 KST 종료0, status/preservation passed, Java373(단위170+DB202+전화1)/browser68/frontend162/Node72, 필수 누락·실패·skip0이다. 입력394개 SHA256 `8014C15ED73DADB795FFBAF28971EA1F1F1FB29B2D0C8E16CC24BD6F231D18E4`, 새 DB `d2_it_66e4fee0aa26433b8537462a91fc7677`, 미디어는 해당 증거의 db/media다. 정적 독립 검토 필수 잔여 지적 없음. 동일 소스를 동결하고 독립 `.local-tools/p08-b-review1-20261027`을 실행한다. 독립 전 과거14158+A4721+B2894 실패 증거의 누락/해시 불일치0 확인. B 완료/C 착수는 독립 최종 결과 이후다.

### B 독립1 실패·보완 판정

`.local-tools/p08-b-review1-20261027`은 status failed/preservation passed다. Java373·브라우저68 통과 후 Node71통과/1실패에서 중단됐고 프런트 단위·후속 검사는 미실행이다. `proxy.test.mjs`는 시작83ms에 공유 `frontend/node_modules/.vite/deps` rmdir EPERM을 보고했다. 제품 응답 assertion 실패와 구분한다. 정확한 잠금 소유 프로세스는 미확정이다.

독립 검토에서 `node --test tests/*.test.mjs`의 proxy/proxy-stream이 동일 frontend root와 기본 cacheDir을 사용함을 확인했다. R-B07: 각 새 테스트 Vite 인스턴스에 고유 캐시 경로를 주고 경로 분리를 assertion하도록 원 구현자에게 보완을 맡겼다. 기존 공유 캐시 삭제·서버 종료·검사 병렬성 축소·타임아웃 확대는 하지 않는다. review1 실패/에뮬레이터 manifest는 기존 B2894와 별도로 보존한다. 수정 뒤 새 개발 전체→독립 전체가 필요하며 B는 미완료/C 미착수를 유지한다. fix round1 시작.

B 독립1 실행 범위 정정: 실제 루트 순서는 typecheck→lint→test:unit→build→test다. 독립 검토자가 로그/XML을 대조해 타입·lint·프런트단위162·build 성공을 확인했다. 위 '프런트 단위·후속 검사 미실행'은 순서에 대한 잘못된 추정이며 정정한다. 마지막 Node71/1 실패로 frontend-coverage 최종 필수대조가 미실행이다. 전체 failed 판정은 유지한다. 독립1 및 에뮬레이터576파일 별도 manifest를 추가했고 prior21773+576=22349파일 누락/해시 불일치0이다.

B fix round1 집중 결과: 동일 유효 cacheDir을 사용하는 기존 설정으로 신규 assertion RED1을 확인했다. 인스턴스마다 새 `.local-tools/vite-proxy-test-*` 경로를 만들도록 test-only helper를 연결하고 실제 두 서버 동시실행을 포함한14검사 green을 확인했다. 공유 캐시339파일 before/after 변경0. 기존 병렬성·timeout·응답 assertion 유지, 실패manifest1파일 별도 추가. 개발2 `.local-tools/p08-b-dev2-20261027`를 새 경로에서 시작하고 소스 동결했다. R-B07 범위 독립 재검토와 개발2 후 새 독립 전체가 남았다.

B 개발2 전체는 16:55:50~17:00:33 KST 종료0/status·preservation passed, Java373/browser68/frontend162/Node73, 누락·실패·skip0이다. 입력396개 SHA256 `85F3C6E15DBAFAA45F30AE5E6F2F139F0B0DF61F3996786BA3F8918552869774`, 새 DB `d2_it_c763e78f70d2458db84e6b5ae5b0518d`, 해당 db/media를 사용했다. R-B07 정적 재검토 적합/신규 차단 없음. 동일 소스의 독립2 `.local-tools/p08-b-review2-20261027`을 실행한다. 개발1/독립1/개발2 결과를 각각 보존한다.

### B 마감·C 인계

독립2는 종료0/status·preservation passed, Java373/browser68/frontend162/Node73, 필수 누락·실패·skip0이다. 입력396개는 개발2와 동일하고 실행 후 변경0, 실패22350파일 누락/해시 불일치0이다. 공유 캐시338파일은 동일하며 Vitest 결과1파일만 정상 실행기록 갱신됐다. R-B01~07 해소, B 필수 잔여 차단 없음으로 마감한다. 상세 시각/새 DB/미디어·제약별 assertion·증거29개는 P08-B-review 최종 절을 따른다. 독립1 실패는 그대로 보존한다. fix round1은 해소1/잔여0으로 종료한다.

C에 V32와 Outbox.append의 호출자 거래 참여·안정 dedupe/cause/최초시각 계약, handler의 거래밖 호출·업무효과 멱등 책임을 넘긴다. C만 착수하며 D 이후 구현은 C 개발/독립 완료 이후다.

### C 사전 검토 중: 통합 잠금·재견적

독립 검토자가 주문 FK→기존 coupon account FOR UPDATE 순서의 교착 위험, inventory 예약 후 coupon 내부 재견적의 자기재고 차감 충돌, 외부 거래 참여 시 Quotes의 독립 RR 선언만으로 일관성을 주장할 수 없는 점을 제시했다. 모두 C1 통합 잠금 표에 반영할 요구로 구현자에게 전달했다. 공개 API를 통해 주문 INSERT 전 관련 계정/매대 잠금을 준비하고 coupon→inventory 순서를 검토하며, 실제 변경 거래와 대기후 권한/가격 재검사 assertion으로 증명한다. 아직 제품 결함 재현/해결 판정은 아니며 구체 검토는 P08-C-review에 누적한다.

C 잠금 검토 추가: V30이 coupon_batch.member FK를 이미 제거했으므로 계정 FOR UPDATE 완화 제안은 채택하지 않고 기존 강도를 유지한다. 반면 category_event.actor_id 및 account_state_audit.actor_id의 관리자 감사 FK가 역방향 대기를 만들 수 있다는 정적 경로가 발견됐다. 관리자도 구매자/판매자인 경우를 실제 barrier 검사로 확인하고, 필요하면 관련 mutation의 actor/target 사전 정렬 잠금까지 최소 보완한다. 아직 재현 완료를 뜻하지 않는다.

C R-C07 판정 갱신: 기존 강도 유지 방침을 감사 FK 호환 근거로 재검토했다. 독립 검토상 SellerMembership의 FOR NO KEY UPDATE는 같은 account의 상태/역할 변경에 필요한 UPDATE/NKU 배타성을 유지하고 참조 감사 FK KEY SHARE만 허용한다. 따라서 실제 FK호환·category/admin target 경합 RED/green과 기존 대기후 거부 회귀를 전제로 채택한다. 제거된 coupon FK 때문의 완화가 아니다. 역할행 변경은 여전히 parent account 잠금 선행을 유지해야 한다. C 안에서 cart/wishlist/profile/address의 FOR UPDATE로 다시 승격하지 않으며 cart는 commit 후 별도 거래다. 구현자가 이 조건과 근거를 C 계약에 기록한다.

C 집중 진행: 입력 hash 정규화 assertion RED, HTTP 미구현404 RED, UI store3·guestcart1 RED, 장바구니 미지원 assertion RED를 각각 보존했다. Checkstyle/게스트 인증quota fixture 오류는 요구사항 RED와 구분한다. focused-green1에서 단위171 및 DB10 중 통합 rollback28지점·권한/쿠폰만료 실제대기7조건·category/account 감사경합·마지막재고·게스트/HTTP/cart 검사는 통과했다. cross/samebuyer 검사의 blocker가 같은구매자 경우 공통 행이 아니어서 barrier assertion이 실패했다. 공통buyer 행으로 fixture를 수정하며 timeout을 늘리지 않는다. 전체 개발/독립 검증은 아직 미실행이다.

C 정적 구현 검토 R-C08: 견적 A1과 submit 시 최신 cart 전체 snapshot이 다르면 다른탭 B추가/A2변경까지 cleanup될 수 있는 경로를 발견했다. 견적 승인 당시 SKU/수량/revision에 cleanup을 묶고 quote→submit 사이 변경·같은수량 재추가까지 회귀를 추가하도록 구현자에게 전달했다. C 기존 postsubmit 변경검사만으로 이 조건을 충족했다고 보지 않는다.

현재 세션 보완: 필터 검사 후 계정 잠금을 기다리는 동안 logout/password reset이 발생할 수 있어 HTTP의 서버유래 member/authVersion/sessionId를 거래 중 재검증한다. identity→session 순서로 실제 session행을 commit까지 잠가 부재/만료/소유자/version 불일치를401로 거부한다. 새 생성·성공결과 재조회 모두 적용하고 trusted 내부 포트를 HTTP가 우회하지 못하게 검사한다. 독립 검토가 이 계약을 확인했으며 실제 RED/green은 구현자가 진행한다.

C 세션 실제 RED: `.local-tools/p08-c-session-red2-20261027`에서 실제 POST /orders가 account 잠금을 기다리는 동안 로그아웃한 뒤 기대401/실제200 assertion 실패1·오류0을 확인했다. 따라서 필터 시점 권한만으로 충분하지 않은 문제가 실행으로 재현됐다. 공개 identity validator·계정 뒤 session행 잠금·신규/성공retry 재검증 보완을 진행한다. 첫 session-red1의 mailbox 테스트 API 컴파일 오류는 요구사항 RED가 아니다.

C R-C04/R-C08 보완 집중 결과: 실제 logout/expiry/passwordreset 대기후401 및 기존 HTTP 재시도/CSRF가 단위171·선택DB2에서 통과했다. 최종 테스트에는 신규/성공retry ×3철회 조건의6조합과 전체 참여행 불변을 연결했다. 장바구니8검사는 승인전 필터·승인후 snapshot·동수량재추가·빈cleanup no-op를 포함하며 모두 집중 통과했다. 독립 정적 재검토 신규 차단 없음, 실제 독립 실행은 아직 전이다.

C 개발1 `.local-tools/p08-c-dev1-20261027` 선택없는 루트 Offline 전체를 시작했다. 신규14통합+hash/API 단위, frontend8/browser6 필수 등록을 추가하고 기존 목록을 유지했다. V33 최신 이행 기대개수를 갱신하며 과거 target을 고정하지 않았다. 실행 중 모든 제품·검사·하네스 소스를 동결한다.

C 개발1 실패: 단위171 및 통합217 중 기존 잠금대기 probe3 assertion 실패·오류0, 브라우저74 통과, preservation passed다. 기존 inventory/media/SKU 검사 일부가 identity FOR UPDATE 문자열만 검색해 채택한 NO KEY UPDATE의 실제 대기를 인식하지 못했다. 결과 권한 assertion/대기/timeouts는 유지하고 실제 SQL 탐지만 최소 보완한다. 이번 전체에서 새 HTTP 세션6조합은 통과했다. 성공 후 이전 '주문/예약 미실행' 안내가 남는 표시도 성공화면 assertion과 함께 보완한다. 개발1 결과는 보존하고 집중 회귀 후 새 전체를 실행한다.

C 보완 집중: 기존 잠금3+확장세션 선택DB4 및 단위171 통과. 프런트 lint의 render-time ref 접근 지적은 승인 snapshot을 주문 workflow closure로 옮겨 해결했고 lint/typecheck/관련8검사 통과. 독립 검토상 workflow·store 1:1, 승인시 복사/재시도 불변/owner 변경 차단이 유지돼 R-C08 의미 약화 없음. 새 개발2 `.local-tools/p08-c-dev2-20261027` 전체를 소스 동결 상태로 시작했다. 개발1 대비 변경은 정확한 기존 wait probe3·성공안내/브라우저 assertion·lint 보완이다.

C 개발2 전체: `.local-tools/p08-c-dev2-20261027` 18:10:41~18:15:47 KST 종료0/status·preservation passed, Java388/browser74/frontend170/Node73, 필수 누락·실패·skip0. 새 DB `d2_it_8b8ebb4d8b084a659d6885b35f64fcfe`, 해당 db/media 사용. 과거+C실패28847파일 해시 불일치0을 개발자가 대조했다. 같은 동결 소스로 독립 `.local-tools/p08-c-review1-20261027`을 실행한다. C 완료/D 착수 판정은 독립 최종 이후다.

### C 마감·D 인계

독립1 `.local-tools/p08-c-review1-20261027` 18:17:27~18:22:33 KST 종료0/status·preservation passed, Java388/browser74/frontend170/Node73, 필수 누락·실패·skip0. 개발2=독립1=현재 입력412개 SHA256 `11FCDABD57149864B01808E0EC108A961FA41C0D8AA482BD1F92F7EA51A2F633`, 전후 소스 불일치0. 실패28847개와 manifest 자체해시도 불일치0. 새 DB `d2_it_4700d9aeb72e486dbc9552e66e505041`, 독립1/db/media 사용. 독립 증거23사본과 제약별 판정은 P08-C-review 최종 절을 따른다. R-C01~08 해소·필수 잔여 차단 없음, C 범위 완료. 모든 이전 실패는 당시 결과대로 보존한다.

D는 C의 Orders.reservations 전체ID, 주문 만료시각, ORDER_EXPIRY 안정 키·기한, 현재 session/guest 권한과 통합 잠금 계약을 소비한다. UNKNOWN을 단순 실패/해제로 번역하지 않고 승인·만료·후행성공 보상과 재시작을 검증한다. D만 착수하며 E 이후 구현은 아직 시작하지 않는다.

D 사전 검토 초기: 예약 commit의 REQUIRED 참여에서 후속 예약 오류가 외부 거래를 rollback-only로 만들면 같은 catch의 보상 기록도 사라진다. 전체 결과 거래 rollback 뒤 새 거래로 보상 상태/job을 내구 저장하고 참여자 사이 기한경계/주입 실패로 증명하도록 전달했다. 또한 B의 마지막 crash는 D handler 호출 없이 일반 job만 FAILED로 만들므로 D의 UNKNOWN 운영노출/대사 한도를 handler 실행 횟수에만 맡기지 않는다. 소진/실행불가에서도 운영 확인 가능성을 검증하며 예약 해제로 번역하지 않는다.

D 집중/정적 진행: 상태규칙2 assertion 및 HTTP404 RED 확인. 첫 집중 fixture의 쿠폰 열/대소문자/미등록type 오류를 제품 요구사항 RED와 분리했다. focused2에서0원·두매대·10 checked/runtime rollback·쿠폰후재고기한초과·HTTP권한/CSRF/세션wait·운영대사 통과, 남은slug fixture를 보정했다. 전체 개발은 아직 전이다.

R-D07 독립 지적: 0원 PREPARED 중단 뒤 일반 query가 외부조회에 들어가고, 로컬 확정불가 뒤 일반 cancel보상으로 넘어가는 경로를 발견했다. 0원은 승인/조회/취소 모두 외부호출0이어야 한다. 실제 PREPARED kill/restart 및 기한경계 RED→로컬 조정 분기로 검증하며 예약 commit규칙을 우회하지 않는다. 돈이 이동하지 않은0원은 외부보상 없이 안전한 로컬 종결로 처리한다.

D R-D07 판정: 0원 준비~전체예약확정~주문PAID~결제이력/Outbox를 한 거래로 묶는 원자적 로컬 설계를 채택한다. 별도 commit된0원 PROCESSING이 없어 조회복구 분기를 만들 필요는 없으나, precommit 실제kill 전체rollback/재승인1회와 postcommit 응답전kill 재시도1회, 기한충돌 rollback/보상0, provider 승인·조회·취소·취소조회 모두0 직접assertion이 필수다. 독립 검토가 이 조건으로 동의했다.

D 추가 필수: 초기 승인/만료가 같은 주문 잠금을 실제 경쟁하는 양승자 경합 증거, 전체 예약 검증에서 선택coupon ID·member·기한과 주문snapshot 정확대조를 추가한다. 준비 후 PROCESSING 검사나 개수 일치로 대체하지 않는다.

D 추가 집중: focused4 단위173/DB11통과(실제 결과/보상kill재개 포함), focused5 DB14 중13통과/1fixture오류는 기존 복합FK가 다른member 연결을 이미 거부한 것이므로 DB거부/행불변 assertion으로 정정한다. 같은개수 다른coupon/누락/기한 변조 및 실제2orderlockwaiter 양승자 경합 통과. 0원 COUPON/INVENTORY precommitkill rollback과 postcommitkill 재개·provider4작업0 검사도 통과했다.

R-D07 잔여: 독립 scoped검토에서 준비통과 직후 PREPARE→정각만료 때 applyLocked 첫 분기가0원도 일반 compensation으로 보내는 경로를 발견했다. 그 정확한 Clock 경계 RED를 추가하고 전체rollback/로컬종결로 보완한다. 다른 D02/D03/kill assertion 구성은 정적 적합하나 이 조건 전에는 필수차단 해소로 판정하지 않는다.

## 2026-10-28 재개 확인

사용자 요청으로 미완료 작업을 재개했다. 이전 구현 agent는 사용량 제한 오류로 D 마감 전 종료됐으며 A/B/C 완료 기록은 유지한다. D/E/F/F1/F2/G는 완료로 판정하지 않는다. 이전 실행 원본을 먼저 확인했다.

- p08-d-focused6-20261027: 선택DB19 실패/오류/skip0, 브라우저80통과. 루트는 미선택 과거 필수coverage 누락으로 failed, preservation passed다.
- p08-d-zero-deadline-green-20261027: 단위173통과, 선택DB3 중0원2검사 통과·브라우저launcher1실패. 브라우저78통과/2실패는 PC/모바일 비회원 실제 주문→결제 이동 후 승인버튼 미발견이다. 기존5초 제한을 유지하고 원인 조사한다. 루트 failed/preservation passed. R-D07 정적 적합과 집중0원 통과를 전체 완료로 확대하지 않는다.

원 구현자에게 D 나머지 검증·비회원 결제흐름 보완·새 전체 실행을 맡겼다. 새 실행은20261028 고유 경로를 사용하며 기존 DB/미디어·실패원본·서버는 보존한다. D 독립 전체 완료 뒤에만 E→F→F1→F2→G를 진행하고 P09·커밋·배포·푸시는 하지 않는다.

재개 후 비회원 결제 보완: AuthConfiguration의 정확한 GET결제조회/POST승인2경로만 익명 routing을 허용했다. 서비스의 원래guest attempt/browser/session·EXISTING token 소유권 검증과 CSRF는 유지하고 관리자 경로는 열지 않았다. 독립 정적 검토 적합. 20261028 guestgreen1 단위173·선택DB4·실제browser80 통과(소유guest결제성공/무CSRF403/금액mode400/타인401/익명관리자401 포함). 집중 root의 과거coverage 누락은 전체통과와 구분한다. 타입·lint통과 후 sourcefreeze, 새 D개발전체를 준비한다.

과거 실패manifest22개 전체 union 대조는 고유29350파일 누락/해시변경0이다. 이전28847집계보다 넓은 전체manifest union이며 당시 집계를 소급 변경하지 않는다. 상세는 `.local-tools/p08-execution/d-prior-failure-preservation.json`에 남긴다.

D 최종 UI 추가 지적: PaymentStore가 CSRF 조회 네트워크 실패(POST0)에도 approved=true를 유지해 재조회NONE 뒤 활성버튼이 no-op되는 경로를 독립 검토가 발견했다. 자동승인/UNKNOWN 재승인은 유지 금지하고, 확인된 prePOST실패 또는 인증된NONE 뒤 명시적 사용자 재클릭을 허용하는 RED/green을 추가한다. 개발전체1은 이미 시작됐으므로 소스동결을 유지해 종료·보존 후 수정하고 별도개발2 전체를 실행한다. 실행중 소스를 바꿔 결과를 오염시키지 않는다.

D 개발1(20261028) 16:25:29~16:31:31 실패/preservation passed. Java407 집계에서 browserlauncher1만 실패, 실제browser79통과/1실패로 frontend root 미진입. 원인은 D 무료배송 fixture의 같은제목 행사7개에서 기존 제목 전역 locator가 모호해진 것. event-id 링크가 있는article 내부 exactheading으로 지정하고 페이지20/이동/ID중복0/상세·매대 assertion과 중복fixture를 유지한다.

승인재시도 UI는 신규단위2 actualRED(기존3통과)를 확보했다. 실제POST 직전에 latch를 설정하고 검증된GET NONE+PENDING_PAYMENT에서만 수동 재클릭을 허용한다. UNKNOWN/401 차단·자동POST0은 유지한다. 실제browser CSRFabort→POST0→GETNONE→수동click→PAID/POST1 assertion을 추가했다. 독립 scoped검토에서 두수정 적합/새차단없음, 집중green 뒤 새전체2가 필요하다.

D 개발2 전체 `.local-tools/p08-d-dev2-20261028` 16:35:00~16:41:43 KST 종료0/status·preservation passed, Java407(173+234)/browser80/frontend175/Node73 및 필수/type/lint/build 통과. 새 DB `d2_it_7f66fb24e574440cb219ba0c40df9818`, 해당db/media. 입력428개 SHA256 `EBADA9D50642EFAB23E9F0A368B815A92D5E55678D0E702A34EEBCBD7170EE10` 동결. 전체실패union36552파일 존재/해시 일치. 새 독립 `.local-tools/p08-d-review1-20261028`을 실행하고 D완료/E착수는 최종판정 뒤에 한다.

### D 마감·E 인계 (2026-10-28)

독립1 `.local-tools/p08-d-review1-20261028` 16:44:04~16:50:48 KST 종료0/status·preservation passed, Java407/browser80/frontend175/Node73 및 필수/type/lint/build 통과. 개발2=독립1=현재428입력 SHA256 `EBADA9D50642EFAB23E9F0A368B815A92D5E55678D0E702A34EEBCBD7170EE10`, 전후불일치0. manifest24개 union36552파일 및 manifest자체해시 불일치0. 새 DB `d2_it_3ca14632770e4e04942b9d21e5356276`, 독립1/db/media. 상세 제약·결과·프로세스중단 증거는 P08-D-review와 최종24사본을 따른다. D01~05/R-D01~07/승인재시도/guest권한 해소·필수차단없음으로 D완료.

E만 착수한다. 공식 문서로 현재 제공자 계약을 확인하고 테스트 키/접근 준비는 값을 노출하지 않고 확인한다. 로컬 Offline HTTP stub 계약검사와 실제 SANDBOX 증거를 구분한다. 키/네트워크/제공자 접근이 막히면 E 미완료를 명시하고 계획의 예외에 따라 D모의 기반 F→F1→F2→G를 진행할 수 있지만 전체 P08/실제PG 완료로 선언하지 않는다.

### E 공식 계약·외부 준비 확인

공식 Toss문서 확인일2026-10-28. SANDBOX는 서버준비→SDK인증→현재소유권/CSRF 확인과 반환paymentKey/orderId/amount 저장시도 대조가 필요하다. D의 즉시모의승인을 그대로 호출하지 않는다. 웹훅 최신문서는 HMAC(payload:transmission-time),v1base64 다중서명을 기술하나 실제 카드/MID 검증키 제공은 미확인이다. 검증키 없으면 failclosed, 로컬 signedstub 계약과 credential인증 서버재조회만 검증하며 실제제공자 검증으로 주장하지 않는다.

키 준비를 이름/존재여부만 확인했으며 TOSS_CLIENT_KEY/SECRET_KEY/API_VERSION/MID 및 MARKETPLACE alias/VITE_TOSS_CLIENT_KEY 모두없다. 비밀값은 출력하지 않았다. 실제SANDBOX는 테스트키 미준비로 외부차단이며 E완료가 아니다. 로컬 E01~03/화면검증을 계속하고, 로컬전체·독립검토 후 공통계획 예외에 따라 후속DEMO단계 진행 여부를 기록한다. 실제PG 증거는 stub 성공으로 대체하지 않는다.

E 집중 진행: TossPaymentProviderTest4 actualRED→GREEN은 저장order/key/amount/mId/version/currency/method 대조·통신단절 후 같은key조회·전체취소/실패·livekey/다른endpoint거부·비밀로그0을 확인했다. Webhook verifier actualRED 뒤 rawbyte/HMAC/다중후보/전송시각±300초/키없음failclosed 구현. 프런트adapter3 RED→GREEN, explicitconfirm응답유실1 RED(기존5green). 새DB contract-red2는 단위5통과·/sandbox 기대200실제404 actualRED/preservation passed다. 아직 로컬전체/독립은 전이며 실제키/MID/APIversion/카드서명키 준비는 외부차단으로 유지한다.

E 핵심 정적 보완: 응답header 이후 InputStream body읽기에 전체deadline 부재, balanceAmount long범위/음수/저장금액상한 미검증, production설정에서 loopbackHTTP가 시험전용으로 제한되지 않은 경로, rawpaymentkey UNIQUE충돌의 PostgreSQL DETAIL 노출 가능성을 발견했다. 실제 neverclose body/금액overflow/일반설정거부/두주문키재사용·로그비노출 회귀를 추가하도록 구현자에게 전달했다. 마지막 DB로그 경로는 정적가능성이며 아직 실제노출 재현을 주장하지 않는다. unique제약은 유지하고 충돌오류 대신 영향행수 판정으로 원문로그를 피하는 방향이다. 승인/취소 동일멱등키는 공식API주소별scope에 맞으므로 결함으로 취급하지 않는다.

E focused1 단위6·선택DB5 전부통과/root은 과거미선택coverage로failed·preservationpassed. bodydeadline 문제는 실제TimeoutException으로 재현했고(준비오류아님), 보완후adapter6+signature1 집중통과. balanceoverflow는 기존에도failclosed였으므로 허위RED로기록하지 않고 explicit범위방어를 추가했다. 일반Settings는 공식HTTPS만허용, test-source PrimarySettings만loopbackfixture를 제공한다. binding은 unique제약을 유지한ONCONFLICT+영향행수거부로 변경했고 다른키동시승자/다른주문키재사용 rollback·앱로그0을 검사한다. DB로그비노출도 해당새실행의 합성fixture키를 출력없이 대조하도록 요청했다.

추가 sessionwait401/provider0,웹훅키없음503/효과0,SANDBOX0원외부0 및 fakeSDK+HTTPstub/실제DB예약4개·redirect자동POST0 브라우저검사를 연결했다. focused2는 새키/소스상태에서 실행중이며 전체개발/독립은 아직전이다.

E focused3 단위181/선택DB6/browser84 전부통과, root은 미선택과거coverage failed/preservation passed. 제한구간08:21:20Z~08:25:16Z PostgreSQL로그8줄에서 합성충돌키1개·fixturesecret 발견0을 확인했고 원문키/일치줄은 저장하지 않았다. prior24manifest36552파일 불변. 필수등록의 브라우저이름 합쳐짐을 전체전 발견해 별도4이름으로 정정(기존80유지→84),프런트175→179/Java필수348→361 삭제0.

E 개발1 `.local-tools/p08-e-dev1-20261028`을 선택없이 시작하며 제품/검사/하네스 전체소스동결. 로컬필수잔여차단 없음 자체판정이나 개발전체/독립은 아직전이다. 실제Toss키/MID/APIversion/카드서명키공급 미검증 때문에 E3·E전체는 여전히 미완료다.

E 개발1 실패: Java420(단위181통과/통합239 중2assertion실패),browser84통과. 기존D 실제프로세스 paymentZeroCrashRecoveryNeverCallsAnyProviderOperation/paymentApprovalProcessRecoveryBeforeAndAfterProvider 자식기동에서 Erouter Primary와 D시험forbiddenProvider Primary가 동시에 존재해 NoUniqueBeanDefinition이 발생했다. 보존된 자식로그로 원인확인, timeout문제가 아니다. config선택 actualRED 후 Mock Fallback/일반router/시험Primary 우선선택의 최소보완을 검토하며 D 실제kill검사는 유지한다. 새개발전체2 전 독립최종은 실행하지 않는다. 실제PG외부차단도 유지한다.

E routing 보완 actualRED: prior단위1통과/신규1assertion실패로 NoUniqueBeanDefinition을 재현했다. 설치Spring7.0.9 Fallback지원 확인 후 MockFallback/routerPrimary제거만 적용하고 D시험probe는 변경하지 않았다. focusedroutinggreen1 단위182/선택DB7통과(기존D 실제kill2+E5 유지),root은 과거미선택coverage failed/preservationpassed. 기본routeridentity·DEMO4연산위임·키없는SANDBOX4연산UNKNOWN/mock0·시험Primary선택 검사를 추가했다. 첫E실패3964manifest는불변,followup1786은별도immutablemanifest다. 새최종전체2와 독립전체가 남았다.

E 개발2 로컬전체 `.local-tools/p08-e-dev2-20261028` 17:40:01~17:47:06 KST 종료0/status·preservation passed. Java421/browser84/frontend179/Node73 및 필수gates 통과. 소스439개 SHA256 `B66F405B767EDF211C112B666FE1ED4F42BB4D81337D2DFBDDDC597079A10EBF` 동결. 새 DB `d2_it_35f768fdb0194cf896355626a4fb68a6`, 해당db/media. 새 독립 `.local-tools/p08-e-review1-20261028`을 실행한다. prior26manifest union42302 보존을 전후 대조하며 로컬독립결과까지 확인한 뒤에만 계획의 외부차단 예외로 F에 인계한다. E실제SANDBOX·카드webhook키/전송시각계약 미확인은 해소되지 않았다.

### E 로컬 마감·F 착수 (2026-10-28)

독립 전체 .local-tools/p08-e-review1-20261028 17:49:48~17:56:49 KST 종료0/status·preservation passed. Java421(182+239)/browser84/frontend179/Node73, 필수46묶음 및 type/lint/build 통과·누락/실패/오류/skip0. 개발2=독립1=현재439입력 SHA256 B66F405B767EDF211C112B666FE1ED4F42BB4D81337D2DFBDDDC597079A10EBF, 전후불일치0. 실패manifest26개 union42302파일·manifest자체 해시 불일치0. 새DB d2_it_9fd636bdd5a24841b50a0ae356f2f2da, 독립1/db/media 보존. 제약별 assertion과 감사 사본은 P08-E-review.md를 따른다.

판정: E 로컬 필수차단 없음. 실제 테스트키/MID/API버전/공개webhook 접근, 실제SDK·승인·조회·전달 및 일반카드 보안키/시간형식 계약은 미검증이므로 E전체 미완료다. 공통 계획의 외부차단 예외로 F DEMO 구현에 착수하며 이를 실제PG 성공으로 확대하지 않는다. F→F1→F2→G 순서를 유지하고 P09·커밋·배포·푸시는 하지 않는다.

F 사전 판정: 기존 Orders.authorize/PaymentCoordinator.get은 원 구매browser·소비attempt 및 구매자격·대사쓰기와 연결되어 새브라우저 이력용으로 재사용하지 않는다. F 공개 읽기권한은 현재 회원 또는 P06 GuestAccessSession의 version/idle/max/revoked·GuestBuyer 귀속을 검증하고 결제이력은 별도 읽기 projection으로 제공한다. 기존 D status/approval 계약은 유지한다. 기존 fixture 참조 guest/orders는 명시적인 guest/access/references로 구분하며 기존 assertion을 보존하고 guest/orders는 실제 주문 페이지가 된다. 경로변경 회귀·중복매핑·권한/개인정보 검사로 검증하며 fixture만으로 F완료를 주장하지 않는다.

F 집중 진행: 기본조회 실제RED 뒤 focused5 단위182·새DB8 통과(과거 미선택coverage 때문에 root failed, 전체통과 아님). 실제 비회원reset/NEW·EXISTING, 두판매자private/closed·금액, 동일email 실제탈퇴재가입, 페이지사이삽입/상태변경, 잠금대기 후 guest idle/max·member/payment/sellerexpiry와 권한폐기 검증을 보강했다. 대사필요 count/time/FAILEDjob 및 보상중true/종결false의 순수조회 무변경을 확인했다. 잠금대기 후 만료 재검사 누락은 실제404대신200 RED로 보완했다. focused6 browser88통과/2동명매대locator실패를 보존하고 하위주문ID로 검사대상을 구분한다. 최종 화면저장 직전 계정변경 microtask RED2→guard추가8단위통과, 프런트187/type/lint통과. 주문 Owned 공개최소포트로 결제SQL 소유경계를 보완했다. focused7은 단위+F8+browser90를 새 격리경로로 실행하며 최종전체/독립은 아직전이다. 기존26manifest42302실패파일 불변 확인.

F 최종 개발 전체 진입: focused7 단위182·선택DB9(F8+브라우저host)·browser90 통과, 프런트187/type/lint 통과. 기존필수명누락0. source453 SHA256 649C318C0A763FD8CBF63A5C3E1ED738C9FD133286FD3CA06C1DDD556D45A5FE 동결, HEAD460aa8bb0bb22f5138849eb00c050abd18f6fb53 미커밋. F실패7129파일(14실행/14emulator/39sidecar) immutablemanifest F5D901F323E43594E9709DC74434CBAC1ED8639D1E4BFBE2FC52BA17462A36FD 보존. 전체27manifest/49431파일 불일치0. .local-tools/p08-f-dev1-20261028에서 MAVEN_ARGS 없이 루트전체 실행을 시작하며 아직 완료판정 전이다.

F 개발전체1 .local-tools/p08-f-dev1-20261028 18:42:19~18:49:46 KST 종료0/status·preservation passed. Java429(182+247)/browser90/frontend187/Node73 및 type/lint/build 통과. source453 SHA649C318C0A763FD8CBF63A5C3E1ED738C9FD133286FD3CA06C1DDD556D45A5FE 동결 유지, 새 DB d2_it_b29720d6469b4a09be7de549d8a50945/해당db/media. 독립 .local-tools/p08-f-review1-20261028을 새환경에서 진행하며 최종판정 전 F1 착수하지 않는다.

### F 마감·F1 착수 (2026-10-28)
독립 .local-tools/p08-f-review1-20261028 18:51:54~18:59:27 KST 종료0/status·preservation passed, Java429(182+247)/browser90/frontend187/Node73 및 필수47묶음/type/lint/build 통과. source453 SHA649C318C0A763FD8CBF63A5C3E1ED738C9FD133286FD3CA06C1DDD556D45A5FE 개발/독립/현재동일. 27manifest/49431실패파일·manifestSHA 전후불일치0. 새DB d2_it_5d92172c863c4498831ccd447ec2a24d/해당db/media. R-F01~07+microtask지적해소, F DEMO 필수잔여차단없음. F1 기본배송 데이터·상태·이력에 착수한다. E실제SANDBOX 차단유지, F2/G는 선행마감뒤 순차진행하며 P09·커밋·배포·푸시는 금지.

F1 사전 판정: D PAYMENT_SETTLED는 성공/실패 모두 발행되고 payment no-op handler가 소비하므로 fulfillment로 단일 소유권을 이전한다. 실제 D승인→Outbox→Bworker 경로에서 주문잠금 후 PAID만 기본배송을 생성한다. 기존완료job은 보존하고 과거PAID 누락은 실제앱에서 실행 가능한 bounded/restartable 내부 FULFILLMENT_PREPARE enqueue 경로로 보완한다. 새job과 실시간job은 같은 sellerOrderId+BASIC 사업효과 dedupe를 쓰며 동시생성1/중단후이어하기/기존job불변 assertion을 요구한다. seller account→store→order→shipment와 worker order→shipment 잠금순서, 주문잠금 뒤 현재세션 시간재검사, outer거래 종료까지 잠금유지, 공개클라이언트 delivered/actor 지정불가를 검증한다. 상세 R-F101~106은 P08-F1-review.md 사전검토이며 아직 통과판정 아님.

F1 집중/정적 보완: 실제 D producer→Bworker 준비/동시생성발송·원job kill재개·order/shipment 대기후expiry, checked/runtime rollback16조합·취소경합, workerstartup50개backfill/원자enqueue/commit후ack전kill재개를 검증했다. 시계역행/동일시각 이벤트순서 actualRED를 event_sequence로 해결, 타SellerOrder항목 및 부모주문불일치 INSERT actualRED 뒤 header/item compositeFK로 연결했다. 마지막 부모FK 두방향 거부 단일검사통과, focused5 단위183/선택DB12통과, focused4 browser90통과. 모두 선택root의 미선택coverage failure와 구분한다. 최종정적 추가차단없음. source463 SHA F077524124595397AB87CEA04E19F1BCA7F1C3D11E877679382292C0F66B08EB 동결, F1실패5518파일 immutablemanifest SHA3B90C1B12ED63F6B5AC640F7B4394F11DE13FFE5CCA40464D87BA6B9FD7FFE03 보존. 새전체 .local-tools/p08-f1-dev1-20261028 준비, 실제전체/독립 판정전이다.

F1 개발전체1 .local-tools/p08-f1-dev1-20261028 19:38:46~19:46:44 KST 종료0/status·preservation passed. Java441(183+258)/browser90/frontend187/Node73 및 필수48묶음/type/lint/build 통과. source463 SHAF077524124595397AB87CEA04E19F1BCA7F1C3D11E877679382292C0F66B08EB 동결/현재불일치0. 새DB d2_it_dcd3123a94d44420881c859b89e595df/해당db/media. 전체28manifest54949실패파일 사전대조0, 사후감사 진행중. 별도 .local-tools/p08-f1-review1-20261028 독립전체를 시작하고 F2는 독립최종판정 뒤 착수한다.

### F1 마감·F2 착수 (2026-10-28)
독립 .local-tools/p08-f1-review1-20261028 19:50:24~19:58:20 KST 종료0/status·preservation passed, Java441(183+258)/browser90/frontend187/Node73 및 필수48묶음/type/lint/build통과. source463 SHAF077524124595397AB87CEA04E19F1BCA7F1C3D11E877679382292C0F66B08EB 개발/독립/현재동일. 28manifest/54949실패파일과 manifestSHA 전후불일치0. 새DB d2_it_384c76e7a1e942bfb48f1b0cf45bf18d/해당db/media. R-F101~108해소·필수차단없음, F1기본모의배송 완료. F2판매자발송·회원/비회원배송조회 화면에 착수한다. E실제PG 차단유지, G는 F2마감뒤 순차진행, P09·커밋·배포·푸시 금지.

F2 진행: 판매자 최소필드 자체store keyset목록을 실제200대신404 RED 후 구현. ShipmentStore/UI 실제RED8·MOCK_PROVIDER표시오류/PAIDgate RED2·최종generation경계RED2 뒤 보완. 발송200후GET실패는 화면개인정보를 비우고 현재identity의 최초불변요청만 독립확인버튼으로 유지,401/403/계정변경은 모두폐기.409후GET실패는 재POST없이 명시GET복구. 실제panel클릭/포커스/같은본문복구 검사와409GET실패검사를 추가해 프런트206/type/lint통과. focused2 browser88통과/8실패는 이전placeholder2+미등록testfixture6; focused3 browser92통과/4실패는 asyncPOST전검사2+동일전화cooldown4292. 세width키보드실제API/DB 및기존lifecycle통과. 새브라우저다른NEW묶음은 별도전화로검사하며 동일전화NEW분리는 기존F실제DB필수검사로유지(새시간조작없음). 목록만료fixture는 실제expiry_time까지만료시켜수정. focused4 새격리DB+browser96재실행중; 전체개발/독립은아직전.

F2 추가 집중: focused4 browser94통과/2logout응답전assert실패, 실제응답대기로수정. focused5 browser95통과/1조회인증source쿼터429: 기존18+불필요구브라우저인증2+필수새브라우저2의테스트구성문제. 불필요구브라우저GAS로그인제거(실제D승인유지),새브라우저실제로그인으로준비/조회연결하여쿼터정책/시간/검사skip변경없음. focused6 단위183/선택DB4/browser96 전부통과·skip/flaky0. source실측.1=20/.2=8/.3=14. 선택root의과거필수누락failure와전체통과는구분한다. 최종source469 SHA9AF9A9D9BFC6FF7C69FA7BC08F319822E33E9FD2514489C5D0510D8EB4B89DE9 동결. F2실패4415파일/7roots/7emulator/17sidecar immutableSHA1ECE2EA859A1DA48BDB0DC6D8F7F30AFC307C6DF017BF651C0B71B65E7760415 보존,전체29manifest감사뒤 .local-tools/p08-f2-dev1-20261028 선택없는개발전체진입.

F2 개발전체1 20:50:08~20:58:03 KST failed/preservationpassed: 단위183·PostgresJdbcIT259/259·browser96 통과이나 별도 PhoneProviderEmulatorIT browserprobe TypeError fetch failed로실패. frontend/Node미진입. 원인단정하지않고고정stage/name/networkcode allowlist진단만추가(원문/토큰/stack/address출력없음·요청/retry/timeout변경없음);독립정적적합. 새phonefocused1 단위183+제공자1 실제SDK→emulator→Java통과(53초),기존실패미재현/원인미확정으로유지. aggregate최대4후보비노출검사추가 Node6통과. 새동결469 SHAB095D13D07F1E2D9C19CBA8218C91BCE78C137B8CCA500784AD1A2B250F450F2. 추가실패1431파일/2roots/2emulator/7sidecar immutableSHAF98C434157C9F5434445426279CD348DC9B0F6468AC94EC08893EFE115F72828 보존,전체30manifest감사뒤새개발전체2실행. 첫실패를성공으로소급변경하지않는다.

F2 개발전체2 .local-tools/p08-f2-dev2-20261028 21:09:05~21:17:51 KST 종료0/status·preservationpassed. Java443(183+260)/browser96/frontend206/Node75 및 필수49묶음/type/lint/build통과·누락/실패/skip0. 제공자SDKprobe도통과했으나 dev1 fetch실패원인미확정 기록유지. Source469 SHAB095D13D07F1E2D9C19CBA8218C91BCE78C137B8CCA500784AD1A2B250F450F2 동결. 전체30manifest60795파일 최종감사/증거복사 진행중이며 독립review1은최종인계뒤실행한다.

### F2 마감·G 착수 (2026-10-28)
독립 .local-tools/p08-f2-review1-20261028 21:23:16~21:32:05 KST 종료0/status·preservationpassed, Java443(183+260)/browser96/frontend206/Node75 및필수49묶음/type/lint/build통과. source469 SHAB095D13D07F1E2D9C19CBA8218C91BCE78C137B8CCA500784AD1A2B250F450F2 개발/독립/현재동일,30manifest60795파일과manifestSHA전후불일치0. 새DB d2_it_39bebeeb93f44ca78818581c9ceec171/해당db/media. R-F201~207해소·F2모의배송필수차단없음. G회원/비회원실제구매통합·증거전수대조에착수한다. E실제PG/카드webhook계약외부차단 및 이전phonefetch원인미확정은유지. P09·커밋·배포·푸시금지.

G 집중 진행: 신규purchaseTotalsAndReservationsConverge 실제수량3/2의단위→항목→매대→부모55800합계/재고감소/승인1/배송원항목ID수량검사통과. focused1/2는브라우저host선택오류로미실행,DB만통과했으며원본보존. focused3 정확한host에서신규G8통과·전체browser103통과/기존F2전화source한도429로1실패. GAS만.4실제TCP격리했으나추가전화2회가.1한도를소비한검사간섭이며제품결함아님. 정확한POST /api/guest/verifications도G에서만.4실제TCP로격리(기존login과2상수allowlist·loopback대상·cookie/CSRF/body/Set-Cookie/error유지),제품quota/reset/timeout변경없음. 독립정적적합,focused4새환경에서전화/조회양쪽limit와기존F2/G동시통과검증중. G03준비는C신뢰생성서비스이며다른G실제UI생성과구분,기존같은전화NEW/EXISTING/보상/0원중단검사필수유지. E실제PG 별도NOT-RUN기록·P09문서인계만작성.

최종fresh독립교차검토(읽기전용)에서R-FINAL-01결제UI인증변경시view잔류/generation누락,P2;R-FINAL-02예약행대기뒤회원session시간재검사및EXISTINGguest주문대기뒤max재검사누락,P2를발견했다. 아직정적지적이며실행RED전이다. docs/work-items/P08-final-review.md에원본판정/필요assertion/범위밖항목을저장했다. 진행중Gdev1소스는변경하지않고종료후한수정작업으로재현·보완→정적재검토→새전체개발/독립확보. Gdev1통과하더라도열린지적이있어최종완료로판정하지않는다.

G 개발후보전체1 .local-tools/p08-g-dev1-20261028 22:02:52~22:12:20 KST 종료0/status·preservationpassed,Java444(183+261)/browser104/frontend206/Node75 및필수50묶음/type/lint/build통과·누락/실패/오류/skip0. source472 SHA690D1D23868242821F8C2554463A50C425D7CD49D11FD001B174ADDA8A3C2658 동결/22:12:35수정전현재대조0. 새DB d2_it_2730407667434a10b15c2e061f4ea60e/해당dbmedia. 그러나최종교차P2지적2건열림으로G최종완료판정아님. 단일수정담당자에게소스해제,실제RED→수정→집중→독립정적재검토→새전체개발/독립을진행한다. 이후수정소스와후보1의이전소스기록을혼동하지않는다.

최종수정집중결과: R01실제화면4assertionRED및CSRF401/정상refresh경계후보완,프런트216/type/lint통과. R02는실제예약/주문잠금대기에서7/7 expected401actual200 RED(errors0)후동일보유권한재검사추가. 첫집중은새7및기존worker등통과/browser105통과1쿠폰재조회네트워크실패,실제쿠폰AVAILABLEv5·예약RESTORED/RELEASED,원인미확정. guest최대수명검사정밀화후집중2단위183/선택DB17/browser106전부통과. 최종fresh교차검토자의단일재검토잔여지적없음,R-FINAL-01/02구현·집중해소. 새개발/독립전체전최종완료판정금지.

최종수정소스동결472 SHAA20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF,집중2/현재불일치0·G후보1대비10파일변경. 새실패2458파일 immutableSHAB73AD0841A11AFCBDD50629D0DEC3F6152C15868FADABAAF4E46D8E7F5E41103 보존. 최종 G개발전체2 .local-tools/p08-g-dev2-20261028을선택없이준비하며 소스변경금지. D원래결과는당시이력이고추가지적검증을소급하지않도록D작업문서에도연결했다.

G 수정후최종개발전체2 .local-tools/p08-g-dev2-20261028 22:42:46~22:52:28 KST 종료0/status·preservationpassed. Java451(단위183+PG267+Phone1)/browser106/frontend216/Node75,필수50묶음/type/lint/build통과·누락/실패/오류/skip/flaky0. source472 SHAA20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF=freeze2/root/22:52:58현재대조0. 새DB d2_it_4c83a711300b497fa2a8968fa675f844/해당dbmedia/emulator9d4698f609224448b87d1f44673e2720. 최종독립 .local-tools/p08-g-review1-20261028 실행시작,소스동결유지. E실제PG/G05차단·두네트워크실패원인미확정 기록유지/P08전체완료판정금지.

## 최종 마감 (2026-10-28)
G독립 .local-tools/p08-g-review1-20261028 22:55:23~23:04:52 KST 종료0/status·preservationpassed,Java451(단위183+PG267+Phone1)/browser106/frontend216/Node75 및필수50묶음/type/lint/build통과·누락/실패/오류/skip/flaky0. 후감사23:06:41 source472 SHAA20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF 개발2/독립1/현재동일.32manifest65820실패파일·manifestSHA전후불일치0. 새DB d2_it_1f0485ba3a1c408dbb40e0e278617477/해당dbmedia/phone4ca8c434b9504f7097cfc95f2c30619f보존. G01~04 및R-FINAL01/02 로컬필수차단0. E/G05 실제SANDBOX·카드webhook계약외부차단/P08전체미완료;과거phonefetch/couponreload원인미확정유지. P09구현·커밋·배포·푸시없음. 최종결과/단계별근거/미검증은 P08-execution-summary.md와P08-G-review.md에연결한다.

마감후비필수서식점검 git diff --check는기능검사와별도로종료1: PostgresJdbcIT.java:5501,App.tsx:91,verify.ps1:93의EOF빈줄3건. 필수루트하네스는모두통과했고해당소스는최종검증된472파일그대로다. 이미검증된소스를서식만으로바꾸지않고이사소한서식항목을기록한다. 이는권한/동작/인수필수차단으로판정하지않으며검사결과를통과로표시하지않는다.
