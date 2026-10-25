# HARNESS-01 최종 검토

2026-10-25, 이 세션에서 코드·실행 결과를 직접 대조했다. **R-H01 해결, 통합 하네스 검증 통과.** 별도 검토 에이전트의 독립 검토라고 주장하지 않는다.

## 원인과 수정

운영 매대 목록은 UUID 순으로20개씩 표시한다. 기존 lifecycle과 seller-journey는 대상 매대가 첫 페이지에 있다고 가정해, 다른 페이지에 있는 `조치 사유` 입력칸을 기다리다30초 제한을 넘겼다. UUID와 앞선 검증 데이터에 따라 통과/실패가 달라졌다. finally의 브라우저 정리 오류가 최초 locator 실패를 가리기도 했다.

새 DB의 첫 페이지를 최소 UUID fixture20개로 채우자 PC/모바일의 두 흐름4건이 결정적으로 실패했다. [RED 로그](evidence/HARNESS-01-pagination-red-browser.txt)에서 최초 대기 위치를 확인했다. `openManagedStore`가 실제 목록 응답과 로딩 종료를 확인하며 다음 목록 버튼을 누르도록 수정했고, 첫 페이지 밖에서 대상을 찾았다는 assertion을 두 흐름에 추가했다. lifecycle은 원래 오류가 정리 오류로 덮이지 않도록 수정했다.

제품 API·권한·상태 전이·페이지 크기·정렬은 바꾸지 않았다. 기존 테스트와 업무 assertion, timeout30초·retry0도 유지했다. 검증 데이터는 새 격리 DB에만 추가한다.

## 실행 결과

`./verify.ps1 -Offline -EvidenceDirectory .local-tools/lifecycle-pagination-final-20261025`

| 검사 | 결과 |
| --- | --- |
| JUnit 기본 검사 | 137 통과, 이 중 ArchUnit18 |
| 실제 PostgreSQL 통합 | 90 통과 |
| PC·모바일 브라우저 | 22 통과, 운영 흐름4건 모두 두 번째 페이지 탐색 |
| Vitest | 96 통과 |
| Node HTTP·프록시 | 68 통과 |
| 하네스 자체 거부 사례 | 13 통과 |
| 타입·린트·빌드 | 통과 |
| 소스 해시·기존 환경 보존 | 통과 |

[최종 결과](evidence/HARNESS-01-final-result.json), [JUnit 필수 검사 연결](evidence/HARNESS-01-final-coverage.json), [브라우저 필수 검사 연결](evidence/HARNESS-01-final-browser-coverage.json), [프런트 필수 검사 연결](evidence/HARNESS-01-final-frontend-coverage.json), [자체 검사](evidence/HARNESS-01-final-gate-tests.txt)를 확인했다. 이전 중간 성공·실패·린트 차단은 [작업 기록](HARNESS-01.md)에 그대로 남겼다.

## 보존·범위

기존 DB 목록119개, 미디어282개 SHA256, 개발/테스트 DB 덤프, 기존 컨테이너·서버 리스너를 전후 비교했다. 새 검증 DB와 미디어도 유지한다. 과거 검증 DB의 전체 행을 모두 덤프 비교한 것은 아니며 해당 DB에 쓰기를 하지 않았다.

실제 기기·상용 부하·전체 접근성·P04-E 이후 기능은 이번 통과 범위가 아니다. 개발 세션은 사용자 승인에 따라 공유 폴더 작업을 중지한 상태다. 이번 커밋에는 P04-E 준비 파일을 포함하지 않으며 P04-E 구현·검증·배포·푸시는 하지 않았다.
