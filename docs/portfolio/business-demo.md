# 실제 업무 시연과 화면 기록

이 시연은 [P12-E](../work-items/P12-E.md)의 독립 컨테이너 재현에서 실제 실행한 로컬·모의 업무다. 새 public-demo Compose의 desktop/mobile 브라우저2개가 집중 검증을 통과했다. 현재 전체 검증과 독립 검토는 진행/대기 중이며 P12-E 전체 수용 판정은 아니다. 초대 접근 제한과 개별 앱 로그인을 모두 사용하며, 화면과 같은 업무 ID의 API 응답·DB 상태·후속 소비를 연결한다. 실제 결제·송금·공개 서비스·실기기 결과로 확대하지 않는다.

## 준비와 사용자 명령의 경계

실행 도구·소스·이미지·빌드 산출물과 비밀 공급 방법은 [실행 안내](../local-development.md)를 따른다. 기존 DB 복사 없이 새 DB를 생성하고 Flyway 이행을 확인한다. 합성 계정과 최초 운영자 준비는 시연 전제조건으로 표시한다. 상품·주문·배송의 최종 상태를 SQL로 주입하여 사용자 명령을 수행한 것처럼 제시하지 않는다.

| 역할 | 사용자 명령 | 관측할 생산→소비 계약 |
|---|---|---|
| 판매자 후보 | 개별 로그인 후 판매 신청 | 실제 신청 API와 DB 접수 → 운영자 심사 대상 |
| 운영자 | 신청 확인·승인 | 권한 있는 승인 명령 → 신청 상태/매대 권한 → 판매자 화면 |
| 판매자 | 상품 준비·판매 | 상품/옵션/재고 API 생산 → 공개 상품과 구매 가능 상태; API 준비와 UI 명령은 구분 |
| 구매자 | 상품 선택·주문·모의 결제 | 서버 견적 → 주문 금액 snapshot → 승인 효과와 후속 배송/알림 |
| 판매자 | 해당 주문 발송 | 자기 매대 배송 명령 → 배송 DB 상태 → 구매자의 같은 주문 조회 |
| 방문자 | 공개 데모 제한 확인 | 이번 실행은 runtime-policy의 public-demo/restricted, 비회원 발급503·DEMO_FEATURE_UNAVAILABLE, 제품의 테스트 조회 경로404 확인. 제공자/DB 무효과의 기존 C/D 검증과 별도 local-full 비회원 성공 근거를 구분 |

각 자동 사용자 업무는 준비 완료 뒤 시작하며 클릭·응답 대기·재조회·최종 assertion을 하나의 10초 deadline 안에서 수행한다. 폴링은 남은 기한을 소비하며 재시도마다 연장하지 않는다. 사람의 설명 시간이나 시연 전체 길이를 10초로 제한하는 규칙은 아니다. 준비·기동·요청·정리는 별도의 유한 제한을 따른다.

## 화면의 공개 범위

desktop/mobile 화면은 실제 브라우저 viewport이며 실기기 검증이 아니다. 공개용 화면에는 계정·연락처·주소·세션·토큰·개인 경로를 남기지 않는다. 가림은 별도 파생물에 적용하고 원본은 로컬에 보존한다. 상태·금액·버튼·업무 결과를 바꾸는 가림이나 합성 화면은 사용하지 않는다.

화면별 [안전한 manifest](p12-e-images/manifest.json)에 원본/파생물 SHA256, 실행 ID, 소스11파일 해시, 모드, viewport, URL path, 합성 업무 ID 대응, 촬영 시각, 가림 범위와 **정확한 operation 이름·ID·실행 결과·소스 assertion**을 연결했다. 촬영 시각은 원본/가림 화면 촬영 완료 후 메타데이터를 기록한 시각이다. 가림 화면은 같은 실제 상태에서 별도 Playwright capture로 만들었으며 승인된 preview와 동일 바이트로 복사했다.

배포 가능한 파일은 가림 PNG8개와 manifest뿐이다. 원본 private PNG·계정 비밀값·연락처·주소·token은 복사하지 않았다. `input`, `header` 및 합성 연락/배송 문구를 capture 시점에 가렸고 금액·상태·업무 결과는 유지했다. parent가 desktop4개와 mobile3개를 확인했고 문서 담당이 mobile 판매자 발송 화면을 직접 확인했다. 합성 주문/신청/상품/매대주문 ID는 같은 거래 연결을 위해 보존한다. 원증거는 Git 제외 로컬 자료이므로 이 문서/manifest만 전달하면 원증거도 함께 전달되는 것은 아니다.

## 같은 거래의 실제 흐름

원 실행은 `.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26`다. [결과](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/result.json), [브라우저 report](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/browser-report.json), [업무 progress](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/browser-progress.ndjson)를 연결한다. 소스는 P12-D HEAD `dbaef2ddedfa7f2393e93bea6b648cf832c5cbf9` 위의 [E 실행 소스11파일 목록/해시](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/proof-source.json)로 고정했고 manifest에 그 해시를 보존했다.

계정과 초기 운영 권한만 bootstrap했다. 이후 신청/승인/구매/모의결제/발송은 실제 UI, category/store/product/SKU/재고 준비는 획득한 권한의 일반 API로 수행했다. 상품 준비를 UI로 실행했다고 표현하지 않는다. 최종 업무 결과를 SQL로 주입하지 않았으며 PostgreSQL 관측은 같은 서버 주문번호를 읽는 용도다.

| 실제 operation·소스 assertion | 확인한 결과 | 화면·원증거 |
|---|---|---|
| `seller application UI produces unapproved request` → `operator approval UI consumes same application ID` | 신청 POST201 → 같은 application ID 심사 POST200·처리 메시지 → 같은 판매자 세션의 BUYER/SELLER 역할 정확 일치 | 운영자 승인 화면, [spec](../../infra/p12-e/reproduction.spec.ts)·manifest의 operation/progress 연결 |
| `declared API product preparation uses earned seller role` → `buyer storefront UI consumes seller product and server quote` | 일반 API 상품10,000원·재고5 준비 → 실제 상품 화면 장바구니 → 서버 견적13,000원 | 구매자 견적 화면. 촬영 후 생성되는 주문과의 연결은 최종 business 기록에 별도 명시 |
| `buyer order UI persists exact server money and identifier` | 주문 POST200·`total='13000'`, `stores=1`, `PENDING_PAYMENT`; 화면 주문번호가 서버 주문번호와 일치 | [desktop business](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/desktop-business.json), [mobile business](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/mobile-business.json) |
| `mock payment UI and autonomous worker visible consumer` | 결제 완료 화면, 같은 주문 배송1건과 PAYMENT_BUYER 알림 목적지/링크 확인; DB `PAID/13000`, 재고 `COMMITTED`, 배송 `PREPARING`, `approval_effects=1/success_events=1` | 같은 business 기록과 progress. 승인 시도/worker 관측과 업무 효과1을 구분 |
| `seller shipment UI consumes buyer same order` | 판매자 목록에서 같은 orderNumber의 sellerOrderId 선택 → 원 수량 확인·발송 클릭 → 발송 완료 UI 및 DB `SHIPPED` | 판매자 발송 화면 |
| `buyer reload consumes shipment and other role cannot own purchase` | 구매자 재조회에 같은 주문번호·발송 완료, 타 판매자 주문조회404·구매자 운영 API403·가로 overflow 없음 | 구매자 배송 화면, manifest의 정확한 spec/progress 연결 |

다음 ID는 모두 새 검사 환경이 실제 생성한 합성 거래다. 운영자 승인·견적 화면은 주문 생성 전 촬영됐으므로 manifest는 촬영 시 이미 존재한 ID와 **이후 생성된 같은 흐름의 최종 ID**를 구분한다.

| 프로젝트 | 신청 ID | 구매자 주문번호 | 판매자 주문 ID |
|---|---|---|---|
| desktop | `2f1317e6-0850-49ab-abcb-0512d69b762a` | `Ocb991bf831ed454896f57668b426026a` | `a4f7a5da-01ce-4fce-ab57-34aeeadcb440` |
| mobile | `16787053-2894-4283-89e7-9995fb69bfba` | `O8c2d6924e21243168d0e5b9984f25ef1` | `e67cde98-b266-43a8-b6a8-04da1aa00682` |

## 실제 화면8개

desktop viewport는1280×720, mobile viewport는412×839다. full-page capture라서 PNG 전체 높이는 viewport보다 길 수 있다. mobile은 브라우저 emulation이며 실제 휴대전화 검증이 아니다.

| 화면·촬영 직전 통과 assertion | desktop | mobile |
|---|---|---|
| 같은 신청 승인·판매자 역할 반영 | [운영자 승인](p12-e-images/desktop-operator-approval.png) | [운영자 승인](p12-e-images/mobile-operator-approval.png) |
| 서버 견적13,000원 | [구매자 견적](p12-e-images/desktop-buyer-quote.png) | [구매자 견적](p12-e-images/mobile-buyer-quote.png) |
| 같은 주문 발송·DB SHIPPED | [판매자 발송](p12-e-images/desktop-seller-shipped.png) | [판매자 발송](p12-e-images/mobile-seller-shipped.png) |
| 구매자 같은 주문 재조회·권한 경계 | [구매자 배송](p12-e-images/desktop-buyer-shipment.png) | [구매자 배송](p12-e-images/mobile-buyer-shipment.png) |

## 현재 결과

집중 실행은 **passed**, desktop/mobile2개 모두 expected·실패/skip/flaky0이다. 새 DB의 [Flyway 결과](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/migration.json)는 성공131개·실패0·최신V132다. 두 최종 business 기록 모두13,000원·PAID·COMMITTED·SHIPPED·승인 효과1/성공 event1이다. 가장 긴 업무 operation은 모의 결제/worker 소비의 **3507.3983ms**로 원래 공유10초 안에서 완료했다. 이 측정은 해당 두 브라우저 실행의 최대값이며 일반 성능 보장이 아니다.

이번 project의 컨테이너는 정지하고 DB/media volume·실패 이력·증거를 보존했다. [preservation 결과](../../.local-tools/p12-e-reproduction14-cd7a6078e7fa44d5bcba2af612fdda26/preservation.json)는 기존 자원 보존을 확인한다. 새 실행은 기존 DB를 사용하지 않았고 실제 외부 결제도 호출하지 않았다. 이후 full1 무선택 전체와 IR1 보완 후 영향 선택을 통과했다. IR1은 같은 신청 ID의 DB 승인 이력과 캐시 이미지 누락 시 거부를 강화했으며, 위 화면의 촬영 당시 source11개 해시는 그대로 유지한다. 최종1082입력 자체의 새 전체 통과는 아니며 최종 독립 인계 결과는 [P12-E 작업 기록](../work-items/P12-E.md)에 연결한다.

이미 수용된 전체 기능·금액/경합·복구 검증은 [P12-A](../work-items/P12-A.md)와 [P12-D](../work-items/P12-D.md)의 정확한 소스·검증 범위로 재사용한다. 이번 대표 시연이 F01~F18 모두를 새로 실행했다거나 P12 전체 완료라고 표현하지 않는다. 사용자 본인의 직접 수행·학습 확인은 미확인이다.
