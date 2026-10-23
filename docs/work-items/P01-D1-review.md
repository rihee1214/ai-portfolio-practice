# P01-D1 개발 검토

2026-10-23 / 대상: `476cb04..f1716c412b19df150d1afddf9707a6736b4d53f5`

판정: D1 범위 검토 및 독립 재검증 통과. 제품 코드 수정이 필요한 결함은 발견하지 않았다. 실행 안내의 Maven 종료 코드 확인과 작업 디렉터리 복원 예제를 보완했다. 사용자 직접 실습은 미확인이고 D2 이후는 미착수다.

## 코드 검토

- 순수 Jupiter 13건, Boot MOCK + MockMvc 12건, Boot RANDOM_PORT 7건을 실제 소스와 대조했다. MockMvc에서 상태를, 실제 서블릿 서버에서 오류 JSON을 확인하는 분리가 적절하다. 업무 오류·인증·브라우저를 검증한 것으로 확대하지 않는다.
- health의 200 UP/503 DOWN, status-only, JSON 협상, 미구현/비노출 경로 404, POST 405를 검사한다. 실제 오류 JSON은 status/error/path/timestamp와 상세 정보 비노출을 확인한다. 현재 Boot 기본 형식을 고정하는 회귀 검사이며 향후 업무 오류 계약을 도입하면 해당 기대값도 검토해야 한다.
- D1은 DataSource/Flyway 자동 구성을 제외한다. HealthMvcTest에서 관련 빈이 없는지 확인한다. 이 설정은 DB 통합의 정확성을 검증하는 것이 아니라 D1 실행 범위를 제한한다.
- C3 FixtureConfiguration의 c3-fixture 프로필 제한과 JdbcContract의 명시적 활성화를 확인했다. 기존 일반 Configuration이 Boot 검색에 들어온 최초 오류와 변경 후 C3 회귀 근거를 구분했다.
- 테스트 의존성은 test scope다. Surefire는 *Test만 포함하며 failIfNoTests를 명시한다. 없는 테스트 이름 선택 실패 증거는 failIfNoSpecifiedTests 동작의 증거이며 모든 테스트 파일을 제거한 경우까지 실제 실행한 증거는 아니다.

## 이 세션의 독립 실행

2026-10-23 21:18 KST, 오프라인 Maven verify를 `.local-tools/d1-review-build`에 격리해 실행했다. Checkstyle·컴파일·JUnit **32건, 실패 0/오류 0/skip 0**·패키징 성공, 종료 코드 0을 확인했다. 원본 실행 로그는 `.local-tools/d1-review-output.txt`에 있다. 테스트는 임시 loopback 서버만 사용하며 DB 연결이나 새 DB 생성은 하지 않았다.

실행 후 기존 8080 PID68748·5174 PID63940과 health UP, DB 컨테이너 6a9f494624c9·68d17431a4b1의 healthy를 읽기 전용으로 확인했다. 기존 서버/DB를 중단·재시작·초기화하지 않았다.

## 개발세션 증거 확인

[최종 집계](evidence/P01-D1-test-summary.json), [없는 테스트 선택 실패](evidence/P01-D1-no-tests.txt), [노출 변경 실패](evidence/P01-D1-health-exposure-red.txt), [C3 회귀](evidence/P01-D1-c3-results.txt), [Node 회귀](evidence/P01-D1-frontend-tests.txt)를 확인했다. C3 실DB 26건과 보호 13건, Node 68건은 개발세션 증거이며 이 세션에서는 반복하지 않았다. 기존 DB 덤프 해시 보존도 [종료 기록](evidence/P01-D1-after.json)에 근거하며 여기서 재덤프하지 않았다.

## 문서 보완과 한계

java-testing.md의 PowerShell 예제는 Maven 비정상 종료를 명시적으로 확인하지 않고 Pop-Location이 후속 실행되는 형태였다. 실패 시 throw하고 finally에서 위치를 복원하도록 보완했다. 테스트 코드나 빌드 동작을 바꾼 것은 아니다.

Mockito 동적 attach 경고는 이번 재실행에도 발생했지만 현재 JDK21의 검사 실패는 아니다. 향후 JDK 대응, 새 PC/OS, DB 통합 체계, 보안·업무 오류·구매 흐름·사용자 실습은 미검증으로 유지한다. 프런트 단순화 제안은 이 검토에 포함하지 않았다. 작업 문서와 계획의 검토 상태를 갱신했으며 검토 작성 당시 문서는 미커밋으로 인계했으며 개발세션에서 확인 후 별도 로컬 커밋으로 마무리한다.
