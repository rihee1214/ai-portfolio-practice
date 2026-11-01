# P09-C backend 제약·검증 대응표 독립 검토

현재 작업은 P09-C다. B 커밋 작업으로 돌아가지 않는다. 이번 요청은 고정 backend source375와 집중 실행의 실제 assertion 범위를 검토하는 것이다. 제품 수정·새 하네스·커밋·배포는 요청하지 않는다. 구현 담당은 브라우저 작업을 계속한다. 최종 선택없는 개발전체 후 별도 독립전체 실행은 여전히 필요하다.

## 고정 소스

source-manifest.json SHA256 `2E0F71B5BB1DD12599E63A2DEFCDFE747B0F80FFA1EC6BE1A7CCE9E44A3871F7`. backend main/test 전체, required 목록·하네스·설계·C/B/A 작업 문서를 포함한다. 복사 당시 현재 파일과 불일치0. HEAD `c9f924123b42e8d413f3ff70a095ffec06fd9593` 유지. 마지막 success-ack-focused1 inputs551/current 불일치0은 구현자가 확인했고 원본 inputs 사본을 포함했다. 부모는 별도로 source375 복사 해시를 대조했다.

이전 command-fix-review1 대비 최근 변경의 중심은 실제 금액·현재 권한·경합·구형 이행·성공 처리 복구 assertion과 fixture다. 적용된 V41~57은 불변이며 최근 묶음에서 제품 정책 변경은 없다. R-C01~08의 scoped ADDRESSED 판정은 유지하고 이번 검토에서 전체 완료로 확대하지 않는다.

## 요청 범위

1. source/docs/work-items/P09-C.md 마지막 대응표(C01~11, §11.5/12.4)가 실제 테스트의 assertion과 실행 증거를 정확히 표현하는지 확인한다.
2. 설계/P08 인계/P09-B 계약의 핵심 제약 중 backend에서 빠진 실제 assertion, SQL 우회·원자성·현재 자격·원금 한도·original shipment fee 누적/미수납·금융 결과와 물리 반품 경계·종결 모순을 찾는다.
3. legacy fixture의 실제 구형 JSON 재구성, 새 독립 DB/default marketplace 이행, 옛 주문 exact retry/미확정 policy·명시동의, 미래 주문 현재 policy의 구분을 확인한다. 과거 바이너리 실행 또는 production 외 비기본 schema 지원으로 확대하지 않는다.
4. 성공 T4의 14개 rollback/실제 저장 lease 검사 및 commit-beforeACK 실제 child 종료/재시작이 중복 환불·재고/쿠폰 복구·종결을 막는 근거인지 확인한다.
5. 발견 사항은 P09-C-review.md에 구체적인 위치·재현 조건·필요 보완으로 기록한다. 최종 전체 재수집과 브라우저 미실행은 알려진 잔여다. 신규 중대사항이 없으면 검토 범위에서만 그 사실을 기록한다.

## 증거와 한계

evidence 디렉터리에 각 root result/inputs/coverage/java-cases 및 DB Maven/XML을 복사했다. 모두 선택 실행이므로 root failed / preservation passed이며 전체 통과가 아니다. 원문 세부 child 로그는 각 `.local-tools/p09-c-<이름>-20261101`과 실행 ledger의 불변 manifest로 연결된다. 전체 artifact-manifest는 이 보고서까지 고정한다.

- money-race-focused2 DB9/unit196 GREEN: 실제 33,001/26,004/무료60,000·원금/배송비·발송 경합·부모 한도·T1 rollback.
- auth-closure-focused2 DB9/unit196 GREEN: member/GAS/SUPPORT 최종 잠금 대기 뒤 만료와 혼합 종결 두 순서.
- fee-policy-focused1 DB6 중5pass/1fixture failure; focused2 컴파일 준비 실패/assertion NOT RUN; focused3 나머지 DB1/unit196 GREEN. 실제 두 원배송8,000·동일 bucket 경쟁·상품 입력/권한·서로 다른 buyer/seller 정책 변경 경합.
- legacy-focused1 DB6=1pass/1failure/4error: 구형 quote를 새 주문에 재사용한 fixture, JDBC JSON operator, 비기본 schema와 명시 marketplace 이행 충돌. 적용 파일은 바꾸지 않았다.
- legacy-focused2 DB5=4pass/1fixture failure: A/B/쿠폰/예약 이행 통과; 새 C child는 nanosecond Draft 준비 오류로 정책 뒤 검사는 미실행.
- transition-focused1 DB5=4pass/1fixture failure: 구형 C child의 동의·실제 금융 적용, 만료 쿠폰, 0원 provider0, 실제 쿠폰 RETURN 무효과 통과. SHIPPING JSON 노드 타입 비교 오류는 focused2 DB1/unit196 GREEN에서 보완. 각 실패 원문은 보존했다.
- success-effect-focused1 DB1/unit196 GREEN: 실제 T3 성공 뒤 ITEM7/SHIPPING7 중간 실패와 최종 저장 lease 만료의 전체 업무 rollback, 같은 결과 재개·replay·최종 상태.
- success-ack-focused1 DB1/unit196 GREEN: 성공 T4 commit-beforeACK 실제 child 종료/lease 뒤 재시작, 기존 금액·재고·이력/Outbox 불변/provider1.

브라우저 C 신규 시나리오와 선택없는 개발전체/지정 독립전체는 아직 NOT RUN이다. 실제 Toss·사용자 직접 확인도 미검증이다. 반품 회수·검수·추가비용 수납 등 D 구현은 범위 밖이다.
