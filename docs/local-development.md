# 로컬 실행과 검사 — P01 종합 기준

P02-A2 인증 API는 `db` 웹 프로필에서 활성화된다. 로컬 HTTP에서만 `local-http`를 추가한다. SESSION 쿠키의 기본 Secure=true 때문에 HTTP에서 이 프로필을 빼면 로그인 유지가 되지 않을 수 있다. 가입 테스트 도구까지 필요하면 `local-mailbox`를 추가한다. 기존 DB를 자동 갱신하지 말고 새 격리 DB를 쓰며, 이번 단계의 재현 명령은 `./backend/verify-db.ps1 -EvidenceDirectory .local-tools/a2-새로운이름`이다. 이 명령이 임시 서버와 새 DB를 구성한다. [A2 계약·검증·한계](work-items/P02-A2.md)를 먼저 확인한다. React 가입·확인·로그인 화면은 P02-A3에서 구현했다. 실제 외부 메일은 발송하지 않는다. 인증 브라우저 검증은 ./backend/verify-db.ps1 -EvidenceDirectory .local-tools/a3-새로운이름 -RunBrowser로 별도 실행한다. 최신 P03-A 기준 PC·모바일14개를 실행하며 기본130개·DB통합52개도 함께 검증한다. [판매 신청·심사 계약](work-items/P03-A.md)을 확인한다. [R1 최신 검증](work-items/P02-R1.md)과 [D 범위](work-items/P02-D.md)를 확인한다. 운영 권한과 별도 로컬 초기 설정 명령은 [C 기록](work-items/P02-C.md)을 따른다. 복구·프로필·배송지 계약과 새 DB 실행은 [B 기록](work-items/P02-B.md)을 따른다. 자세한 메일 확인·재현 방법은 [A3 기록](work-items/P02-A3.md)을 따른다.

현재 검증 호스트는 Windows x64·PowerShell 7이며 루트는 `D:\Workspace\ai-portfolio-practice`다. 아래 명령은 저장소 루트에서 시작한다. 시스템 PATH·JAVA_HOME을 영구 변경하지 않는다.

## 준비된 도구

| 도구 | 버전 | 이 호스트의 프로젝트 상대 경로 |
| --- | --- | --- |
| JDK | Temurin 21.0.12.1+1 | `.local-tools/jdk-21.0.12.1+1` |
| Maven / Wrapper | 3.9.16 / 3.3.4 | `backend/mvnw.cmd`, `.local-tools/m2/wrapper` 캐시 |
| Node / npm | 24.22.0 / 11.19.1 | `.local-tools/node-v24.22.0-win-x64` |
| Git | 2.45.1.windows.1 | `D:\Tools\Git\cmd\git.exe` |

도구 바이너리·캐시·빌드 결과는 Git에 넣지 않는다. 새 PC는 [JDK·Maven 준비](work-items/P01-B1.md), [Node 준비](work-items/P01-B2.md)의 공식 다운로드·체크섬 절차를 따른다. 준비된 도구를 복제한 것으로 간주하지 않는다. 다른 OS에서 `npm.ps1`의 Windows ZIP 경로를 그대로 쓸 수 없으며 Node/npm을 준비한 뒤 frontend에서 npm 명령을 실행한다. Maven은 Java 21 JAVA_HOME 지정 후 `./mvnw`로 실행한다. Linux/macOS 실행은 이번에 검증하지 않았다.

## Java 빌드와 실행

```powershell
$env:JAVA_HOME = Join-Path $PWD '.local-tools/jdk-21.0.12.1+1'
$env:MAVEN_USER_HOME = Join-Path $PWD '.local-tools/m2'
Push-Location backend
try {
    ./mvnw.cmd -B -ntp '-Dmaven.repo.local=../.local-tools/m2/repository' clean verify
    if ($LASTEXITCODE -ne 0) { throw 'Java build failed' }
} finally { Pop-Location }
```

Checkstyle은 validate에서 먼저 실행되며 위반 시 빌드를 멈춘다. 기본 Java 검사는 130개이며 실제 DB 통합 검사는 포함하지 않는다. 플러그인 3.6.0(현재 의존 엔진 Checkstyle 9.3)과 최소 규칙은 [pom](../backend/pom.xml), [규칙](../backend/config/checkstyle.xml)에 있다. 역할은 [Maven 공식 설명](https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html)을 참고한다. 자동 포맷터는 도입하지 않았다.

설정이 필요한 경우 예제를 `backend/application-local.properties`로 복사하고 값을 편집한다. 기존 로컬 파일은 덮어쓰지 않는다. `local` 프로필은 기본 활성화되지 않는다. 아래 실행은 **backend 폴더를 작업 디렉터리로 사용**해야 외부 파일을 찾는다.

```powershell
Push-Location backend
try {
    if (!(Test-Path application-local.properties)) {
        Copy-Item application-local.properties.example application-local.properties
    }
    & "$env:JAVA_HOME/bin/java.exe" -jar target/marketplace-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
} finally { Pop-Location }
```

서버는 Ctrl+C로 종료한다. 기본 예제는 127.0.0.1:8080이며 이미 사용 중이면 기존 프로세스를 임의 종료하지 않는다. 다른 포트를 쓰면 현재 Vite 프록시 대상도 별도 변경해야 한다. `Invoke-RestMethod http://127.0.0.1:8080/actuator/health`의 예상 응답은 status UP이다. DB·인증·업무 기능의 정상 판정은 아니다.

## React 빌드와 실행

```powershell
./frontend/npm.ps1 ci
./frontend/npm.ps1 run typecheck
./frontend/npm.ps1 run lint
./frontend/npm.ps1 run build
./frontend/npm.ps1 test
./frontend/npm.ps1 run dev '--' --port 5173 --strictPort
```

명령은 순서대로 성공한 뒤 다음을 실행한다. npm.ps1은 실패하면 예외를 던진다. ci는 잠금 파일에 따라 node_modules를 재설치한다. 실행 중 서버가 사용한다면 먼저 자신이 시작한 서버를 종료한 뒤 수행한다. 빌드 미리보기는 `./frontend/npm.ps1 run preview '--' --port 4174 --strictPort`다. 개발과 preview는 8080으로 프록시하며 공개 운영 서버가 아니다. 테스트는 임시 포트와 모의 upstream을 생성·종료한다. 샌드박스 EACCES는 앱 오류와 구별하고 허용된 실행 환경에서 재검증한다.

현재 사용자 정의 환경 변수는 필요 없다. [frontend/.env.example](../frontend/.env.example)은 보안 안내다. VITE_*는 브라우저에 공개될 수 있으므로 암호·토큰·DB 주소를 넣지 않는다. 백엔드는 Spring 프로필, 프런트는 빌드 도구 환경이라는 차이가 있다. 실제 DB·비밀값 설정은 [C1](work-items/P01-C1.md), JDBC·Flyway는 [C2](work-items/P01-C2.md), 격리 DB 통합 실행은 [D2](work-items/P01-D2.md)를 따른다. D2 실행은 새 전용 DB와 데이터를 생성하므로 기본 검사와 구분한다.

## Git과 직접 확인

로컬 `main`에서 `git status --short`, `git diff`, `git log --oneline`으로 변경을 확인한다. 원격 저장소·push는 이번 범위가 아니다. `.gitignore`는 도구·캐시·빌드·IDE·로컬 설정·키 파일을 제외한다. 이것만으로 모든 비밀값을 탐지하지는 못하므로 커밋 전 `git diff --cached`와 파일 목록을 점검한다. 공유 가능한 설정 예제와 package-lock·Maven Wrapper는 추적한다.

학습 실습: Java에 쓰지 않는 import를 잠시 추가하면 validate가 실패하는지 확인하고 원복한다. React에는 사용하지 않는 변수를 추가해 lint 오류를 비교한다. 자동 검사 결과와 사용자의 직접 실습 완료는 별개다.

## 단위·브라우저 검증

저장소 루트에서 아래 순서로 실행한다. JAVA_HOME과 MAVEN_USER_HOME은 위 Java 준비와 같다. MAVEN_USER_HOME은 Wrapper 캐시이며 의존성 저장소는 -Dmaven.repo.local로 별도 지정한다.

```powershell
./frontend/npm.ps1 run test:unit
./frontend/npm.ps1 test
./backend/mvnw.cmd -f backend/pom.xml "-Dmarketplace.build.directory=$PWD/.local-tools/d4-build" verify
./frontend/npm.ps1 run build
./frontend/npm.ps1 run test:e2e
```

각 명령의 성공을 확인한 뒤 다음을 실행한다. 현재 Vitest 73개·Playwright 16개다. Node 68개는 B에서 통과한 기록이며 C·D에서는 전송 코드 변경이 없어 재실행하지 않았다. Playwright는 최신 JAR와 dist를 자동 재빌드하지 않으므로 위 빌드를 생략하지 않는다. Chromium 준비 방법은 [D4](work-items/P01-D4.md)를 따른다. 18080·24174·24175는 전용 검사 포트이며 점유 시 기존 서버를 종료하지 말고 원인을 확인한다. 실제 DB는 사용하지 않는다. Windows 이외 환경·새 PC 전체 재현은 미검증이다.
