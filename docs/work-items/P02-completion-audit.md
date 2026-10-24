# P02 전체 완료 점검

2026-10-24 / 제품 기준 `01c878eb83063c38c87af24027f9c3c8a018f4c9`

## 판정

**계획된 기능 구현은 갖춰져 있으나 P02 전체 최종 완료 판정은 보류한다.** 이메일 확인 토큰의 잠금 대기 중 만료 처리에서 정적 결함 후보와 필수 경계 검증 공백 R1을 발견했다. 기존 테스트 통과가 이 시나리오까지 검증한 것은 아니다. 보완 후 새 격리 DB에서 재검증해야 한다. P03는 미착수로 유지한다.

이번 작업은 코드·계획·테스트·저장된 실행 증거의 대조 점검이다. 제품/테스트 코드를 변경하거나 테스트 서버·DB를 생성·시작·중단하지 않았다. 기존 서버와 모든 DB를 변경하지 않았다. 이전 단계 완료 문서는 당시 결과로 보존하고 현재 판단은 이 문서를 우선한다.

## 계획과 구현·검증 대조

| 작업 | 현재 구현 | 대응 검증과 판단 |
| --- | --- | --- |
| A1 가입·이메일 확인 | SignupService/JdbcIdentityStore, V3, Argon2id, 해시 토큰, 로컬 메일함 | 정규화 중복·동시 가입·일회성·고정 만료 경계·롤백·로그 비밀값 제외 통과. **잠금 대기 중 만료 R1 미검증** |
| A2 로그인·JDBC 세션 | AuthConfiguration/LoginMembers/CurrentMemberFilter, V4, me/csrf | 로그인·로그아웃 JSON, ID 교체, 만료, CSRF, 계정 상태·authVersion 반영 통과 |
| A3 React 인증 | AuthStore/AuthLayout/AuthPages, 가입/확인 HTTP | 실제 DB 가입·확인·새로고침·다중 탭·독립 문맥·로그아웃, 오류·늦은 응답·개인 상태 제거 통과 |
| B 복구·프로필·주소 | PasswordRecoveryService/JdbcPasswordRecoveryStore/JdbcAccountOperations, V5, React 폼 | 최신·일회성 복구, 잠금 대기 후 만료 재검사, 세션 폐기, 타인 주소 거부, 기본주소·동시성·롤백 통과 |
| C 역할·운영 권한 | JdbcAccessPolicy/InitialAdmin/WorkspaceAccess, V6 | 공개 승격 차단, 현재 권한 재조회, 개별 운영 권한, 초기 설정 실제 CLI 경쟁·반복 거부, 변형 URL 가드 통과 |
| D 정지·복구·탈퇴 | JdbcAccountStateOperations/AccountStateController, V7, 탈퇴 폼 | 상태 전이·세션/토큰 폐기·감사·중복 경쟁·후반 실패 롤백·새 UUID 재가입·참조 유지 통과 |

실제 검사 이름은 [PostgresJdbcIT](../../backend/src/test/java/com/example/marketplace/dbtest/PostgresJdbcIT.java), [React 단위 검사](../../frontend/tests/unit), [인증 브라우저 검사](../../frontend/auth-e2e)를 대조했다. 기능별 상세 계약은 [A1](P02-A1.md), [A2](P02-A2.md), [A3](P02-A3.md), [B](P02-B.md), [C](P02-C.md), [D](P02-D.md)를 따른다.

## R1 — 이메일 확인 토큰의 잠금 대기 중 만료

- 근거: [SignupService.verifyEmail](../../backend/src/main/java/com/example/marketplace/identity/application/SignupService.java)은 `clock.instant()` 결과를 먼저 [JdbcIdentityStore.consumeAndActivate](../../backend/src/main/java/com/example/marketplace/identity/infrastructure/JdbcIdentityStore.java)에 넘긴다. 저장소는 토큰 UPDATE의 `expires_at > :now`와 consumed_at에 이 고정 시각을 사용한다.
- 예상 문제: 아직 유효할 때 확인을 시작한 요청이 토큰 행 잠금에서 기다리다가 만료 뒤 실행돼도 비교 시각이 갱신되지 않는다. 만료 후 활성화될 가능성이 있다. 이번 점검에서 실제 DB 재현은 하지 않았으므로 정적 근거와 재현 완료를 구별한다.
- 빠진 검사: 기존 `expiryBoundaryIsExclusiveAndExpiredTokenRemainsUnconsumed`는 즉시 실행하는 고정 시각 경계, `concurrentTokenConsumptionHasExactlyOneSuccess`는 중복 소비 검사다. 잠금 대기와 시간 경계의 조합은 없다. B 복구의 `recoveryRechecksExpiryAfterWaitingForMemberLock`와 같은 종류의 보완이 필요하다.
- 완료 조건: 새 격리 DB에서 다른 트랜잭션이 관련 행을 잠그고, 확인 요청의 실제 잠금 대기를 확인한 뒤 시간을 만료 경계까지 진행시킨다. 잠금을 풀어도 활성화/토큰 소비가 없어야 한다. 유효 시각의 성공·동시 소비 1회·실패 롤백도 유지해야 한다. 수정 시 회원/토큰 잠금 순서 및 실제 활성화 시점의 만료 정책을 함께 확인한다.
- 이번 처리: 기록만 수행. 별도 P02 보완 작업으로 실패 재현→최소 수정→회귀→지정 세션 검토가 필요하다. P03 진행으로 대체하지 않는다.

## 남은 제한과 후속 범위

| 항목 | 현 상태·영향 | 분류 / 다시 확인할 시점 |
| --- | --- | --- |
| 이메일 확인 재발송 | 미구현. 토큰 만료·메모리 메일 유실 시 동일 미확인 계정의 정상 회복 경로가 없다 | A1부터 명시된 기능 제한. 자력 가입 데모/외부 사용자 공개 전에 별도 보완 필요 |
| 외부 메일·수동 시연 | local-mailbox에서만 가입·복구 서비스 제공. 자동검사는 test-only 메일 조회를 사용. 문서의 디버거 기반 수동 토큰 확인은 직접 검증 안 됨 | 외부 전달·내구성·재시도와 사용자 직접 시연은 미완료 |
| 실제 주문 보존 | 탈퇴 후 UUID/FK 참조 fixture만 검증. 실주문·결제·정산과 보관 기간은 없음 | F01 거래 보존 전체는 P08 이후 거래 구현과 함께 확인 |
| 판매/운영자 관리 | 판매 승인·판매 정지 및 운영자 추가/권한 편집/통합 조치 UI 없음. 운영자 정지·탈퇴 차단 | P03/P10 등 후속 업무. C 권한 기반과 D 계정 API 완료를 관리 기능 전체 완료로 확대하지 않음 |
| 운영 환경 | HTTPS/Secure 실제 브라우저 흐름, production 빌드 전체 인증, 분산 제한·부하·모든 경쟁 순서 미검증 | 공개 운영 전에 검증. 인메모리 제한은 재시작·다중 인스턴스에 공유되지 않음 |
| 환경 재현·이행 | 현재 Windows/도구 캐시/Chromium 기준. 새 PC 전체 재현 및 기존 DB V3~V7 이행은 미실행 | AC16 전체 완료 아님. 기존 DB 유지 요청에 따라 현재 DB를 자동 갱신하지 않음 |
| 이미 진행 중인 요청 | 상태/권한 변경을 다음 보호 요청에서 검사. 모든 진행 중 요청의 소급 취소 보장은 없음 | 후속 업무 트랜잭션·동시성 설계에서 다시 검사 |

F07은 P02에서 배송지 관리 기반만 구현했다. 장바구니·찜·구매 시 배송지 선택은 P05/P08과 연결되므로 F07 전체를 완료로 표시하지 않는다. AC11도 회원 정지·탈퇴·권한 조회 부분만 검증했으며 판매 정지는 남아 있다. 사용자의 직접 학습·설명·시연 완료도 자동 테스트 결과로 대신하지 않는다.

## 실행 증거와 이번 점검의 한계

아래 수치는 **직전 P02-D의 동일 제품 코드에 대한 실제 실행 기록**을 이번에 다시 읽고 집계한 것이다. 이번 점검에서 전체 테스트를 새로 실행했다고 주장하지 않는다. 제품 변경이 없고 추가 실행의 구체적 목적이 없으므로 반복 실행/새 DB 생성은 하지 않았다. R1은 기존 실행에 포함되지 않는다.

| 증거 | 확인 결과 |
| --- | --- |
| [D 독립 Java/DB 집계](evidence/P02-D-review-summary.json) | 기본130·DB46, 실패/오류/skip 0 |
| [D 인증 브라우저 JSON](evidence/P02-D-review-browser-report.json) | 12 성공, unexpected/skipped/flaky 0 |
| [D Vitest](evidence/P02-D-vitest.txt) | 8파일73 통과 |
| [D 기존 P01 브라우저](evidence/P02-D-p01-browser.txt) | 16 통과 |
| [D 완료 기록](P02-D.md) | typecheck/lint/build 및 JAR 테스트 코드 제외 확인 |

Node 전송 검사68건은 P02-B 당시 기록이며 최신 P02 전체 재실행 수치가 아니다. Java130·DB46에는 P01 회귀도 포함되어 있어 P02 신규 테스트만의 수로 해석하지 않는다. 과거 단계의 미착수/차단 문장은 당시 이력으로 읽고, 이후 해소된 항목과 현재 남은 제한을 구별했다.

## 독립 검토

[검토 및 반영세션의 전체 검토](P02-completion-review.md)를 함께 따른다. R1은 해당 세션이 먼저 발견했고 개발세션에서도 코드·기존 테스트를 재확인했다. 현재 전체 완료 보류 사유는 R1이며 운영 준비 제한은 별도 분류다. 다음 요청은 P02-R1 보완으로 한정할 수 있으며, 이번에는 구현하거나 P03를 시작하지 않았다.
