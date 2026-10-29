# P08 최종 단계 간 독립 검토

2026-10-28. 별도 검토자 p08_final_broad_review의 읽기 전용 정적 검토. 검사/하네스/소스 변경 없음. 기준 HEAD460aa8bb0bb22f5138849eb00c050abd18f6fb53, 동결472입력 SHA690D1D23868242821F8C2554463A50C425D7CD49D11FD001B174ADDA8A3C2658. 아래는 당시 미해결 지적이며 실행 재현·수정 결과는 후속 절에 기록한다.

## R-FINAL-01 (P2) 결제 화면의 인증 변경 후 개인 상태 잔류

PaymentStore.ts:14,35,44 / PaymentPage.tsx:7,9,15. 회원 A 결제 화면을 연 뒤 다른 탭에서 로그아웃/계정 전환하면 AuthProvider BroadcastChannel이 인증을 무효화하나 PaymentStore는 구독·view폐기·generation검사를 하지 않는다. number만 key로 쓰는 panel은 유지되어 이전 금액/결제상태를 표시한다. owner불일치/401도 blocked만 설정하고 view는 유지한다. 수령인 상세 노출은 아니며 이전 결제정보 잔류 문제다. current()는 이후 명령 일부만 막고, 같은 identity 로그아웃/재로그인 사이의 예전 응답도 구분하지 못한다.

필요 assertion: 표시중 인증무효화 즉시 개인정보0; A요청→인증변경→늦은성공/실패 재노출0; 같은identity의 새세대도 예전응답거부; 자동승인재전송0; 실제 panel DOM 검사. 정적 지적이며 원본검토자는 실행재현하지 않았다.

## R-FINAL-02 (P2) 결제 준비의 마지막 잠금 대기 후 권한 만료 재검사 누락

PaymentCoordinator.java:80 / JdbcOrders.java:138–142. 회원 HTTP session은 주문잠금 뒤 확인되나 validate(order,true)가 쿠폰/재고 행을 기다린 뒤 세션 자연만료를 재검사하지 않고 PROCESSING/제공자승인으로 진행할 수 있다. EXISTING guest 자격도 주문잠금 전만 검사하고 주문잠금 뒤 HTTP session만 다시검사하여 guest max기한이 지날 수 있다. 세션행 잠금은 동시변경을 막지만 시간경과는 막지 못한다.

필요 assertion: 실제 reservation행 대기 관측→회원session기한넘김→잠금해제→거부/결제·provider효과불변; EXISTINGguest order행 대기→guest max만료(HTTPsession유효)→조회/승인거부. worker의 현재고객권한과 독립적인 정상 settlement복구는 유지한다. 정적 control-flow 근거이며 원본검토자는 실행재현하지 않았다.

## 다른 검토 범위와 보류

불변금액/수량, 구매 잠금순서, 예약/Outbox거래, UNKNOWN보존·대사, 보상주문의배송차단,0원원자성,실제PAYMENT_SETTLED소비,기본배송dedupe/backfill,판매자최소projection,새브라우저조회,배송UI폐기에서는 추가구체결함을 발견하지 않았다. 이는 실행통과를 대신하지 않는다.

P09 분할배송/클레임·환불, 운영모니터링, 실제택배, 새브라우저guest승인은 요청범위 밖으로 판단했다. 실제Toss SANDBOX/카드webhook 계약은 외부차단이며 이전phonefetch원인은미확정이다. 현재2지적해소+새개발/독립전체 전에는 로컬최종완료를판정하지 않고, 실제PG차단 때문에 P08전체완료도 선언하지 않는다.

## 단일 수정 묶음의 독립 재검토 (2026-10-28)
R-FINAL-01/02의 검토된 구현과 집중 실행에서 해소 확인, 잔여 지적 없음. PaymentStore는 인증변경 즉시개인정보폐기/요청abort/generation검사를하고늦은401이새인증에영향을주지않는다. 같은identity재로그인도세대분리하며freshGET뒤명시승인만허용,자동POST0·실제DOM/StrictMode검사유지. 서버는예약대기뒤현재권한재검사,EXISTINGguest는주문잠금뒤자격재검사를하며기존거래가보유한행만다시검사한다. worker settlement의고객인증독립성유지. guest최대기한검사는정상활동갱신으로max−1초까지유효/idle=max인상태에서실제주문대기를관측하고max를넘겼다.
집중2 단위183/선택Postgres17/browser106 모두통과(정밀max2/새인증브라우저2포함). 선택root의과거필수누락실패는전체통과가아니다. 수정후새개발·독립전체가아직필요하다. 집중1쿠폰재조회네트워크실패는원인미확정으로보존하며후속통과를원인해결로표시하지않는다. E실제PG는외부차단/P08전체미완료. 재검토자는읽기전용으로검사/소스변경/외부동작없음.

## 수정 후 전체 실행 인수
2026-10-28 G개발2(22:42:46~22:52:28)/독립1(22:55:23~23:04:52) 선택없는전체모두통과:Java451/browser106/frontend216/Node75·필수50묶음·보존통과. source472 A20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF 동일,32manifest65820파일불변. P08-G-review.md에독립실행·제약assertion·자원/해시근거연결. R-FINAL01/02 로컬최종해소, E/G05실제PG차단과원인미확정네트워크이력은유지.
