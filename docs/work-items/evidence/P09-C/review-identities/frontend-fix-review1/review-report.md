# P09-C R-C07/R-C08 재검토 — 2026-11-01

고정 source131을 이전 frontend-review1과 비교해 R-C07/R-C08 및 추가 StrictMode 수명 검사 보완만 재검토한다. 변경4개는 ClaimStore.ts, ClaimPanel.tsx, 신규 ClaimReview.test.tsx, required-frontend.json이다. 나머지는 고정 맥락이다. source-manifest SHA256은 5DEC70E8F9E0BC3414C7D5FAA41EFDFFF19F96208655DF60121B1F90B9723AFF, 복사 당시 현재 소스와 불일치0이다. identity.json에 기준과 제한을 기록했다. 제품/새 검사/커밋/배포/D 작업은 요청하지 않는다.

R-C07은 최신 ITEM 시도 기준 철회 가능 판정을 화면과 명령에서 공유하도록 보완했다. seq1 FAILED+seq2 UNKNOWN/PROCESSING에서는 철회 form 대신 확인 중 안내를 제공하고, 최신 RESERVED/FAILED는 실제 최신 revision으로 제출한다.

R-C08은 claim 상태와 목적별 최신 환불 증거를 함께 검증한다. COMPLETED+ITEM RESERVED/FAILED/UNKNOWN 및 COMPLETED+SHIPPING UNKNOWN/FAILED를 store와 panel에서 거부한다. 정상 부분/배송비 포함 완료와 과거 FAILED 뒤 최신 SUCCEEDED, 실제 중간 SETTLEMENT_PENDING 등 정상 조합을 허용한다. 서버 응답의 성공을 프런트가 추정해 보정하지 않는다.

추가 수명 검사에서는 StrictMode 효과 재설정 후 인증 변경에도 진행 정보를 비우고 구독을 정리하는지 확인했다. constructor의 즉시 auth 구독을 지연 연결/재연결·해제로 바꾸어 쓰이지 않은 인스턴스가 구독을 남기지 않도록 보완했다. 원 재현은 컴포넌트 범위이며 실제 사용자 간 노출을 입증한 브라우저 검사라고 확대하지 않는다.

실제 RED `.local-tools/p09-c-frontend-review-red1-20261101`: 신규7개 중5 assertion failure/2 양성 대조 pass/skip0. 잘못된 form, store/panel 모순 완료 표시, StrictMode 뒤 정보 잔류, 미사용 constructor 구독을 재현했다. 원본과 manifest는 보존했다.

GREEN `.local-tools/p09-c-frontend-review-focused1-20261101`: typecheck/lint/unit255/exact required255/build 통과, failure/error/skip0. 기존248과 신규7 모두 포함한다. evidence/에 원문 파일을 복사했다. 입력 식별은 실행 후 캡처이며 전체 Offline의 실행 전 inputs 동등성 검증으로 주장하지 않는다. 브라우저와 전체 Offline은 아직 NOT RUN이다.

기존 R-C01~06의 범위 ADDRESSED를 유지하고, 두 지적의 해결 여부·수정으로 생긴 문제·추가 수명 보완 판단을 P09-C-review.md에 기록한다. 현재 작업공간의 backend 및 남은 브라우저 작업은 별도로 진행 중이므로 고정 사본만 검토한다. C 전체 승인은 요청하지 않는다.
