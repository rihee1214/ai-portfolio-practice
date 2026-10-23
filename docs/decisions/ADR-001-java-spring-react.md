# ADR-001 Java Spring Boot와 React 기술 구성

P01-C2 JDBC·Flyway 구성과 격리 DB 검증을 완료했다. [최신 결과](../work-items/P01-C2.md)는 후속 검토 대기이며 검토 승인·사용자 실습 완료를 뜻하지 않는다. 아래 C2 미착수 표현은 이전 이력이다. P01-C3는 진행하지 않는다.

P01-B5에서 Checkstyle 3.6.0과 설정 예제·실행 안내를 추가했다. [작업 기록](../work-items/P01-B5.md)을 최신 상태로 참고한다. 기존 기술 선택과 DB 후속 범위는 유지한다.

작성일: 2026-10-20 / 적용: 설계 v4 / 상태: **채택, B1 최소 API·B2 첫 화면·B3 라우팅 검증 완료, B4 R4 수정·회귀 검증 완료, 업무·DB 연동은 후속**

## 1. 결정

백엔드는 Java·Spring Boot, 프런트엔드는 React·TypeScript·Vite로 구성한다. 한 저장소의 `backend/`와 `frontend/`에서 각각 빌드하며, 백엔드는 업무 모듈을 구분한 모놀리스로 시작한다. 데이터는 PostgreSQL에 저장하고 Spring JDBC·Flyway로 접근과 변경 이력을 관리한다.

2026-10-21 개발·테스트용 PostgreSQL의 실행 방식은 Docker Compose로 확정했다. PC에 PostgreSQL 서버를 직접 설치하지 않는다. 환경 재현·애플리케이션과 DB의 실행 분리·3계층 학습에 관한 이유와 한계는 [ADR-002](ADR-002-docker-postgresql.md)를 따른다. 구성·실행 검증은 P01-C1 이후이며 현재는 미착수다.

사용자가 Java·Spring Boot와 React 사용을 지정했다. 익숙한 Java로 업무 로직을 검토하면서 React 화면·상태·API 연동을 배우고, AI가 만든 코드를 직접 설명·수정·검증하는 것이 선택의 핵심 이유다. 특정 기술의 국내 점유율이나 채용 효과를 검증된 사실로 전제하지 않는다.

사용자 요청에 따라 이전 기술 결정 문서들은 제거하고 이 결정을 ADR-001로 정리했다. [P01-A](../work-items/P01-A.md)는 2026-10-21에 현재 구성으로 본문을 개정했다. 과거 환경 관찰과 현재 설계 목표·미검증 항목을 구분하며, 이전 기술안의 설치 지시는 제거했다.

## 2. 목표와 범위

[목적·목표 및 요구사항](../requirements.md)의 G01~G10, F01~F18, AC01~AC20을 기준으로 한다.

- 회원·비회원 구매, 판매자의 매대·상품·이벤트 운영, 운영자의 대시보드·비공개 조치를 완성한다.
- 비회원은 쿠폰을 쓰지 못하고 공개 이벤트는 적용받는다. 이름·전화 인증 후 조회 비밀번호를 직접 정한다.
- 작은 기능마다 AI 요청 → 설계 → 구현 → 검증 → 수정·기록을 반복한다.
- Java 서버의 거래 일관성과 React 화면의 상태·오류·API 계약을 함께 이해한다.
- 전체 기능 완성 뒤 문제를 재현하고 내부 개선과 서비스 분리를 비교해 MSA로 발전시킨다.

React와 API 서버의 실행 경로를 나누는 것만으로 MSA 전환을 완료했다고 보지 않는다. MSA는 업무 데이터 소유권과 독립 배포·실패 복구의 경계를 분리하는 후속 단계다.

## 3. 기술별 역할과 선택 이유

| 선택 | 역할·이유 | 부담·한계 | 대안·재검토 조건 |
| --- | --- | --- | --- |
| Java 21 LTS | 익숙한 언어로 업무 규칙·거래·권한 구현 | Boot·웹·동시성·운영은 별도 학습 | 다른 LTS는 지원 조건에 따라, Kotlin은 실제 필요가 생길 때 |
| Spring Boot·Spring MVC | Java REST API·의존성 구성·실행 JAR | 자동 설정·DI·프록시·예외·트랜잭션 이해 | 현재 동기 JDBC에 맞춰 Servlet MVC 사용. WebFlux는 별도 필요가 있을 때 |
| React | 구매·판매·운영 화면의 컴포넌트·상태·상호작용 | JSX·props·state·Hooks·비동기 요청·접근성 학습 | 다른 UI 기술은 사용자 목표 변경이나 구체적인 문제에 따라 비교 |
| TypeScript | 화면 모델·컴포넌트 props·API 데이터 계약의 정적 검사 | Java와 다른 타입 체계 학습. 응답 JSON을 런타임에 검증해 주지는 않음 | JavaScript도 가능하나 현재는 변경 영향 확인을 위해 타입 검사 채택 |
| Vite | React 개발 서버·빌드 | 라우팅·인증·데이터 요청·SEO는 별도 설계 | SSR·검색 노출이 중요해지면 React 프레임워크와 사전 렌더링 비교 |
| React Router | 화면 URL·중첩 레이아웃·경로 변수·404 처리 | 직접 URL 접근·새로고침·권한 안내 검증 | 초기에는 브라우저 라우팅 사용. 서버 렌더링 기능은 도입하지 않음 |
| Node.js·npm | 프런트엔드 개발·빌드·테스트 도구 실행, 의존성·잠금 파일 | 런타임 조건·패키지 설정·비동기 JS 학습 | 서버 업무는 Java가 맡음. 빌드된 정적 화면 배포에는 상시 Node 서버가 필수 아님 |
| 일반 CSS | 화면 스타일·반응형·공통 디자인 값 | CSS·접근성·화면 구성 학습 | 초기 UI 라이브러리·CSS 프레임워크 일괄 도입 없음 |
| Maven Wrapper | 백엔드 빌드·테스트·패키징 | XML·라이프사이클·의존성 학습 | Gradle은 빌드 요구나 사용자 선호가 생길 때 |
| PostgreSQL | 관계형 거래 데이터·제약·트랜잭션 | Oracle과 문법·자료형·잠금·운영 차이 | Oracle·MySQL 등은 환경·배포 요구에 따라 비교 |
| Docker Compose | 개발용 PostgreSQL의 실행 설정·버전 관리, 애플리케이션과 DB의 독립 실행 | Docker 엔진·자원·포트·볼륨 관리. 같은 PC의 실행 분리 | PC 직접 설치는 채택하지 않음. 상세 이유·범위는 [ADR-002](ADR-002-docker-postgresql.md) |
| Spring JDBC | NamedParameterJdbcTemplate으로 SQL·파라미터·결과 매핑 명시 | CRUD·매핑 작성, SQL 성능 검토 필요 | MyBatis·JPA는 실제 반복 비용과 학습 목표를 비교한 뒤 |
| Flyway | SQL 스키마·세션 테이블 변경 이력 | 호환 배포·이행 실패·복구 계획 필요 | 스키마 자동 생성이나 다른 초기화 도구와 중복 사용하지 않음 |
| Spring Security·Spring Session JDBC | 회원 인증·쿠키 세션·CSRF·서버 세션 폐기 | 가입·복구·소유권·SPA 연동은 직접 연결 | 초기 JWT·Redis·별도 인증 서버를 도입하지 않음 |
| JUnit Jupiter·Boot Test·MockMvc | Java 규칙·API·보안·DB 검증 | 모의 객체와 실제 SQL·세션 검증 구분 | DB 동시성은 실제 PostgreSQL로 확인 |
| Vitest·React Testing Library | React 입력·오류·상태 변화 검사 | 내부 구현에 결합하지 않는 사용자 행동 중심 검사 필요 | 프런트 테스트만으로 실제 서버 권한 검증을 대체하지 않음 |
| Playwright Test·TypeScript | React → Java API → DB의 브라우저 검증 | 두 앱·테스트 DB·브라우저 실행 준비 | Java E2E 도구를 중복 운영하지 않음 |
| Actuator·Micrometer | B1의 표준 health, 이후 API·worker·JVM·DB 관측 | 상태 검사 범위와 관리 엔드포인트 보호 | 기본 health는 도입 완료. 상세 지표·접근 정책은 E, 외부 수집은 운영 단계 |

React 공식 문서는 Vite 같은 빌드 도구로 시작할 수 있지만 라우팅·데이터 요청·렌더링 전략을 추가 설계해야 한다고 설명한다. 이 프로젝트는 React와 API 경계를 배우기 위해 초기 SPA를 선택한다. 상품 검색 노출·초기 로딩·공유 미리보기가 실서비스 요구가 되면 렌더링 방식을 재검토한다. [React 구성 안내](https://react.dev/learn/build-a-react-app-from-scratch), [Vite](https://vite.dev/guide/), [React Router](https://reactrouter.com/start/declarative/installation)

Spring JDBC는 Spring Data JDBC와 구분한다. 현재는 직접 SQL 방식이며 자동 저장소·JPA 엔티티를 전제로 하지 않는다. [Spring JDBC](https://docs.spring.io/spring-framework/reference/data-access/jdbc/core.html), [Flyway](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

## 4. 화면과 서버의 연결

| 항목 | 채택한 규칙 |
| --- | --- |
| 저장소 | `frontend/` React 앱, `backend/` 단일 Maven 앱, `docs/` 공통 설계 |
| 개발 | B4에서 업무 `/api`와 정확한 `/actuator/health` 경로만 Boot로 프록시. 브라우저는 상대 URL 사용 |
| 배포 | 같은 공개 origin에서 `/`는 React 정적 파일, `/api/**`는 Java API로 연결 |
| 라우팅 | React의 직접 URL·새로고침은 index fallback. `/api`·관리 경로·실제 정적 파일 오류는 fallback에서 제외 |
| 입력·응답 | 서버 Bean Validation·업무 검증을 최종 기준으로 사용. 오류는 공통 JSON |
| 회원 인증 | HttpOnly 세션 쿠키. React는 `/api/auth/me` 결과로 화면을 구성 |
| CSRF | `/api/auth/csrf`에서 토큰을 받고 변경 요청 헤더로 전달. 로그인·로그아웃 후 새 토큰 획득 |
| 자격 저장 | 세션·비밀번호·인증 토큰을 localStorage에 넣지 않음. 장바구니에는 SKU·수량만 허용 |
| CORS | 초기 같은 origin. 별도 origin이 필요해지면 명시한 출처·credentials·쿠키 정책을 함께 검증 |

React의 로그인 여부·버튼 숨김·라우트 가드는 사용자 안내 기능이다. 실제 소유권·권한·가격·혜택·재고는 Java API가 검사한다. 세션 쿠키를 사용하므로 React로 바꿨다는 이유로 CSRF를 끄지 않는다. 인증 전후 토큰 갱신과 실패 계약을 P02에서 검증한다. [Spring Security CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html), [CORS](https://docs.spring.io/spring-security/reference/servlet/integrations/cors.html)

초기 데이터 요청은 공통 fetch 클라이언트와 기능별 Hooks로 구성한다. 요청 취소·로딩·빈 결과·401/403/409·재시도 상태를 일관되게 처리하고, 중복 클릭 방지는 서버 멱등성 검사를 대체하지 않는다. 서버 데이터 캐시가 복잡해지면 전용 라이브러리를 비교한다. Redux·전역 상태·캐시 도구를 미리 모두 추가하지 않는다.

2026-10-21 상태 확인용 `/api/status`를 Actuator의 `/actuator/health`로 교체했다. 고정 응답보다 실제 HealthIndicator 집계·HTTP 상태를 이용하기 위한 결정이다. 현재는 health만 노출하고 status 외 상세·컴포넌트·discovery·probes를 끈다. DB 검사는 DataSource 구성 후 C 단계에서 확인한다. B4는 JSON Accept와 503·연결 실패 처리를 포함하며 업무 API의 공통 오류 구조로 health를 감싸지 않는다. 공개 배포에서의 관리 경로와 liveness/readiness는 해당 단계에서 설계한다. [구현·검증](../work-items/P01-B1.md), [B4 계획](../work-items/P01-B4.md), [Actuator](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html)

## 5. 버전과 재현성

[P01-A2 보완 조사](../work-items/P01-A2.md)를 2026-10-21에 완료했다. 아래 표는 조사에서 정한 목표와 검증 배정이다. 이후 B1의 실제 빌드·API, B2의 첫 화면, B3의 Router·공통 레이아웃 검증 결과는 각 작업 기록을 따른다. DB·인증·프런트 API 연동을 포함한 전체 조합은 아직 미검증이다.

| 영역 | 목표·결정 | 후속 검증 |
| --- | --- | --- |
| 서버 | JDK 21.0.12.1+1·Boot 4.1.1·Maven 3.9.16 | P01-B1: Windows 자산·다운로드·Wrapper·빌드·API |
| 화면 | React/DOM 19.3.1·TypeScript 6.0.3·Vite 8.3.4·React 플러그인 6.1.2·Router 8.5.0 | P01-B2/B3: 나머지 타입/린트 패치·의존성 해석·화면/빌드 |
| 프런트 런타임 | Node 24.22.0 LTS·동봉 npm 11.19.1 예상 | P01-B2: 실제 배포본·경로·설치 버전 |
| DB | Docker Compose의 PostgreSQL 18.6 목표, JDBC/Flyway는 Boot 관리 조합 | P01-C: 실제 이미지 확인·개발/테스트 DB 분리·볼륨 보존·연결·이행·트랜잭션 |
| 테스트 | Java는 Boot 관리 조합, Vitest 5.0.5·RTL 16.3.3·Playwright Test 1.64.1 후보 | P01-D: 실제 프로필·DOM 환경·브라우저·실행 건수 |

Maven 공식 페이지는 3.10.0을 최신 권장으로 안내하지만 3.9.16도 유지보수 중이다. 기존 3.9 계열 설계와 Boot 지원 범위 안에서 3.9.16을 선택했다. TypeScript latest 7.0.2는 확인한 typescript-eslint 지원 범위 밖이므로 6.0.3을 선택했다. 이 결정은 최신 태그를 무조건 설치하기보다 선택한 도구의 지원 조건을 맞추기 위한 것이다. [Maven](https://maven.apache.org/download.cgi), [typescript-eslint](https://typescript-eslint.io/users/dependency-versions/)

P01-A2 당시 기본 JDK는 21.0.1이고 Maven은 PATH에서 발견되지 않았다. 이후 시스템 값을 바꾸지 않고 B1용 JDK 21.0.12.1+1·Maven 3.9.16을 프로젝트 안에 준비했다. 2026-10-21 Actuator 교체 전 재검증에서 공식 체크섬 일치, clean verify, HTTP 검사 8건을 확인했고 이후 health 교체 검증은 HTTP 27건이다. 제한된 실행 환경의 DNS·로컬 통신 실패와 허용된 환경의 성공을 구분한다. 조사 완료·특정 API 실행 성공·전체 환경 준비는 별개다. [B1 결과](../work-items/P01-B1.md)

Maven에는 고정된 parent/BOM·의존성·플러그인·Wrapper를 사용한다. 프런트는 잠금 파일을 관리한다. 브라우저 번들에 포함되는 `VITE_*` 값은 공개 값으로 취급하고 DB·PG 비밀키·세션 비밀값을 넣지 않는다. [Vite 환경 변수](https://vite.dev/guide/env-and-mode)

## 6. 단계별 도입

1. P01-A2: Java·React 환경·버전 조사·기록 완료. 설치·코드 생성은 하지 않음.
2. P01-B1: 최소 Boot 앱·Actuator health·Maven 빌드. 기존 직접 작성 상태 API는 교체.
3. P01-B2: React·TypeScript·Vite 첫 화면과 Node/npm 빌드.
4. P01-B3: React Router·공통 레이아웃·CSS, 직접 URL·새로고침.
5. P01-B4: React에서 `/actuator/health` 호출, 제한된 개발 프록시·공통 fetch·503/통신 오류.
6. P01-B5 및 C~E: 설정·Git·DB·테스트·모듈 경계.
7. P02~P12: React 화면과 API를 작은 기능별로 연결하고 전체 구매·판매·운영·복구 검증.
8. P13~P14: 서버 모듈의 문제를 측정하고 Java 서비스 하나를 분리. React의 외부 API 계약은 가능한 범위에서 유지.

전화 인증은 무료 Firebase Auth Emulator 우선, 환경에 맞지 않으면 Java 모의 어댑터를 사용한다. 결제는 자체 모의와 PG 테스트 결과를 구분한다. 실제 문자·실명확인·실판매·송금은 완료했다고 표시하지 않는다.

## 7. 완료와 재검토 기준

새 기술 이름을 추가한 사실만으로 학습·포트폴리오 성과를 판단하지 않는다. 요구사항 변경, 실패 재현, AI 제안 검토, 테스트와 복구의 근거를 남긴다. Java 경험을 활용하면서 React·TypeScript·브라우저 문법은 필요한 기능과 연결해 설명한다.

서비스 분리는 데이터 소유·독립 배포·통신 실패·Outbox/Inbox·보상·대사를 검증해야 한다. Spring Cloud·브로커·Kubernetes·다른 서버 언어는 현재 기본 구성에 포함하지 않는다.

P01-B1에서 프로젝트 전용 JDK·Maven 준비, 최소 MVC API·Wrapper·실행 JAR·HTTP 검증을 완료했다. 상세 증거는 [P01-B1](../work-items/P01-B1.md)을 따른다. P01-B2에서 React 첫 화면·Node 준비·타입·린트·빌드·UI 조작 확인을 완료했다. [P01-B2 기록](../work-items/P01-B2.md)을 참고한다. P01-B3에서는 Router 8.5.0의 선언형 BrowserRouter·중첩 Outlet·공통 메뉴를 채택해 직접 URL·새로고침·역할별 화면 틀을 검증했다. 상세 결정·검증은 [P01-B3](../work-items/P01-B3.md)에 기록했다. P01-B4는 health 연동과 Node 기본 테스트 러너로 응답·취소·경합·프록시 경계를 검증하고 [검토 수정](../work-items/P01-B4-review.md)을 반영했다. SQL·JUnit·React DOM 단위/E2E 체계·Git 초기화·배포는 후속 작업이다.


