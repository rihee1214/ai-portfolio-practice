export const orderState=(state:string)=>({PENDING_PAYMENT:'결제 대기',PAID:'결제 완료',CLOSED:'주문 종료',CANCELED:'주문 취소',EXPIRED:'결제 기한 만료'}[state]??'주문 확인 중')

export const paymentEvent=(action:string)=>({CREATED:'결제 시도 생성',PROCESSING:'결제 승인 요청',SUCCEEDED:'결제 승인 확인',FAILED:'결제 실패 확인',UNKNOWN:'결제 결과 확인 중',COMPENSATION_PENDING:'늦은 승인 취소 요청',COMPENSATED:'승인 취소 완료',REVIEW_REQUIRED:'운영 확인 필요',RECONCILE_REQUESTED:'운영 재확인 요청'}[action]??'결제 상태 확인')
