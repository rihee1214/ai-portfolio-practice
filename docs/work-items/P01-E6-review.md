# P01-E6 개발 검토

2026-10-24 / 대상: `347b34b..e314c8d`

판정: **수정 필요(R1)**. 기존 Java 114개는 독립 실행에서도 통과했으나 추가 fixture로 문서상 금지 의존을 허용하는 규칙 누락을 재현했다. 이 세션에서는 제품·테스트 코드를 수정하지 않고 검토 문서와 요약만 추가했다. 개발세션의 수정 및 회귀 검증 후 완료 여부를 판단한다.

## R1 — 업무 api에서 platform.api로의 의존을 허용함 (P2)

위치: `backend/src/test/java/com/example/marketplace/architecture/ModuleRules.java`의 `targetApi` 및 `layer(origin, "api") && !targetApi` 분기.

문서 계약은 업무 모듈 api가 platform 타입에 의존하지 않는 것이다. 그러나 현재 targetApi는 대상의 두 번째 패키지 이름만 검사한다. 대상이 platform.api이면 targetApi=true, targetPlatform=true여서 세 분기 모두 위반을 만들지 않는다. 단방향 의존이므로 순환 검사도 통과한다.

독립 재현은 ignored `.local-tools/e6-review-probe` 아래에만 가상 소스와 실행기를 만들고, 독립 빌드로 생성한 실제 ModuleRules를 호출했다. 제품·저장소 테스트 파일은 바꾸지 않았다.

```java
package probe.catalog.api;
public class Product { probe.platform.api.Shared value; }
```

```java
package probe.platform.api;
public class Shared {}
```

두 클래스 컴파일 성공 후 `ModuleRules.rules("probe")`의 모든 규칙을 평가한 결과: **expectedViolation=true, actualViolation=false**. 이는 보안 권한 우회가 아니라 설계 계약을 놓치는 아키텍처 테스트 결함이다. 현재 업무 모듈0개라 실제 제품 의존 위반이 있다는 뜻은 아니다.

권고: 업무 api의 platform 의존 거부를 targetApi 여부와 별도로 검사하고, 최소한 `catalog.api → platform.api` 음성 fixture를 추가한다. `catalog.api → platform.web` 거부와 `catalog.application → platform` 허용도 함께 확인하면 보완이 과도한 제한으로 이어지는 것을 방지할 수 있다. 기존의 다른 업무 모듈 api 허용 검사는 유지한다.

## 독립 검증과 범위

[검토 요약](evidence/P01-E6-review-summary.json): 별도 `.local-tools/e6-review-build`에서 **114개 통과**, 실패·오류·skip0, Checkstyle·컴파일·패키징 성공. 최초 오프라인 실행은 지정 로컬 캐시에 ArchUnit이 없어 의존성 해석 단계에서 중단됐으며 테스트 실패로 계산하지 않는다. 고정 의존성을 같은 캐시에 내려받도록 재실행한 뒤 전체 검증이 성공했다. 추가 R1 probe는 Maven 114개 집계와 별도다.

실제 main CodeSource 경로 import, 비어 있지 않음·진입점 존재·현재 검사 클래스 제외 단언을 확인했다. fixture가 같은 ModuleRules를 호출하고 API 경유 순환을 검사하는 구성도 적절하다. test scope 의존이며 제품 Java·설정에는 diff가 없다. 개발세션의 RED·배포 JAR 기록은 검토했고 RED와 패키지 검색은 독립 재실행하지 않았다.

## 비차단 관찰·한계

- 현재 main은 진입점/platform뿐이다. 실제 검사 통과가 미래 업무 모듈 경계의 완성이나 MSA 성과를 뜻하지 않는다.
- 기술 패키지 금지는 나열된 패키지에 한정된다. fixture는 JDBC·제네릭 JDBC·Principal 사례를 검증하며 Servlet/Spring의 각 금지 접두사를 모두 개별 재현한 것은 아니다.
- 루트 클래스는 문서대로 slice 밖이고 다른 루트 패키지·리플렉션·문자열·런타임 배선·SQL 소유권은 보호하지 않는다. 루트에 업무 클래스를 놓는 우회까지 막는 패키지 배치 규칙은 현재 없다.
- domain→application 의존은 현재 규칙이 금지하지 않는다. 문서는 domain→web/infrastructure 금지로 한정하므로 이번 결함으로 분류하지 않지만 전체 계층 방향을 검증했다고 표현하면 안 된다.
- 사용자 직접 실습·이해와 프런트·실제 DB 연결은 이번 미검증 범위다.

## 보존

검토 후 dev/test를 읽기 전용 pg_dump로 확인한 정규화 해시는 기존 기록과 동일했다. 무작위 restrict/unrestrict 토큰을 제외했고 원문은 저장하지 않았다. DB 컨테이너 ID·healthy 및 15432/15433 PID57168이 유지된다. 기존8080/4174/5174 앱 리스너는 없으며 임의 실행·종료·재시작하지 않았다. DB 쓰기·생성·초기화·다음 단계·업무 구현·push·커밋은 수행하지 않았다.

## 개발세션 후속 반영

위 판정은 e314c8d에 대한 독립 검토 당시 기록이다. 개발세션이 R1 재현 검사 실패를 확인하고 규칙을 수정했으며 platform.web 거부/application→platform 허용 사례를 추가했다. 수정 후 전체117개 verify가 통과했다. [완료·수정 증거](P01-E6.md#독립-검토-r1-반영과-최종-완료)를 따른다. 수정 후 검증은 개발세션 실행으로, 독립 검토 재실행 결과와 구분한다.
