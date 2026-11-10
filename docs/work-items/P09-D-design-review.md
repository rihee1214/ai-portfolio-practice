# P09-D D1 설계 검토 — 반품의 물리 처리와 금융 처리

2026-11-01. **D1 승인 범위 설계 확정 / 본 검토의 구현·실행 검증 NOT RUN**. 기존 지정 검토세션에서 작성한다. 기준 커밋은 `80ad8cd4d194766a4e09e7e49c5ecce1b46bc506`이며 [C 커밋 마감](P09-C-commit.md)의 완료를 다시 수행하지 않는다. 작성 중 부모가 수정 중인 README·requirements·implementation-plan·P09는 본 검토의 변경 대상이 아니다. 부모가 검수 후 최종 구매자 동의안과 공개 포트 확장을 채택해 기존 Sol 담당의 D2~D4 RED→구현을 승인했다. 아래 검증 계획은 실행 결과가 아니며 D 전체 완료를 뜻하지 않는다.

근거: [개발 흐름](../development-workflow.md), [하네스](../verification-harness.md), [모델 정책](../model-routing.md), [D 계획](P09-D.md), [C 실제 인계](P09-C.md), [C 설계 §11~12](P09-C-design-review.md), [공통 H03/H04/H06](P09.md), [P08 공개 인계](P08.md). 역할 지정은 실제 모델 전환을 뜻하지 않는다. 이번 산출물은 이 파일뿐이며 제품·검사·migration 수정, 하네스, 커밋·푸시·배포는 수행하지 않는다.

## 1. 확정 정책과 판단 범위

부모가 전달한 사용자 확정: **발송 후 최초 배송비는 판매자 책임으로 전체 반품할 때만 환불하고 변심 반품은 환불하지 않는다.** 원 배송비 실제 결제액만 대상으로 하며 현재 배송비·상품 가격·무료배송 조건으로 재계산하지 않는다. D 계획의 최초 배송비 미확정 문구는 이 결정 이전 이력이다.

다음은 재질문할 정책이 아니다.

- 신청은 원 배송완료부터 7일 미만이며 미확정·미점유 원단위만 가능하다. 정확히 7일은 거부한다. 기존 주문에도 저장된 B 기준을 적용한다.
- 회수비는 구매 당시 상품별 정책, 기본3,000원·0원 허용. 변심 대상만 원 배송별 누적최대와 후속 차액을 적용한다. 판매자 책임 단위와 반품하지 않는 고가 정책 상품은 최대값에서 제외한다.
- 누적 부과 A는 공제+미수납이다. 첫 net2,000/fee3,000 후 net1,000/fee5,000이면 A5,000·누적공제3,000·미수납2,000이다. 이전 미수납을 새 환불에서 자동 상계하지 않는다.
- 구형 주문의 UNRESOLVED 정책을 소급 수정하지 않는다. 새 견적의 명시 동의가 필요하다. 상품 MIGRATED_DEFAULT_3000과 과거 주문 정책은 다르다.
- C §11.5의 **미수납 존재 시 전체완료0**은 이미 확정이다. 수납/면제 방식은 미구현이며, 미수납을 무시한 완료 허용 여부를 다시 선택하지 않는다.
- 환불 성공·fee apply만으로 검수·입고·반품완료·쿠폰복구·주문종결을 선언하지 않는다.

후속 사용자 답변으로 취소 혼합 배송비까지 §5에 확정했다. §9의 분실·미회수·미수납 보류 상태는 D 범위이고 새로운 손실 금전 처분·실수납·면제는 범위 밖이다. 승인된 D 범위의 정책 질문은 남아 있지 않다. 이 문서는 D1 설계 검토이며 D 전체 완료 판정이 아니다.

## 2. 현재 코드에서 확인한 확장 필요점

| 실제 경계 | 현재 동작 | D에서 필요한 명시적 확장 |
| --- | --- | --- |
| `order/ReturnSources.lock` | null hold는 OPEN, non-null은 ACTIVE RETURN/동일 단위 검사. 두 경우 모두 현재 시각의 7일 제한과 PAID를 재검사 | 신규 신청 적격성 검사와 이미 적법하게 접수한 반품의 후속 처리를 분리. 접수시각·적격성 영수증을 서버에 저장하고 이후 승인/검수/환불에 새 신청 기한을 적용하지 않음 |
| `claim/ReturnFees.quote/accept/bindRefund` | quote는 OPEN 대상, accept/bind는 현재 소유권·실제 hold와 `quote.orderRevision+1` 검사. ITEM 금액은 net−공제 | 기존 hold의 검수 확정 견적과 수락 경로 추가. 현재 OPEN 견적의 보안을 느슨하게 바꾸지 말고 별도 계약/견적 종류로 구별 |
| `FinancialIntents.registerReturn/results` | 실제 RETURN hold를 등록하고 금융 결과를 영속 보존. RETURN binding은 ITEM만 허용 | 실제 반품 업무 레코드와 동일 intent 귀속, 검수 후 ITEM 처리. 별도의 검증된 SHIPPING obligation/binding 추가. ITEM 검증을 배송비 포함 총액으로 우회하지 않음 |
| `ReturnFees.withdraw` | 금융 철회와 B hold 해제를 함께 수행 | 물품이 반환된 뒤 금융 재견적 때문에 이 함수를 호출하지 않음. 물리 hold를 유지하는 금융 예약 변경이 필요하면 별도 좁은 계약과 검사 필요 |
| `Restitutions.restoreCancellation` | 원 COMMITTED 예약 검증, 원단위 중복 복구 금지, cause/fingerprint 동일 재생 | RETURN 검수 근거를 받는 별도 입고 포트. 취소 함수를 반품 영수증 없이 호출하지 않음. 원단위 중복 입고 방어는 CANCEL/RETURN을 통틀어 유지 |
| `AftercareSettlements.record(Item/Shipping)` | CANCEL hold·취소 정산 사실 및 전체 취소만 수용 | RETURN 완료 사실과 배송비 유지/환불 판정의 별도 타입. order는 claim 내부를 조회하지 않고 공개 사실로 집계 |
| `AftercareSettlements.evaluate` | REFUNDED에는 취소 정산행을 요구하며 전체 취소 배송비 영수증 후 CANCELED | 반품·취소·확정 혼합을 평가. 실제 발송 후 반품은 CLOSED 계열로 종결하고 C의 ALL_UNSHIPPED_CANCEL/늦은 승인 보상 예외를 오염시키지 않음 |
| `OrderUnitHolds.completeRefund` | kind와 무관하게 ACTIVE hold의 단위를 REFUNDED로 전환하는 trusted 포트이며 물리/금융 증거 자체를 검사하지 않음 | D 완료 coordinator의 증거 검증 뒤 T4b에서만 호출하고 RETURN 정산 사실과 원자 저장. 이 함수 성공만으로 반품완료를 증명하지 않음 |

C 공통 포트를 현재 그대로 연결하는 것만으로 D가 완성되지는 않는다. 특히 후속 7일 검사, RETURN 배송비 binding, 완료 집계는 필수 설계 변경점이다. 실행된 V41~57은 수정하지 않고 구현 시 다음 미사용 번호의 추가 migration으로 진행한다.

## 3. 접수·비용 동의 방식의 대안과 권고

| 대안 | 결과 | 판단 |
| --- | --- | --- |
| 신청과 동시에 현재 C accept/reserve/bind | 현재 포트를 가장 적게 변경하나 검수 전 환불 worker가 동작할 수 있고, 책임 정정·반려·장기 회수 동안 bucket/payment 예약을 보유 | 채택하지 않음. 외부 호출을 지연하는 추가 payment gate 없이 현재 경로 사용 금지 |
| 신청 시 hold·예상 비용 표시, 검수 확정 뒤 현재 구매자 최종 견적 동의 후 금융 예약 | 책임/반환 단위와 최종 비용을 일치시키며 물품 처리와 지급을 분리. 추가 확인 화면과 기존 hold용 포트 필요 | **권고 기술안**. 명시 동의가 없는 비용 증가/책임 변경을 자동 수락하지 않음 |
| 검수 후 판매자/worker가 구매자 Authority를 구성하여 현재 C accept 호출 | 현재 본인·GAS·동의 경계 우회 | 금지 |

권고 흐름: 신청에서 원단위 hold와 신청사유/구매자 주장 책임, 예상 비용/정책 설명을 저장한다. 승인·회수·검수에서 실제 수량과 확정 책임을 기록하고 최종 견적을 제시한다. 현재 구매자가 같은 return revision·견적 hash·단위집합을 수락하면 한 거래 안에서 fee assessment·실제 payment reserve·binding·이력/Outbox를 만든다. 서버가 기록한 검수·귀속과 다른 책임을 입력해 비용을 바꿀 수 없다. legacy 동의도 이 최종 불변 영수증에 포함한다.

신청 시에는 fee bucket/payment 예약이 없으므로 같은 원배송의 다른 원단위 신청을 막지 않는다. 금융 수락 시에는 기존 C와 동일한 bucket slot1·충돌409를 유지한다. 선행 처리가 A를 바꾸면 새 견적을 발행하고 이전 quote를 자동 수락하지 않는다. 검수 당시 금액을 약속한 것으로 오인하지 않도록 표시한다. 소유자 미응답은 `AWAITING_CONSENT`로 남기고 7일 만료로 접수를 없애거나 자동확정하지 않는다. 동의 없이 지급하는 별도 자동화는 이 안에 포함하지 않는다.

## 4. 상태·원단위·권한 계약

단일 상태만으로 재고와 금융 결과를 추론하지 않는다. 반품 업무 상태와 수량별 회수/검수 사실, ITEM/SHIPPING 금융 시도, fee 적용·미수납, 입고 영수증, 완료 영수증을 각각 저장한다.

| 단계/명령 | 허용 주체·선행 조건 | 저장과 다음 상태 |
| --- | --- | --- |
| 신청 | 현재 본인 회원 또는 GAS+CSRF, 적격 원단위 전부 같은 seller-order | `REQUESTED`, RETURN hold ACTIVE, 신청시각/원 배송완료/정책 참조·사유·원 단위집합 동결. payment/재고/쿠폰 효과0 |
| 승인 | 해당 seller-order의 현재 판매자 거래 권한, expectedRevision | `APPROVED`, actor/사유/시각. 구매자의 책임 주장을 곧 확정 금융 책임으로 취급하지 않음 |
| 반려 | REQUESTED에서 해당 판매자, 필수 사유 | `REJECTED`·감사·hold release·B 재평가 예약을 같은 거래. 회수 후 반려로 물품·미수납을 버릴 수 없음 |
| 신청 철회 | REQUESTED의 현재 본인, 물품 회수·금융 예약0 | `WITHDRAWN`, 동일한 해제/재평가. 승인 후 회수 취소는 별도 정책 없이 확대하지 않음 |
| 회수 기록 | APPROVED 이후 해당 판매자 | 단위별 `RECEIVED`와 회수 영수증. 미수량은 PENDING, 분실 주장은 LOST_REPORTED. 회수 스캔만으로 판매가능 입고0 |
| 검수 기록 | 실제 RECEIVED 단위, 해당 판매자 | `SALEABLE`/`DEFECTIVE` 및 근거·사유. 판매가능 단위만 입고 영수증1회. 미회수/분실은 검수완료로 위장하지 않음 |
| 책임 확정/분쟁 | 판매자 판단+근거, 구매자 주장과 변경 이력 유지 | 비용/책임 이견은 REVIEW_REQUIRED. 최종 동의 없는 책임 변경으로 금융 처리 금지. 지원자는 명시 SUPPORT·사유로만 조정 |
| 금융 동의 | 필요한 모든 물리 단위 판정과 최종 견적, 현재 본인 | `REFUND_PENDING`, C fee/ITEM 예약·binding. `UNKNOWN`은 확인중, FAILED는 실패 후 지원 재시도. 접수/검수 영수증은 그대로 |
| 반품완료 | 서버 coordinator, 실제 물리 종결·금융 적용·미수납0·필요 배송비 처리 증거 | `COMPLETED`, RETURN 원단위 종결 및 order 공개 완료 사실. 클라이언트 full/confirmed/success 입력은 받지 않음 |

부분 회수는 원 신청집합 안에서만 기록한다. D 기본 경로에서는 신청 전체의 물리 판정이 준비되기 전 ITEM 환불 예약을 하지 않는다. 분실·미반환 부분을 빼고 잔여만 환불하려면 §9의 단위 분리/부분 종결 정책과 새 동의가 먼저 필요하다. 없는 물품을 반환 처리하거나 남은 hold를 자동 해제하지 않는다.

현재 회원 version·GAS idle/max/회전·판매자 소유권/거래 권한·SUPPORT 자격은 마지막 업무 잠금 대기 뒤 재검사한다. 판매 중지/폐점 상태의 기존 거래 처리는 기존 EXISTING_TRADES 계약을 유지하며 새 판매 허용으로 확대하지 않는다. worker는 현재 사용자 세션을 위조하지 않고 저장된 유효 명령/동의와 실제 결과·소유권 귀속을 검증한다. 만료 세션이 이미 수락된 환불의 재개를 취소하지 않는다.

## 5. 최초 배송비 귀속표와 완료 평가

2026-11-01 부모가 전달한 마지막 사용자 답변 원문: **“환불하되, 실제 반품되면 환불하도록 하고, 판매자 책임으로 확인된 것만 환불되도록.”** 질문은 일부 미발송 취소 후 실제 발송한 나머지 전부가 SELLER 책임 반품일 때 최초 배송비를 환불할지였다. 이 답변은 해당 혼합 취소도 환불 대상으로 확정하되 **실제 회수·검수, 판매자 책임 확인, ITEM 정산 근거**가 모두 있어야 한다는 조건이다. 신청·승인·책임 주장만으로 지급하지 않는다. 아래 표의 기존 확인 대기는 이 답변으로 해소한다.

원 결제 배송비 `S=shipping_base−shipping_discount`는 현재 C 구현상 **seller-order당 한 금액**이다. 원배송 회수비 bucket별로 S를 복제하거나 주문 전체 S를 판매자마다 중복 지급하지 않는다. 무료배송 S=0도 정책 판정 사실을 남기되 provider 호출0이다.

아래에서 전체는 원 seller-order의 단위 전체를 대상으로 평가한다. 한 신청 전체·한 배송 전체만으로 하위주문 전체가 되지 않는다. 판정은 실제 완료 사실과 원단위별 책임을 읽어 서버가 한다.

| 원 하위주문 누적 사실 | 배송비 판정 | 근거/주의 |
| --- | --- | --- |
| 전부 미발송 취소·금융 정산 | C의 S 환불1회 | 기존 C 규칙 유지. D가 새 obligation을 추가하지 않음 |
| 모든 원단위가 판매자 책임 반품, 물리/ITEM 정산 완료 | S 환불1회 | 사용자 확정 원칙의 직접 적용. 여러 신청·원배송에 나누어 완료해도 마지막 충족 시1회 |
| 일부 판매자 책임 반품, 나머지 보유/확정/미처리 | 현재 환불0, 미처리 단위가 있으면 판정 대기 | 부분 반품만으로 배송비 반환 금지. 나중에 누적 전체가 되면 다시 평가 |
| 전부 변심 반품 또는 변심·판매자 책임 혼합 반품 | S 유지, 환불0 | 전체가 판매자 책임이라는 조건 불충족. 회수비는 BUYER 단위만 별도 계산 |
| 일부 미발송 취소·금융 정산, 나머지 모두 판매자 책임 반품 | 실제 회수·검수/SELLER 책임 확인/ITEM 정산 충족 후 S 환불1회 | 사용자 후속 답변으로 확정. 취소를 제외한 실제 발송 단위 모두에 증거가 있어야 하며, 신청·승인·주장만으로 지급0. 누적 신청도 마지막 조건 충족 때1회 |
| 취소+반품 혼합이며 반품 중 BUYER 존재 | S 유지, 환불0 | 혼합 취소를 이용해 변심 배송비 반환으로 우회하지 않음 |
| 분실/미반환/책임 분쟁 또는 ITEM UNKNOWN | 판정 대기, 환불 확정0 | 미완료를 전체 반품으로 세지 않음 |

취소 혼합은 이제 승인된 구현·검사 범위이며 PENDING_POLICY 사유로 남기지 않는다. 다만 실제 회수·검수/책임 확인/ITEM 정산 중 하나라도 없으면 사실 미충족으로 PENDING을 유지한다. BUYER가 포함되면 미환불이며, 미회수·분실·책임 분쟁은 보류한다. 이미 발생한 BUYER 완료를 SELLER로 덮어써 S를 환불하거나 A를 취소하지 않는다. 과거 책임/금액 정정은 별도 보정 정책·원장 없이 지원 명령으로 수행하지 않는다.

배송비 처리에는 seller-order별 공통 단일 obligation/결정 영수증과 revision이 필요하다. CANCEL/RETURN 양쪽 coordinator가 같은 원래 배송비 권리를 잠그며, `REFUND_REQUIRED`/`RETAINED`/`PENDING`을 구분한다. 미처리 단위가 있는데 RETAINED 최종 영수증을 발급하면 누적 전체 반품 시 재평가를 막으므로 금지한다. SHIPPING은 ITEM fee와 별도 실제 Refunds 시도/귀속/결과를 갖고 부모 payment 성공+예약 한도를 공유한다. SHIPPING FAILED/UNKNOWN 중 전체 완료·쿠폰 복구0이다. 배송비를 회수비 또는 기존 미수납 공제에 사용하지 않는다.

완료는 세 수준으로 나눈다.

1. **단위 물리 완료**: 수령·검수와 필요한 입고 또는 승인된 예외 처분의 근거. 환불 결과와 독립적이다.
2. **개별 반품 완료**: 해당 단위의 물리 완료+실제 ITEM 결과/fee 적용+해당 미수납0. 전체 배송비 반환이 필요한 마지막 반품은 그 SHIPPING 정산까지 기다린다. 다른 진행 중 반품이 있어도 앞선 부분 반품의 사실을 잃지 않는다.
3. **하위주문/부모 완료**: 미완료 hold·분쟁·미수납·필요 SHIPPING0, 모든 단위의 확정/취소정산/반품완료 근거. 쿠폰은 모든 원단위가 취소/반품 완료한 seller-order에만1회, CONFIRMED가 남은 부분 반품은0. 만료 쿠폰은 복구 영수증과 reusable=false를 분리한다. 부모는 모든 seller-order가 종결해야 종료한다.

전체 미발송 취소는 기존 CANCELED/ALL_UNSHIPPED_CANCEL 유지. 실제 반품이 섞인 종료는 CLOSED와 별도 reason(예: ALL_RETURNED/MIXED_AFTERCARE)로 구별한다. 원 결제 APPROVED 재통지로 전체 환불 보상이 다시 발생하지 않는 회귀 검사가 필수다. CLOSED 이후 조회·정상 동일키 재생·이력은 유지하되 새로운 신규 반품 적격성을 열지 않는다.

## 6. 데이터·공개 포트 제안

명칭은 구현에서 조정 가능하지만 아래 검증 책임을 삭제할 수 없다.

- `claim/api/Returns`는 구매자 request/get/consent/withdraw와 판매자 approve/reject/receive/inspect를 명시적으로 분리한다. 요청에는 key·expectedRevision·원단위·사유가 있고 금액/재고증가량/완료 boolean은 서버 계산이다. 타 주문·타 판매자의 returnId 존재 여부도 최소 노출한다.
- claim 소유 return_request/unit/command_receipt/collection/inspection/history를 추가한다. request/hold/intent/order/seller/cause 복합 귀속, 단위 FK, 허용 상태 CHECK, request-key 내용 fingerprint, 회수/검수 revision 및 원단위별 최초 입고 고유 제약을 둔다. 다른 cause로 같은 물품을 두 번 반환 처리할 수 없다. 집계 수량 대신 실제 ordinal 집합을 보관한다.
- order 공개 포트 `beginReturn`은 현재 owner·적격성·hold·신청 당시 증거를 제공한다. `lockAcceptedReturn`은 유효 ACTIVE hold/접수 증거와 원 주문 snapshot을 검증하며 **새 신청 기한을 다시 요구하지 않는다**. 사용자 호출의 현재 Authority는 계속 필요하고 worker용 경로는 서버 승인 영수증을 검증한다. 단순 ignoreDeadline/skipAuthority 옵션은 금지한다.
- `ReturnFees.quoteForReturn`/`acceptForReturn`은 서버 returnId·검수 revision·책임 snapshot·현재 bucket revision·quote fingerprint를 결합한다. 기존 OPEN quote+1 계약과 구별하고 다른 주문 변경에 따른 충돌은 새 견적으로 처리한다. 같은 거래에서 accept/reserve/bind 완결을 강제한다. 기존 C 테스트용/공통 공개 계약도 그대로 회귀 검증한다.
- `FinancialIntents.bindReturnShipping` 또는 동등한 별도 공개 계약은 서버 배송비 obligation과 실제 원 S·payment/order/seller/intent/purpose/reference를 검증한다. ITEM fee binding의 금액/assessment 필수 조건을 풀지 않는다. payment에서 claim 내부 Java 의존을 추가하지 않는다.
- inventory 공개 `restoreReturn`은 검수 영수증 식별·SALEABLE 원단위·원 reservation/checkout을 받아 cause 동일 재생 및 내용충돌409, 원단위 중복 입고 거부를 제공한다. 검수 기록과 같은 거래에서 실행하며 검사 실패 시 둘 다 rollback한다. 원 예약 COMMITTED/소비 이력은 유지한다.
- order 공개 `recordReturnCompletion`은 actual return/hold/unit/payment/inspection/fee/shipping-decision의 불변 참조를 받고 중복/다른 귀속을 거부한다. claim coordinator가 증거를 검증하고 order는 자신이 소유한 hold·단위·종결 사실을 검증한다. `RefundCompletion(true,true)`를 HTTP에서 직접 전달하지 않는다.
- coupon cause는 seller-order/원 coupon별 공통 복구 원인으로 고정한다. 취소·반품 두 coordinator 사이에 다른 cause로 동일 쿠폰을 복구하려는 경합도 한 효과로 수렴해야 한다.

미수납은 assessment별 원 부과·공제·미수납과 수납 증거를 분리한다. D에서 수납을 구현하기로 승인되면 수납 귀속·중복 참조·금액 상한·정당한 수납자 권한을 가진 별도 원장이 필요하다. 상태 문자열을 PAID로 바꾸거나 미수납을0으로 덮어쓰는 임시 우회는 허용하지 않는다.

## 7. 잠금·거래 경계와 실패 복구

전역 순서는 C의 현재 identity→order→canonical 원단위→claim/intent→sorted fee bucket/assessment→payment budget→coupon→inventory→최종 lease를 유지한다. 실제 정렬 키는 C `lockBuckets`의 결정적 bucket UUID 문자열 순서와 일치시킨다. 판매자 명령은 자기 identity/거래 권한 잠금을 먼저 획득하고, 구매자 identity를 order 잠금 뒤 추가로 획득하지 않는다. 부모 order 잠금을 공유해 취소/확정/반품/배송비 완료를 직렬화한다. 최종 권한 재검사 때문에 새로운 반대순서 잠금이 생기지 않도록 기존 R-C03 보완과 함께 검증한다.

| 경계 | 한 거래 안의 효과 | 실패·재개 |
| --- | --- | --- |
| 신청 T1 | 적격성/RETURN hold·접수/intent·불변 receipt·history·Outbox | 어느 단계 실패도 전부0. 성공응답 유실은 같은 key/내용의 원 receipt, 변경내용409 |
| 승인/반려 | 현재 판매자·revision·상태/사유·history·필요 hold해제/B 재평가 Outbox | 해제만 남거나 반려만 남지 않음. 자동확정과 잠금 경합 후 winner1 |
| 회수/검수 | 원 단위별 사실·검수·SALEABLE 입고/영수증·history·Outbox | inventory/감사 실패는 해당 명령 전부 rollback. 이미 성공한 앞선 회수 사실은 보존 |
| 최종 동의/금융 예약 | 현재 owner·최종 quote·fee consent/assessment/R·Refunds.reserve/bind·history·Outbox | orphan 예약/동의0. 모든 원배송 bucket을 같은 거래로 확보하거나 전부 rollback |
| provider T2/T3 | DB 잠금 밖 submit/query, 실제 금융 결과의 별도 원자 저장 | UNKNOWN 동일 attempt query·예약 유지. FAILED만 명시 SUPPORT 사유로 새 sequence. 준비 child/lease takeover와 늦은 submit 한도 보존 |
| fee 적용 T4a | 실제 SUCCEEDED 귀속 재검증·A/R 전환·공제/미수납·적용 receipt·history·Outbox·최종 lease | 결과가 먼저 영속되어도 재처리1회. fee 성공≠RETURN 완료 |
| 완료 T4b | 물리·금융/fee·미수납·배송비 판정 확인, RETURN 종료·order 사실·조건부 쿠폰/부모종결·history·Outbox·최종 lease | 완료 후 ACK 유실은 추가 효과0. 쿠폰/종결/Outbox/lease 실패는 완료 거래 전부 rollback. 이미 완료된 T4a와 검수입고는 유지 |

검수 입고 뒤 환불 UNKNOWN이면 입고 영수증을 되돌리지 않고 반품은 확인중으로 남는다. 환불 SUCCEEDED 뒤 업무 적용 실패이면 재환불 없이 저장 `FinancialIntents.results`와 실제 `Refunds.get`을 재대조해 재개한다. 오래된 FAILED 결과가 새 SUCCEEDED 결과를 덮지 않으며 attempt purpose·sequence·resultVersion을 검증한다. 기존 결과 worker의 ACK만 의존하지 않고 미적용 intent/receipt를 찾는 영속 재개 경로를 둔다. 스캔은 재처리 조건만 찾고 상태를 임의 성공시키지 않는다.

0원 ITEM도 실제 성공 영수증과 fee 적용 경로를 거치며 provider0이다. 0원이라고 검수·원단위 귀속·미수납 검사를 생략하지 않는다. 반품 완료 후에는 동일키 조회/재생만 허용하며 해제된 다른 단위에 옛 명령을 재사용하지 못한다.

## 8. D01~D08 assertion 계획 — 모두 NOT RUN

다음은 실제 구현에서 추가/확장할 검사와 기대값이며 아직 통과 근거가 아니다. SQL UPDATE로 SUCCEEDED/COMPLETED를 만든 fixture를 E2E 증명으로 사용하지 않는다. 실제 공개 명령→worker/provider DEMO→원장/물리 증거를 대조한다.

| ID | 검사와 구체 assertion |
| --- | --- |
| D01 | `ReturnRulesTest#returnWindowAndTransitions`:7일 직전 신청1/정확7일0, 미배송/CONFIRMED/HELD 거부. 승인 전회수·미수령검수·미검수완료0. `acceptedReturnContinuesAfterApplicationDeadline` 실제 DB: 기한 내 접수 후7일 경과 승인/검수/동의/완료 가능, 새신청은409, 현재 자격 만료는 거부 |
| D02 | `PostgresJdbcIT#returnCompetesWithConfirmationAndOtherClaims`: 실제 barrier로 B 직접/자동확정·RETURN/CANCEL/기존 EXCHANGE hold 경합 winner1·다른 ordinal 허용. reject release와 재신청/자동확정 경합, 중복효과0. 교환 제품 workflow는 E 범위로 실행하지 않음 |
| D03 | `returnInspectionSeparatesRefundAndInventory`: 수령3/SALEABLE2/DEFECTIVE1이면 on_hand+2·reserved불변·원 COMMITTED 유지. 검수 동일키/다른cause 중복입고0. 수령없는검수409/환불성공만입고0/분실입고0. 입고성공+UNKNOWN 보존, 실제 재개후 추가입고0 |
| D04 | `returnCompletionAndCouponRestorationAreTrusted`: 물리미완료·ITEM UNKNOWN·미수납>0·필요 SHIPPING 미정산 각각 완료/쿠폰0. 누적 seller전체 취소/반품+정산이면 쿠폰1·만료 reusable=false, CONFIRMED단위 남으면복구0. 두 seller 중 하나미완료 부모PAID. 마지막 완료 두순서 모두CLOSED, 전체미발송취소만CANCELED |
| D05 | `returnCommandsRollbackAndRecheckAuthority`: 실제 HTTP 타회원/타판매자/만료GAS/무CSRF/SUPPORT없음 거부·participant0. 실제 최종 lock 대기 중 세션/권한변경 뒤거부. T1·검수입고·금융예약·T4a/T4b 각 단계/Outbox/최종lease 실패 주입, 해당 거래 전부 rollback·재개효과1. reason/revision/key변경409, 반려/철회 후 B 재평가1 |
| D06 | `order-returns.spec.ts`·`ReturnFlow.test.tsx`: 새 browser GAS→본인신청→판매자승인/회수/검수→구매자최종동의→DEMO환불을 실제 API/DB로 대조. 회원/타판매자/세션전환·403/409·네트워크응답유실·중복클릭·늦은private응답 소거, 불변접수영수증과 최신진행 분리. 금액 문자열/BigInt, 원단위선택·원배송별차액·legacy동의·미수납/분실/UNKNOWN 표시 검사. PC/mobile 둘 다 필수 이름 등록 |
| D07 | 기존 `ModuleArchitectureTest`/`ModuleRulesTest` 실제+금지fixture 유지. claim→order/payment/inventory/coupon 공개 경계·역방향/내부참조/순환0. C fee fixtures 및 신규DB 공격: 유효한 타주문/타seller/타hold/타검수/타attempt 교차귀속 거부, 같은 원단위다른cause 입고 거부, 음수/상한/순번/가짜완료·receipt-only 쓰기 거부 |
| D08 | `returnUsesOriginalUnitsAndPersistedFeePolicy`: ordinal 잔여1원과 원net 보존, 정책3000→5000 후차액2000·첫/후속 부족 사례 A5000/공제3000/미수납2000·과거미수납자동상계0. 0정책·다른배송합산·SELLER제외·현재정책변경영향0·legacy원JSON불변/동의없는예약0. §5 배송비 전행(S0/유료/누적/혼합) 표 구동, 같은seller 두마지막완료 경합 SHIPPING1·부모한도 보존. 확정된 취소 혼합은 실제 회수·검수/SELLER 확인/ITEM 정산 전 지급0, 모두 충족 후 원 S1회; BUYER포함0, 미회수/분실/분쟁 보류 |

사용자 후속 답변의 D08 근거는 §5 원문과 판정표다. 실제 공개 흐름으로 일부 미발송 취소를 정산한 뒤 나머지 발송 단위의 신청만/승인만/일부 회수/검수 미완료/SELLER 미확인/ITEM UNKNOWN 각 단계에서 SHIPPING 지급0을 검증한다. 마지막 단위까지 실제 회수·검수·SELLER 확인·ITEM 정산을 마친 뒤에는 SHIPPING obligation1·원 결제 S 환불1, 재처리/경합 추가 지급0을 확인한다. BUYER가 섞이면 원 배송비 유지·환불0이며 현재 배송 정책 변경은 원 S에 영향0이다. 이 assertion은 설계 확정됐으나 본 검토에서는 **NOT RUN**이다.

추가 필수 사례: 신규 hold용 견적을 다른 return/검수 revision에 재사용409, 책임변경 후 옛동의409, bucket 경쟁으로 낡은 견적409, 신청 후7일 경과와 현재 owner 만료를 구분, 배송비 RETURN binding의 ITEM assessment 우회 거부, 결과ACK후 재개/commit-beforeACK replay, CLOSED 후 원 APPROVED 재통지 보상0. 이 검사는 D01/05/07/08의 필수 목록에 포함한다.

최종 구현 검토는 동결 입력과 제약별 실제 메서드/assertion을 연결한 후 새 경로의 선택없는 `./verify.ps1 -Offline -EvidenceDirectory <새 경로>`로 개발/지정 독립 각각 수행한다. JUnit·ArchUnit·새 PostgreSQL·프런트·브라우저·Node·필수 목록·보존을 모두 확인한다. 기존 C의 Java553/browser130/frontend256/Node75 및55묶음은 과거 기준이지 D 실행 결과가 아니다. 누락·skip·실패·미확정 필수 정책을 총 검사 수로 숨기지 않는다. 현재 browser287초와 실패 이력은 유지하고 timeout만 늘려 통과로 만들지 않는다.

## 9. 확정 범위와 D1 구현 진입 판정

| 항목 | 확정 계약 | D 범위와 제외 범위 |
| --- | --- | --- |
| 최초 배송비 책임 혼합 | SELLER 전체만 환불, BUYER 포함 반품은 미환불. 미발송 취소 제외 나머지 전부가 실제 회수·검수/SELLER 확인/ITEM 정산되면 원 S1회 환불 | §5 사용자 원문으로 정책 대기 해소. 누적 전체·취소 혼합·단계별 지급0/최종1회 모두 D 구현·검사 범위 |
| 미반환·회수 중 분실 | 물품 없으면 입고0, 거짓 검수완료/자동확정 금지, REVIEW_REQUIRED/물리미완료 유지 | 조회·현재 권한·허용 재시도·조기완료 금지는 D 범위. 손실 환불/채무 면제·미반환 단위 해제 같은 새로운 금전 처분은 범위 밖 |
| 추가비용 미수납 | DUE_PENDING, 전체완료0, 과거미수납 자동상계0, A재부과0 | 상태 조회·권한·조기종결 거부는 D 범위. 실제 수납·면제는 범위 밖이며 잔액을0으로 덮어쓰지 않음 |

후속 답변 이력: 최초 설계에서 취소 혼합 배송비는 추천안/확인 대기였다. 부모가 §5의 사용자 원문을 전달함으로써 해당 대기는 종료됐다. 사용자 확정 전 검사를 통과했다고 소급하지 않으며, 설계 확정과 실제 구현·검증 완료는 분리한다. 범위 밖 손실 금전 처분·실수납·면제를 향후 추가할 때만 별도 정책 판단이 필요하고 현재 D 착수의 질문으로 남기지 않는다.

**D1 승인 범위 설계 확정·D2~D4 구현 진입 가능**으로 판정한다. 부모가 채택한 검수 후 최종 구매자 동의와 포트 확장, 확정된 최초 배송비 표, REVIEW_REQUIRED/DUE_PENDING의 조회·권한·조기종결 방지를 구현 담당이 RED→구현으로 진행한다. 이 판정은 보류 거래를 COMPLETED로 만들거나 D 전체 실행 검증을 대체하지 않는다. D01~08과 선행 회귀·새 개발/지정 독립 전체·보존 근거가 충족되기 전 최종 D 완료 판정은 하지 않는다.

기존 지정 부모·Sol 구현·검토세션 구조를 유지한다. E는 회수/검수 영수증·입고cause·미완료/미수납 경계를 향후 소비할 수 있다는 인계까지만이며 E 이후 구현·배포 범위는 없다.

## 10. 검토 수행 기록

현재 코드/문서 읽기로 공개 계약·귀속·거래 경계를 확인했다. 기존 하네스·실패 증거·DB·서버·미디어를 변경하지 않았다. 새 하네스나 자동 검사를 실행하지 않았으며 이 설계의 기대 assertion은 모두 NOT RUN이다. 본 검토세션은 제품/검사 구현·커밋·푸시·배포를 하지 않았다. 후속 사용자 답변과 부모의 채택/범위 지시를 이 문서에만 반영했으며, 부모가 승인한 Sol의 진행 상황을 본 검토의 실행 결과로 표현하지 않는다.

## 11. 명령 고정본의 order/claim 소유권 판단 — 2026-11-01

부모의 `commands-review1` 설계 질문에 대한 판단: **order의 `JdbcReturnApplications.validate`에서 claim 테이블 직접 조회를 제거하고, order 소유 증거와 claim workflow 검증을 분리한다.** 이는 새로운 사업 정책이 아니라 §2/6 및 C 인계의 기존 모듈 소유권을 준수하는 수정이다. 고정본의 해당70행은 `claim_financial_intent JOIN claim_return_request`를 직접 조회한다. Java import가 없어도 다른 모듈 내부 저장소 구조에 의존하므로 경계 위반이다.

채택 계약:

1. order `lockOwner/lockSeller`는 현재 actor·해당 order/seller 접근, 불변 order_return_application, 저장 원단위/source 귀속, ACTIVE RETURN hold의 cause/정확한 단위집합, HELD lifecycle/active_hold/seller를 검증한다. 신규신청 기한을 후속처리에 재적용하지 않는다. 이 포트 성공은 **order가 소유한 접수·점유 근거 유효**를 뜻하며 claim workflow 승인이나 금융완료 증명이 아니다.
2. claim `ReturnCommands.execute`는 같은 거래·기존 잠금 순서 안에서 현재 claim row를 잠그고 accepted와 id/order/seller/hold/cause 귀속, 허용 상태/expectedRevision, 동일key/fingerprint, 명령 영수증/history/Outbox를 책임진다. 실제 intent 결합의 추가 검사가 필요하면 claim 내부의 기존 FinancialIntents 계약/검증을 사용한다. order가 claim API를 역호출하거나 claim 테이블 SQL을 helper로 숨기지 않는다.
3. V58~60의 FK·불변성·deferred graph는 저장 경계의 연결 완전성을 계속 보장한다. 정상 공개 명령은 부모 order 잠금을 공유하므로 위 검증과 전이 사이에 다른 정상 명령이 끼어들지 못한다. 애플리케이션의 허용상태 검사를 DB trigger만으로 대체하지 않으며, 반대로 order 포트를 claim 상태 판정기로 만들지 않는다. V58~60 적용본을 변경하지 않는다.

대안인 order→claim 공개 검증 호출도 claim→order의 반대 의존을 만들므로 채택하지 않는다. 기존 SQL을 유지하는 안은 ArchUnit import 검사만 통과할 뿐 소유권을 해결하지 않는다. 책임 분리 후에는 현재 승인/반려/철회·기한 뒤 처리·권한/rollback/replay와 begin-only/receipt-only DB 거부를 재검증하고, 좁은 order JDBC 경계 검사 등으로 claim 테이블을 다시 직접 조회하지 않음을 확인한다. 상세 수정 요구는 [R-D01](P09-D-review.md)의 명령 고정본 절을 따른다. 이 판단 자체는 수정 완료·실행 통과가 아니다.

## 12. SUPPORT·동의된 worker의 저장 접수 잠금 계약 — 2026-11-01

**기술 판단: trusted `lockRecorded(id)` 포트를 채택한다.** 실제 SUPPORT 또는 이미 수락된 금융 명령의 worker가 구매자 Authority를 만들어 `lockOwner`를 호출하지 않는다. §11의 order/claim 책임 분리를 유지하며 새 사업 정책이나 별도 사용자 선택은 필요하지 않다.

근거는 `physical-review1/source` 입력575 SHA256 `F99091D4432E94BDD9D810AADB39425DA3141A2E34D41D43CD061EF065FDE967`의 ReturnApplications/OrderUnitHolds, CurrentSupport, Jobs/JobLeases, C ReturnFees.apply/retryFailed와 기존 §2/6/7/11이다. live의 진행 중 buyer quote/consent 코드는 이 판단에 포함하지 않았으며 추가 live 확인은 필요하지 않다. 아래 서명과 검사는 **제안 계약·NOT RUN**이다.

### 12.1 최소 공개 서명과 검증 책임

order/api/ReturnApplications에 다음 의미의 읽기·잠금 전용 계약을 추가한다. 이름은 조정 가능하나 의미와 책임은 유지한다.

```java
enum RecordedHoldState { ACTIVE, RELEASED, REFUNDED }
record Recorded(Accepted application, RecordedHoldState holdState,
                String holdOutcome, String orderState) { }
Recorded lockRecorded(UUID applicationId);
```

호출자는 이미 실행 중인 **같은 transaction**을 유지한다. transaction 없이 호출하면 거부한다. 입력 UUID는 locator이지 권한 증명이 아니다. 포트에 Authority/worker boolean/skipAuthority/ignoreDeadline/allowTerminal 옵션을 넣지 않는다. HTTP binding·일반 사용자용 generic endpoint를 만들지 않으며 반환 DTO를 그대로 공개 응답으로 노출하지 않는다.

ACTIVE만 반환하는 서명은 정상 적용 후 hold가 REFUNDED가 된 재전달을 잘못 거부하므로 채택하지 않는다. 대신 order가 검증한 **현재 hold 상태·저장 outcome·부모 상태**를 반환하고 claim이 새 변경/기존 결과 재생을 구분한다. terminal 상태를 읽는 것은 terminal에서 새 변경을 허용하는 뜻이 아니다.

order 구현의 책임:

1. 불변 application의 최소 라우팅 읽기 뒤 부모 order→canonical 원단위→해당 hold를 기존 순서로 잠그고 저장 귀속을 재대조한다. application/source의 order/seller/checkout, 실제 원단위/원 배송·구매 정책 참조, hold.kind=RETURN·id/cause/order·정확한 원단위집합을 검증한다. claim/fee/payment 테이블을 직접 조회하거나 claim API를 역호출하지 않는다.
2. ACTIVE이면 모든 현재 lifecycle이 해당 hold의 HELD·동일 seller인지 확인한다. 신규 업무 변경은 claim에서 ACTIVE와 해당 업무에 맞는 부모 상태(현재 정상 흐름은 PAID)를 다시 요구한다.
3. REFUNDED이면 모든 원단위의 REFUNDED/refund_cause가 이 hold cause와 맞고 active_hold가 해제됐는지, hold의 저장 refund outcome이 존재하는지 확인한다. 이 outcome만으로 실제 payment 성공이나 반품완료를 인정하지 않는다. RELEASED이면 저장 outcome이 REJECTED/WITHDRAWN인지 확인하되 해당 원단위가 지금도 OPEN일 것을 요구하지 않는다. 해제 뒤 합법적인 새 hold/구매확정이 있을 수 있으므로 과거 release를 재적용하지 않는다.
4. 부모가 CLOSED/CANCELED라는 이유만으로 저장 근거 조회를 거부하지 않는다. 해당 terminal 조합의 업무 유효성은 claim의 기존 적용 영수증/원인 검증에서 판정한다. 이 포트가 부모 상태를 변경하거나 hold를 acquire/release/complete하지 않는다.
5. 접수 당시 requested_at/저장 window·원 배송 증거를 보존하고, 현재 시각으로 신규신청7일 검사를 다시 하지 않는다. 기존 `begin`의 기한/현재 owner 검사와 `lockOwner/lockSeller`의 현재 권한·ACTIVE 요구는 유지한다. 현재 owner/seller 경로를 새 trusted 포트 하나로 공개 대체하지 않는다.

`OrderUnitHolds.Hold`에는 현재 outcome이 없으므로 새 Recorded DTO의 outcome은 order가 자신의 hold 행에서 읽어 제공한다. claim이 이를 얻으려고 order 내부 SQL을 추가하지 않는다. 모르는 hold 상태나 불일치 증거는 fail-closed이며 성공 DTO로 정규화하지 않는다.

### 12.2 두 호출자의 진입 조건과 거래 순서

**SUPPORT 명령:** 기존 CurrentSupport.require의 실제 현재 회원 세션/version 및 AccessArea.SUPPORT를 transaction 시작에서 확인한다. 필수 reason/key/expectedRevision과 명령 종류를 검증한다. 사용자 구매자 권한·GAS로 승격하지 않는다. 그 뒤 lockRecorded→claim/request/intent→기존 정렬 fee bucket/assessment→payment budget/attempt 순서로 잠그고 모든 귀속·명령 허용 상태를 확인한다. 상태/기록/Outbox 변경을 같은 거래로 저장한 후 **마지막 업무 잠금 대기 뒤 현재 SUPPORT를 다시 확인**한다. 기존 명령 receipt 재생 경로에도 현재 SUPPORT 검사를 적용한다. SUPPORT 권한만으로 미동의 금액을 예약하거나 책임·회수비·물리 사실을 수정하지 않는다.

**worker 명령:** 사용자 세션 Authority는 받지 않는다. 전용 D job handler/coordinator가 실제 claim된 Jobs.Job으로 진입하고, `Jobs.find(jobId)`의 저장 type/dedupeKey/causeKey/payload와 처리 대상 return/intent/operation을 대조한다. 임의 UUID/token·클라이언트가 보낸 `worker=true`·새로 만든 Jobs.Job 객체만으로 진입을 인정하지 않는다. 잠금 전의 이 확인은 라우팅/조기 거부일 뿐 최종 실행 권한을 대신하지 않는다.

worker 거래 순서:

1. 저장 업무 명령·동의의 존재와 job 라우팅을 읽어 대상 후보를 정한다. payment/fee/inventory 효과는 아직0이다. 승인 없는 원물리 접수나 단순 RETURN intent만으로 환불 worker를 만들지 않는다.
2. lockRecorded로 부모/원단위/hold 잠금을 얻은 뒤 claim row/intent를 잠그고 위 명령·동의를 다시 검증한다. return/intent/order/seller/hold/cause/정확한 원단위집합과 operation purpose를 전부 대조한다. 부모 잠금 뒤 사용자 identity를 새로 얻는 순서로 바꾸지 않는다.
3. 최초 금융 명령에는 실제 구매자 최종 동의 receipt·최종 quote hash·검수/책임 revision·legacy 명시 동의·assessment/binding·원금/차액/미수납 배분이 모두 필요하다. 재개에서는 **수락 당시 고정한 binding/revision**을 검증한다. 실행 이후 바뀐 일반 workflow version을 수락 당시 version과 무조건 같게 요구해 정상 재개를 막지 않는다. 비용/단위/책임 변경은 새 동의 없이 진행하지 않는다.
4. 실제 `FinancialIntents.results`와 `Refunds.get`의 attempt/resultVersion/상태/금액/목적/원 reference·결과 reference를 대조한다. 단순 job payload나 C worker ACK를 금융 결과로 믿지 않는다. fee 적용과 물리/최종 완료의 별도 영수증 조건을 유지한다.
5. 같은 전역 순서로 필요한 금융/쿠폰/재고 등의 업무 효과·receipt/history/Outbox를 저장한다. **최종 업무 잠금 이후** `JobLeases.requireOwned(jobId, ownerToken)`을 필수 호출하고 commit한다. null lease pair는 이 D worker 경로에서 허용하지 않는다. C Apply의 선택 lease 서명을 그대로 열어 worker 호출자가 null/null로 생략하게 만들지 않는다. 필요하면 D 내부의 필수 lease wrapper를 둔다.
6. 기존 결과 재생·효과 없는 정상 중복 처리에도 동일 최종 lease guard를 호출한다. lease를 먼저 잠그고 그 뒤 새 업무 잠금을 얻는 역순은 금지한다. job의 저장 라우팅/명령은 불변 근거와 일치해야 하며 최종 검증 시 다시 확인한다. worker 효과는 lease 상실 시 전부 rollback하고, ACK는 기존 작업 실행기의 own-token 절차를 따른다.

Jobs.find/기존 JobLeases로 저장 job의 binding·현재 소유를 검증하는 책임은 claim/platform 경계에 둔다. order 포트가 worker lease·SUPPORT·구매자 동의의 허가 엔진이 되지 않는다. worker 재개는 이미 적법하게 수락한 명령을 수행하므로 그 뒤 구매자 세션이 만료돼도 자동 취소하지 않는다.

### 12.3 신규 적용·종결 후 재생·재시도의 분리

| 저장 상태/요청 | 허용 동작과 필수 근거 |
| --- | --- |
| ACTIVE, 유효 동의·binding·실제 SUCCEEDED, 아직 효과 receipt 없음 | 해당 operation의 현재 업무 전제/원금·물리·미수납 조건을 검증하고 최초 적용. fee 금융 적용은 반품 완료가 아님 |
| ACTIVE 또는 REFUNDED, 같은 operation의 적용 receipt 존재 | 실제 immutable result/동의/binding·receipt fingerprint가 모두 일치하면 원 receipt를 반환하고 추가 금융/입고/쿠폰/종결 효과0. worker lease/SUPPORT 현재 권한은 여전히 필요 |
| REFUNDED인데 해당 operation 적용 receipt 또는 실제 금융 결과가 없음/불일치 | 성공 재생으로 취급하지 않고 거부·불일치 기록. hold 문자열만으로 receipt 생성/금전 적용/완료 보정 금지 |
| RELEASED, 같은 반려/철회 명령 receipt 재생 | 저장 outcome·명령/원인 일치와 현재 호출자 권한 하에 원 receipt만 반환. 이미 새 hold/확정이 생겨도 과거 release를 다시 실행하지 않음 |
| RELEASED인데 금융 apply/새 재시도 요청, 또는 다른 intent/목적의 결과 | 거부. 원 hold를 다시 ACTIVE로 만들거나 다른 단위의 현재 hold를 대신 사용하지 않음 |
| UNKNOWN/PROCESSING | 기존 attempt의 허용 query/recovery만. 같은 금액 새 attempt·예약 해제·임의 FAILED/SUCCEEDED 변경0 |
| definitive FAILED, ACTIVE, 기존 동의/assessment 유지 | 실제 현재 SUPPORT·사유/key/revision 아래 명시 새 sequence retry. 같은 원 의무/금액·동의 귀속 유지, 옛 실패 결과 보존. worker가 새로운 지원 승인을 스스로 만들지 않음 |
| 이미 SUCCEEDED/APPLIED 또는 terminal에 새 retry key | 중복 환불 재시도 거부. 이전에 성공한 SUPPORT retry 명령의 동일key 재생만 원 command receipt 반환 가능 |

재생은 **operation별**로 판정한다. ITEM fee APPLIED 영수증이 있다는 이유로 SHIPPING/반품완료/쿠폰 효과가 모두 적용됐다고 판단하지 않는다. 최종 COMPLETED receipt 재생에는 실제 물리 검수·SALEABLE 입고, ITEM/필요 SHIPPING 결과, 미수납0, hold outcome과 원단위·완료 사실·필요 쿠폰/부모종결 영수증을 대조한다. parent가 아직 PAID인 부분완료도 가능하므로 CLOSED만 완료 증거로 요구하지 않는다.

commit 후 ACK 유실·오래된 중복 이벤트는 저장 operation receipt로 재생한다. 같은 operation에 오래된 FAILED/UNKNOWN 결과가 새 SUCCEEDED 뒤 도착하면 현재 binding/sequence와 불변 기록을 대조해 효과 없는 stale 처리 또는 명시 거부로 끝내며 최신 상태를 되돌리지 않는다. 실제 새 결과가 없는 불일치는 성공으로 ACK하지 않는다. Outbox/result 보존 후 영속 스캔으로 재개할 때도 새로 claim한 실제 lease와 원 동의/명령 귀속을 요구한다.

### 12.4 구현 전 연결할 필수 assertion — NOT RUN

| D ID/검사 후보 | 기대 assertion |
| --- | --- |
| D05/D07 `recordedReturnPortKeepsOrderOwnershipAndTerminalEvidence` | 실제 JDBC recorder에서 order 직접 claim/payment/fee relation0. transaction 밖 호출 거부. 정상 ACTIVE/REFUNDED/RELEASED 증거 반환, 다른 application/hold/cause/원단위 위조 거부. RELEASED 후 새 hold/확정이 있어도 옛 기록 조회 가능·현재 상태 변화0 |
| D05 `returnSupportUsesCurrentSupportWithoutBuyerImpersonation` | 다른 실제 operator의 SUPPORT 정상 명령, 일반 회원/판매자/GAS 및 위조 worker 입력 거부. reason/key/revision 누락/충돌 거부. 실제 최종 업무 lock 뒤 저장 세션 만료/권한 폐기 시 전체 participant0, 동일key 재생도 현재 권한 요구 |
| D03/D05/D07 `returnWorkerRequiresRecordedConsentIntentAndOwnedJob` | 미동의·다른 return/order/seller/hold/unit/assessment/attempt·다른 purpose/hash/resultVersion/금액·legacy동의 누락 각각 거부/효과0. 실제 job 없이 UUID/가짜Jobs.Job/type·cause·payload변조, null/누락/틀린 token·타job lease 거부. 실제 수락 뒤 구매자 세션 만료는 유효 worker 처리를 막지 않음 |
| D05 `returnWorkerLosesLeaseAfterActualBusinessWait` | 실제 payment/inventory 등 마지막 업무 잠금 대기 중 lease 만료·takeover를 만들고 구 owner의 효과/receipt/history/Outbox 전부 rollback. 새 owner만1회. ledger명령과 최종 guard를 생략한 호출이 성공하지 않음 |
| D04/D05 `completedReturnReplayAfterHoldClosureHasNoAdditionalEffect` | 실제 동의→금융/물리→완료→REFUNDED hold 후 commit-beforeACK 재전달, 원 receipt 동일·환불/입고/쿠폰/종결 추가0. partial-parent PAID와 최종 CLOSED 양쪽. terminal hold만/receipt만 위조한 graph 거부, 같은 operation이 아닌 영수증 재사용 거부 |
| D05/D08 `returnFailedRetryAndUnknownQueryKeepOriginalObligation` | UNKNOWN 같은attempt query·예약 유지, FAILED 실제 SUPPORT 새sequence·같은 동의/assessment·원금/미수납 불변, stale결과로 역행0. SUCCEEDED/terminal의 새retry 거부, 이미 수행한 지원명령 replay0추가효과 |
| D01/D05 기존 owner/seller 회귀 | 신규 신청 정확7일 거부 유지. 접수 후7일 경과 SUPPORT/worker 계속 처리와 현재 owner/seller/GAS 권한 거부를 분리. 새 포트의 generic HTTP endpoint 부재, controller가 trusted 포트로 권한 경계를 우회하지 않음 |

여기서 “거부”는 현재 계약에 맞는 4xx/domain rejection 또는 실제 DB/lease guard 실패이며, 검사에는 실제 도달한 경계와 participant 불변을 함께 남긴다. UI 버튼·총 검사 수·코드 독해만으로 확인하지 않는다. raw job binding/result·lease takeover·제어 Clock/실제 lock 대기를 구분한 증거를 보존하고 새 개발/지정 독립 전체에 연결한다.

이 판단은 기존 owner quote/consent 작업과 독립적으로 적용 가능한 기술 계약이다. 새 무권한 endpoint·사업 정책·실수납/면제·손실 금전 처분·E 이후 범위는 추가하지 않는다. 본 검토에서는 설계 문서만 갱신했으며 제품/검사/하네스 실행·커밋·푸시·배포는 하지 않았다.

## 13. 공통 최초 배송비 의무와 C/D 누적 종결 — 2026-11-01

**기술 판단: seller-order 단일 배송비 의무를 공통 financial intent 귀속으로 확장하고, SHIPPING 전용 binding·결과·정산 경로를 둔다.** §5의 확정 정책을 구현하기 위한 §6/12의 구체화다. RETURN의 ITEM fee 계약을 느슨하게 하거나 완료된 부분 반품을 다시 여는 안은 채택하지 않는다. 새로운 사업 정책 선택은 필요하지 않다.

정적 근거는 `worker-review1/source` 입력585 SHA256 `5F0CA12AB8BEB6A2AF8117100EF209F585E5389851CFA2E2F0F53C54CB604605`의 V44, FinancialIntents/JdbcFinancialIntents, JdbcClaims, JdbcFinancialResults, ReturnFinancialWorker, AftercareSettlements/JdbcAftercareSettlements, PaymentCoordinator 및 본 문서 §5/6/12다. live SUPPORT 구현은 이 판단 대상이 아니다. 고정본은 V67까지이며 부모가 알린 V68 적용 상태는 별도 최신 작업 근거를 따른다. **적용 V41~68은 수정하지 않고 이후 신규 migration으로 확장한다. 아래 서명·검사는 설계이며 NOT RUN이다.**

### 13.1 독립 의무와 terminal 경계

의무의 사업상 주체는 원 seller-order의 `ORIGINAL_SHIPPING_REFUND`다. V44의 seller_order_id 단일 고유성·원 S는 유지한다. 현재 CANCEL claim 복합FK를 공통 intent의 order/seller 복합 귀속으로 확장하되, 기존 obligation/attempt/cause/금액/결과를 새 의무로 복제하거나 재예약하지 않는다. 공통 부모 FK가 있다고 CANCEL/RETURN 모두 무조건 허용하는 것은 아니며, SHIPPING 전용 근거 검증을 추가한다.

- `originIntentId`는 의무를 최초 생성한 실제 마지막 C 또는 D 처리와 감사 연결을 제공한다. 이것을 SHIPPING 적용 시 ACTIVE여야 할 ITEM hold로 해석하지 않는다. 현재 의무의 식별·revision·current attempt·정산 receipt가 SHIPPING의 재생/신규 적용 경계다.
- 이전 부분 RETURN은 COMPLETED/REFUNDED와 기존 완료 receipt를 유지한다. 마지막 C 취소 때문에 처음 S 의무가 발생해도 이전 RETURN의 version/완료시각/ITEM 결과·재입고를 바꾸지 않는다. 기본적으로 마지막 C를 origin으로 삼을 수 있으며, D origin을 참조하는 의무라도 terminal 조회는 불변 증거 확인만 한다.
- §12의 “REFUNDED에서 새 적용 금지”는 해당 hold의 ITEM/단위 효과·새 반품 명령에 적용된다. **별도로 정당하게 생성된 seller SHIPPING 의무의 최초 정산은 별도 operation**이며 이 금지와 충돌하지 않는다. SHIPPING worker는 ReturnFinancialWorker의 ACTIVE/ITEM/fee guard를 우회 호출하지 않고 전용 coordinator로 처리한다. RELEASED/REFUNDED hold만으로 S 의무를 생성할 수는 없다.
- 당시 부분완료는 배송비 판정 PENDING을 참조할 수 있다. 이를 나중에 수정해 REQUIRED로 소급하지 않는다. 새 seller 결정 revision/새 의무 근거가 누적 전체 사실을 참조한다. 부분완료 receipt와 현재 seller 배송비 상태는 조회에서도 구분한다.
- 마지막 RETURN이 준비되어 S가 필요한 경우 그 RETURN은 S 성공까지 완료를 기다린다. 마지막 C도 혼합 전체를 만드는 경우 seller 정산 대기를 기록할 수 있지만 이전 완료 RETURN을 되돌리지 않는다. 기다리는 주체와 이미 완료된 주체를 단일 claim state로 합치지 않는다.

### 13.2 최소 공개 포트와 소유권

아래는 의미상의 최소 계약이며 타입/이름은 조정 가능하다. trusted transaction 전용이고 HTTP에서 금액·eligible/settled boolean을 받지 않는다.

```java
// order: 자기 소유 원단위·원 S·원 결제 식별·기존 정산 projection만 조회
AftercareView lockAftercare(UUID sellerOrderId);
void recordReturnCompletion(ReturnCompletionFact fact);
void recordShippingSettlement(ShippingSettlementFact fact);
void evaluate(UUID orderId);
boolean aftercareClosureMatches(UUID orderId, UUID originalPaymentId);

// claim: 의무 식별은 서버의 저장 결정에서 얻음
ShippingDecision evaluateOriginalShipping(UUID sellerOrderId);
void bindReturnShipping(UUID intentId, UUID obligationId, UUID attemptId);
List<ShippingResult> shippingResults(UUID obligationId);
```

1. `AftercareView`는 orderId/sellerOrderId/checkout·원 S·원단위 canonical 집합, 현재 lifecycle/hold, 불변 취소정산·반품완료·확정 사실의 식별을 제공한다. order는 claim/payment 테이블을 직접 조회하지 않는다. claim coordinator가 해당 조회와 자신의 실제 수령/검수·책임·동의·ITEM 결과/fee·미수납을 대조한다. order 반환값을 임의 “전부 환불됨” boolean 하나로 축소하지 않는다.
2. **배송비 평가 입력은 최종 반품완료만 요구하지 않는다.** 마지막 RETURN은 아직 S를 기다리므로, 검수/책임/ITEM 정산까지 끝난 claim 소유의 불변 `return-item-ready` 근거를 누적 평가에 포함한다. ready는 수령·검수와 SALEABLE 입고, 실제 ITEM 성공/fee 적용 및 미수납0을 참조하고 hold를 완료하지 않는다. 이미 완료한 RETURN은 해당 완료 receipt와 그 ready를 참조한다. 따라서 “S 필요→완료 필요→S 성공 필요” 순환이 없다.
3. `ReturnCompletionFact`는 실제 return/intent·hold/cause·정확한 단위, ready/inspection·ITEM result/fee application·배송비 결정 또는 정산 참조를 담는다. claim은 자기 근거를 검증하고 order는 자기 hold/단위·원 주문 귀속과 기존 receipt를 검증한다. 완료 hold 전이와 order projection·claim completion·조건부 쿠폰/종결은 같은 transaction이다. 참조 문자열만으로 완료가 되지 않도록 deferred graph가 실제 반대편 영수증도 검증한다.
4. `ShippingSettlementFact`는 obligation/decision revision·원 payment/order/seller·amount·실제 SHIPPING attempt/result version/reference를 갖는다. S=0은 아래 zero-settlement의 별도 증거 타입으로 표현한다. 기존 C `record(Item)`/기존 fingerprint와 원 cancellation receipt는 그대로 보존한다. order의 새 공통 정산 조회는 기존 취소 행을 포함하며 두 저장소에 같은 seller 배송비 효과를 이중 생성하지 않는다.
5. RETURN ITEM의 `bindReturnRefund`는 assessment 필수·purpose ITEM·금액/원 quote 계약을 그대로 유지한다. SHIPPING 전용 binding은 fee assessment를 받지 않고 obligation의 current attempt·원 S·원 payment/order/seller·origin intent·purpose SHIPPING·sequence·원 reference를 검증한다. nullable assessment를 핑계로 임의 RETURN purpose를 허용하지 않는다. 기존 CANCEL binding도 새 공통 의무 검증을 거치며 이전 C 재생은 호환한다.
6. SHIPPING 결과 타입은 obligation/attempt/version/state/amount/reference/time을 포함한다. 기존 `FinancialIntents.Result`의 non-null ITEM assessment 가정을 깨지 않는다. 결과 dispatcher는 저장 purpose/binding으로 먼저 분기하며 SHIPPING을 ITEM fee worker·반품 ITEM 결과 테이블로 보내지 않는다. payment는 claim 내부 Java에 의존하지 않고 기존 공개 결과/예산 계약을 유지한다.

### 13.3 판정·예약·재평가와 거래

부모 order 잠금 아래 seller의 전체 원단위를 한 번 평가한다. 실제 취소정산 또는 SELLER return-ready/완료로 전부 채워져야 REFUND_REQUIRED다. BUYER가 포함된 최종 전체는 RETAINED, 미처리/회수·검수·책임·ITEM 불명확은 PENDING이며 §5 표를 유지한다. 미처리 단위가 있는 동안 최종 RETAINED를 발급해 재평가를 닫지 않는다. 미수납>0을0으로 보거나 배송비로 상계하지 않는다.

판정은 decision revision과 입력 사실 fingerprint/단위별 근거를 보존한다. REFUND_REQUIRED가 처음 성립하면 동일 seller 단일 obligation을 생성하거나 기존 것을 재생한다. 원 S>0은 같은 transaction에서 실제 Refunds.reserve(SHIPPING)·typed binding·예약 receipt·Outbox를 저장한다. C/D 양쪽이 같은 의무 잠금·공통 parent payment budget을 사용한다. 이미 성공/예약된 의무를 다른 origin intent나 새 key로 또 만들지 않는다.

S=0은 원 주문 무료배송 사실에 결합한 명시적 ZERO_SETTLED 결정/정산 receipt를 남긴다. provider 호출·금액 있는 환불0이며, 구현이 기존 C의 실제0원 내부 attempt를 재사용하면 그 자동성공 근거를 보존한다. 과거0원 attempt를 삭제하거나 가짜 provider reference를 새로 만들지 않는다. zero receipt와 positive SHIPPING 성공의 요건을 타입/DB 제약으로 분리한다.

**양쪽 ITEM 처리에서 공유 seller 재평가를 발행한다.** C ITEM 정산 완료와 D return-ready/완료의 각 불변 사실을 저장하는 거래에서 `seller-aftercare:<seller>:<fact-id>` 등 사실별 고유 키로 같은 handler의 Outbox를 남긴다. seller 하나에 영구 고정 dedupe key를 써 첫 실행 뒤 후속 사실을 누락시키지 않는다. worker는 payload의 수량/책임을 믿지 않고 저장 사실을 다시 읽는다. SHIPPING 결과 정산 뒤에도 기다리는 마지막 C/D와 seller 종결의 재평가를 발행한다. 역순·중복·ACK 유실은 현재 결정/operation receipt로 수렴하고 영속 스캔은 미처리 사실/의무를 다시 찾을 수 있어야 한다.

거래 잠금은 identity(현재 SUPPORT일 때)→order→canonical units/holds→claim intents/ready·공통 shipping decision/obligation→필요한 정렬 fee 자료→payment budget/attempt→coupon/필요 inventory→최종 lease 또는 SUPPORT 재검사다. SHIPPING 자체는 재입고를 수행하지 않는다. C 기존 직접 SHIPPING 생성 경로와 새 D 경로는 같은 공통 평가/잠금으로 수렴시켜야 하며 두 독립 allocator를 남기지 않는다. provider 호출은 거래 밖 기존 payment worker가 맡는다.

SHIPPING worker는 실제 저장 job·obligation/current binding·보존된 실제 Refunds 결과를 확인하고 정산/history/Outbox 및 order projection을 원자 저장한다. 최종 업무 잠금 뒤 필수 lease, receipt 재생에도 동일 guard가 필요하다. UNKNOWN은 같은 attempt query·예약 유지, definitive FAILED는 실제 현재 SUPPORT/reason/key/revision으로 같은 의무의 새 sequence만 허용한다. 실패 attempt/binding history는 보존하고 current pointer만 전이한다. 오래된 FAILED/UNKNOWN은 현재 성공을 역행시키지 않는다. 반품 ITEM의 retry API를 S 재시도에 사용하지 않는다.

### 13.4 종결·쿠폰·late APPROVED

order의 종결 평가는 단위별 CONFIRMED 또는 실제 CANCEL 정산/RETURN 완료 projection을 요구한다. REFUNDED 문자열만으로 취소로 분류하지 않는다. 미완료 hold·분쟁·미수납·필요 SHIPPING 미정산이 있으면 해당 seller 종결/쿠폰과 부모 종결0이다. 다른 seller가 남으면 부모 PAID를 유지한다.

전부 미발송 CANCEL 정산일 때만 CANCELED/ALL_UNSHIPPED_CANCEL이다. 실제 RETURN이 하나라도 포함된 전체 환불은 CLOSED/ALL_RETURNED 또는 MIXED_AFTERCARE이며 CONFIRMED 혼합도 CLOSED다. 원 payment 식별과 각 closure reason의 실제 단위 projection/배송비 정산을 불변 closure receipt에 결합한다. 완료 단위/receipt는 이후 부모 상태와 무관하게 재생할 수 있어야 한다.

쿠폰은 seller 모든 원단위가 CANCEL/RETURN 완료이고 미수납·필수 SHIPPING이 해결됐을 때 원 coupon/seller 공통 cause로1회 복구한다. CONFIRMED가 남으면0, 만료는 복구 receipt와 reusable=false를 구분한다. C와 D가 서로 다른 cause로 중복 복구하지 않도록 기존 C cause/receipt를 이행·재사용하고, 두 경로 모두 실제 promotion 포트의 동일 원 의무를 사용한다.

고정 PaymentCoordinator는 이미 CLOSED+원 attempt SUCCEEDED/compensation NONE에서 재통지를 무효과 처리한다. **CLOSED이면 무조건 성공이라는 새 완화는 필요 없다.** 확장 closure proof와 원 payment의 일치를 회귀 검사하고 CANCELED의 기존 customerCancellationSettled 보장은 유지한다. 필요하면 기존 포트를 공통 `aftercareClosureMatches`로 확장하되 실제 order 소유 closure 근거에만 응답한다. 실제 결제 성공 없는 만료/취소 주문의 late success 보상이나 CLOSED_PAYMENT_CONFLICT 처리를 막지 않는다. 실제 정상 C/D aftercare 종료 뒤 동일 원 APPROVED는 추가 compensation/refund/재고·쿠폰 효과0이어야 한다.

### 13.5 신규 DB graph와 실제 assertion 계획

새 migration은 기존 C 데이터에 대한 실제 귀속을 검증한 후 공통 FK/타입을 확장한다. 기존 단일 seller obligation·attempt 고유성·payment 예산·ITEM fee 제약은 약화하지 않는다. source/결정/binding/history/결과/완료 projection은 필요한 immutable·복합FK와 양방향 deferred graph를 둔다. 가짜 SHIPPING binding/ready·receipt만 또는 실제 효과만 저장한 commit, RETURN을 CANCEL로 위장한 closure, fee assessment 없는 ITEM, 다른 seller/원금/책임 snapshot 결합을 거부한다. graph는 transaction 전체의 최종 사실을 검사하되 미래 정상 재시도·다른 단위 완료 때 과거 receipt가 현재 revision과 영원히 같아야 한다고 요구하지 않는다.

| 제약·실제 검사 제안 | 필수 assertion |
| --- | --- |
| D-last와 C-last 두 순서 | 공개 실제 C 일부취소→D 나머지 SELLER 회수·검수·ITEM정산, 반대로 D 부분완료→C 마지막 취소. 둘 다 seller S 의무1/원 S 지급1·동일 원 payment 한도, CLOSED/혼합 reason. 두번째는 옛 RETURN receipt/version/REFUNDED hold·재고·ITEM 효과 완전불변. 마지막 C 경로가 orders.allCanceled=false라는 이유로 S 평가를 빠뜨리지 않음 |
| 지연·동시 마지막 사실 | 두 claim의 실제 parent 잠금 경합, 재평가 job 순서교환·commit-before-ACK 재전달·실제 child 중단 후 영속 재개. 단일 obligation/attempt current sequence·정산/쿠폰1, 모든 원 실패 증거 보존 |
| 준비와 완료 분리 | 신청/승인/일부수령/검수없음/SELLER미확정/ITEM UNKNOWN 각각 S 예약·지급0. 마지막 ready에서 S 예약 가능하나 필요한 S UNKNOWN/FAILED 동안 마지막 완료·쿠폰/종결0. SELLER 완료 뒤 S 성공이면 정상 완료; 미수납>0은 hold/종결0 유지 |
| 책임·원 S | BUYER 포함 전체/취소혼합은 S환불0; 여러 원배송에서도 seller S1. 현재 배송 정책 변경 영향0. S0 명시 정산근거·provider호출0. C 전체미발송은 기존 CANCELED와 원0원 경로 보존 |
| typed 경계·DB 공격 | RETURN SHIPPING을 ITEM bind/apply에 전달409, assessment없는 ITEM 거부, 다른 obligation/intent/order/seller/payment/amount/reference 거부·전체불변. 실제 coordinator의 ready/binding/정산/완료 receipt INSERT를 각각 누락시켜 commit23514·전체rollback, 올바른 재개1 |
| 실패·재시도·권한 | 실제 SHIPPING UNKNOWN 동일attempt query, FAILED만 SUPPORT 새sequence·원 의무/금액 불변. 옛 결과 후착 역행0. null/fake job/타token 및 최종 실제 업무잠금 뒤 lease/SUPPORT 만료는 전부rollback; 새 owner 재개1 |
| 종결·쿠폰·late APPROVED | CONFIRMED 혼합 쿠폰0, 만료쿠폰 receipt1/reusable=false, 다른seller 미완료 부모PAID. 전체미발송 CANCELED·실제RETURN CLOSED 각각 원 APPROVED 재통지 후 compensation/refund0. 진짜 미결제취소 late success는 기존 보상 계약 유지 |
| terminal SHIPPING 독립성 | 이미 COMPLETED/REFUNDED인 부분 RETURN의 과거 조회/재생은 유지하면서 뒤의 독립 SHIPPING 의무 처리 성공. terminal hold를 근거로 새 ITEM/회수·입고·fee 적용 시도는 거부. 부분 RETURN을 REFUND_PENDING으로 되돌리는 UPDATE0 |
| 소유권·보존 | order adapter 실제 JDBC recorder에서 claim/payment 직접조회0, 기존 C JUnit/ArchUnit·DB·브라우저 필수 목록 유지. 적용 V41~68 해시불변·새 격리 DB/미디어·실패 이력 보존·선택없는 개발/독립 전체를 최종 완료 근거로 연결 |

이 절은 SHIPPING/종결 구현을 위한 기술 계약이며 구현·검사 통과 기록이 아니다. 설계 문서만 갱신했고 제품/검사/하네스 실행·커밋·푸시·배포는 하지 않았다.

### 13.6 마지막 B 구매 확정과 RETURN 혼합 종결

**추가 판단: 기존 B 확정 사실을 claim 측 공통 seller 재평가에 연결한다.** order.evaluate가 원단위 CONFIRMED만 보고 claim 소유의 PENDING 결정을 남겨 둔 채 혼합 주문을 먼저 종결하는 방식은 채택하지 않는다. order→claim 직접 호출/SQL은 추가하지 않는다. 이는 §13.3의 C/D 사실 발행에 **B confirmation 사실 소비**를 추가하는 연결 보완이다.

정적 근거는 `support-review1/source` 입력589 SHA256 `B19ADB452E81F1760FA6DF26711CD49FB7FAC3097F3164A2CE9746849BE24053`의 JdbcOrderConfirmations.write(단위/불변 confirmation receipt→AftercareSettlements.evaluate→ORDER_CONFIRMATION_RECORDED Outbox)와 ConfirmationJobs.confirmationRecorded(현재 no-op COMPLETE)다. live pure decision/order projection 구현은 확인하지 않았다. direct/auto가 공유하는 저장 확정 경계를 사용하며 B 공개 API·command key/fingerprint·최초 Result·이력은 바꾸지 않는다.

연결은 다음 최소 계약으로 한다.

1. 기존 `ORDER_CONFIRMATION_RECORDED`의 confirmationId와 `confirmation-recorded:<id>` 식별을 유지한다. claim 측 handler/coordinator가 이 사실을 소비하여 영향을 받은 seller별 공통 재평가를 수행하거나 원자적으로 발행한다. 현재 no-op handler와 같은 type의 handler를 둘 등록하지 않는다. 기존 handler를 claim 측 소비자로 옮기거나 platform 중재로 연결할 수 있으나 order가 claim 구현/API를 참조하는 역의존은 금지한다.
2. order 공개 포트에 `ConfirmationFact lockConfirmation(UUID confirmationId)` 또는 동등한 조회를 제공한다. fact는 실제 order/confirmation/cause(DIRECT/AUTO)/canonical 원단위·각 seller 귀속을 담으며 order 소유 receipt/unit/lifecycle을 대조한다. UUID payload는 locator일 뿐 신뢰 증명이 아니다. claim은 order 테이블을 직접 JOIN하지 않고 이 포트와 `lockAftercare`를 사용한다. 새 handler는 실제 저장 job의 type/dedupe/cause/payload·현재 lease도 검증한다.
3. 한 confirmation이 여러 seller 단위를 포함하면 실제 영향 seller를 모두 재평가한다. 별도 작업으로 나눌 때 키는 `seller-aftercare:<seller>:confirmation:<confirmationId>`처럼 **사실별**로 고정한다. duplicate/ACK 유실은 효과0이며 seller 하나에 영구 dedupe key를 쓰지 않는다. 동기 소비라면 결정·projection·종결과 최종 lease를 한 거래에 묶고, fan-out이면 모든 하위 Outbox 발행과 원 job의 최종 fence를 원자화한다.
4. 공통 pure decision은 원단위 중 실제 CONFIRMED가 있고 나머지도 CANCEL 정산/RETURN ready·완료 등 최종 사실을 갖춘 경우 **RETAINED(reason=CONFIRMED_UNITS_REMAIN)**를 반환한다. S 환불 obligation/attempt/provider 호출은0, 원 S는 유지한다. 아직 처리 중인 RETURN·hold·분쟁·미수납이 있으면 최종 seller 종결을 허용하지 않는다. 현재 §5의 부분 반품+보유/확정 미환불 규칙이며 새 사업 정책이 아니다.
5. claim의 최종 RETAINED decision과 order 소유 shipping-decision projection을 같은 거래로 저장하고 `AftercareSettlements.evaluate`를 다시 호출한다. order는 실제 CONFIRMED 단위와 다른 단위의 취소정산/반품완료 projection, 동일 seller/decision revision·참조의 최종 RETAINED를 검증한다. 금전 SHIPPING 성공 receipt를 만들거나 REFUND_REQUIRED를 단순 삭제하지 않는다. 기존 PENDING은 이력으로 보존하되 **현재 결정**은 RETAINED가 된다. 실제 유효 SHIPPING 예약이 있는데 모순되는 CONFIRMED가 나타나면 정상 유지 판정으로 덮지 않고 불일치로 거부/기록한다.
6. B write에서 먼저 호출되는 order.evaluate는 RETURN 혼합에서 필요한 최종 decision projection이 아직 없으면 변경 없이 보류한다. B 확정 자체를 실패시키거나 원 confirmation receipt를 뒤에 다시 쓰지 않는다. 이후 claim 소비 거래에서 RETAINED projection과 CLOSED closure가 함께 확정되므로 CLOSED인데 current decision=PENDING인 상태를 만들지 않는다. 순수 ALL_CONFIRMED와 기존 C/B만의 이미 검증된 종결은 종전 동기 경로를 유지하며 D 이벤트를 기다리는 불필요한 장벽을 추가하지 않는다.

CONFIRMED-first이면 B 이벤트 당시 다른 단위가 아직 OPEN/RETURN 미완료여서 PENDING일 수 있다. 이후 D ready/완료 이벤트가 같은 evaluator로 최종 RETAINED를 만든다. RETURN-first이면 부분 RETURN 완료/PENDING이 보존된 상태에서 마지막 B 이벤트가 최종 RETAINED를 만든다. 이벤트가 역순 도착해도 handler는 저장된 최신 사실을 읽으며 과거 PENDING 결과를 현재 결정에 덮어쓰지 않는다. 이미 처리 완료된 예전 B job도 있었을 수 있으므로, 후속 D 사실의 재평가와 미종결 혼합 seller에 대한 영속 재평가가 안전망이다. 기존 완료 job 상태/원 receipt를 강제로 초기화하지 않는다.

최종 RETURN+CONFIRMED seller는 쿠폰 복구0이고, 다른 seller 미완료면 부모 PAID다. 전 seller가 적법하게 끝나면 CLOSED와 RETURN/CONFIRMED 혼합을 구분하는 closure reason·원 payment 근거를 남긴다. 원 APPROVED 재통지는 보상/환불/입고/쿠폰 효과0이며 기존 진짜 late-success 보상 계약은 유지한다. 원 RETURN 완료 receipt·hold·ITEM/fee 효과와 B 동일키 Result 재생은 그대로 유지한다.

추가 실제 검사는 **NOT RUN**이며 다음 assertion을 기존 D04/B 회귀에 더한다.

| 순서·경계 | 필수 실제 assertion |
| --- | --- |
| CONFIRMED-first / RETURN-first × DIRECT / AUTO 네 경우 | 실제 공개 확정 또는 적격 기한의 실제 auto job, 실제 RETURN 수령·검수·ITEM 정산·완료를 사용. 최초 원net에 따른 RETURN ITEM만1회, CONFIRMED 원금 환불0·원 S 유지/SHIPPING 예약·provider0·쿠폰0. 최종 current decision RETAINED/부모 CLOSED이며 PENDING은 과거 이력만 남음 |
| RETURN-first B 직후와 이벤트 처리 후 | B command는 기존 receipt로 성공하고 혼합 decision 미반영 중 부모 PAID. 실제 confirmation 소비 뒤 RETAINED projection/closure가 같은 거래로 반영. 원 RETURN receipt/version/hold·입고 불변, B key 재생 원 Result 동일·새 event/effect0 |
| 다른 seller·미완료 조건 | 한 seller RETURN+CONFIRMED 종결 후 다른 seller 진행 중 부모PAID. 미완료 hold·미수납·검수/ITEM 불명확 상태에서는 confirmation만으로 완료/쿠폰0 |
| 이벤트 유실·역순·경합 | 실제 B/D 두 이벤트 역순·중복·ACK 유실·worker lease 상실/재인계·영속 재평가에서 결정 및 closure1·환불0. 기존 no-op으로 ACK된 B 사실 뒤 D 처리도 수렴. 다중seller confirmation은 누락0 |
| projection/closure proof | 실제 coordinator의 RETAINED decision 또는 order projection INSERT만 누락하면 closure commit 거부/전체rollback, 올바른 재개1. 다른seller/가짜confirmation/CONFIRMED 없는 RETAINED를 결합한 graph 공격 거부. order JDBC claim 직접조회0 |
| 기존 B·late APPROVED | 순수 ALL_CONFIRMED direct/auto 기존 동기 종결·동일키/종결 후 재생 유지. 네 혼합 종료 뒤 같은 원 APPROVED 재전달은 compensation/refund·재입고·쿠폰0, 실제 미결제취소 late success 회귀도 유지 |

본 보완은 설계 문서만 변경했다. 제품·검사·하네스 실행·커밋·푸시·배포는 하지 않았다.

## 14. 영속 누락복구의 별도 전달 식별과 실행 근거 — 2026-11-01

**판단: 실제 업무 사실에 묶인 immutable recovery-delivery manifest와 recovery 전용 dedupe key를 채택한다.** 같은 실제 handler type/payload/cause를 쓰되 primary와 recovery의 허가 근거를 명시적으로 구별한다. 새로운 사용자 정책·금융 권한은 추가하지 않는다.

정적 기준은 `r02-fix-review1/source` 입력599 SHA256 `3F3E74B4DAE01E0C4C123AF6B187D59BFB73A9F000DF2B8666C6C87BD4F85865`의 Jobs/JdbcJobs, ConfirmationJobs scan, ReturnFinancialWorker, JdbcFinancialResults/ReturnShippingResults 및 SellerAftercareWorker다. live 후속 파일은 읽지 않았다. 이후 V72/source601의 SHIPPING SUPPORT 성공은 부모가 전달한 별도 실행 정보이며 본 설계의 정적 검토·실행 근거에 포함하지 않는다. 적용 V41~72는 불변, 필요한 graph 확장은 신규 migration으로만 한다. 아래 계약·검사는 **설계/NOT RUN**이다.

### 14.1 대안과 선택

| 방식 | 판단 |
| --- | --- |
| 같은 primary Outbox/dedupe 재발행 | 단순하지만 JdbcJobs.enqueue가 기존 SUCCEEDED 행을 반환하므로 잘못 ACK된 전달 누락을 복구하지 못함 |
| 기존 job을 READY로 초기화·가짜 primary Job 생성 | 과거 실행/토큰 이력을 훼손하거나 실제 저장 job 검증을 우회하므로 금지 |
| 별도 recovery handler가 원 coordinator 내부 메서드를 직접 호출 | 타입은 분리되지만 권한/lease/graph를 두 경로에서 유지해야 한다. 기존 worker를 가짜 primary 객체로 호출하는 지름길 위험도 있어 현재에는 불필요 |
| 같은 실제 handler + typed delivery proof | 업무 검증·원자성·최종 lease를 한 경로에 유지하면서 새 실제 job으로 재전달 가능. 채택 |

primary key를 무조건 prefix 허용으로 바꾸는 것은 채택안이 아니다. 전달 검증은 실제 Jobs.find와 아래 exact manifest 대조를 함께 수행한다. 원 primary 형식 검증도 그대로 유지한다.

### 14.2 세 복구 구간과 업무 제한

| stage | 후보 근거·재전달 대상 | 허용 효과·금지 |
| --- | --- | --- |
| ITEM_EFFECT | 실제 buyer consent + 현재 ITEM binding/assessment + 불변 claim 보존 결과(attempt/resultVersion)가 있고 해당 D operation receipt가 없는 경우. `RETURN_FINANCE_RESUME.v1`, 기존 return/attempt/version payload·cause | 실제 현재 Refunds와 다시 일치하는 SUCCEEDED/FAILED/UNKNOWN 결과를 기존 D 규칙대로 적용/기록. 신규 payment attempt·provider submit·SUPPORT 승인·동의 변경0 |
| SHIPPING_RESULT | 실제 typed SHIPPING binding·seller 단일 obligation/current attempt, 공개 Refunds에서 확인한 실제 결과/version에 해당 shipping result/정산 전달이 누락된 경우. `REFUND_RESULT_RECORDED.v1`, 기존 attempt/version payload·cause | 목적 SHIPPING으로 기존 dispatcher에 진입해 실제 결과 보존/허용 정산만 수행. UNKNOWN/FAILED를 성공으로 바꾸지 않고 retry/query 새 명령을 만들지 않음 |
| SELLER_REEVALUATE | 실제 immutable ready가 있으나 미완료 또는 seller current decision/필요 후속 평가가 누락된 경우, 또는 실제 SHIPPING result 뒤 완료 평가가 누락된 경우. `SELLER_AFTERCARE_REEVALUATE.v1`, 기존 RETURN_READY 또는 SHIPPING_RESULT route | 최신 order 공개 사실·claim 근거를 재평가해 기존 규칙대로 결정/완료. 당시 전체가 충족되지 않아 PENDING인 것은 실패가 아니며 강제완료0. 기존 정책으로 처음 S 의무가 성립할 때의 정상 예약은 가능하나 scanner 자체는 예약하지 않음 |

ITEM의 **C 공통 결과 보존 자체가 아직 없는 구간**은 ITEM_EFFECT 완료로 표시할 수 없다. 이번 최소 scan은 보존 ITEM 결과 이후의 D 누락을 대상으로 한다. C 결과 수집 누락까지 포괄하려면 실제 current payment 결과→기존 collector 전달의 별도 typed stage와 검사를 명시해야 하며, manifest로 가짜 보존 결과를 만들어 대체하지 않는다. 실제 provider 결과가 아직 PROCESSING/UNKNOWN이라 다음 조회가 필요한 상태도 전달 누락과 구별한다. query/retry 정책은 기존 payment recovery·현재 SUPPORT 경로가 소유한다.

이미 operation receipt가 있는데 같은 거래의 일부 효과/ready가 없다면 먼저 무결성 위반으로 기록한다. scan이 성공 receipt를 무시하고 raw SQL로 부족한 행을 채우거나 돈을 재적용하지 않는다. 지원된 과거 버전의 합법적 누락 이행은 별도 검증 계약이 필요하다.

### 14.3 최소 typed proof와 소유권

claim 소유 `RecoveryRun`/`RecoveryDelivery` 및 작은 내부 validator를 둔다. 의미상 최소 필드는 다음과 같다.

```java
enum RecoveryStage { ITEM_EFFECT, SHIPPING_RESULT, SELLER_REEVALUATE }
record Delivery(UUID id, UUID runId, RecoveryStage stage,
                UUID orderId, UUID sellerOrderId, UUID returnOrIntentId,
                UUID attemptId, Long resultVersion, UUID sourceFactId,
                String sourceFingerprint, UUID jobId,
                String type, String dedupeKey, String causeKey, String payload) {}
// 실제 저장 job과 원업무 사실을 대조하는 내부 포트; HTTP에 노출하지 않음
Delivery requireRecoveryDelivery(Jobs.Job actualJob, RecoveryStage expectedStage);
```

stage별 필요한 식별은 non-null/CHECK로 강제한다. ITEM은 consent/assessment/current binding·보존 결과 identity, SHIPPING은 obligation/current binding·원 S·payment/result identity, seller는 ready/실제 shipping result와 seller 귀속을 연결한다. 필요 필드는 별도 typed 테이블로 분리해도 된다. caller가 보내는 eligible/success/worker boolean이나 금액 override는 없다. immutable sourceFingerprint는 금액/귀속 검사를 대신하지 않는다.

키는 예를 들어 `d-recovery:<deliveryUUID>`로 만들고 manifest에 exact 값을 저장한다. 별도로 `(runId, stage, sourceFactId, attemptId/resultVersion 또는 사실 fingerprint)`의 **NULL에도 중복이 생기지 않는** 고유성을 둔다. 같은 run/page 재실행은 같은 delivery/job을 반환한다. UUID는 권한이 아니고, payload/cause는 원 canonical 업무 route와 동일해야 한다. 원 primary key와 recovery key를 분명히 분리하며 원 job id/status/token/receipt는 변경하지 않는다.

order/payment/platform storage는 기존 공개 포트로만 읽는다. scanner는 자기 claim 후보를 찾을 수 있으나 payment 결과를 직접 JOIN하지 않고 Refunds, 원단위/현재 종결을 AftercareSettlements/ReturnApplications, 실제 job을 Jobs/JobLeases로 확인한다. FK/deferred graph의 교차 연결과 Java adapter 직접 SQL을 구별한다. manifest는 전달 증거이지 결제 성공·SUPPORT 권한·신규 동의 근거가 아니다.

### 14.4 bounded scan·cursor·원자성

1. 내부 신뢰된 스케줄러만 run을 생성한다. 임의 외부 runId/upper/cursor로 금융 worker를 만드는 endpoint는 추가하지 않는다. run은 시작 시각, stage별 고정 upper, 순서 정의·scan version을 저장한다. 한 page는 최대50 locator이며 안정된 전체 키(동일시각 tie-break 포함)로 진행한다. 단순 UUID max를 쓰면 이는 유한 상한일 뿐 시점 snapshot이 아님을 명시한다. cursor 뒤/앞으로 늦게 들어온 사실은 다음 run에서 다시 포착한다.
2. run/page의 claimed 실제 scan job·payload/cursor를 저장 근거와 대조한다. cursor는 caller가 건너뛸 수 없으며 page receipt에 읽은 범위·후속 cursor·delivery 또는 skip 이유를 남긴다. 고정 upper보다 뒤 사실은 다음 run으로 넘긴다. 마지막 page 뒤 재개해도 끝없이 동일 page를 만들지 않는다.
3. 각 candidate는 부모 order부터 기존 전역 순서로 자기 사실/current binding/공개 결과를 확인하고 immutable manifest+실제 Outbox/job을 **같은 transaction**으로 저장한다. Outbox가 반환/생성한 실제 jobId를 manifest에 결합하고 양방향 graph로 짝을 요구한다. manifest를 먼저 별도 commit하고 나중 job을 발행하지 않는다. scan 자신이 업무 효과를 쓰지 않는다.
4. page cursor는 해당 page의 후보가 delivery 또는 명시 skip으로 영속 처리된 뒤에만 전진한다. candidate 단위 transaction을 사용하면 page 재실행이 고유성으로 재생되고 cursor 마지막 저장만 별도 수행한다. scan row/cursor 잠금을 먼저 잡은 채 order/payment 잠금을 기다리는 역순을 만들지 않는다. 최종 cursor 거래는 새 업무 잠금 없이 page proof·후속 page Outbox·최종 scan lease를 검증한다.
5. candidate 전달 거래에도 마지막 업무 잠금 뒤 실제 scan lease가 필요하다. scan owner 상실은 해당 미커밋 manifest/job을 rollback한다. 이미 커밋된 이전 page/candidate는 유지하고 새 owner가 같은 run을 이어 간다. manifest에 기록한 발행 당시 scan job/token은 불변 감사이며 실행할 delivery 시점에 그 scan job이 영원히 RUNNING일 것을 요구하지 않는다.
6. 다음 run은 새로운 전달 기회를 제공하므로 영구 사실 dedupe만으로 복구를 막지 않는다. 이미 완료된 업무는 재검사 후 발행하지 않거나 무효과 재생으로 끝낸다. primary/recovery가 이미 RUNNING/READY이면 불필요한 새 전달을 줄일 수 있으나 그것만으로 영구 skip하지 않는다. 동일 run에서 delivery가 다시 잘못 ACK된 경우 새 무한 key를 만들지 않고 다음 bounded run이 새 delivery로 복구한다. 반복 scan의 허용 추가 자료는 run/page 감사이며 추가 금융/입고/쿠폰/종결 효과0이다.

### 14.5 worker·DB graph·오래된 결과

각 worker의 payload parse와 업무 검증은 유지하고 전달 검증만 `정확한 primary` 또는 `정확한 recovery manifest`로 분기한다. 실제 stored job의 type/key/cause/payload와 RUNNING/현재 owner를 확인하고, recovery이면 manifest의 jobId/stage/원 사실 식별을 exact 대조한다. **primary 객체로 바꿔서 다시 호출하지 않는다.** 원 job과 recovery job이 동시에 오더라도 같은 부모/원 의무 잠금 및 기존 operation 고유성으로 효과는1회다.

manifest 발행 때의 현재 상태는 실행 권한을 영구 보장하지 않는다. 업무 잠금 후 current binding·Refunds version/state/reference/amount·consent·assessment 또는 seller 사실을 다시 읽는다. 새 sequence/새 성공 뒤 옛 FAILED/UNKNOWN manifest는 명시 stale/409·효과0으로 끝내며 현재 상태를 역행시키거나 신규 attempt를 만들지 않는다. 정상 duplicate receipt는 기존 원 receipt/효과0·최종 delivery lease를 요구한다. terminal hold의 기존 ITEM 재생과 독립 SHIPPING 처리는 §12/13의 operation별 규칙을 그대로 따른다.

receipt에 기록하는 jobId/token은 **실제 recovery job**이다. 현재 SQL에서 primary dedupe만 허용하는 V66 등의 새 effect/receipt graph도 신규 migration으로 동일한 primary-or-exact-manifest 조건을 적용해야 한다. key prefix나 manifest 존재 하나만으로 old job/다른 seller/금액/attempt/resultVersion을 허용하지 않는다. manifest의 type/payload/cause/job과 원 사실의 관계, manifest↔delivery Outbox/job, 효과↔원 결과/receipt graph를 검증한다.

최종 업무 쓰기/잠금 이후 실제 delivery의 `requireOwned(jobId,ownerToken)`은 새 적용·재생 모두 필수다. scan job lease나 발행 당시 token으로 delivery lease를 대신하지 않는다. 새 receipt INSERT는 현재 실행 job/token을 검사하고, 이후 과거 receipt 재검증은 현재 RUNNING을 영구 요구하지 않는다(V71 원칙). manifest/source/job 식별은 불변으로 보존하며 old SUCCEEDED job을 새 receipt에 재사용하는 공격은 거부한다.

### 14.6 B 과거 no-op과 범위 구분

B의 과거 ORDER_CONFIRMATION_RECORDED가 no-op ACK됐더라도 뒤에 정상 D ready/완료 사실이 생기면 §13.6의 D 이벤트가 최신 CONFIRMED를 읽어 RETAINED로 수렴한다. 이는 B job 자체 복원이나 위 세 구간의 missing payment-result 복구와 다른 경로다. 반대로 RETURN-first 이후 B-last가 no-op ACK되어 seller가 PENDING/미종결이면, 기존 실제 ready를 anchor로 SELLER_REEVALUATE가 최신 order confirmation을 다시 읽어 수렴할 수 있어야 한다. 완료된 부분 RETURN만 있고 미완료 return이 없다는 이유로 이 seller를 scan에서 제외하지 않는다. 실제 ready/완료 anchor와 미종결/current decision 불일치를 자기 claim 후보 및 order 공개 view로 확인한다. 순수 B는 기존 B scan/종결 계약을 유지하며 claim이 임의 confirmation/단위를 생성하지 않는다.

### 14.7 필수 실제 assertion

| 경계 | 실행해야 할 실제 증거 |
| --- | --- |
| 세 구간 잘못 ACK | 실제 동의·ITEM 보존 결과, 실제 SHIPPING 결과, 실제 ready 각각을 만든 뒤 원 job을 실제 claim→의도한 no-op ACK로 SUCCEEDED 처리하고 업무 누락을 확인. 새 recovery jobId/key와 exact manifest로만 정상 복구·효과1. 원 job/실패/receipt byte-identical |
| 발행·cursor 원자성 | manifest/job/Outbox/page receipt/cursor/next-page 실제 INSERT·UPDATE 실패 주입과 프로세스 중단. 미커밋 전부rollback, 커밋 후 ACK 전 재시작은 같은 run/delivery 재생·누락0/중복효과0. 0/1/50/51/복수page·고정 upper·중간 추가 사실의 다음 run 회수 |
| 위조 전달·graph | manifest 없음/다른run/job/stage/return/seller/attempt/version/sourceHash/type/payload/cause/key, primary처럼 바꾼 Job·다른 token·old SUCCEEDED job 재사용 모두 거부. 실제 manifest 또는 Outbox/job INSERT 누락·효과 receipt만 저장 시 정확 SQLState/guard와 전 participant 불변 |
| 현재 결과·권한 | 무동의/미검수/fee미적용·미수납/틀린binding은 효과0. UNKNOWN/FAILED scan으로 새attempt/submit/SUPPORT승인0. 적법한 SUPPORT 새sequence/새성공과 오래된 recovery 양순서에서 최신상태역행0·과거원문보존 |
| lease·경합 | scan의 실제 최종 업무 대기 및 delivery의 실제 fee/payment/완료 대기 뒤 저장 deadline+1µs/실제 takeover. 구 owner의 해당 거래 전부rollback, 새 owner 재개1. primary와 recovery, 두run delivery 실제 동시 경합에서도 의무/효과1·최종 lease 생략불가 |
| 반복 run·종결 재생 | 첫 복구 후 같은run/새run 반복에서 금융·재입고·쿠폰·closure0추가. 완료 receipt의 실제 recovery job이 ACK된 뒤 재조회/다른 사실 처리도 정상, 그 job으로 다른 새receipt는 거부 |
| B no-op 두 순서 | CONFIRMED-first no-op ACK 뒤 D 사실 정상 수렴, RETURN-first 완료/PENDING 뒤 B-last no-op ACK는 ready-anchor scan으로 RETAINED/CLOSED. DIRECT/AUTO·원 S 유지/쿠폰0·원 receipt불변·late APPROVED 추가0 |
| 소유권·범위 | 실제 scanner/worker JDBC에서 외부 모듈 직접 relation0, 공개 포트 실제 결과 대조. 각 누락구간과 미지원 구간을 결과문서에 분리하고 새 격리 전체 개발/지정 독립 검증에 필수 이름 수집 |

이 계약 아래 구현 진행 가능하다. 현재 배송비 지원/SQL·graph·child 검사의 독립 진행을 막지 않는다. 다만 recovery 기능을 이 설계만으로 구현·검증 완료로 선언하지 않는다. 이번에는 설계 문서만 갱신했고 제품/검사/하네스·커밋·푸시·배포는 하지 않았다.

## 15. D4 최소 화면·현재 조회 계약 — 2026-11-01

**판단: 기존 구매자 주문 상세·판매자 하위주문 상세와 SUPPORT 주문 메뉴에 최소 반품 패널을 연결한다. 이 경계 안에서 Sol 구현 진행 가능하며 추가 사업정책 결정은 없다.** 아래는 §6 actor 분리와 D06을 구체화한 설계/필수 검사이며 **NOT RUN**이다. 제품 승인·D 전체 완료 판정이 아니다.

정적 기준은 `recovery-atomicity-fix-review1/source` 입력607 SHA256 `9139E7DCB3783EC073D77D4B61426726577C7FB5CC9198AC9CE9A324EB769027`이다. OrderDetailPage/OrderQueryStore/ClaimPanel·ClaimStore, SellerShipmentsPage, App의 orders→SUPPORT 접근 구분, OrderQueries/JdbcOrderQueries, Returns/ReturnFinances/ReturnCollections/ReturnInspections/ReturnSupport와 현재 controller/권한 구성을 읽었다. 적용 V41~75는 불변이며 검사 추가 중인 live 소스는 판단에 포함하지 않는다.

### 15.1 범위·구현 선택

기존 상세 화면 확장을 채택한다. 별도 전면 반품 대시보드는 탐색·필터·권한 범위를 불필요하게 넓히므로 이번 최소 범위에서 제외한다. UUID를 사용자가 직접 알아야 하는 명령 전용 화면도 새로고침·이력 발견을 해결하지 못하므로 채택하지 않는다. 기존 운영자 주문 영역은 SUPPORT 접근 구분이 있으나 고정본에는 반품 query/retry 전용 패널이 없으므로 그 메뉴의 최소 실기능으로 연결한다.

| actor | 최소 화면과 행위 | 제한 |
| --- | --- | --- |
| 회원/현재 GAS | 본인 주문의 서버 적격 원단위 선택·사유/주장 책임으로 신청, 반품 목록·현재 상세, 허용 철회, 실제 검수 뒤 최종 견적 확인·명시 동의 | 판매자 검수·SUPPORT 실행 불가. 접수/견적 조회만으로 금융 동의하지 않음 |
| 현재 판매자 | 기존 자기 하위주문 상세에서 반품 목록·상세, 승인·사유 있는 반려, 실제 수령/분실 보고, 수령 단위별 SALEABLE/DEFECTIVE·BUYER/SELLER 책임 검수 | 구매자 동의 대행·임의 환불/금액·미반환 물품 입고 불가. 신규 손실 처분 없음 |
| 현재 SUPPORT | 주문번호를 명시해 해당 주문의 반품 목록/현재 상세를 찾고 ITEM/SHIPPING 상태별 query 또는 definitive FAILED retry, 필수 사유 입력 | 전역 검색/새 관리 대시보드 불필요. buyer/seller 명령 대행·수납/면제·강제 성공/종결 불가 |

반품 승인/회수·검수, 최종 동의의 기존 backend 명령을 재사용한다. 원 요청의 책임 주장은 검수 책임의 확정값과 구분한다. LOST_REPORTED는 물리 미완료 표시와 기존 명령만 연결하며 손실 환불·hold 해제 같은 새 처분을 만들지 않는다. 미수납은 조회·종결 차단만 연결하고 수납·면제 버튼은 추가하지 않는다. 교환/E·새 정책·레이아웃 전면 개편은 제외한다. 파일/컴포넌트 분리, 세부 route 이름과 페이지 크기는 아래 보안·검증 책임을 유지하는 한 Sol이 선택한다.

### 15.2 최소 읽기 API와 서버 권한

현재 buyer 단건 `get(number,id)`만으로는 목록 재발견과 seller 화면을 만들 수 없다. claim 공개 조회 포트에 다음 의미를 추가한다. 새 목록을 order 내부에서 claim으로 역호출하지 않는다.

| 의미상 endpoint 예시 | 권한과 조회 범위 |
| --- | --- |
| `GET /api/orders/{number}/returns` 및 기존 `/{id}` 상세 | 같은 transaction에서 `OrderQueries.lockOwned(number,currentAuthority)`→해당 order의 claim 목록/상세→최종 현재 owner 재확인. seller 필터는 그 주문 안에서만 적용 |
| 본인 주문의 반품 신청 적격 원단위 조회 | 현재 서버의 원 배송 완료시각/정확7일 경계·CONFIRMED/hold·원단위를 기준으로 eligible/reason과 현재 order revision 반환. B 확정 eligibility를 반품 eligibility로 재사용하지 않음 |
| `GET /api/seller/orders/{sellerOrderId}/returns` 및 `/{id}` 상세 | `lockSellerOrder`의 현재 기존거래 권한으로 부모 잠금→정확 seller/order claim 필터→최종 판매자 권한 재확인. 다른 seller의 sibling 정보 반환0 |
| `GET /api/admin/support/orders/{number}/returns` 및 `/{id}` 또는 동등한 최소 조회 | 실제 CurrentSupport→공개 Orders/Aftercare 포트로 부모·필요 원단위 잠금→claim projection/공개 Refunds→최종 CurrentSupport. buyer owner로 위장하거나 buyer 목록 권한을 풀지 않음 |

읽기도 처음 권한만 확인하고 끝내지 않는다. 현재 권한의 자연 만료가 마지막 업무 잠금 대기를 가로지르면 응답을 거부한다. 기존 identity→order→원단위/claim→payment 순서를 유지하고 final recheck 때문에 다른 actor identity를 역순 획득하지 않는다. owner/seller 공개 포트를 재사용해 같은 principal을 재검증하고 SUPPORT는 자기 CurrentSupport를 재검증한다. 지급/검수/동의 변경 없는 GET은 요청마다 현재 권한을 확인하는 읽기여야 하며 worker/recovery나 quote 자동 수락을 실행하지 않는다.

claim locator를 먼저 읽어 seller/order를 찾는 경우 아직 응답을 공개하지 않고, 현재 포트 권한 획득 뒤 locator/id/order/seller를 재대조한다. 다른 주문/다른 판매자 id, 복합 경로 불일치는 기존 숨김404 규칙을 따른다. 권한 오류를 빈 성공 목록으로 숨기지 않는다. CLOSED/REJECTED/WITHDRAWN/COMPLETED도 현재 소유자의 역사 조회 대상으로 유지하고 신규 명령 허용과 분리한다. 판매자의 기존거래 접근은 기존 `lockSellerOrder` 정책을 따른다.

목록은 기본적으로 terminal 포함 전체 이력을 발견할 수 있게 한다. 서버에서 유효한 status·seller 필터만 허용하고 크기 상한과 안정된 `(requestedAt,id)` 커서를 둔다. 커서는 actor·order/seller·필터 범위에 결합하고 다른 범위의 커서를 권한으로 쓰지 않는다. UI는 모든 page를 탐색할 수 있어야 하며 앞 N개를 전체인 것처럼 표시하지 않는다. 상세와 명령의 권위 있는 revision은 서버에서 다시 확인한다.

현재 회원 session/version과 GAS cookie 정책을 그대로 유지한다. 조회키/복구코드를 query/body/localStorage에 재노출하지 않고 GUEST_ACCESS의 서버 검증·폐기·만료를 사용한다. 구매용 GuestBrowserSession/전화 인증/원 주문번호 지식은 조회용 GAS를 대신하지 않는다. 회원과 GAS가 함께 있을 때 임의 fallback으로 다른 주체 자료를 찾지 않는다. seller/SUPPORT authority에는 guest token을 넣지 않는다. 인증된 주문 읽기의 기존 no-store, 모든 사용자 POST의 세션 CSRF를 유지하고 새 anonymous 허용 matcher는 정확한 GAS buyer 경로/메서드에만 한정한다. seller/admin wildcard나 CSRF 예외를 추가하지 않는다.

### 15.3 공개 DTO·revision·상태 표시

공개 타입은 claim/api의 값 DTO 또는 기존 공개 order/payment 값만 사용하고 JDBC Map/내부 entity·web Principal/transaction·job token을 노출하지 않는다. UUID와 allowedActions/eligible는 화면 안내이며 서버 권한/현재 상태 검사를 대체하지 않는다. request의 금액·입고수량·완료 boolean을 신뢰하지 않는다.

최소 상세는 원 주문/seller/return 식별, 불변 접수 receipt, 현재 progress(state/version/history), 정확한 원단위와 collection/inspection/검수 책임, 필요한 금융 요약 및 별도 seller 배송비 요약을 구분한다. buyer에는 자기 주문만, seller에는 자기 단위만, SUPPORT에는 해당 업무에 필요한 최소 정보만 반환한다. 별도 연락처/다른 seller·운영자 개인 식별/내부 payment 비밀/lease 정보를 덧붙이지 않는다. 원단위 제목·금액·정책은 저장된 원 주문/assessment/quote에서 읽으며 현재 상품 가격으로 재계산하지 않는다.

| 표시/명령 | 사용해야 하는 값 |
| --- | --- |
| 신청 | 현재 적격성 응답의 **order revision**. 최초 receipt의 orderRevision을 최신값으로 재사용하지 않음 |
| 승인/반려/철회/회수/검수·최종 quote/consent·ITEM SUPPORT | 해당 기존 API가 요구하는 **현재 RETURN revision**. ITEM 내부 fee revision은 서버가 결합하며 UI가 대입하지 않음 |
| SHIPPING SUPPORT | 독립 obligation의 **shipping revision**. RETURN revision·payment resultVersion과 섞지 않음 |
| 원 receipt/지원 receipt | 당시 수락 사실로 별도 표시. 재생 응답의 당시 progress를 최신 GET 결과에 덮어쓰지 않음 |
| ITEM | 현재 attempt/sequence/result state와 fee 적용·추가 미수납을 분리. provider SUCCEEDED만으로 반품 완료로 표시하지 않음 |
| 원 S | seller 단일 의무/원 origin return과 독립 revision·state/금액을 표시. 다른 부분 RETURN에 같은 배송비를 중복 합산하지 않음 |

BUYER/Seller 조회에서 SHIPPING 명령 권한을 빌려 쓰지 말고 자기 권한 아래 읽기 projection을 제공한다. SUPPORT는 ITEM 현재 상태·revision을 재발견할 읽기가 필요하며 SHIPPING은 기존 전용 GET을 재사용/일관된 DTO로 조합할 수 있다. 아직 obligation이 없는 상황을 FAILED/0원 성공으로 만들지 말고 decision의 PENDING/RETAINED/REFUND_REQUIRED, 의무 없음과 처리 중을 구분한다. already COMPLETED인 과거 개인 RETURN에 늦은 독립 S가 남아도 그 개인 반품을 다시 미완료로 바꾸지 않는다.

최종 견적은 원단위 net·검수 책임·원배송별 누적 부과/이번 차액·공제·ITEM 환불·이번 미수납·legacy 동의 필요 여부를 보여 준다. 과거 미수납을 이번 환불에서 자동 차감해 보이게 하지 않는다. quote id/fingerprint/RETURN revision을 그 사용자 확인과 묶고 동의 checkbox는 기본 false다. legacy는 별도 필요한 명시 동의이며 견적/대상/권한 변경 시 기존 체크를 지운다. 0원 환불도 서버 receipt/fee 적용·미수납 상태를 확인한다.

통신 성공/접수/수령/검수/입고/ITEM 성공/fee 적용/배송비 정산/개인 완료/주문 CLOSED는 각각 다른 사실이다. 미회수·분실 보고·검수 대기·UNKNOWN/PROCESSING·DUE_PENDING은 미완료 원인을 보여 주며 '완료' 버튼이나 강제 성공 경로를 제공하지 않는다. SUPPORT는 UNKNOWN/PROCESSING에 동일 attempt query만, definitive FAILED에 명시 새 sequence retry만 연결한다. UI는 사유와 원 의무를 보여 주고 결정 권한은 기존 서버 명령에 남긴다.

### 15.4 클라이언트 수명·오류·접근성

기존 C의 pending command 방식처럼 한번 생성한 key와 canonical body를 요청 결과 확정까지 유지한다. 빠른 중복 클릭/Enter는 한 pending 명령만 전송하며 응답 유실·5xx·성공 body 파싱 실패는 성공/실패를 단정하지 않고 '결과 확인 필요'로 둔다. 같은 key/내용 재확인만 허용하고 새 key나 바뀐 revision으로 자동 재제출하지 않는다. 새로고침은 서버 목록·상세로 다시 찾되 브라우저 메모리에 없어진 원 요청을 새 신청으로 자동 복원하지 않는다. 민감한 pending payload의 영구 저장은 추가하지 않는다.

409는 낡은 견적/단위/revision을 폐기하고 현재 목록·상세·적격성/견적을 다시 읽게 한다. 자동 승인/동의/재시도하지 않고 새 명령에는 새 사용자 확인이 필요하다. 400은 입력 정정, 401/403/숨김404는 private data·draft·pending을 지우고 재인증/권한 안내로 전환한다. GET 실패를 빈 이력/완료로 표시하지 않는다. 성공 명령 뒤 최신 조회가 실패하면 수락 사실과 '현재 상태 재조회 필요'를 구분한다.

store 범위는 actor/session generation·member/GAS mode·order/seller/return·purpose를 포함한다. 로그인/로그아웃/조회자격 폐기·회전/route 전환/dispose에서 요청 abort와 generation 증가, private 상태 소거를 수행한다. abort를 무시하고 늦게 도착한 성공/실패도 publish 직전 generation·현재 권한·대상 일치 검사를 통과해야 한다. guest 자격 전환은 회원 auth 이벤트만 기다리지 말고 기존 GuestAccess 수명과 결합한다. StrictMode setup→cleanup→setup 이후 재구독/정상 재조회가 가능해야 하며 mount/focus/poll에서 POST를 발행하지 않는다. 수동 재조회가 최소이며 polling을 추가하면 중복·숨김탭·unmount·uncertain command 때 정리한다.

DTO의 식별/enum·정수 revision/ordinal·중복 단위·단위 귀속·money 문자열·목록 커서를 검증한다. 금액은 부호 없는 10진 문자열+BigInt로만 계산/표시하고 Number 변환·부동소수 합산을 금지한다. 수치형 revision은 안전 정수 범위를 검증하고 범위 밖이면 fail closed한다. 같은 응답의 논리 모순(예: 다른 return의 quote, inspection 없이 재입고 완료 주장, purpose 불일치)은 전체 해당 projection을 거부하고 명령을 비활성화한다. 서로 다른 GET 사이 정상 시점 차이는 최신 재조회로 처리하며 오래된 값을 섞어 새 DTO를 만들지 않는다.

원단위 선택과 검수 입력은 상품명/개별번호 label·fieldset/legend·명시 사유·키보드 가능한 native control을 사용한다. 상태는 색만으로 구분하지 않고 status/alert를 제공한다. 오류 시 입력/오류로 focus, 완료 후 상세 heading/결과로 focus를 이동해 버튼 소실로 focus를 잃지 않게 한다. 모바일에서 각 단위·금액·사유가 읽히고 주요 동작이 가로스크롤/마우스 hover에 의존하지 않아야 한다.

### 15.5 필수 검증 매핑 — 구현 전 등록, 현재 NOT RUN

아래 메서드/시나리오 이름은 제안이며 동등한 실제 이름과 assertion을 작업 문서·필수목록에 연결한다. 수치 총계/스크린샷은 assertion을 대신하지 않는다.

| 층/검사 | 필수 기대 결과 |
| --- | --- |
| JUnit·ArchUnit `returnReadProjectionRules` 및 기존 모듈 검사 | 상태/allowed action·목적별 revision 분리, 금액/원단위·정확 기한; claim 공개 DTO 내부타입/순환/역호출 금지. 기존 금지 fixture 유지 |
| 새 PostgreSQL `returnReadListsRespectCurrentActorsAndTerminalHistory` | member·현재 GAS만 자기 목록/상세, sibling seller/타주문/다른 cursor 거부, terminal 역사 조회 가능, current ownership 복합결합, GET 금융/입고/동의 효과0 |
| 새 PostgreSQL `returnReadAuthorityIsRecheckedAfterFinalLockWait` | owner/member/GAS·seller·SUPPORT 각각 실제 마지막 필요 lock 대기 중 저장 세션 자연만료/현재 권한 상실 뒤 응답 거부·새 정보0, 정상 대조군. CSRF 없는 모든 사용자 POST 거부·participant 불변 |
| 새 PostgreSQL `returnUiProjectionSeparatesReceiptsFeesAndShipping` | 오래된 원 receipt와 최신 progress 분리, ITEM/SHIPPING version·purpose/현재 attempt·원 S 귀속, 미수납/UNKNOWN/late S·0원·legacy·원 net/현재 상품 변경 무영향. current SUPPORT의 잘못된 revision/권한/새 성공 retry 거부 |
| 프런트 `ReturnFlow`/seller/support store 검사 | 중복 click/Enter1, 응답 유실 같은 key/body,409 후 옛 quote/동의 폐기, 401/403/404 소거, actor/route 변경 뒤 늦은 응답0, contradictory DTO 명령0, BigInt 큰금액/잔여1원, StrictMode 재사용·dispose/focus cleanup·POST0 |
| 브라우저 D06 PC/mobile `guest return to inspected demo refund` | 새 browser에서 실제 GAS 인증→목록/신청→새로고침 재발견→별도 seller 인증 승인/실회수/검수→buyer 최종 견적/명시 동의→실 DEMO worker 결과. 실제 DB hold/consent/inspection/입고/ITEM fee/provider/개인완료·원 S와 주문종결을 단계별 대조. provider/COMPLETED를 SQL로 직접 만들지 않음 |
| 브라우저 actor/실패 경계 | 실제 다른 seller 접근 거부·GAS 폐기 후 private 화면 소거, 승인/반려/허용철회·미회수/분실/불량/미수납 표시, 정상 회원 경로, 현재 SUPPORT ITEM/SHIPPING FAILED retry와 UNKNOWN 같은 attempt query·독립 revision, 오류/409/중복/늦은 응답·키보드/모바일. fixture는 검증용 제공자 행위 설정까지만 허용하고 결과는 실제 명령·worker·DB로 확인 |
| 기존 A/B/C 회귀·전체 하네스 | 배송/확정/발송전취소·guest 조회키/CSRF/GAS 분리·원 영수증/가격·세션 수명 기존 필수 이름 유지. 새 JUnit/ArchUnit/격리 DB/프런트/PC·mobile 시나리오를 하네스 필수목록에 추가하고 같은 동결 소스의 개발·지정 독립 Offline 전체에서 수집/skip0 확인 |

최소 정상 D06은 seller 한 단위 happy path만으로 끝내지 않고 반품 완료에 필요한 실제 단계와 이력 재발견을 증명한다. 복잡한 정책/경합 전체를 브라우저에 중복하지 않아도 되지만 UI가 표시하는 미완료 사유·actor 경계와 연결 명령은 위 검사에서 검증해야 한다. 브라우저 기존130·aggregate287초·개별30초/catalog60초·workers1/retries0을 지금 변경하지 않는다. D 추가 실행의 실제 시간·실패 원인에 근거한 별도 판단 전 timeout 확대/필수 축소로 통과시키지 않는다.

위 계약은 구현 경계에 대한 지정 판단이다. 추가 사용자 승인 질문·제품 구현·검사 실행 없이 이 설계 문서만 보완했으며, 기존 자원/실패 이력과 V41~75를 보존하고 커밋·푸시·배포·E는 진행하지 않았다.

### 15.6 seller 공통 배송비의 typed origin과 SUPPORT 경로 — 2026-11-02

**현재 최소 채택안은 서버가 확인한 origin과 명령 revision 종류를 명시하여 기존 권한 있는 지원 계약으로 연결하는 것이다. CANCEL까지 RETURN 지원 계약에 즉시 편입하거나 C terminal guard를 해제하지 않는다.** 화면에 표시하는 seller 공통 S의 소유자는 현재 보고 있는 returnId와 다를 수 있다. 이를 별도 원 의무로 정확히 표시·발견하는 것은 확정 정책의 구현 보완이며 새 사용자 정책 질문은 필요 없다.

이번 판단은 편집 중인 live 소스의 해당 분기와 C 인계/§12~15를 읽은 **국소 코드 판단**이다. whole/frozen 검토·재현 실행 결과가 아니다. 읽은 JdbcReturnQueries SHA `8F0B62F318D9B0249CAD82A550D0449DE1B2056DEED32096EF00A3934D7276F8`, JdbcClaimSupport SHA `799ABA25D0DE6C36B59728B870E3C98A9EBDBC3A279AB02ECE5EF2FF5CCEDA51`, SellerShippingReservations SHA `DA55281011CC09BB0FEE1E4AD21F5345DB81778BDBF678143D1BB5B76A8299E1`를 구분한다. 동시 편집 전체가 이 해시와 일치한다고 주장하지 않는다.

**먼저 재현 전제를 분리한다.** 현재 JdbcClaims의 ITEM 처리에서 preferredCancellation을 넘기는 예약은 `CancellationOrders.allCanceled`가 참일 때뿐이다. 그 공개 구현은 모든 단위가 실제 CANCEL hold·미발송·취소정산임을 요구한다. 혼합 seller의 공통 SellerAftercareWorker는 preferredCancellation 없이 예약하며 allocator는 실제 SELLER_RETURN_READY origin을 우선한다. 따라서 **C-last 순서 자체는 CANCEL-origin 혼합 S의 증거가 아니다.** 실제 origin intent kind/id·단위 사실·생성 경로를 기록해야 한다. 과거 데이터나 다른 경로로 CANCEL-origin 혼합 S가 실제 성립하는지는 아직 미검증이다. 반면 같은 seller의 다른 RETURN origin을 현재 returnId로 호출하는 문제는 CANCEL 여부와 무관하게 피해야 한다.

| 서버가 확인한 원 의무 | 명령 연결·revision |
| --- | --- |
| RETURN-origin 독립 S | 기존 ReturnShippingSupport에 **origin returnId**로 연결, SHIPPING_OBLIGATION revision 사용. 화면 context returnId의 workflow revision이나 최신 receipt revision으로 대체하지 않음 |
| 기존 C 계약이 소유하는 CANCEL-origin S | 기존 ClaimSupport의 **origin claimId**·SHIPPING 목적, **CANCEL_CLAIM revision** 사용. C 현재 claim 상태/현재 payment/원 의무/지원 가능 조건을 읽어 허용 행위를 결정 |
| 의무 없음/이미 성공/현재 계약상 지원 불가 또는 결합 모순 | 현재 상태·미완료 원인을 표시하고 해당 명령을 내보내지 않음. 진행 가능한 S 실패가 지원 경로 없이 남는 실제 사례는 완료 차단으로 기록하며 단순 버튼 숨김을 해결로 세지 않음 |

최소 projection은 `sellerOrderId`, originKind(CANCEL/RETURN), originIntentId, 목적 SHIPPING, obligation 현재 state/revision, actual attempt/sequence/resultVersion/state/원 amount, **supportContract·commandRevisionKind·commandRevision**을 구분한다. 표시용 decisionRevision도 별개다. origin과 command revision은 서버가 claim 소유 의무·intent·binding과 공개 Refunds/원 order 사실을 대조해 산출한다. 클라이언트가 URL/id/숫자를 임의 조립하거나 임의 server URL을 실행하는 형식 대신 고정 typed 계약/route 매핑을 사용한다. caller supplied origin/allowedAction은 권한이 아니며 서버가 매 호출 재해석한다.

기존 C는 SHIPPING FAILED/UNKNOWN 결과를 claim REVIEW_REQUIRED와 claim version에 반영하는 반면 obligation state/version이 RETURN 방식으로 함께 증가하지 않을 수 있다. 따라서 모든 origin에 `obligation.state==FAILED/UNKNOWN`와 동일 shipping revision을 강요하지 않는다. 기존 C의 실제 현재 결과/지원 조건과 RETURN 독립 지원 조건을 각각 사용한다. 동일 seller의 여러 반품에서 공통 S를 보여도 한 의무로 식별하고 중복 합산·중복 지원 명령을 만들지 않는다.

현재 SUPPORT→부모 order/전체 원단위→원 intent/obligation/current binding→payment→최종 SUPPORT 재검사를 유지한다. C endpoint를 재사용한다고 order 소유자 권한을 요구하거나 buyer를 위장하지 않는다. 새 조회와 route resolver도 마지막 잠금 뒤 현재 SUPPORT를 확인한다. 다른 order/seller/origin·receipt를 바꿔 보낸 요청은 거부한다. 기존 C receipt key/fingerprint/claim version·원 receipt 재생, RETURN 독립 receipt/S revision 계약은 그대로이며, endpoint 간 fallback 재전송은 금지한다. 동일 사용자 동작은 확정한 origin/contract/key/body에 고정하고409 때 최신 origin/조건을 재조회해 새 확인을 받는다.

**CANCEL-origin 혼합/terminal S가 실제 재현될 경우의 추가 경계:** 기존 C 명령은 terminal을 거부할 뿐 아니라 C SHIPPING 결과 적용과 order의 취소 정산 포트가 전량 미발송 CANCEL 사실을 요구한다. 그러므로 RETURN의 `claim_id==returnId`만 풀거나 C terminal guard/`allCanceled`만 완화해서 해결할 수 없다. 그 경우에만 공통 독립 S의 origin-typed 지원·typed binding/결과 dispatcher·정산 projection·복구/SQL graph까지 한 수직으로 확장해야 한다. 원 CANCEL/RETURN의 개인 완료/동의/ITEM/재고·원 의무와 origin을 보존하고 별도 S revision/receipt로 처리한다. 과거 C endpoint의 동일키 재생·revision 의미를 바꾸지 않는 호환 adapter/명시된 legacy 분리가 필요하며 두 명령 경로가 같은 의무를 서로 다른 revision으로 동시에 갱신하게 해서는 안 된다. 적용 migration 수정 없이 신규 migration과 실제 RED→GREEN·독립 재검토가 선행돼야 한다. 현재는 그 확장을 구현했다고 판단하지 않는다.

필수 검증은 다음과 같다(현재 모두 이 판단에서 NOT RUN).

- 실제 C-first/D-first(C-last 포함) 혼합 fixture에서 생성 origin kind/id·원단위 근거를 먼저 assert한다. 대상 returnId와 다른 RETURN origin에서도 조회된 지원 행위가 정확한 origin endpoint/독립 revision으로 성공해야 한다. 잘못된 view returnId·revision/purpose로는409/all0이다.
- 별도 실제 순수 C SHIPPING FAILED/UNKNOWN을 만들어 typed C origin/claim revision 조회→현재 SUPPORT 기존 retry/query→실 결과 정산까지 검증한다. FAILED 새 sequence·원 의무1, UNKNOWN 같은 attempt/submit 추가0, 성공 후 원 key 재생0·새 retry 거부, C 최초 receipt/기존 C 지원 회귀를 유지한다.
- 혼합 C-last의 실제 FAILED/LOST_RESPONSE도 같은 방식으로 원 origin을 사용해 끝까지 검증한다. CANCEL origin이라고 가정하거나 SQL로 origin/terminal을 조작해 정상 업무 생성의 증거로 쓰지 않는다. 정상 생성으로 도달 불가한 legacy/변조 사례는 별도 무결성/이행 검사로 구분한다.
- 최종 실제 잠금 대기 뒤 SUPPORT 만료/권한 폐기, 다른 seller/order/origin/지원 contract 위조, stale revision·동일 key 내용충돌·동시 두 view의 지원 요청은 명령별 원자성/멱등성으로 검증한다. 서버 읽기·UI DTO의 origin/명령 revision 혼동,409 후 다른 endpoint 자동 재전송0, 새로고침 뒤 실제 원 의무 재발견도 필수다.
- 확대 경로가 필요하다고 실제 확인되면 C terminal/과거 개인 RETURN 불변, 새 S 결과/정산·최종 lease·쿠폰/종결·복구/SQL graph를 함께 검증한다. 읽기/지원 버튼만 연결한 결과로 성공 정산까지 보장됐다고 표시하지 않는다.

구현은 Sol에 남긴다. 이번에는 이 설계 보완과 해당 판단의 검토 기록만 추가했고 제품/검사/하네스 실행·커밋·푸시·배포·E는 진행하지 않았다.
