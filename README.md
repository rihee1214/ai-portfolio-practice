# AI 개발 연습용 마켓플레이스

검증 진입점: 루트에서 `./verify.ps1 -Offline`. JUnit·ArchUnit·실제 DB·프런트 단위·브라우저 검사를 통합한다. 이후 검토와 완료 판정에는 [하네스 선행 규칙](docs/verification-harness.md)을 적용한다. 도입과 실행 결과는 [HARNESS-01](docs/work-items/HARNESS-01.md)을 따른다.

모바일 lifecycle 시간 초과(R-H01)는 목록 페이지 탐색 누락을 수정하고 첫 페이지를 채운 회귀 조건에서 해결했다. [최신 하네스 검토](docs/work-items/HARNESS-01-review.md)는 기본137·DB90·브라우저22·프런트96·HTTP/프록시68과 보존 검사 통과다. P04-E 구현·검증은 진행하지 않았다.

최신 작업은 [P04-D SKU 재고](docs/work-items/P04-D.md)이며 새 격리 DB 검증과 [독립 검토](docs/work-items/P04-D-review.md)를 완료했다. 기본137·DB90·브라우저22·프런트96개 통과, 필수 잔여/차단 없음. 기존 서버·DB·검증 DB·미디어를 보존했다. P04-E 이후는 미착수다. [P04-C 완료 기록](docs/work-items/P04-C.md)과 아래 수치는 당시 이력이다.

현재 P01 종합 점검 결과는 [완료 점검 기록](docs/work-items/P01-completion-audit.md)을 기준으로 한다. [P02 전체 완료 점검](docs/work-items/P02-completion-audit.md)의 R1을 실제 재현·수정·독립 검증해 **P02 계획 범위의 구현·검증을 완료**했다. [P03-A](docs/work-items/P03-A.md)의 모의 판매 신청·심사·이력과 승인 시 판매자 자격 추가를 구현했다. 독립 검토 결과는 [검토 기록](docs/work-items/P03-A-review.md)을 따른다. [P03-B](docs/work-items/P03-B.md)의 매대 기본 관리·공개 조회를 구현·검증하고 독립 검토를 완료했다. [P03-C](docs/work-items/P03-C.md)의 판매 정지·복구·폐점과 기능별 제한을 구현·검증했다. 지정 [검토 세션의 독립 검증](docs/work-items/P03-C-review.md)까지 완료했다. 다음 단계는 진행하지 않는다.

익숙한 Java·Spring 경험을 활용하고 React를 배우면서 AI와 함께 유지보수 가능한 CRUD 마켓플레이스를 만드는 포트폴리오 프로젝트다. 개발자가 생성된 코드의 흐름을 이해하고 직접 수정·검증하며, 구매·판매·사이트 운영 기능을 완성한 뒤 재현한 문제를 근거로 MSA로 발전시킨다.

회원과 비회원은 상품을 구매한다. 판매자는 자신의 매대에서 상품과 행사를 운영하면서 구매자 역할도 한다. 운영자는 대시보드와 관리 화면에서 사이트를 관리하며 문제 상품·리뷰·댓글을 플래그로 비공개 처리한다.

## 설계 문서

설계 v4 Java·Spring Boot + React, 개정일 2026-10-21. 목적·목표를 다시 정리하고 기존 F01~F18의 기능·정책과 AC01~AC20의 완료 기준을 유지했다. 다음 순서로 읽는다.

| 문서 | 내용 |
| --- | --- |
| [목적·목표와 요구사항](docs/requirements.md) | AI 개발·React 학습·유지보수·MSA 목표 G01~G10, F01~F18 기능 범위, AC01~AC20 완료 기준 |
| [전체 기술 설계](docs/shop-design.md) | 기술 선택, 화면·권한, 모듈·데이터, 거래 상태, API·검증·운영 |
| [ADR-001 현재 기술 선택 근거](docs/decisions/ADR-001-java-spring-react.md) | Java·Spring Boot·React·TypeScript·Vite·PostgreSQL 등의 역할·이유·장단점·대안·버전 정책 |
| [ADR-002 Docker PostgreSQL 구성](docs/decisions/ADR-002-docker-postgresql.md) | PC 직접 설치 없이 DB 실행, 환경 재현·애플리케이션과 DB의 실행 분리·3계층 학습, 한계와 검증 계획 |
| [단계별 구현 계획](docs/implementation-plan.md) | P01~P14 단계, 작은 작업, 의존 관계, 결과물과 검증 |
| [MSA 전환 설계](docs/msa-evolution.md) | 문제 재현, 대안 비교, 분리 절차, 일관성·복구·전후 평가 |
| [개발 작업 흐름](docs/development-workflow.md) | 새 작업 창 요청문, 설계→구현→검증→기록, 인수인계 |

## 주요 기준

- Java·Spring Boot·PostgreSQL 기반 모듈형 모놀리스 API와 React 화면으로 시작한다. Java·Spring Boot·React는 사용자 확정 선택이며 세부 도구의 근거는 새 ADR-001에 기록한다.
- 화면은 React·TypeScript·Vite·React Router·일반 CSS, DB 접근은 Spring JDBC, DB 변경은 Flyway로 관리한다. Node.js·npm은 React 개발·검증·빌드에 사용한다.
- 개발·테스트용 PostgreSQL은 PC 직접 설치 없이 Docker Compose로 구성한다. 환경 재현과 애플리케이션·DB의 독립 실행, 3계층의 통신 경계 학습이 목적이다. 같은 PC에서 실행 환경을 분리하는 계획이며 별도 물리 서버 구성은 아니다. [선택 이유와 구성 원칙](docs/decisions/ADR-002-docker-postgresql.md)
- 한 저장소의 `backend/`와 `frontend/`로 관리한다. 개발 중에는 Vite의 API 프록시, 공개 환경에서는 같은 출처의 화면·API 경로를 사용한다.
- 상태 확인은 Spring Boot Actuator의 `/actuator/health`를 사용한다. 현재 HTTP에는 health만 노출하고 응답은 status로 제한한다. B4에서 이 경로만 개발 프록시로 연결한다.
- 회원 인증은 Spring Security·Spring Session JDBC의 쿠키 세션과 CSRF 방어를 사용한다. Java 검사는 JUnit Jupiter·MockMvc·실제 PostgreSQL, 화면 검사는 Vitest·React Testing Library·Playwright Test로 나눈다.
- 비회원은 쿠폰 사용 불가, 공개 이벤트 적용 가능이다.
- 비회원은 이름·휴대전화 인증 후 주문 조회 비밀번호를 직접 설정한다.
- 무료 전화 인증 테스트는 Firebase Authentication Emulator를 우선 사용하며 실제 문자는 보내지 않는다.
- 테스트 결제·배송·정산을 제공하고, 실제 판매 운영은 별도 범위다.
- 상품 옵션·이미지, 취소·반품·교환·부분 환불, 문의·신고·모의 정산까지 전체 기능에 포함한다.
- 기본 서비스 완성 후 재현한 문제와 측정 결과를 근거로 한 영역씩 MSA로 전환한다.

## 진행 상태

P01 기반 구현과 단계별 검증 기록을 종합 점검했다. 당시 재검증은 Java 117개, Vitest 47개, Node 68개, Playwright 16개와 타입·린트·빌드였다. 실제 DB 통합은 후속 보완에서 당시 최신 코드12개를 재검증했다. E4·E5 조합과 [P01 결과](docs/work-items/P01-db-followup.md)를 확인한다.

화면은 역할별 공개 틀이며 로그인·상품·주문·권한 기능은 아직 없다. health는 정상 200·UP / 장애 503·DOWN의 status 단일 필드 계약을 유지한다. 현재 실행 방법은 [로컬 실행 안내](docs/local-development.md), 미검증과 제한은 [종합 점검](docs/work-items/P01-completion-audit.md)을 따른다.

P08 구매 완료, P12 전체 기능·유지보수, P14 MSA 전환은 후속 목표다. P02-D까지 회원·복구·프로필·주소·역할/운영 권한과 정지·탈퇴를 연결했다. 최신 R1 Java130개·DB통합48개·인증/계정/권한 브라우저12개, Vitest73개·기존 브라우저16개가 통과했다. 지정 검토 세션의 새 DB 독립 검증과 [전체 완료 기록](docs/work-items/P02-completion-audit.md)을 따른다. React73·P01 브라우저16은 D 당시 기록이며 R1에서는 변경이 없어 반복하지 않았다. Node68개는 P02-B 기록이며 C·D에서는 전송 코드 변경이 없어 재실행하지 않았다. 외부 메일은 실제 발송하지 않으며 기존 DB와 서버를 보존했다. P02-D 다음 단계는 진행하지 않았다.
