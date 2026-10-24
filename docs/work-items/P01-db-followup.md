# P01 최신 db 프로필 통합 보완 검증

2026-10-24 / 기준 67960e7 / 상태: 구현·검증·독립 검토 완료

## 범위와 실행 계획

P01 종합 점검에서 남은 최신 실제 PostgreSQL 통합을 보완한다. 기존 D2 보호 절차를 유지하고 새 d2_it UUID DB만 생성한다. 기존 서버·컨테이너·DB를 중지·초기화·재시작하지 않는다. P02와 새로운 업무 기능은 제외한다.

1. 시작 시 리스너·컨테이너·기존 개발/테스트 DB 해시·DB 목록을 기록한다.
2. 기존 PostgresJdbcIT를 loopback RANDOM_PORT로 바꿔 실제 db,c3-fixture 컨텍스트에서 E4·E5를 함께 확인한다.
3. 기본117개와 실제 JDBC 기존8개 + 조합4개를 새 DB에서 실행한다. 제품 코드 변경이 없는 검증 추가이므로 미구현 기능의 RED라고 주장하지 않는다.
4. DB 보존 상태와 신규 잔여 DB를 기록하고 검토 및 반영세션의 독립 검토를 받는다.

## 추가 검사 계약

- E4 필수 설정 post-processor가 활성 상태이며 실제 DataSource 설정·DB health contributor가 함께 생성된다.
- 필수 url/username/password 각각 공백이면 실제 DataSource/Flyway 자동 구성을 제외하지 않은 별도 컨텍스트에서도 E4 진단으로 실패하고 DataSource singleton·probe는 생성되지 않는다. 별도 컨텍스트는 신규 DB 생성 초기화기를 사용하지 않는다. 암호는 실제 비밀값 대신 합성값이며 연결 시도 전에 거부되어야 한다.
- 실제 DB 연결 상태에서 GET health는 200·status UP만, HEAD는 본문 없음, POST는405, 요청 ID는 UUID v4다.
- db 프로필에서도 관리 경로·DB health 상세·probes는404, JMX를 켜도 전용 관리 domain 등록0, heapdump bean 없음.
- 기존8개 JDBC 검사는 실제 PostgreSQL18, Flyway V1/V2 반복, 매핑·커밋·제약·checked/REQUIRED/중간 실패 롤백을 독립 연결로 확인한다.

기본 검사에서 E4의 누락/미해결 placeholder와 E5의 web exposure 확대 방어도 함께 실행된다. 이번 통합은 기본 health 노출 설정을 그대로 사용하며 JMX만 테스트에서 켠다. 실제 DB 중단을 유도하지 않으며 기존 장애 health 계약은 기본 회귀에서 확인한다.

## 실행 및 증거

프로젝트 루트 PowerShell에서 다음을 실행했다. 스크립트가 로컬 JDK·Maven과 테스트 자격을 프로세스 범위에 설정하고 복원하며 로그의 암호를 가린다.

```powershell
./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p01-db-followup -Offline
./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p01-db-followup-final -Offline
```

첫 실행은 기본117·통합11 모두 통과했다. 최종 실행은 E4 자동 구성 활성 상태의 설정 실패 검사1개(세 필수 키)를 추가한 대상이다. 실행 결과·보존·독립 검토는 아래 후속 기록으로 확정한다.

## 제한과 학습

새 PC·다른 OS·배포·실기기·추가 브라우저는 이번 범위 밖이다. 프런트는 수정하지 않아 앞선 P01 감사 결과를 재사용하며 새로 실행했다고 계산하지 않는다. db 프로필 검증 성공은 기존 사이트 서버 가동 성공이나 업무 CRUD 완료가 아니다.

흐름: 대상 보호 → 새 DB 생성 → E4 설정 검사 → DataSource/Flyway → JDBC 트랜잭션과 E5 HTTP 정책. 단위 검사는 개별 규칙을, 이번 통합 검사는 함께 켠 구성을 확인한다. 작은 실습은 테스트 목록에서 db 상세 경로가 왜404인지 설정과 비교하는 것이다. 사용자 직접 실습은 미확인이다.

## 최종 개발 검증 결과

[정상 로그](evidence/P01-db-followup-verified-maven.txt), [집계](evidence/P01-db-followup-verified-summary.json): 기본117개·실제 DB 통합12개, 실패/오류/skip0, Checkstyle·컴파일·패키징 모두 통과했다. 최종 명령은 `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p01-db-followup-verified -Offline`이다.

[첫 실행](evidence/P01-db-followup-first-summary.json)은117+11 통과였다. [중간 실패](evidence/P01-db-followup-failed-summary.json)는117 통과·통합12 중1 ERROR다. E4가 의도대로 시작을 거부한 후 테스트가 실패 컨텍스트의 getSourceApplicationContext에 접근한 테스트 오류였다. 생성 전 관찰자를 초기화 시점에 등록해 DataSource/Flyway 생성 시도 자체를 기록하도록 수정했고 최종12개가 통과했다. 제품 결함·연결 실패 또는 의도적 RED로 표시하지 않는다.

신규 DB는 각 실행의 [첫 대상](evidence/P01-db-followup-first-target.txt), [중간 대상](evidence/P01-db-followup-failed-target.txt), [최종 대상](evidence/P01-db-followup-verified-target.txt)에 기록했다. 모두 새 이름이며 삭제하지 않는다. 루트 .env는 읽기만 했고 암호는 기록하지 않는다. 증거는 스크립트의 비밀값 치환 로그와 집계만 복사하며 원본 XML은 로컬 빌드 디렉터리에 둔다.

## 독립 검토와 보존·완료

[검토 및 반영세션](P01-db-followup-review.md)은 필수 수정 결함 없이 통과했다. 별도의 새 격리 DB에서 기본117·통합12개를 독립 재실행해 실패/오류/skip0을 확인했다. [독립 집계](evidence/P01-db-followup-review-summary.json), [로그](evidence/P01-db-followup-review-maven.txt), [대상](evidence/P01-db-followup-review-target.txt)을 보존한다.

이번 생성 DB는 개발3개·검토1개, 총4개다. 모두 삭제하지 않았다.

- d2_it_204ff6a7b1304b6f9dac8d2352a0623d: 최초117+11 성공.
- d2_it_62470ff6f020486aadb61008e361e430: 테스트 관찰 오류 실행.
- d2_it_8af65276aade41f9a464f28e76fbe1ba: 최종117+12 성공.
- d2_it_f4d47cbfa4604a0f8f9f7a6a0c68fe86: 독립117+12 성공.

[시작](evidence/P01-db-followup-before.json)과 [검토 후 종료](evidence/P01-db-followup-after.json)를 비교해 기존 marketplace_dev/test의 정규화 덤프 해시가 각각 동일함을 확인했다. 두 컨테이너 ID·healthy·DB 포트 PID57168도 유지됐다. DB 목록에는 위4개만 추가됐으며 기존 DB 삭제는 없다. 기존8080·4174·5174 리스너는 시작/종료 모두 없었고 임의 기동하지 않았다. 기존 과거 격리 DB 전체의 내용 해시는 이번에 계산하지 않았으며, 해당 DB를 대상으로 쓰는 절차도 실행하지 않았다.

P01 감사의 최신 DB 조합 미검증 항목은 이번 범위에서 해소했다. 현재 차단 항목은 없다. 생성 전 관찰자는 현재 dataSource/flyway bean 이름에 대한 것이며 모든 소켓 연결을 외부 계측한 증거는 아니다. 실제 DB 중단/복구·운영 인증·업무 CRUD·새 PC 재현까지 완료한 것으로 확대하지 않는다. 제품 코드·설정·마이그레이션·기존 health 계약은 유지했다. P02·push는 진행하지 않았다. 코드·작업/검토 기록·정제된 증거·현행 안내를 최종 로컬 커밋으로 보관한다.
