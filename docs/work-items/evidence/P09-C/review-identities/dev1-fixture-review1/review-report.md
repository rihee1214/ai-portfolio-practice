# P09-C dev1 실패 준비 보완 실제 증거 재검토

현재 P09-C이며 dev1은 실패 상태로 보존한다. 이번은 승인된 세 fixture 보완 방향의 실제 assertion과 보존 결과 검토다. 새 하네스/제품변경/커밋/배포 없이 C-review.md에 판정하며, 이 판정 후 새 unselected dev2를 실행할 예정이다. 과거 B 마감으로 돌아가지 않는다.

고정 source414 manifest SHA `D994092EDA68EDFB23F7D783A8B851E111B45DE3260E0FB6E60C903D0D906035`, 복사/current 불일치0. browser-review1 대비 PostgresJdbcIT·required-tests·C작업/검토문서4파일 차이. runtime-freeze1 이후 실제 runtime 변경은 test/required2파일이며 제품·실행 V41~57·프런트·브라우저·승인287 제한은 그대로다. 마지막 focused3 inputs552/current0은 구현자가 확인했고 SHA `6A153776910635B0DD5436C21ABF6D4396D739BE28B0E49934A0FAC8782ABD21` 사본을 보존한다.

## 보완 및 직접 증거

1. activeMember 준비만 실제 SignupService와 전용 LocalTestMailbox를 사용하고 실제 register/verifyEmail을 유지한다. 전용 mailbox는 UUID map에 보존하며 기존 mail(email)의 fixture fallback으로 기존 잘못된 용도/소비된 토큰 검사를 유지한다. 실제 HTTP signup/shared mailbox/cap1000/만료 정리/제품은 변경하지 않는다. 기존 helper를 그대로 둔 isolation-red1은 공유메일함 증가0 기대/실제2로 실패했다. 최종 직접 검사는 실제 비어있지 않은 shared 메시지1을 먼저 만들고, helper 뒤 list내용/객체가 불변인지·전용 token 분리·PENDING→ACTIVE·HTTP login401→200·token 재사용 거부를 확인한다. 새 fixture를 SQL ACTIVE로 주입하지 않는다.
2. C05 cancellationGuestAuthorityAndFinalWaits의 IDLE/최종 잠금3은 실제 guest buyer+tokenhash 귀속의 저장 idle deadline을 읽고+1micro를 주입한다. 초과를 직접 assertion하고 이전 Clock+1800과 저장 값 진단을 보존한다. 실제 권한 포트·404·전체 participant 불변은 유지하며 dev1의 원인을 정밀도로 단정한 채 기대를 완화하지 않는다.
3. p4d V16·p4b V14 이행 준비는 새 독립 DB/default marketplace로 옮긴다. 기존 원SKU/원재고/이력20+5/version/상품 생성 rollback 검사는 유지하고 원 context 데이터 불변도 대조한다. 실제41·43 migration→57/replay0을 확인하며 적용된 SQL 파일은 수정하지 않는다.
4. 보완 후 실행된 기존 catalogProductHttpOwnershipLifecycleAndPublicFilters는 read-only hiddenByAdmin:false 요청에 원200기대/현재strict400으로 실패했다. 기존 이름과 후속 lifecycle를 유지하되 해당 요청400+전체상품불변을 확인하고, 쓰기계약 필드만 보낸 정상PUT200 뒤에도 hidden/public404·stale/storeclosed/rollback을 검사한다. 기존의 조용히 무시200→명시거부400 응답 변화가 보호 계약과 양립하는지 별도 판정한다. 제품 whitelist를 느슨하게 하지 않았다.

## 실제 실행

- dev1 unselected는 unit196/phone1pass, PG355=304pass2failure49error/skip0, browser130pass276090ms, frontend/Node NOT RUN, rootfailed/preservationpassed다. 원문을 보존한다.
- mailbox-isolation-red1 DB1failure/error0/skip0/unit196pass. 새로운 직접 assertion이 기존 공유 누적을 실제 재현했다.
- dev1-fixture-focused1은 dev1의 실패50unique메서드+새직접검사+기존복구 호환을 선택한 실제DB53=52pass1failure/error0/skip0/unit196pass다. 메일함/C05/두이행/HTTPsignup/recovery는 통과, 남은 failure는 위 readonly400 기대 차이다. 준비 오류로 본문이 미실행됐던 실패군을 실제 재수집했다.
- focused2는 catalogDB1/unit196 실패·오류·skip0, 원 lifecycle/숨김/권한 검사를 유지한다.
- focused3는 비어있지 않은 shared 메시지를 강제한 강화 mailboxDB1/unit196 실패·오류·skip0이다.

선택 focused root는 모두 필수 미선택 failed/preservationpassed이며 전체 통과가 아니다. evidence에 원JSON/Maven/XML 및 최상위GAS/mailbox 진단을 복사했다. 자세한 실제 새 DB/자원 경로는 P09-C.md 마지막절·implementation-progress와 원 run manifest를 따른다.

세 준비 보완 및 read-only 응답 기대 변경의 수용 여부를 각각 기록한다. 미흡한 assertion이 있으면 구체적으로 지적한다. 원 R01~10 scoped ADDRESSED와 브라우저130 실제 dev1 통과는 유지하지만 전체 완료로 확대하지 않는다. dev2와 지정독립전체는 아직 NOT RUN이다.
