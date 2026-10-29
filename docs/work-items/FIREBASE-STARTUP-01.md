# FIREBASE-STARTUP-01 — Offline 검증 실행 권한 진단

2026-10-29. 상태: 원인 확인, 전체 재검증 대기.

## 범위와 원인

모델 라우팅 정책 반영 후 실패한 전체 하네스를 재실행한다. 기존 기능·정책·필수 검사·시간 제한은 변경하지 않는다.

이전 실패: `.local-tools/phone-emulator-2ce34c27d834460ab4a9f77923e0d408`와 `.local-tools/model-routing-review-20261029-01`을 원본 그대로 보존한다.

Firebase CLI의 `lib/emulator/portUtils.js`의 `waitForPortUsed`는 tcp-port-used의 모든 예외를 TIMEOUT 메시지로 대체한다. 동일 라이브러리로 새 loopback 서버에 연결하는 최소 재현에서 기본 제한 실행은 1ms 후 `connect EACCES`로 실패했다. 같은 파일을 추가 실행 권한으로 실행하면 1ms에 통과한다. 실제 60초 대기나 포트 충돌이 원인이라는 근거는 없다. 로컬 연결 권한 제한을 해소한 실행으로 수정하며 제품·하네스 코드를 수정하지 않는다. Firebase 갱신 확인 경고는 별도 메시지로, 이번 연결 오류의 원인으로 단정하지 않는다.

## 제약조건과 검사

| ID | 제약 | 검사와 기대 결과 | 실제 증거 |
| --- | --- | --- | --- |
| FS01 | 로컬 에뮬레이터 연결 가능 | 동일 Node·tcp-port-used·loopback 새 포트 진단 통과 | `.local-tools/firebase-startup-diagnostic-20261029-01/restricted-probe.txt`: EACCES; `approved-probe.txt`: passed |
| FS02 | 필수 검사 생략 없이 검증 | `./verify.ps1 -Offline -EvidenceDirectory .local-tools/firebase-startup-review-20261029-01`; 종료0·status/preservation passed·필수 누락/실패/skip 없음 | 실행 대기 |
| FS03 | 기존 서버·DB·미디어 보존 | 하네스 `Get-PreservationSnapshot`/`Assert-Preservation` 전후 대조 | 실행 대기 |
| FS04 | 이전 실패 기록 보존 | 이전 두 증거 폴더 파일 SHA256 전후 동일 | 진단 폴더의 `prior-evidence-before.json`과 후속 대조 |

## 실행 환경과 제한

진단 파일: `.local-tools/firebase-startup-diagnostic-20261029-01/loopback-probe.cjs`. 새 서버만 열고 닫으며 기존 서버를 조작하지 않는다. 소스 식별은 새 전체 실행의 inputs.json·commit·dirty 및 진단 파일 SHA256으로 남긴다. 기존 개발 진행 상태·외부 Toss 차단·과거 검사 결과는 변경하지 않는다. 커밋·푸시·배포하지 않는다.

## 후속 실행 이력

- 추가 권한의 첫 전체 실행 `firebase-startup-review-20261029-01`은 Firebase 시작 성공 후 기존 db-dev 중지로 보존 스냅샷 단계에서 실패했다. 원본 `phone-emulator-30708b870201493c91a8b556e2a06d97`과 result.json 보존.
- 읽기 확인에서 db-dev `6a9f494624c9`, db-test `68d17431a4b1` 모두 작업 전 exited(255) 상태였다. `docker compose start db-dev db-test`로 기존 컨테이너만 시작했다. 재생성·볼륨 삭제 없음. 시작 전후 목록은 진단 폴더의 containers-before.jsonl/containers-started.jsonl.
- 테스트 DB는 과거 비정상 종료에 따른 자동 복구를 진행했으며 2026-10-29 21:17:26 KST ready 상태가 됐다. 준비 전 pg_isready 실패는 전체 하네스 실패와 구분한다. 준비를 기다린 후 새 `firebase-startup-review-20261029-02`에서 전체 실행을 시작했다.
- 진단 소스 SHA256: EAA784A8EEBAADF7D89A374998940D90EE11EE977B37E7268C58F4D1CC686314.

## 최종 결과 — 2026-10-29 21:26:58 KST

이번 실행 권한 문제 해결 및 새 전체 Offline 재검증 범위 완료. 코드·검사·필수 목록·시간 제한 변경 없이 실행 환경의 로컬 연결 권한을 바로잡고 중지된 기존 DB를 시작했다.

- 명령: `./verify.ps1 -Offline -EvidenceDirectory .local-tools/firebase-startup-review-20261029-02` (추가 실행 권한).
- 실제 실행 21:17:32~21:26:58 KST, 종료0, `status=passed`, `preservation=passed`.
- Java451(단위/아키텍처183 + PostgreSQL267 + Firebase 제공자1), 브라우저106, 프런트216, Node75, Java 필수50묶음 및 타입·린트·빌드 통과. 필수 누락·실패·오류·skip·브라우저 flaky 없음.
- HEAD `460aa8bb0bb22f5138849eb00c050abd18f6fb53`, 미커밋 작업 트리. 입력472개, inputs.json SHA256 `A20ECE665A7F2EEE4CB04ED91819C97945CF58219C64CB6B600C7501E42BF9BF`; 실행 후 현재 소스 대조 불일치0.
- 새 DB `d2_it_39cd3e5cd95f4439bf09e175deb85504`, 새 미디어 `.local-tools/firebase-startup-review-20261029-02/db/media`.
- 정상 에뮬레이터 실행·종료 증거 `.local-tools/phone-emulator-004bb5f273e74978acf988a45ef72720`. 실패 에뮬레이터 및 첫 전체 재시도 증거는 삭제하지 않았다.
- FS01: 실제 제공자 IT까지 통과. FS02: result.json, coverage.json, java-cases.json, browser-coverage.json, frontend-coverage.json 및 각 로그. FS03: before.json/after.json 및 preservation passed. FS04: 이전 두 증거 경로4파일 SHA256 전후 불일치0. `final-audit.json`에 자동 assertion 대조 결과 저장.
- 기존 DB 컨테이너 ID 전후 동일. 기존 서버·DB 내용/목록·미디어는 하네스 보존 검사 범위에서 통과. 시작 전 중지 상태였던 두 DB는 현재 실행 중이다. 검증 종료 후 임의로 중단하지 않았다.
- 변경 파일은 이 작업 기록이며 제품 코드·하네스·설정 수정은 없다. 진단과 새 검증 증거는 `.local-tools/`에 보존한다.

### 남은 한계

제한 실행에서 loopback 연결 자체가 허용되지 않는 환경은 이후에도 추가 실행 권한이 필요하다. 하네스 코드를 수정하여 권한 제한을 우회하거나 시간을 늘리지 않는다. 기존 실패65,820파일 전체를 이번에 재해시한 것은 아니며 기존 실패 자료는 조작하지 않았고, 이번 실패의 직접 대상4파일은 별도로 전후 해시 대조했다. 이전 검증 DB의 전체 행은 하네스의 전수 보존 비교 범위 밖이다. 실제 Toss SANDBOX·실기기·외부 서비스 검증과 기존 P08 전체 완료 판정은 이번 통과 범위에 포함하지 않는다. 과거 정책 문서 변경 당시의 실패 기록은 당시 사실로 유지한다.
