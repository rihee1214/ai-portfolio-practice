# P01-E5 개발 검토

2026-10-24 / 대상: `83e427b..a36d72a`

판정: 현재 Actuator 기본 접근·노출 정책 범위에서 독립 검토 통과. 필수 수정 결함은 발견하지 않았다. 이 세션에서는 제품·테스트·상태 문서를 수정하지 않고 검토 문서와 요약만 추가했다. 최종 완료 기록·로컬 커밋은 개발세션에서 처리한다.

## 독립 실행

기존 로컬 JDK/Maven 캐시와 별도 `.local-tools/e5-review-build`에서 DB 통합 프로필 없는 verify를 실행했다. [검토 요약](evidence/P01-E5-review-summary.json): **99개 통과**, 실패·오류·skip 0, Checkstyle·컴파일·패키징 성공. 기존 83개와 신규 Actuator 검사 16개를 포함한다. RED 및 최초 E4 변환 오류는 개발세션 로그를 검토했으며 재현하지 않았다. Node 68개는 개발세션 증거를 검토했고 여기서 반복하지 않았다. 브라우저·실제 DB 연결 검사는 실행하지 않았다.

검토 시작 시 변경은 stage 상태였고 이후 a36d72a로 커밋됐다. 최종 커밋 diff와 직접 읽은 제품·테스트가 일치함을 확인했다. 중간 커밋 시점 차이를 미구현으로 판단하지 않았다.

## 구현·검사 판단

| 항목 | 검토 결과 |
| --- | --- |
| 최소 정책 | 제품 변경은 기본 access=none, health access=read-only, JMX exposure exclude=* 세 설정이다. 기존 HTTP exposure=health·discovery/details/components/probes 설정은 유지한다. |
| 접근과 노출 | 새 테스트만 HTTP include=*로 넓힌다. 그 상태에서도 관리 경로들이404인 결과는 제품의 health-only 노출 목록에만 의존하지 않고 접근 기본 거부가 적용되는 증거다. |
| health 유지 | 실제 GET200/status-only·HEAD200/빈 본문·POST405/Allow와 ID 헤더 존재를 검사한다. 기존 health DOWN503·요청 ID 회귀도 모두 통과했다. |
| JMX | JMX를 활성화한 테스트에서 전용 domain의 MBean이0개다. 테스트 종료 시 컨텍스트를 닫는다. JVM platform MBean 전체 부재나 원격 JMX 보안 검증으로 확대하지 않는다. |
| heapdump | HTTP dump를 호출하지 않고 heapDumpWebEndpoint bean 부재를 확인한다. 현재 Boot 버전의 bean 이름에 의존하므로 버전 업그레이드 시 이 단언의 유효성도 확인해야 한다. |
| E4 테스트 보정 | ApplicationContextRunner의 Environment에 ApplicationConversionService를 적용하여 실제 SpringApplication의 소문자 enum 변환과 맞췄다. 필수 설정 검사를 제거하거나 제품 설정을 우회하지 않았으며 E4 14개가 모두 통과했다. |

## 비차단 관찰·한계

- POST405는 원래 읽기 연산인 health의 계약 보존 증거다. read-only 값만 제거했을 때 반드시 실패하는 독립적인 검사라고 해석하지 않는다. health 예외 허용과 나머지 기본 거부는 설정 및 전체 컨텍스트 결과를 함께 확인했다.
- HTTP 관리 경로 목록은 대표 사례이며 향후 모든 사용자 정의 endpoint를 열거한 검사가 아니다. JMX도 현재 전용 domain에 한정된다. 새로운 endpoint·별도 관리 포트·보안 설정 도입 시 범위를 다시 검토해야 한다.
- 환경이나 명령행에서 설정을 덮어쓰는 것을 막지 않는다. 인증·인가·관리망·방화벽 구현이 아니며 운영 배포 보안 완료로 표시하면 안 된다.
- 그룹을 추가하지 않은 결정은 현재 probe 소비자가 없는 단계에 부합한다. 현재 UP은 업무 거래·DB 전체 정상 보증이 아니다. 실제 DB 프로필 연결과 운영 JMX 원격 접속은 미검증이다.
- 사용자 직접 실습·이해는 미확인이다. ArchUnit 등 다음 작업은 시작하지 않았다.

## 보존

검토 후 기존 dev/test를 읽기 전용 pg_dump로 확인했다. 무작위 restrict/unrestrict 토큰을 제외한 해시가 개발세션 기록과 동일하며 원문은 저장하지 않았다. DB 컨테이너 ID·healthy와 15432/15433 PID57168이 유지된다. DB 쓰기·생성·초기화·재시작은 하지 않았다.

기존8080/4174/5174 리스너는 이전처럼 없었으며 임의 실행·종료·재시작하지 않았다. 소유 RANDOM_PORT 테스트 서버만 사용했다. 제품 수정·프런트 단순화·다음 단계·push·커밋은 하지 않았다.
