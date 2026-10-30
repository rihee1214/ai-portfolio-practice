# P09-B 로컬 커밋 마감 — 2026-10-30

사용자의 로컬 커밋 요청에 따라 P09-B 완료 기록과 검증 근거를 정리한다. 현재 브랜치 `codex/p05-d`의 기준 HEAD는 `ed96936740a6295affb30c5c6046ca3a3553513a`다. 아직 커밋되지 않은 선행 P09-A 배송 구현·검토 기록과 P09 계획 문서를 B 의존 기반으로 함께 보존한다. P09-C~G 문서는 기존 계획이며 구현을 시작하지 않는다. 푸시·배포는 하지 않는다.

## 완료 범위와 실제 검증 연결

[B 작업 기록](P09-B.md)과 [독립 최종 검토](P09-B-review.md)의 B01~09/R-B01 제약→assertion→실제 실행 매핑을 따른다. 직접/자동 구매 확정·원단위 보류/해제/환불 사실·원배송+저장7일 정책·기존 데이터 이행·worker fencing·전체 확정 시 CLOSED·현재 권한/합법 재시도 보존이 승인된 로컬·모의 범위다. R-B01의41자 실패를 V40과40/41/200/201자 실 DB 검사로 해결했다.

개발2와 독립1은 같은496파일, 입력 SHA256 `238FDB81F428062815D49246B716536420304B9EAC95DDEF27F7F6F7340C5FD4`에서 각각 Java488·브라우저118·프런트234·Node75·필수52묶음·보존을 통과했다. 개별 기본30초/기존 catalog60초·workers1/retries0·필수 이름/assertion을 유지하고 검사 증가와 비용 측정 후 child 전체 예산만260초로 조정했다. 실제 개발252.477초·독립252.205초로 원240초를 각각12.477/12.205초 초과했다. 이를 성능 개선이나240초 충족으로 표현하지 않는다.

## 커밋 전 새 전체 검증

명령은 `./verify.ps1 -Offline -EvidenceDirectory .local-tools/p09-b-commit1-20261030`이다. 선택/진단 환경변수를 제거하고 새 DB·미디어·전화 에뮬레이터로 실행한다. 실행 완료 결과와 보존/소스 대조는 후속 기록에 추가한다. 이 절 작성 시점에는 실행 중이며 커밋 전 검증을 통과했다고 주장하지 않는다.

### 최종 실행 결과

위 실행은2026-10-30 **23:30:49~23:44:00 KST, exit0/status passed/preservation passed**로 종료했다. Java488(기본191·DB296·전화1), 브라우저118, 프런트234, Node75 및 필수52묶음·타입·린트·빌드·gate 회귀 검사를 통과했다. 실패·오류·skip·미수집은 없다. 브라우저 child252053ms는 원240초보다12053ms 초과·현260초보다7947ms 짧다. 이는 운영 예산 안의 통과이며 제품 성능 개선을 주장하지 않는다.

커밋 전496파일은 개발2·독립1의 위 입력 SHA와 동일하고 실행 후 해시 불일치0·입력 추가/삭제0이다. [실행 결과](evidence/P09-B-commit-final-result.json), [소스 대조](evidence/P09-B-commit-final-source-verification.json), [Java 필수](evidence/P09-B-commit-final-coverage.json), [브라우저 필수](evidence/P09-B-commit-final-browser-coverage.json), [프런트 필수](evidence/P09-B-commit-final-frontend-coverage.json), [DB 결과](evidence/P09-B-commit-final-db-summary.json), [브라우저 실제 시간](evidence/P09-B-commit-final-browser-child-timing.ndjson)을 남긴다. 새 전화 제공자 증거는 `.local-tools/phone-emulator-89d349ae1dab42fdaa86b482104f200e`다. 이번 커밋 전 실행은 첫 시도에 통과했고 새 실패는 없다.

선행 A 독립·B 개발/독립·이번 커밋 전 실행의 핵심 결과 및 A/B 실패 manifest 등 **111개 원문 복사본**을 [증거 인덱스](evidence/P09-B-commit-evidence-index.json)의 원본경로/복사경로/SHA로 연결했다. 과거 실패·실험 **85643파일 누락/해시불일치0**은 [보존 감사](evidence/P09-B-commit-final-failure-preservation.json)에 기록했다. 기존 tracked 증거 파일은 수정하지 않았다. 상세 소스·검증 계약은 독립 검토의 B01~09/R-B01을 유지하며 제품 코드는 이번 마감에서 추가 변경하지 않았다.

## 보존과 제한

기존 자원과 과거 실패·미완료·미채택 실험을 보존한다. 증거 파일은 원문 복사와 SHA 대조로 기록하며 공백 정규화로 바꾸지 않는다. 전체 로그·새/과거 검증 DB·미디어는 `.local-tools` 및 기존 DB에 그대로 유지하고, 저장소에는 핵심 결과·필수 목록·소스·보존 감사와 실패 manifest를 남긴다.

실제 Toss SANDBOX는 기존 외부 차단 상태다. 사용자 직접 확인·실기기·상용 부하는 미검증이다. 실제 취소/환불 결제·재고/쿠폰 처리·혼합 REFUNDED 재무종결·반품/교환 완료·대사는 후속 범위다. EXCHANGE_COMPLETED는 현재409·보류 유지다. C를 시작하지 않는다.

이 문서를 포함하는 로컬 커밋의 식별자는 Git 이력에서 확인한다. 과거 문서의 '커밋하지 않았다'는 당시 상태이며 이번 후속 요청의 로컬 마감과 구분한다.

## 커밋 내용 점검

새 P09 증거 복사본은 `.gitattributes`의 해당 경로만 `-text`로 지정해 Git 줄바꿈 변환 없이 원문 바이트를 보존한다. 기존 증거와 제품 파일의 속성은 변경하지 않는다. 원문 CRLF를 인정한 staged 공백 검사에서는 증거 로그에만12건이 있으며 소스·문서 경고는0이다. 원문 보존을 위해 로그 EOF/공백을 정리하지 않았다. 이는 필수 하네스 실패가 아니라 증거 원문 서식 기록이다. 초기 staged 바이트 비교 명령의 Windows 역슬래시 경로 오류는 Git용 슬래시로 바꾸어 재검사했다. 원문 복사본111개는 SHA256과 staged Git 바이트가 모두 일치하며 제품·증거 파일을 수정하지 않았다.
