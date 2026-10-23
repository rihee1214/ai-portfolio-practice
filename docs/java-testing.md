# Java 검사 실행 범위 — P01-D1

JUnit Jupiter는 테스트를 발견하고 실행하며 assertion/파라미터 검사를 제공한다. Spring Boot Test는 실제 앱 설정과 자동 구성을 로딩한다. MockMvc는 그 컨텍스트의 MVC 요청 처리를 네트워크 없이 실행한다. 셋은 대체 관계가 아니라 함께 쓰는 서로 다른 도구다. 현재 Boot4.1.1 BOM이 관리하는 Jupiter6.0.3, Spring Test7.0.9를 사용한다. [Boot 공식 테스트 안내](https://docs.spring.io/spring-boot/reference/testing/)

## 현재 실행되는 검사

| 클래스 | 실행 수 | Spring/서버/DB | 확인하는 계약 |
| --- | ---: | --- | --- |
| DedicatedDatabaseTargetTest | 13 | 모두 없음 | C3 대상 허용·거부. 연결 없이 URL·계정·확인값 검증 |
| HealthMvcTest | 11 | Boot MOCK + MockMvc / 서버·DB 없음 | health 200·status-only·JSON 협상, 기존/없는 경로 404, health POST405, DB/Flyway 빈 없음 |
| UnhealthyMvcTest | 1 | Boot MOCK + MockMvc / 서버·DB 없음 | diskspace 임계값으로 503 DOWN 생성, 상세 정보 비노출 |
| ErrorHttpTest | 7 | Boot RANDOM_PORT / loopback 서버 / DB 없음 | 실제 Servlet 오류 전달의 404·405 JSON 및 정상 health |

합계32개이며 실패·오류·skip는0이다. MockMvc의404/405 상태 확인과 실제 HTTP 오류 body 확인을 구분한다. 실제 서버는 OS가 고른 임시 포트에서 뜨고 테스트 JVM 종료 시 닫힌다. 기존8080·5174는 시작·종료 대상이 아니다. RANDOM_PORT 검사는 DB 통합 검사가 아니다.

`DbFreeBootTest`와 `ErrorHttpTest`는 DataSource/Flyway 자동 구성을 제외하고 SQL init/Flyway를 끈다. 로컬 root.env를 읽지 않고 classpath 설정만 로딩한다. DB 자동 구성 회귀 자체를 검증하는 검사는 아니며 D1 범위를 DB와 분리하기 위한 명시적 제한이다. C3의 FixtureConfiguration은 `c3-fixture` 프로필에서만 켜지고 기존 JdbcContract가 명시적으로 활성화한다.

`@SpringBootTest`의 기본 MOCK는 실제 HTTP 서버를 띄우지 않는다. MockMvc는 Servlet 컨테이너의 오류 페이지 전달을 실제 네트워크처럼 재현하지 않으므로 `/error` JSON은 RANDOM_PORT로 확인한다. 향후 업무 Controller의 좁은 범위 검사는 `@WebMvcTest`를 사용할 수 있지만 현재 Actuator health는 Boot 자동 구성과 함께 검사한다. [Boot 앱 테스트와 실행 모드](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)

## 명령

기존 서버의 backend/target을 보존하려면 저장소 루트의 PowerShell7에서 격리 출력 경로를 사용한다.

```powershell
$env:JAVA_HOME = Join-Path $PWD '.local-tools/jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = Join-Path $PWD '.local-tools/m2'
$build = Join-Path $PWD '.local-tools/d1-build'
Push-Location backend
./mvnw.cmd -B -ntp '-Dmaven.repo.local=../.local-tools/m2/repository' "-Dmarketplace.build.directory=$build" verify
Pop-Location
```

의존성을 처음 준비할 때 Maven Central 접근이 필요하다. 캐시가 준비됐다면 `-o`로 오프라인 실행할 수 있다. `verify`는 Checkstyle·컴파일·아래 Surefire 검사·패키징을 실행한다. 기본 `test`에도 실제 HTTP 임시 포트 검사7개가 포함되므로 “전부 순수 단위 검사”라고 부르지 않는다.

위 Maven 명령에서 `verify` 대신 다음 인자를 사용할 수 있다.

| 목적 | 인자 |
| --- | --- |
| 모든 D1 검사 | `test` |
| Spring/DB/서버 없는 검사 | `-Dtest=DedicatedDatabaseTargetTest test` |
| Boot+MockMvc만 | `-Dtest=HealthMvcTest,UnhealthyMvcTest test` (PowerShell에서는 -D 인자 전체를 따옴표로 감싼다) |
| 실제 HTTP 오류 계약만 | `-Dtest=ErrorHttpTest test` |

Surefire3.5.6은 `*Test.java`를 실행하고 `*IT.java`는 제외한다. `failIfNoTests=true`를 명시했다. 없는 이름을 선택하면 비정상 종료하며 0건을 통과로 보고하지 않는다. DB 통합의 `*IT`/Failsafe/`-Pdb-it`는 D2에서 도입할 범위이고 지금 사용할 수 있는 명령이 아니다. C3의 Java main 검사와 Surefire의 실행 수는 따로 센다.

보고서는 `.local-tools/d1-build/surefire-reports`에 생긴다. 파라미터 검사도 각 입력마다1건으로 집계한다. 전체 XML에는 JVM 시스템 속성·환경 경로 등이 들어갈 수 있으므로 그대로 공유하지 말고 필요한 집계/로그를 선별한다.

## 현재 오류 계약과 한계

health는 정상200 `{"status":"UP"}`, 장애503 `{"status":"DOWN"}`만 노출한다. 관리 상세·components는 응답에 없다. 존재하지 않는/비노출 경로는404, health의POST는405다. JSON 요청의 현재 Boot 기본 오류 필드는 `timestamp`, `status`, `error`, `path`이며 message/trace/exception은 없다. 405의 Allow에는 GET이 포함된다.

향후 업무 오류의 `{code, message, fieldErrors?, requestId}`는 P01-E다. 이번 단계에 업무 API,400/401/403/409/429,CSRF/세션/보안 필터,500 업무 예외를 검증한 것으로 표시하지 않는다. MockMvc/HTTP 검사도 React 브라우저 또는 전체 구매 흐름 검증을 대신하지 않는다.

## 직접 확인할 작은 실습

1. 순수 검사와 MockMvc 검사 명령을 각각 실행하고 실행 수13/12와 서버 시작 로그 유무를 비교한다.
2. ErrorHttpTest를 실행해 임시 포트와404 JSON 확인 지점을 읽는다.
3. 별도 임시 Maven 인자로 `-Dmanagement.endpoint.health.show-details=always`와 `-Dmanagement.endpoint.health.show-components=always`를 함께 전달해 HealthMvcTest의 status-only 검사가 실패하는지 확인한다. 파일/기존 서버 설정은 바뀌지 않는다. 인자를 제거하고 다시 전체 검사한다.

자동 검사 통과와 사용자 이해·직접 실습 완료는 별개다. 사용자 실습은 미확인이다. 결과와 보존 증거는 [P01-D1](work-items/P01-D1.md)을 참고한다.
