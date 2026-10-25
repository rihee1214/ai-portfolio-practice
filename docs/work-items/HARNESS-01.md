# HARNESS-01 통합 자동 검증과 하네스 선행 규칙

최신 상태: R-H01 원인 수정과 전체 하네스 검증 완료. [최종 검토](HARNESS-01-review.md)를 따른다. 아래 실패/미커밋 표현은 해당 시점 이력이며 최종 마감은 문서 끝을 따른다.

## 요청과 설계

2026-10-25 사용자가 통합 자동 검증 하네스(JUnit 단위 테스트와 ArchUnit 아키텍처 테스트 포함)를 지정했다. 기존 `verify-db.ps1`, JUnit·ArchUnit·Vitest·Playwright가 이미 있었으므로 중복 프레임워크 도입 대신 루트 `verify.ps1`에서 묶고 실행 결과 기반 완료 게이트를 추가한다. 제품 기능은 P04-D까지로 고정한다.

## 구현 순서

1. 누락·실패·skip이 통과되지 않는 자체 테스트를 먼저 작성한다.
2. XML 판정기, 필수 기존 검사 목록, 환경 보존 비교, 전체 실행 진입점을 작성한다.
3. 새 격리 DB/미디어로 전체 검사와 보존 비교를 실행한다.
4. 실행 결과를 확인한 뒤 README·개발 흐름·구현 계획에 하네스 선행 규칙을 적용한다.

## 제약조건

| ID | 기대 결과 | 검사 |
| --- | --- | --- |
| H01 | 필요한 검사 누락·skip·실패·빈 실행은 거부 | `verification/Test-Gate.ps1`, `Assert-Coverage` |
| H02 | 실제 운영 클래스의 모듈 경계와 잘못된 경계 fixture를 검사 | `ModuleArchitectureTest`, `ModuleRulesTest`의 필수 목록 대조 |
| H03 | JUnit·DB·브라우저·프런트 모든 단계 정상 실행 | XML·브라우저 JSON·각 프로세스 종료 코드 대조 |
| H04 | 기존 증거 경로 재사용 금지·새 DB/미디어 사용 | 루트/DB 스크립트 경로 방어와 `DedicatedDatabaseTargetTest` |
| H05 | 기존 환경과 소스 보존, 실패도 결과 기록 | 실행 전후 snapshot·SHA256·`result.json` |

현재 구현의 제약조건→검사 메서드 연결과 완료 차단 규칙은 [하네스 문서](../verification-harness.md)를 따른다. 전체 요구사항을 자동으로 추론해 완전성을 보증하는 도구로 주장하지 않는다. 기존 검토가 모두 육안 검사였다는 해석은 실제 JUnit·ArchUnit·DB·Playwright 증거와 다르다. 이번 변경은 검사들을 모으는 진입점과 완료 판정 규칙을 보완한다.

## 실행 기록

첫 자체 검사에서 판정기 파일이 없어 실패한 것을 확인한 뒤 구현했다. 이후 정상 케이스와 누락·skip·매핑 밖 실패·빈 보고서·빈 제약 목록·빈 매핑·중복 ID·파라미터 케이스 skip의 8가지 거부 사례가 통과했다. 전체 실행 결과는 아래 최종 확인에 기록한다.

보고서 XML의 거짓 합계/케이스 개수 불일치, 예상된 브라우저 skip, 기존 DB 삭제, 기존 미디어 변경의 5개 거부 사례도 추가해 자체 거부 검사13개가 통과했다. JUnit 목록은 직전 P04-D 독립 실행 XML, 브라우저 목록은 같은 실행 JSON, 프런트 목록은 이번 실제 Vitest96개 통과 XML을 바탕으로 작성했다.

첫 전체 실행 `.local-tools/harness-p04-d-20261025`는 JUnit137·DB89/90·브라우저21/22로 **실패**했다. 모바일 lifecycle 시나리오의 `browserContext.close`에서 30초 전체 제한을 넘겼다. 하네스가 완료를 차단하고 실패 결과와 보존 통과를 기록했다. 실패 DB·미디어·로그를 유지한다. 원인이 확정되지 않은 실행을 제품 기능 성공으로 바꾸지 않으며, 제품 코드와 timeout 변경 없이 별도 새 DB에서 재현 여부를 확인한다.

## 범위와 보존

기존 서버·개발/테스트 DB·과거 검증 DB·미디어를 유지한다. P04-E·새 제품 기능·배포·푸시는 진행하지 않는다. 과거 P01~P04-D 완료 기록을 새 하네스 통과 기록으로 소급 변경하지 않는다.

## 최종 검토 중 확인

두 번째 전체 실행 `.local-tools/harness-p04-d-20261025-r2`는 기본137(ArchUnit18 포함)·DB90·브라우저22·Vitest96·Node HTTP/프록시68, 타입·린트·빌드가 모두 통과했다. 첫 모바일 timeout은 재현되지 않았으며 원인 미확정 이력으로 유지한다. 하네스 코드 검토에서 새 체크아웃의 preview 검사에 필요한 `dist`가 먼저 생성되도록 프런트 실행 순서를 타입→린트→단위→빌드→Node 검사로 정리했다. 최종 버전은 별도 새 DB에서 재실행한다.

규칙은 루트 `AGENTS.md`, README, 개발 흐름, 구현 계획, 하네스 문서에 반영했다. 새 세션도 하네스 선행·필수 미검증 완료 차단 규칙을 읽도록 연결했다.

## 이전 판정: 하네스 도입, 전체 재검토 차단

최종 전체 실행 `.local-tools/harness-p04-d-20261025-final`에서 기본137은 통과했으나 DB90 중 브라우저 wrapper1건과 브라우저22 중 모바일 lifecycle1건이 다시 실패했다. [실행 결과](evidence/HARNESS-01-result.json), [브라우저 실패](evidence/HARNESS-01-browser-failure.txt), [실행 소스 해시](evidence/HARNESS-01-inputs.json)를 보존한다. [두 번째 실행 통과](evidence/HARNESS-01-r2-result.json)로 최신 실패를 덮지 않는다. 최신 실행은 DB 단계에서 중단되어 프런트 후속 단계는 미실행이다.

실패는 `finally`의 `browserContext.close` 위치로 보고돼 최초 대기 위치가 가려질 수 있다. 화면 상태도 최초 조치 전 상태이므로 브라우저 정리만의 문제라고 확정하지 않는다. **R-H01: 모바일 판매 정지·복구·폐점 시나리오의 간헐 시간 초과 원인 조사와 수정·재검증이 남았다.** 전체 제한을 늘리거나 테스트를 빼지 않았다. 따라서 하네스 구축/규칙 반영과 기존 기능 전체 재검토 완료를 구분하며 후자는 차단한다.

최신 전체 실행에서 기존 DB 목록115개, 기존 미디어174개, 개발/테스트 DB 덤프, 컨테이너·리스너 비교는 통과했다. 새 DB `d2_it_fa129422faa4437aa6922eab078402e6`와 모든 실패/성공 실행의 DB·미디어를 유지한다.

후속 하네스 검토에서 새 결과 경로의 `db/media`도 보존 목록에 포함하도록 보완했다. 읽기 전용 확인에서 이전 직접 media174개와 새 중첩 media54개, 총228개를 포착했다. 이 보완 후 자체 거부 검사13개를 다시 통과했고 보존 snapshot을 비교했다. 이 마지막 경로 보완 후 전체 테스트를 다시 실행한 것으로 주장하지 않는다. [자체 검사](evidence/HARNESS-01-gate-tests.txt)는 판정기13개 거부 사례이며 제품 전체 검증을 뜻하지 않는다.

제품 코드는 변경하지 않았다. 변경은 로컬 미커밋 상태이며 기존 커밋은 `4869547c1f985500cfe779905f170df67eff1b54`다. P04-E 미착수, 배포·푸시 없음.

## R-H01 원인 조사와 회귀 수정 (2026-10-25 후속 요청)

사용자가 시간 초과 원인 수정·전체 하네스·검토 기록·로컬 커밋을 요청했다. 테스트 제외와 timeout 증가 없이 진행한다. 동시 개발 세션이 같은 폴더에서 P04-E를 시작해 사용자의 명시적 승인을 받고 편집·검증 일시 중지를 전달했다. 그 세션의 `P04-E-before.json`은 보존하되 이번 커밋에 포함하지 않는다.

| 조건 | 검사·기대 결과 |
| --- | --- |
| H06 운영 목록의 대상이 두 번째 이후 페이지에 있어도 찾는다 | 새 검증 DB에 최소 UUID의 매대20개를 준비해 첫 페이지를 채운다. 기존 lifecycle과 seller-journey의 PC/모바일 흐름이 실제 다음 목록 UI를 이용해 정지·복구·폐점을 끝내야 한다. |
| H07 정리 오류가 최초 실패를 가리지 않는다 | lifecycle의 최초 실패 메시지를 기록하고 정리 오류가 있더라도 원래 실패를 유지한다. |
| H08 기존 기능·제약·환경 유지 | 전체 필수 목록, 30초 제한, retry0을 유지하고 새 DB·미디어로 전체 하네스와 보존 검사를 실행한다. |

진단 로그만 추가한 `lifecycle-diagnosis-20261025`는 전체 통과했으므로 그 실행만으로 수정 완료를 선언하지 않는다. 실제 운영 목록 SQL은 `ORDER BY id LIMIT 21 OFFSET :offset`이며 UI는20개를 표시한다. 기존 두 브라우저 흐름은 첫 페이지에만 locator를 걸고 기다린다. 첫 페이지를 채운 회귀 fixture로 이 가정을 검증한다.

`lifecycle-pagination-red-20261025`에서 첫 페이지를 최소 UUID 매대20개로 채우자 lifecycle·seller-journey의 PC/모바일4건이 모두 실패했다. lifecycle 최초 오류는 `locator.fill`이 첫 페이지에 없는 해당 slug의 `조치 사유`를 기다리는 것이었다. [실패 로그](evidence/HARNESS-01-pagination-red-browser.txt)와 [실행 결과](evidence/HARNESS-01-pagination-red-result.json)를 남긴다. 원인은 제품의 정지/복구 처리 지연이 아니라 테스트의 첫 페이지 가정이다. 정리 오류는 이 원래 실패를 가렸던 부차 문제다.

공통 `openManagedStore` 도우미는 실제 목록 응답·페이지 표시·로딩 종료를 기다리며 `다음 목록` 버튼으로 이동하고 정확한 slug의 카드를 찾는다. 두 시나리오 모두 첫 페이지 밖에서 찾았음을 assertion으로 확인한다. API로 상태를 대신 변경하거나 임의 첫 항목을 선택하지 않는다. lifecycle은 원래 실패가 있으면 뒤따르는 정리 오류가 그 실패를 덮지 않도록 했다. 30초 제한·retry0·기존22개 시나리오와 모든 업무 assertion은 유지했다.

첫 수정 실행 `lifecycle-pagination-green-20261025`는 기본137·DB90·브라우저22가 통과했으나 `no-unsafe-finally` 린트에서 차단됐다. 원래 실패 보존 동작을 유지하며 정리 오류 처리를 Promise catch 콜백으로 옮겨 린트를 통과했다. 해당 실행도 전체 통과로 표시하지 않으며 최종 수정본을 새 DB에서 다시 실행한다.

## 최종 검증·로컬 커밋 마감

2026-10-25 12:00 KST `./verify.ps1 -Offline -EvidenceDirectory .local-tools/lifecycle-pagination-final-20261025`가 종료 코드0, `status=passed`, `preservation=passed`로 완료됐다. 기본137(ArchUnit18 포함)·실제 PostgreSQL90·PC/모바일22·Vitest96·Node HTTP/프록시68, 하네스 거부 검사13개, 타입·린트·빌드가 통과했다. 누락·skip·retry 없이 두 운영 흐름 모두 두 번째 페이지에서 대상 매대를 찾아 기존 업무 assertion을 통과했다.

실행 전 DB 목록119개와 기존 미디어282개, 개발/테스트 DB 덤프와 컨테이너·리스너가 유지됐다. 새 DB `d2_it_0fd69d87adfd4b74ba132eedcf2bd1e6`와 모든 이전 검증 DB·미디어·실패 증거를 보존한다. 실행 전후와 기록 정리 후 소스 해시를 대조했다. [최종 결과](evidence/HARNESS-01-final-result.json), [입력 해시](evidence/HARNESS-01-final-inputs.json), [브라우저](evidence/HARNESS-01-final-browser.txt), [DB 집계](evidence/HARNESS-01-final-db-summary.json), [추가 HTTP/프록시 검사](evidence/HARNESS-01-final-test.txt)를 함께 읽는다.

전용 브랜치 `codex/harness-lifecycle`에서 하네스·검증 규칙·회귀 수정·검토 문서·증거를 로컬 커밋으로 마감한다. 커밋 식별자는 Git 이력과 최종 보고에서 확인한다. 다른 세션의 `docs/work-items/evidence/P04-E-before.json`은 미추적 상태로 보존하고 이 커밋에서 제외한다. P04-E 구현/검증·배포·푸시는 수행하지 않았다.
