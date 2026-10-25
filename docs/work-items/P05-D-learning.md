# P05-D: 입력에서 저장 결과까지

## 이번 변경을 읽는 순서
1. frontend/src/forms/useFormDraft.ts: values는 화면에서 수정 중인 문자열이다. change가 새 객체로 값을 바꾸면 React가 다시 렌더링한다. input(field)는 value/onChange와 오류 연결 속성을 돌려준다. 훅을 두 폼이 사용해도 각각 독립된 state를 가진다. validate는 첫 오류를 기록하고 그 입력으로 포커스를 옮긴다.
2. frontend/src/forms/QuantityForm.tsx: 부모가 label/initial/disabled/onSubmit을 props로 전달한다. 수량은 입력 중 빈 문자열도 허용하지만 제출할 때만 정수1~99인지 확인한다. TypeScript 타입은 브라우저 입력/HTTP를 대신 검증하지 않는다. noValidate는 브라우저 기본 말풍선 대신 동일한 한국어 오류와 포커스를 제공하기 위한 것이며 서버 검증은 유지된다.
3. frontend/src/pages/CartPages.tsx: 유효한 수량만 store.add/set에 전달한다. CartStore가 CSRF를 받고 방문자 preview 또는 회원 API를 요청한다. 회원은 Java cart 컨트롤러→서비스→DB를 거쳐 현재 가격/재고와 수량을 응답한다. 방문자 로컬에는 SKU/수량만 저장하며 가격은 응답으로 읽는다.
4. frontend/src/pages/AccountPages.tsx: 기존 회원 배송지의 문자열 draft를 같은 훅으로 관리한다. 제출 시 공백 정리·길이·전화·우편번호를 검사하고 AccountStore.save로 보낸다. 이 화면은 주문서 배송지 선택/구매 인증과 별개다.
5. frontend/src/auth/AccountStore.ts: CSRF→POST/PUT→성공 응답 검증→GET 정본 재조회 순서다. 서버500이면 저장 성공으로 표시하지 않고 draft를 유지한다. 자동 POST 재시도는 하지 않는다. 인증 전환에서는 저장소가 개인 상태를 비우고 폼을 내리므로 임시 주소도 폐기된다. 서버 성공 후 재조회 실패는 성공과 같은 뜻이 아니므로 상태 재확인이 필요하다.

## 작은 수정 실습 (선택)
QuantityForm.tsx의 오류 문구에 '예: 2'를 덧붙이고 상품 상세에서 수량0을 Enter로 제출해 보세요. 오류 문구만 달라지고 입력 포커스·장바구니 수량은 그대로여야 합니다. frontend/npm.ps1 run test:unit tests/unit/InputFlow.test.tsx로 관련 회귀 검사를 실행할 수 있습니다. 수량 최대99나 서버 업무 규칙은 이 실습에서 바꾸지 않습니다. 이후 문구를 원래대로 돌리면 됩니다.

## 검증과 이해의 구분
자동 검사는 React 상태/요청 차단/오류연결/계정전환 및 세 화면 너비의 실제 DB 흐름을 확인한다. 테스트의 서버500은 브라우저 응답 가로채기로 재현하고 그 다음 정상 저장은 실제 서버와 새 격리 DB로 처리한다. 실제 물리 기기, 전체 접근성, 스크린리더 및 사용자가 직접 이해·수정해 본 결과는 미확인이다. P06은 진행하지 않는다.
