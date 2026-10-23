# 로컬 실행과 검사 — P01-B5

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

Checkstyle은 validate에서 먼저 실행되며 위반 시 빌드를 멈춘다. JUnit 테스트는 아직 0개다. 플러그인 3.6.0(현재 의존 엔진 Checkstyle 9.3)과 최소 규칙은 [pom](../backend/pom.xml), [규칙](../backend/config/checkstyle.xml)에 있다. 역할은 [Maven 공식 설명](https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html)을 참고한다. 자동 포맷터는 도입하지 않았다.

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

현재 사용자 정의 환경 변수는 필요 없다. [frontend/.env.example](../frontend/.env.example)은 보안 안내다. VITE_*는 브라우저에 공개될 수 있으므로 암호·토큰·DB 주소를 넣지 않는다. 백엔드는 Spring 프로필, 프런트는 빌드 도구 환경이라는 차이가 있다. 실제 DB·비밀값 설정은 P01-C1에서 설계·검증한다.

## Git과 직접 확인

로컬 `main`에서 `git status --short`, `git diff`, `git log --oneline`으로 변경을 확인한다. 원격 저장소·push는 이번 범위가 아니다. `.gitignore`는 도구·캐시·빌드·IDE·로컬 설정·키 파일을 제외한다. 이것만으로 모든 비밀값을 탐지하지는 못하므로 커밋 전 `git diff --cached`와 파일 목록을 점검한다. 공유 가능한 설정 예제와 package-lock·Maven Wrapper는 추적한다.

학습 실습: Java에 쓰지 않는 import를 잠시 추가하면 validate가 실패하는지 확인하고 원복한다. React에는 사용하지 않는 변수를 추가해 lint 오류를 비교한다. 자동 검사 결과와 사용자의 직접 실습 완료는 별개다.
