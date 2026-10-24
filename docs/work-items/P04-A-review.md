# P04-A 독립 검토

2026-10-24 / 기준 `3c5acc0` 이후 codex/p04-a 작업 트리 / 상태: **R1~R5 보완 및 최종 독립 회귀 확인. P04-A 확정 범위 검토 완료**

## 범위

새 개발세션의 사용자 직접 승인 `01a16de0-6fda-7350-bad3-81c7bdb8e89a`와 [P04-A 계약](P04-A.md), 설계·작업 계획을 확인했다. 이 세션은 이 문서와 P04-A-review-* 증거만 수정한다. 제품·테스트·작업 문서는 개발세션 담당이다. 최초 정적 검토는 개발 실행과 겹치지 않게 읽기만 했으며, 최종 포트 해제 후 아래 독립 검증을 완료했다. 기존 서버·모든 DB 보존, P04-B 이후·커밋·푸시 금지 조건을 유지한다. 아래 발견 당시 설명과 최신 해결 판정을 구분한다.

## 정적 확인

- 카테고리는 ADMIN+CONTENT, 상품은 ACTIVE SELLER 및 본인 매대 조건을 검사한다. 상품 쓰기는 seller.api.requireCapability를 같은 트랜잭션에서 호출하고 회원→매대→카테고리→상품 순서로 잠근다. slug와 소유 매대 변경 입력을 허용하지 않는다.
- DRAFT 생성, 정의한 상태 전이, ARCHIVED 종결, 오래된 version 거부가 구현됐다. 운영 숨김은 판매자 DTO 입력과 UPDATE에서 제외한다.
- 공개 목록/상세는 단일 SQL projection으로 상품 상태·운영 숨김·카테고리 활성·매대 OPEN/visible·회원 ACTIVE/SELLER를 함께 확인한다. 다른 모듈 읽기 결합은 계약에 명시되어 있으며 독립 서비스 분리 완료로 해석하지 않는다.
- 카테고리 변경과 전후 이름/활성·처리자 이력 INSERT가 같은 트랜잭션이다. DB 감사 INSERT 실패 롤백 검사와 카테고리 비활성/상품 수정 경쟁 검사도 작성됐다. 아직 이 세션에서 실행하지 않았다.
- React는 CSRF·version을 보내고 자동 쓰기 재전송을 하지 않으며 인증 변경 후 늦은 응답을 무시한다. 카테고리 재활성 시 ACTIVE 상품 재노출과 보관 후 복구 불가를 안내한다.

## R1 — 최초 발견: 카테고리 잠금 대기 뒤 운영 권한 재확인 누락

JdbcCategories.save는 진입 및 트랜잭션 시작 시 access.require(CONTENT)를 호출하지만 기존 카테고리 SELECT FOR UPDATE 뒤에는 다시 확인하지 않고 UPDATE/감사 INSERT를 수행한다. 다른 트랜잭션이 카테고리 행을 잠근 동안 현재 운영자의 계정이 정지되거나 CONTENT가 회수되면, 권한이 사라진 뒤 잠금이 풀려도 변경할 수 있는 경로다. 기존 seller의 운영 조치는 잠금 뒤 권한을 재확인하므로 일관성도 다르다.

**정적 발견이며 아직 실행 재현 전이다.** 새 격리 DB에서 카테고리 잠금→수정 요청의 실제 대기 확인→권한 회수 commit→잠금 해제 순서를 제어해 변경/이력 불변을 검사하도록 요청한다. 재확인 위치 및 필요하다면 회원 잠금 정책을 기존 모듈 경계와 교착 회피 조건에 맞춰 결정해야 한다. 단순히 요청 시작 시 권한 있었다는 검사는 이 경계를 검증하지 못한다.

## R2 — 최초 발견: 설명 textarea와 서버 줄바꿈 거부의 불일치

CatalogPages의 description은 textarea이므로 정상 입력으로 줄바꿈이 들어간다. JdbcProducts.save가 호출하는 CatalogValues.text는 Character.isISOControl 전체를 거부해 LF/CR을 포함한 설명을400으로 거부한다. 따라서 ‘첫 줄\n둘째 줄’ 같은 일반 설명이 화면에서는 작성되지만 저장되지 않는 경로다.

**정적 발견이며 아직 HTTP/브라우저 재현 전이다.** 설명에 허용할 LF/CRLF·탭 정책을 명시하고 여러 줄 설명 저장/재조회 검사를 추가하도록 요청한다. 이름/slug 같은 단일 행 필드의 제어문자 제한은 구분해야 한다.

## R3 — 기존 조회의 중첩 연결 획득 (개발 재현 후 보완 검토)

개발세션은 `.local-tools/p04-a-pool-red`에서 풀 크기1·획득 제한300ms 조건의 회원 조회/공개 매대 조회 두 검사가 connection timeout으로 실패했다고 보고했다. 이 세션은 아직 해당 재현을 독립 실행하지 않았다.

변경 전 LoginMembers는 ResultSet RowMapper 안에서 roles() SQL을 호출하고, JdbcStores.published는 ResultSetExtractor 안에서 access.allowed SQL을 호출한다. 바깥 쿼리의 연결을 반환하기 전에 다른 연결이 필요하므로 트랜잭션으로 연결이 공유되지 않는 조회에서 풀1 고갈 경로가 있다. 개발 실행의 풀5 전체 점유 로그와 이 최소 재현 결과는 구분한다.

수정 diff를 직접 확인했다. LoginMembers는 같은 SELECT의 ARRAY 서브쿼리로 역할을 읽고 JDBC Array를 free한다. 회원 상태·auth_version·역할은 같은 문장으로 읽으며 RowMapper의 추가 SQL은 제거됐다. 공개 매대 조회는 Candidate(Store,owner)를 메모리로 받은 뒤 identity API를 호출해 중첩 획득을 제거했다. 이는 매대·회원 자격을 단일 snapshot으로 만드는 변경은 아니며 기존의 순차 자격 판정 의미를 유지한다. 풀 크기를 늘리는 설정 변경은 없다.

최종 실제 DB·브라우저 회귀와 풀1 검사의 독립 통과는 아직 대기 중이다. 최초 R1/R2와 글자 수/접근 가능한 레이블 보완도 최종 실행에서 함께 확인할 예정이다.

## 최초 추가 계약 확인

카테고리/상품명/설명 HTML maxLength는 UTF-16 길이, Java 서버는 code point 길이를 사용한다. emoji 같은 보조 문자 최대 입력이 화면에서 서버보다 일찍 제한된다. 기존 P03에서는 별도 길이 검사로 다뤘으므로 동일 기준 여부를 확인해야 한다.

위 내용은 최초 검토 시점의 상태다. 최신 결과는 다음과 같다.

## 실패 증거 대조 및 해결 판정

- **R1 해결:** `.local-tools/p04-a-r1-red/build/failsafe-reports`에서 expected denied / actual changed를 직접 확인했다. 최초 p04-a-review-red의 lock wait 관측 실패는 통계 snapshot 캐시로 인한 검사 문제이며 제품 재현 증거와 구분한다. 현재 코드는 카테고리 FOR UPDATE 직후 CONTENT를 재확인한다. 실제 대기 확인 후 권한 회수 commit·잠금 해제를 수행하는 회귀가 독립 통과하고 이름·이력 불변을 확인했다. 모든 권한 변경 시점을 직렬화하는 보장을 새로 검증한 것은 아니다.
- **R2 해결:** p04-a-review-red 보고서에서 여러 줄 설명의 CatalogException을 확인했다. description 전용 검사는 CRLF/CR을 LF로 정규화하고 LF·탭만 허용하며 다른 제어문자·잘못된 surrogate를 거부한다. 단일 행 상품명 제한은 유지한다. DB 저장/조회 및 여러 줄 브라우저 흐름이 독립 통과했다.
- **글자 수/레이블 보완:** p04-a-unicode-red.txt의 emoji80 입력 실패를 확인했다. UI는 서버와 같은 code point 기준의 제출 검사를 적용하며 textarea/select의 htmlFor/id로 접근 가능한 이름을 고정했다. emoji80과 저장된 여러 줄 설명·선택창 레이블 회귀가 단위 검사에서 통과했다. 기본 textarea/select 스타일도 제품 변경에 포함되며 이 세션은 별도 스크린샷 시각 감사를 수행하지 않았다.
- **R3 해결:** p04-a-pool-red의 두 connection timeout 오류(풀1 active1, 300/313ms)를 직접 확인했다. 변경 전 코드를 다시 실행한 것은 아니며 개발 RED 산출물 대조와 수정 코드 정적 검토, 수정 후 독립 GREEN을 수행했다. 최종 DB 검사에서 회원 조회/current·login 및 공개 매대 조회가 풀1 조건을 통과했다. 기존 모바일 seller-journey와 전체 브라우저도 통과했다. 지속 부하·모든 풀 설정의 무장애 보장으로 확대하지 않는다.

## 최신 독립 실행

`./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p04-a-review -Offline -RunBrowser` 종료0. 프런트 `typecheck`, `lint`, `test:unit`, `build`도 이 세션에서 직접 실행해 종료0을 확인했다.

| 검사 | 결과 |
|---|---|
| Java 기본·아키텍처 | 130개 실패/오류/skip0 |
| 실제 PostgreSQL 통합 | 66개 실패/오류/skip0 |
| 실제 브라우저 | desktop11 + mobile11 = 22개 통과 |
| 프런트 단위 | 12파일·86개 통과 |
| 타입·린트·빌드 | 모두 통과 |

단위 환경 scrollTo 미구현 안내는 실패가 아니었다. 실제 화면 동작은 브라우저 검사로 별도 확인했다. 기존 검사와 새 catalog 흐름을 함께 실행했고 제품 수정은 이 세션에서 하지 않았다. [집계](evidence/P04-A-review-summary.json), [DB 식별](evidence/P04-A-review-target.txt), [브라우저](evidence/P04-A-review-browser.txt), [프런트 결과](evidence/P04-A-review-frontend.txt)를 보존했다. 상세 원본은 `.local-tools/p04-a-review`에 있다.

## 보존·남은 제한

독립 DB `d2_it_e3cfe9e60cd14aab8162b3fb57368ec2`를 보존했다. 읽기 전용 정규화 pg_dump로 기존 개발·테스트 DB의 SHA256을 P04-A-before.json과 대조했고 모두 `6EC093D4CD1B10F9C5516130D120B045391789B6D3DBD462D54833ED96279BE7`로 동일했다. 이전 DB 목록 누락 없음, 컨테이너 ID `6a9f494624c9`/`68d17431a4b1`, DB 포트15432/15433 PID57168 유지도 직접 확인했다. 8080/4174/5174는 당시 리스너가 없었으며 시작/종료하지 않았다. 과거 검증 DB의 전체 행을 덤프 비교한 것은 아니다.

위 실행 뒤 R4를 추가 발견하여 필수 보완 잔여로 분류했다. 실제 SKU·가격·이미지 업로드·재고·구매·공개 탐색 화면, 운영 숨김 조치 UI 및 감사 이력 탐색 UI는 이번 구현 완료와 구분한다. DB 감사 저장은 확인했으나 운영 감사 탐색 화면을 구현한 것으로 표시하지 않는다. 사용자 직접 학습·수동 시연·실제 기기·상용 부하 및 기존 5174의 최신 기능 제공은 미검증이다. 단일 모놀리스 읽기 projection 결합과 공개 매대의 순차 자격 조회 한계는 유지한다. P04-B 이후·커밋·푸시는 진행하지 않았다.

## R4 — 후속 발견: 상품 쓰기의 계정 상태 경합 오류 매핑

개발세션이 제시한 추가 경계를 직접 대조했다. JdbcProducts.save의 최초 access.require 이후 requireCapability가 회원 행 잠금을 기다릴 수 있다. 그동안 계정 정지가 commit되면 JdbcSellerMembership.lockActiveBuyer는 AccountException(STATE_CONFLICT)을 던진다. CatalogErrors는 이 예외를 처리하지 않아 예상 가능한 상태 충돌이500으로 노출되는 경로다. 기존 StoreController는 동일 계열 예외를409로 매핑한다.

아직 이 경계의 HTTP 실행 재현 전이며, 이전 성공한66개 검사로 이 누락이 해결됐다고 보지 않는다. 개발세션에 실제 회원 잠금 대기→계정 정지commit→해제에서500 RED 확인과409 공통 오류·상품 불변 회귀 및 최소 매핑 수정을 요청했다. 최종 완료 판정은 해당 보완 후 갱신한다.

### R4 보완 확인 및 후속 실행 결과

개발 RED p04-a-account-race-red의 보고서에서 예상409/실제500 INTERNAL_ERROR를 직접 확인했다. CatalogErrors가 AccountException을409로 처리하도록 최소 수정한 것을 확인했다. 새 `.local-tools/p04-a-final-r4-review`에서 독립 실행한 R4 개별 검사는 통과했다. 실제 회원 잠금 대기 관측→계정 정지commit→HTTP409/CONFLICT·상품0건을 확인한다. 프런트는 변경하지 않아 앞선 독립 타입/린트/단위86/빌드 증거를 유지한다.

그러나 이 후속 전체 실행은 **종료1**이다. Java130 성공, DB67 중 브라우저 실행을 포함한 묶음 검사1개 실패(그 외66 성공), 브라우저21 성공·1 실패다. 실패를 DB와 브라우저의 별도 두 결함으로 중복 계산하지 않는다. [후속 집계](evidence/P04-A-review-r4-summary.json), [후속 브라우저](evidence/P04-A-review-r4-browser.txt), [후속 DB](evidence/P04-A-review-r4-target.txt)를 보존한다. DB `d2_it_f6c42839656a4bd58643c6c9291f2937`는 삭제하지 않았다.

## R5 — 최종 독립 재현: 실제 조치 순서와 시각 정렬의 불일치

모바일 seller-journey는 승인→공개→정지→복구→재공개→폐점 순으로 응답과 화면을 기다린 뒤 이력을 읽는다. 최종 예상 close/restore/suspend 대신 restore/suspend/close가 반환되어 실패했다. 연결 timeout과 다른 실패이며 과거 성공 실행으로 덮지 않는다.

실패 DB를 읽기 전용 조회했다. 모의 매대 journey-d4474bbc의 저장 UTC 시각은 suspend `13:05:09.166468`, restore `13:05:09.386373`, close `13:05:08.974427`이었다(2026-10-24). 이후 실행한 close에 앞선 시각이 저장돼 실제 처리 순서와 시간값이 역전됐다. 시스템 시간 보정의 구체 원인까지 확인한 것은 아니다.

현재 history는 occurred_at DESC,id DESC를 사용하고 id는 랜덤 UUID이므로 시각 역행이나 동률에서 인과 순서를 보장하지 못한다. 정지/복구/폐점은 이미 매대 잠금과 version으로 직렬화되므로 해당 순서를 신뢰할 수 있는 순번/버전으로 이력에 저장·조회하는 방안 등을 개발세션에 검토 요청했다. 기록 시각은 별도 표시 값으로 유지할 수 있다. 타임스탬프를 임의 조정하거나 기대 순서 검사를 제거하는 방식으로 통과 처리하지 않는다. 아직 R5 수정 및 독립 재검증 전이므로 필수 잔여 항목이다.

### R5 보완 및 최종 독립 검증 완료

개발 RED 보고서의 시각 역행 시 close/restore/suspend 순서 실패와 신규 회귀 검사를 대조했다. 기존 V10은 변경하지 않고 V14에서 nullable 양수 store_version과 매대별 부분 unique 인덱스를 추가했다. JdbcStores는 매대 잠금을 잡은 같은 트랜잭션에서 변경 후 version(current.version+1)을 이벤트에 저장하며 version DESC NULLS LAST로 조회한다. 시각 역행·동률과 미래 시각의 기존 NULL 이벤트 혼재를 강제한 검사가 추가됐다. 일반 수정으로 버전 사이에 공백이 생길 수 있으며 이력 번호의 연속성은 요구하지 않는다.

이 세션에서 `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p04-a-history-review -Offline -RunBrowser`를 실행하고 종료0을 직접 확인했다. **Java130·실제 PostgreSQL68 실패/오류/skip0, 브라우저22개 통과**다. R4 계정 경합과 R5 시각 역행 회귀, PC·모바일 동일 회원 연속 흐름을 포함한다. [최종 집계](evidence/P04-A-review-history-summary.json), [브라우저](evidence/P04-A-review-history-browser.txt), [격리 DB](evidence/P04-A-review-history-target.txt)를 별도로 보존했다. 이전 실패 증거는 삭제하거나 성공 기록으로 덮지 않았다. 프런트 변경이 없어 앞선 독립 타입·린트·단위86·빌드 결과를 유지하며 이번에 재실행했다고 표시하지 않는다.

새 DB `d2_it_c2727f16775844a48a4fe546e4a32949`를 보존했다. 종료 후 기존 개발·테스트 DB 정규화 덤프 해시가 기준과 동일하고 기존 DB 목록 누락과 컨테이너 변경이 없음을 읽기 전용으로 재확인했다. 앞선 실패 DB도 목록에 존재한다. DB 포트15432/15433 PID57168 유지, 8080/4174/5174 리스너 없음이 확인됐다. 기존 서버를 시작하거나 종료하지 않았다. [보존 스냅샷](evidence/P04-A-review-history-preservation.json).

**R1~R5 해결 확인, P04-A 확정 범위의 필수 잔여 검토 항목 없음.** 기존 NULL 이력의 인과 순서를 복원하는 것은 아니며 해당 행끼리는 기존 시각/ID 정렬을 유지한다. 기존 운영 DB의 V14 업그레이드는 별도 실행하지 않았고 새 격리 DB의 전체 마이그레이션과 NULL 이력 혼재 회귀를 검증했다. 위 후속 기능·수동 확인·상용 운영 제한은 유지한다. 이번 반영은 검토 문서와 독립 증거뿐이며 커밋·푸시·다음 단계는 진행하지 않았다.
### 후속 로컬 커밋 마감 기록

2026-10-24 후속 사용자 요청으로 개발세션이 이 검토 문서와 독립 증거를 P04-A 최종 로컬 커밋에 포함한다. 위 커밋 미진행 표현은 독립 검토 당시 상태이며 최종 마감은 [작업 기록](P04-A.md)의 로컬 커밋 마감 항목을 따른다. 검토 판정 R1~R5 해결·필수 잔여 없음은 유지한다. P04-B 이후와 원격 푸시는 진행하지 않는다.
