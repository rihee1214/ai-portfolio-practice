# P04-C 독립 검토

2026-10-25 / 기준877bf55 / 계약 사전 검토. 구현·실행 통과 판정 전.

개발세션의 실제 최신 사용자 메시지에서 P04-C 구현 및 독립 검토 승인과 P04-D 보류를 직접 확인했다. 검토세션은 이 문서와 evidence/P04-C-review-*만 소유한다. 기존 서버·기존/신규 검증 DB와 파일을 보존하고 독립 실행은 개발 실행 종료 후 새 DB·새 미디어 루트로 수행한다. 커밋·푸시는 진행하지 않는다.

## 계약 대조

P04-C.md의 JPEG/PNG/WebP 입력, PNG 재인코딩, 상품5슬롯/매대1슬롯, 버전과 삭제 tombstone, 명시적 별도 저장소, 소유권/공개 자격, 24시간 고아 정리는 설계·계획 범위와 일치한다. PNG 출력은 WebP 입력 지원과 구분돼 있다. [공식 3.15.0 릴리스](https://github.com/haraldk/TwelveMonkeys/releases/tag/twelvemonkeys-3.15.0) 존재 및 WebP 디코딩 개선 설명을 확인했다. 라이브러리 문서만으로 실제 fixture 호환성을 통과 판정하지 않는다.

## 구현 전에 구체화할 검증 조건

1. **픽셀 할당 전 제한:** 이미지 전체 read/BufferedImage 생성 전에 헤더의 폭·높이·long 곱셈으로 픽셀 한계를 검사한다. multipart 전체 요청 한계와 파일5MiB, 출력5MiB 검사 시점도 구분한다. 손상 JPEG가 경고만 내고 부분 복구되는 경우와 PNG의 APNG/WebP 애니메이션을 어떻게 거부하는지 명시하고 실제 fixture로 검사한다. 첫 프레임만 읽었다고 애니메이션 거부가 입증되지는 않는다.
2. **파일과 DB의 실패 경계:** 파일 생성·완료 직후, DB 삽입/연결 실패, commit 실패 시 신규 파일 정리와 기존 연결 유지가 필요하다. 정상 rollback 정리 실패는 고아 재시도로 복구 가능해야 한다. 진행 중인 업로드가 24시간 경계를 넘거나 정리와 겹쳐도 아직 commit되지 않은 파일을 지우지 않도록 정리와 쓰기의 동기화 계약을 명시한다. retired DB 행/파일 삭제 순서와 부분 실패 재시도도 확인한다.
3. **루트 소유권:** 최초 DB 마커 생성도 프로세스 잠금을 얻은 뒤 원자적으로 수행한다. 다른 DB 마커, 마커 없는 기존 파일 루트, 잠금 파일·마커·UUID 파일·상위 경로의 링크를 처리하는 규칙이 필요하다. 디렉터리 확인 실패 시 정리를 중단하며 루트 전체 재귀 삭제로 복구하지 않는다. DB 식별은 접속 URL 표시 문자열만이 아니라 서로 다른 검증 DB를 확실히 구분해야 한다.
4. **읽기/쓰기 권한과 경합:** 소유자 읽기는 유효한 인증/회원 상태를 확인하고 타인 비공개404를 유지한다. 공개 URL은 상품·매대 공개 자격을 적용하며 교체/삭제 후 과거 URL404와 no-store/nosniff를 검사한다. 응답을 이미 받은 사용자의 복사본 회수 불가와 자격 검사 이후 요청 중 상태가 바뀌는 시간 경계를 구분한다. 회원→매대→상품→슬롯 잠금 후 계정 정지·매대 제한·상품 보관·version을 재검사한다.
5. **슬롯 및 화면:** 빈 슬롯 생성 경쟁·삭제 후 오래된 version 재업로드·동시 교체·교체 대 삭제를 검사한다. 대체 텍스트·슬롯 범위·version·CSRF·비파일 본문 오류 계약과 업로드/삭제/목록/binary API를 작업 문서에 명시한다. 새 미디어 연결과 기존 imageKey/imageAlt의 우선순위를 화면/응답에서 명확히 한다. 실패 시 기존 미리보기와 재조회 경로를 보존하고 선택/인증 변경 후 늦은 응답·object URL 정리를 확인한다.

## 현재 판정

현재는 계약을 읽고 구현 검증 조건을 전달한 상태다. 위 항목은 아직 발견된 구현 결함을 뜻하지 않는다. 코덱 fixture, DB/파일 실패 복구, 권한 경합, 독립 실행 및 보존 비교 전이므로 완료 판정을 하지 않는다. P04-D 이후 미진행.

## 구현 중간 정적 검토

ImageNormalizer/LocalImages/JdbcMedia/MediaConfiguration/MediaController、V16、Products.lockForMedia、MediaStore/MediaPanel 및 신규 검사 소스를 직접 확인했다. 전체 픽셀 read 전 크기 검사, PNG APNG 청크 및 WebP 애니메이션 거부, 경고가 발생한 디코딩 거부, PNG 재인코딩, DB namespace/루트 잠금, 단일 projection binary 자격, rollback 동기화 콜백, inFlight와 동기화를 이용한 정리 보호가 존재한다. 구현 존재 확인이며 실행 보장은 아니다. InitialAdminCommand와 설정 전용 검사의 media.enabled=false는 파일을 쓰지 않는 해당 컨텍스트에 한정된 우회인지 최종 회귀에서 확인한다.

### R1 후보 — 이미지 요청의 인증 오류 후 화면 상태

MediaStore.fail은 401/403에도 error만 설정하고 기존 items와 AuthStore 회원 상태를 유지한다. 기존 CatalogStore.fail은 같은 상태에서 stop 및 auth.rejectSession을 호출한다. 따라서 이미지 API에서 세션 만료/권한 회수가 먼저 관측되면 이미지 관리 폼과 로그인 상태가 남고 동일 요청을 반복할 수 있다. 서버 접근 우회를 의미하지 않는다. 개발세션에 401/403 재현 검사와 기존 인증 오류 처리에 맞춘 보완을 요청했다. 아직 실행 재현 전 후보이며 반영/근거 확인 후 판정한다.

### 실행 증거가 더 필요한 항목

- 현재 읽은 DB 검사는 기본 업로드·교체·삭제·공개/비공개·오래된 version 흐름이다. 타인/CSRF·매대 이미지·잠금 후 계정/보관 상태·동시 슬롯 변경·파일 저장/DB rollback·고아/retired/현재 참조 보호 및 루트 거부는 별도 실행 증거가 필요하다.
- codec의 픽셀 수 초과 검사는 PNG IHDR 크기를 수정하면서 CRC는 갱신하지 않는다. 유효한 초과 크기 헤더/CRC로도 거부되는지 확인해 단순 손상 거부와 픽셀 제한 검증을 구분해야 한다. APNG·손상 JPEG 경고·출력5MiB 초과·lossless WebP도 최종 주장과 fixture 증거를 맞춘다.
- JdbcMedia.change가 파일 모니터를 잡고 DB 잠금을 기다리므로 외부 트랜잭션 참여를 허용할 경우 다중 media 호출과 다른 media 요청 사이의 락 순서를 검토해야 한다. 현재 HTTP 단일 호출의 재현 결함으로 판단하지 않는다. 서비스의 호출 계약을 제한하거나 외부 트랜잭션·정리 경합 검사를 통해 지원 범위를 명시한다.

독립 DB/브라우저 실행은 개발 종료 신호 전이므로 수행하지 않았다. 제품 코드나 개발 검사 파일은 변경하지 않았다.

## 후속 정적 재검토

R1의 `.local-tools/p04-c-r1-red2.txt`에서 401/403 두 검사 실패를 직접 확인했다. 현재 MediaStore.fail에는 stop+auth.rejectSession이 반영됐고 대응 회귀 검사도 존재한다. 최종 GREEN은 독립 실행에서 확인한다. PNG IHDR 픽셀 초과 fixture의 CRC 재계산도 반영됐다.

### R2 — DB 연결 풀과 파일 모니터의 획득 순서 역전

change는 DB 트랜잭션/연결 및 대상 잠금을 얻은 뒤 파일 모니터를 짧게 사용하도록 변경됐다. 그러나 content는 파일 모니터를 잡은 상태에서 jdbc.query로 DB 연결을 얻으며, orphan 정리 역시 모니터 안에서 jdbc.queryForObject를 실행한다. 따라서 연결 풀이 writer 트랜잭션으로 채워진 시점에 reader/정리가 모니터를 잡고 연결을 기다리면, 연결을 보유한 writer는 모니터를 기다려 연결 획득 timeout까지 진행하지 못한다. MVCC 조회에 행 잠금이 없다는 사실은 연결 풀 대기가 없음을 보장하지 않는다.

현재 코드의 잠금/연결 획득 순서에서 확인한 필수 재현·보완 항목이다. pool1과 짧은 연결 timeout, latch/실제 대기 관측으로 reader와 writer 또는 orphan과 writer의 역전을 재현하고 모든 경로의 순서를 맞춘 회귀를 개발세션에 요청했다. 이 검토세션에서는 실행하지 않았으며 제품 코드를 수정하지 않았다. R2 해소 및 독립 전체 실행 전에는 최종 통과 판정을 보류한다.

### R2 수정 소스 재확인

후속 소스에서 content는 tx.execute를 시작한 뒤 synchronized(files)에 진입하고, orphan 후보별 처리도 tx.executeWithoutResult 밖에 파일 모니터가 있지 않도록 바뀌었다. change 및 retired 정리와 DB 연결→파일 모니터 순서가 일치하도록 수정된 것을 확인했다. 행 잠금 없는 MVCC 조회여도 연결 확보가 필요하다는 경계를 반영했다.

현재 수정 방향은 적절하다. 다만 새 재현 검사가 아직 추가되지 않았고 수정 전 컴파일 스냅샷의 진행 중 검사로 이 변경을 검증할 수 없다. 별도 DataSource/JdbcMedia 인스턴스를 사용해도 실제 제한된 연결 자원을 먼저 점유하고 경쟁 경로의 연결 대기를 관측해야 한다. content와 orphan 모두 writer의 파일 잠금 진입을 막지 않는지 및 기존 구현에서는 실패하는지를 증거로 남긴 뒤 수정 후 전체/독립 검증으로 최종 판정한다. 이번 재확인에서 새로운 필수 결함은 추가 발견하지 않았다.

### R2 실패 재현 증거 확인

후속 `.local-tools/p04-c-pool-red/maven.txt`와 `.local-tools/p04-c-orphan-red/maven.txt`를 직접 읽었다. 각각 해당 경로의 검사1개에서 파일 모니터 획득500ms TimeoutException에 의한 error1을 확인했다. 추가된 두 회귀 검사도 직접 읽었다. 별도 실제 PostgreSQL Hikari 풀 maximumPoolSize1에서 한 트랜잭션이 연결을 보유하고 getThreadsAwaitingConnection()==1을 관측한 뒤 모니터에 진입한다. orphan 검사는 두 번째 연결 요청을 latch로 제어해 실제 고아 처리 시점을 겨냥한다. 수정 구현에서는 연결 반환 후 binary 읽기 또는 고아 삭제 완료까지 단언한다. 단순 지연이나 모의 연결만으로 판단한 검사가 아니다. 수정 후 개발 최종 실행과 독립 실행은 아직 대기 중이다.

### R3 — 개발세션이 발견한 PNG CRC 손상 수용

개발세션의 `.local-tools/p04-c-crc-red.txt`에서 codec5개 중1실패, 손상 IHDR CRC 입력에 MediaException이 발생하지 않은 실제 실패를 확인했다. 추가 검사 소스는 유효 PNG의 CRC 바이트를 변경하며, 수정 구현은 각 청크 길이의 입력 범위를 확인한 뒤 타입+데이터 CRC32를 저장 CRC와 unsigned 값으로 비교한다. 단순히 디코더가 읽었다는 사실만으로 손상 검사를 통과시키지 않도록 보완됐다. IEND까지 모든 순회 청크에 적용되는 것을 정적으로 확인했다. R2 포함 실행 p04-c-final은 CRC 수정 전 컴파일 결과이므로 R3 검증으로 사용하지 않는다. CRC 보완을 포함한 다음 전체 실행과 독립 검증을 기다린다.

## 최종 독립 검증 (2026-10-25)

개발 종료/포트 반환 신호 후 이 검토세션이 `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/p04-c-independent-review -Offline -RunBrowser`를 직접 실행했다. 종료0, **Java 기본137·실제 PostgreSQL83 실패/오류/생략0, PC·모바일 브라우저22 통과**를 확인했다. [집계](evidence/P04-C-review-summary.json), [브라우저](evidence/P04-C-review-browser.txt), [대상](evidence/P04-C-review-target.txt). 신규 DB `d2_it_3224b884a5024f4582b273be07da4e83`와 `.local-tools/p04-c-independent-review/media`를 보존했다. 해당 루트의 namespace는 `55c5c0b2-5116-41a9-98d2-41de595ef7f4`다.

별도 프런트 타입·린트·단위13파일90개·프로덕션 빌드도 직접 실행해 모두 종료0을 확인했다. [프런트 결과](evidence/P04-C-review-frontend.txt). 단위 환경의 scrollTo 미구현 메시지는 있었으나 실패는 없었으며 실제 브라우저 검사와 구분한다. 검토 입력 소스34개의 SHA256을 독립 실행 전후 대조해 일치를 확인했다([소스 확인](evidence/P04-C-review-source-check.txt), [입력 목록](evidence/P04-C-review-input-sha256.json)).

- R1 인증401/403 후 이미지 목록과 회원 상태 폐기 회귀가 통과했다.
- R2 content/orphan 모두 제한 연결 풀에서 실제 연결 대기를 관측해도 파일 모니터에 진입할 수 있고, 연결 반환 후 읽기/정리가 완료되는 회귀가 통과했다. 외부 REQUIRED 거래와 대상 잠금 대기, 오래된 진행 중 파일 보호도 통과했다.
- R3 손상 PNG CRC 거부와 codec 전체 검사가 통과했다. 실제 JPEG/PNG/WebP, alpha, APNG/WebP 애니메이션 거부, 손상 JPEG, 입력/출력 용량 및 픽셀 경계를 포함한다.
- 소유권·CSRF·상품/매대 공개 자격·교체/삭제 후 이전 URL404·슬롯 version 및 동시 변경1승자, 실제 회원/상품 잠금 대기 후 정지/보관 재검사, rollback 신규 파일 제거·현재 참조 보호·retired/고아 정리와 부분 실패 재시도를 확인했다.
- 기본 관리자 명령 및 설정 전용 컨텍스트 회귀도 전체 검사에 포함됐다. 이미지 저장소를 사용하지 않는 컨텍스트만 media.enabled=false를 사용하고, 독립 이미지 검증은 실제 새 루트를 사용했다.

상품 이미지 업로드·실제 naturalWidth·교체·삭제와 익명 공개 매대 이미지 표시·비공개 후 URL 차단을 PC/모바일 브라우저에서 확인했다. [상품 PC](evidence/P04-C-review-images-desktop.png) 및 [공개 매대 모바일](evidence/P04-C-review-store-mobile.png) 캡처를 직접 열어 폼·설명·줄바꿈·이미지 표시를 확인했다. 추가 [상품 모바일](evidence/P04-C-review-images-mobile.png)·[매대 PC](evidence/P04-C-review-store-desktop.png)도 보존했다. fixture는4×3 픽셀이라 작은 색점으로 보이며 실제 사진의 품질 검증으로 주장하지 않는다.

## 최종 보존과 제한

읽기 전용 정규화 pg_dump로 기존 개발/테스트 DB의 SHA256이 작업 전과 동일함을 직접 확인했다. 작업 전 및 개발 최종 스냅샷의 DB 목록 누락 없음, 컨테이너 ID 동일, 15432/15433 PID57168 및 8080/4174/5174 리스너 없음 유지도 확인했다. 독립 실행 전 기록한 기존 미디어 파일69개의 해시는 실행 후 모두 동일했다. [보존 결과](evidence/P04-C-review-preservation.txt), [DB/서버 스냅샷](evidence/P04-C-review-preservation.json), [기존 파일 해시](evidence/P04-C-review-media-before.json). 과거 모든 검증 DB의 전체 행을 덤프 비교한 것은 아니며 기존 개발/테스트 DB 내용과 검증 DB 목록 보존을 구분한다.

실제24시간 대기·프로세스 강제종료 복구·실제 기기·상용 부하·모든 이미지 변종의 안전성 증명·사용자 직접 학습은 미확인이다. 루트 경로/링크 거부는 소스 확인 범위이며 Windows 링크 교체 공격 전체를 실행 검증했다고 표시하지 않는다. 방향 자동 보정·리사이징·크롭·애니메이션·객체 저장소·후기 이미지·재고/구매는 이번 범위가 아니다. 이미 내려받은 파일은 회수할 수 없다.

**P04-C 확정 범위 독립 검토 통과. R1~R3 해결 확인, 필수 잔여/차단 항목 없음.** 앞선 대기 표현은 진행 당시 기록이다. 제품/검사 소스는 변경하지 않았고 검토 기록과 증거만 작성했다. 기존 서버·DB·기존/신규 검증 DB 및 미디어를 보존했으며 P04-D 이후·커밋·푸시는 진행하지 않았다.
