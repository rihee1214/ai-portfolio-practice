# P09-C 화면 구현 중간 검토 — 2026-11-01

현재 검토 대상은 상품별 회수비 입력·표시와 구매자 취소/철회 화면이다. 고정 source 사본을 정적으로 검토하고 evidence의 기존 검사 결과를 확인한다. 새 검사·제품 수정·커밋·배포·D 구현은 요청하지 않는다. 현재 작업공간에서는 남은 backend 검증이 계속되므로 사본만 사용한다.

## 식별과 검토 위치

HEAD c9f924123b42e8d413f3ff70a095ffec06fd9593. source130은 현재 frontend/src 전체, frontend/tests/unit 전체, 서버 공개 API7개, required-frontend.json 및 package.json이다. 실제 변경 대상은 changed-files.txt의23개이며 나머지는 고정 맥락이다. source-manifest SHA256은 308D699B3D246461AA736107EBA9340DDC74B05AD4CEE8D68072945E4D2F1005, 복사 당시 현재 파일과 불일치0이다. 구현 담당은 마지막 standalone GREEN 뒤 프런트/검사/필수 목록 변경0을 확인했다. 이 standalone 경로에는 전체 Offline inputs와 같은 실행 전 소스 manifest가 없으므로, 전체 입력 동등성 검증을 했다고 확대하지 않는다. 최종 Offline은 별도 소스 동결 후 필요하다.

- 신규 `catalog/ReturnPolicy.ts`: 정책/금액 문자열·long 범위 검증과 공통 표시.
- 신규 `claim/ClaimStore.ts`, `claim/ClaimPanel.tsx`: 원단위 선택→서버 견적→명시 확인, 요청키·내용 고정 재시도, 진행 조회/불확실 상태, owner 철회, 현재 자격/늦은 응답 처리.
- CatalogStore/CatalogPages: 상품별 기본3000/명시0 입력·저장과 누락값 구분.
- Browse/BrowsePages, QuoteStore/QuotePanel, OrderQueryStore/OrderDetailPage: 공개·구매 전·구매 당시 회수비 표시, 기존 주문 정책 미확정 구분, 현재 구매 총액에 회수비 가산 금지.
- 기존 응답 fixture에는 새 계약상 필수 정책을 추가했고 기존 assertion을 삭제하지 않았다. 새 unit14개와 기존234개가 수집됐으며 exact required248을 검사했다.

서버 명령/회수비 계약은 P09-C-design-review.md §11~12와 현재 P09-C-review.md를 따른다. R-C01~06은 각 기존 범위 ADDRESSED이며 이 화면 범위의 승인으로 확대하지 않는다. 반품 접수/회수/검수/추가비용 수납 UI는 C 범위가 아니다.

## 확인할 주요 조건

1. 기본3000/명시0·저장된 상품 정책과 미확정 과거 주문을 구분하는가. 정책을 현재 상품 값으로 소급하지 않는가.
2. 금액은 정확한 문자열/BigInt로 처리하고 잘못된 정책·long 범위 초과·불완전 성공 증거를 정상 완료로 표시하지 않는가.
3. 취소 견적과 확인 명령이 같은 대상·금액·revision을 사용하고, 불확실한 응답/중복 클릭에서 같은 키와 내용을 유지하는가. 충돌 후 조회 실패를 성공으로 처리하지 않는가.
4. GET/POST의 늦은 응답과 인증/비회원 자격 변경, 컴포넌트 전환에서 다른 사용자의 거래 정보나 진행 상태가 남지 않는가.
5. 철회 가능 상태와 완료/실패/확인 중 표시가 서버 계약과 일치하는가. 회수비 표시가 현재 구매 총액이나 원 환불액을 임의 변경하지 않는가.
6. 기존 상품/견적/주문/확정 화면의 동작을 손상시키지 않는가. 필요한 사용자 흐름을 구현 세부사항으로 노출하지 않는가.

## 실제 검사와 한계

마지막 GREEN `.local-tools/p09-c-frontend-focused5-20261101`: typecheck/lint/unit248(기존234+신규14)/exact required248/build 통과, failure/error/skip0. evidence/에 원문7개를 복사했다. `frontend-result.json`도 standalone만 명시한다. 브라우저 및 전체 Offline은 NOT RUN, 기존 브라우저118/전체 예산260초는 아직 그대로다.

실패 재현 이력은 원본 경로와 implementation-progress.md에 보존했다. 최초 seller 입력/잘못된 quote 정책/취소 영역 부재3개, ClaimStore 기능 부재6개, 실제 성공 참조 없음/long 최대 초과 처리2개를 보완했다. 선택 regex 실행의 미선택 skip은 전체 통과로 세지 않았다. TypeScript 검사 옵션·lint 제어문자 규칙·기존 DTO fixture 정책 누락 등 준비/계약 fixture 오류도 기록했다. 본 보고서로 그 과거 실행을 통과로 바꾸지 않는다.

## 결과 요청

구현된 화면 범위의 결함을 구체 재현 조건/수정 기준과 함께 P09-C-review.md에 추가한다. 실제 브라우저 미실행과 남은 backend 경합/legacy 검사는 별도 미완료로 유지한다. 정적 검토 또는 standalone 수치만으로 C 전체 완료를 선언하지 않는다.
