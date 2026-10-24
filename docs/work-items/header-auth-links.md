# 홈 공통 헤더 회원 진입 링크 보완

2026-10-24 / 기준 f96638e / P03-B와 별개인 기존 화면 사용성 보완.

## 요청·범위

검토 및 반영세션의 직접 사용자 요청(01a16d5f-f91c-7df3-bf17-e04cf33802a9)에 따라 홈에서 회원가입·로그인을 바로 찾을 수 있게 한다. 기존에는 헤더의 ‘내 계정 · 로그인’으로 이동해야 회원가입 메뉴를 볼 수 있었다.

- 공통 SiteLayout 헤더에 ‘회원 서비스’ 내비게이션을 추가하고 로그인 `/login`, 회원가입 `/signup`, 내 계정 `/account`를 각각 표시했다.
- 로그인 후에도 내 계정 링크를 유지한다. 기존 AuthLayout과 세션 처리를 사용하며 홈에 새로운 인증 요청이나 전역 상태를 추가하지 않았다. 로그인한 사용자에게도 세 링크가 표시되는 의도된 최소 범위다.
- CSS는 회원 링크 묶음의 간격과 줄바꿈만 추가했다. 모바일에서는 기존 헤더 줄바꿈 규칙을 사용한다.
- 변경 파일: frontend/src/layouts/SiteLayout.tsx, frontend/src/styles.css, frontend/e2e/browser.spec.ts.

## 검증

`./frontend/npm.ps1 run typecheck`, `run lint`, `run test:unit`, `run build` 통과. Vitest 9파일·76개 통과. DOM 테스트 환경의 scrollTo 미구현 안내는 실패가 아니며 이동은 실제 브라우저에서도 검사한다.

Playwright에 홈에서 각 링크의 이름·목적지를 확인하고 키보드 Enter로 회원가입·로그인·내 계정 화면에 도착하는 사례를 추가했다. PC·모바일에서 가로 넘침도 확인한다. 기존 health/라우팅 검사와 함께 실행하며 API JAR는 검증된 `.local-tools/p03-a-browser-fixed/build/marketplace-api-0.0.1-SNAPSHOT.jar`를 사용한다. 최종 결과는 PC 9개·모바일 9개, 총 18개 통과다. [브라우저 실행 증거](evidence/header-auth-links-browser.txt)를 기록했다.

## 보존·제한

백엔드·DB 스키마·데이터·세션 로직은 변경하지 않았다. 기존 서버를 시작·종료하지 않고 기존 Playwright 실행기의 별도 테스트 포트만 사용한다. DB 검증 실행·생성·삭제·초기화는 하지 않는다. 새 가입이나 실제 로그인 성공 자체를 이번 링크 검사 결과로 주장하지 않는다. 인증 기능의 실제 DB 검증은 P03-A 당시 결과와 구분한다. 사용자 직접 실습, 실제 모바일 기기, 배포 환경은 미검증이다. P03-B 및 다음 단계는 진행하지 않았다.

[검토 및 반영세션의 독립 검토](header-auth-links-review.md)를 완료했다. 공통 헤더 이동 검사를 PC·모바일에서 별도로 실행해 2개 통과했고 필수 수정 잔여는 없다. [독립 실행 증거](evidence/header-auth-links-review-browser.txt)를 기록했다. 사용자의 최종 로컬 커밋 요청에 따라 제품·테스트 3개 파일, 작업·검토 문서 2개, 실행 증거 2개를 함께 로컬 커밋으로 기록한다. 완료 범위는 홈 헤더 링크 보완이며 필수 잔여 항목은 없다. 원격 push와 P03-B는 진행하지 않는다. 이 문서를 포함한 커밋은 `fix: expose authentication links in shared header`로 식별한다.
