# Oracle API 배포 준비

현재 API는 로컬 8081에서 실행 중이다. 2026-10-04 사용자가 제공한 Oracle 서버 `152.70.105.93`에 `ubuntu`로 SSH 접속했다. Ubuntu 24.04.4 LTS/aarch64, 메모리 11GiB, 루트 디스크 여유 약 93GiB를 확인했다. 지정된 개인 키는 사용자 Mac의 `.ssh`에 보관하고 권한 0400을 적용했으며 내용을 출력하거나 서버로 전송하지 않았다. 최초 호스트 키만 accept-new로 등록한 뒤 후속 접속은 StrictHostKeyChecking=yes로 확인한다.

Docker 공식 apt 저장소를 사용해 Engine 29.8.2와 Compose 5.6.0을 설치했다. 별도 사용자에게 docker 그룹 권한을 추가하지 않고 sudo로 관리한다. 준비한 ARM64 API 이미지 압축 파일을 `/opt/pebble/releases/pebble-api-oracle-arm64.tar.gz`에 전송했으며 로컬·원격 SHA256은 `6bdad802fc2d8fb03e31fce9a8d3232c630a0933bde3abc46ed350a8c6cc884e`로 일치한다. 이미지 등록 및 런타임 확인 결과는 아래에 기록한다. 운영 DB·Redis·API 서비스와 API DNS/HTTPS는 아직 시작·연결하지 않았다. 이미지 파일 업로드와 실제 API 서비스 배포 완료를 구분한다.

## 현재 준비한 운영 이미지

루트 Dockerfile은 Java 21 JRE에서 검증한 bootJar를 실행한다. UID/GID 10001의 전용 사용자로 실행하고 기본 프로필은 prod다. `.dockerignore`는 JAR와 Dockerfile만 허용해 `.env`·SSH 키·Worker 서명 키·Git·로컬 소스를 빌드 컨텍스트에서 제외한다. bootJar 작업은 기존 규칙대로 개인 application-local 설정을 제외한다.

```sh
./gradlew bootJar
docker build -t pebble-api:<검증한-커밋> .
```

Oracle 서버가 ARM64이면 Linux ARM64 이미지를, AMD64이면 Linux AMD64 이미지를 빌드한다. Java 이미지와 현재 WebP 라이브러리는 두 아키텍처의 배포 파일을 제공하지만 실제 서버 아키텍처에서 이미지 변환까지 확인해야 한다. 다른 아키텍처에서 만든 이미지를 확인 없이 원격 서버로 옮기지 않는다. 검증한 이미지의 digest와 이전 정상 버전을 릴리스 기록에 보존한다.

2026-10-04 로컬 `pebble-api:oracle-preflight` 이미지의 Linux ARM64 빌드가 성공했다. UID/GID 10001, read-only 루트, cap-drop=ALL, no-new-privileges와 실행 가능한 임시 tmpfs 조건에서 현재 운영 JAR에 포함된 WebP/Kotlin 라이브러리로 BufferedImage → WebP 변환(44 bytes)이 통과했다. 이는 Linux 네이티브 이미지 변환 확인이며 운영 prod DB·Redis 연결이나 Oracle 서버의 시작 검증을 대신하지 않는다. 검증 이미지 ID는 `sha256:d9def46329acf67677be9f1a563e552f8518e994e8f958cf1bb1bc26ac3c64d3`이다.

Oracle에서도 `docker load`가 완료됐고 `linux/arm64 user=10001:10001`과 같은 이미지의 비루트/read-only 조건 Java 21.0.12.1 실행을 확인했다. 이 검증 컨테이너는 `--rm`으로 정리했으며 API 프로세스나 DB 연결을 실행한 검증은 아니다.

## 서버 확인 후 진행할 순서

1. 인스턴스 IP·OS·아키텍처·SSH 접속과 기존 서비스/포트를 읽기 전용으로 확인한다. 현재 서버의 해당 확인은 완료했고 기존 Docker·웹 서비스가 없었다. 새 인스턴스 생성이나 기존 서비스 교체를 임의로 수행하지 않는다.
2. 운영 PostgreSQL과 Redis의 위치·인증·TLS·백업/복원 방식을 확정한다. 개발용 compose.yaml을 그대로 외부 서버에 실행하지 않는다. DB와 Redis는 인터넷에 공개하지 않는다.
3. `.env.prod.example`의 항목을 운영 Secret에 별도로 설정한다. 로컬 DB·Redis 비밀번호, OAuth 대역, 테스트 JWT 키·pepper를 운영으로 복사하지 않는다. 기존 이미지 Worker와 동일한 CDN 서명 키는 보호된 환경에서 API에 주입한다. R2 S3 자격 증명은 해당 버킷의 Object Read & Write만 사용한다.
4. 운영 이미지를 올리고 DB 백업 이후 Flyway 마이그레이션과 prod 시작을 확인한다. `ddl-auto=validate`를 유지한다. 이미 적용한 DB 마이그레이션은 이전 이미지로 돌아간다고 자동 복원되지 않는다.
5. HTTPS reverse proxy와 `api.pebble-log.com` 연결을 준비한다. 이 주소는 현재 제안이며 실제 DNS 등록은 아직 하지 않았다. 공개할 포트는 HTTPS와 인증서 발급에 필요한 HTTP로 제한하고 SSH는 관리 출처에 제한한다. API 8080은 외부로 직접 공개하지 않는다.
6. 프론트 Origin은 `https://www.pebble-log.com`, Naver callback은 프론트에서 사용하는 `/auth/naver/callback`을 기준으로 실제 등록 정보를 맞춘다. Secure·HttpOnly Cookie, SameSite와 필수 Origin 검증을 유지한다.
7. 실제 운영 Origin에서 로그인·refresh·로그아웃·공개 조회·이미지 업로드/CDN 조회를 검증한다. 초기 검증에는 테스트 계정을 사용하고 검증 객체만 정리한다.

컨테이너 실행에는 메모리 제한, 재시작 정책, 읽기 전용 루트 파일 시스템과 쓰기 가능한 임시 디렉터리·감사 로그 볼륨을 준비한다. 감사 로그 기본 경로는 `/app/logs/admin-audit`이며 UID 10001이 쓸 수 있어야 한다. WebP JNI는 임시 디렉터리에서 네이티브 라이브러리를 로드하므로 /tmp의 noexec 설정 여부를 실제 이미지 변환으로 확인한다. 감사 로그의 보관·백업·접근 통제는 단순 컨테이너 재시작으로 대체하지 않는다.

현재 API는 remoteAddr를 관리자 제한·감사에 사용하고 Forwarded/X-Forwarded-For를 신뢰하지 않는다. 프록시를 추가하면 같은 프록시 IP로 집계될 수 있다. 편의를 위해 전달 헤더를 모두 신뢰하는 설정을 켜지 않으며 신뢰할 프록시 범위와 기존 제한 정책을 별도 검토한다.

HTTPS reverse proxy는 서버 조건을 확인한 뒤 선택한다. Caddy를 선택하면 도메인과 외부 접속 조건에 따라 인증서 발급·갱신을 자동 관리할 수 있다. Cloudflare SSL/TLS는 origin HTTPS 확인 후 Full (strict)을 사용하고 Flexible로 바꾸지 않는다.

공식 참고: [Ubuntu Docker 설치](https://docs.docker.com/engine/install/ubuntu/), [Java 21 이미지](https://hub.docker.com/_/eclipse-temurin), [Caddy HTTPS](https://caddyserver.com/docs/quick-starts/https), [reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).


## 백업·복원 검증 도구

`backup-postgres.sh`는 운영 DB를 custom format으로 덤프하고 목록 검증·SHA256을 기록한다. 실행·설치는 별도 운영 작업이며 포함된 타이머가 설치되었다는 의미는 아니다.

`sudo bash verify-restore.sh /opt/pebble/runtime/backups/<파일>.dump <기대 성공 마이그레이션 수>`는 별도 임시 DB에 복원한 뒤 검증하며 해당 임시 DB만 삭제한다. 예를 들어 V16 이후 백업에는 16을 명시하며 V16 이전 백업에는 15를 명시한다. 운영 DB를 덮어쓰지 않는다. 예상 릴리스는 해당 백업의 메타데이터로 확인해야 한다.

대역 도구 검사 `python3 infra/oracle/test_backup_restore.py`는 실제 DB/비밀 없이 정상·실패·버전 불일치의 임시 DB 정리와 잘못된 입력 거부를 검증한다. `verify-runtime.py`는 서버 비밀을 내부에서 읽으므로 사용자 제한을 확인하기 전 자동 실행하지 않는다. 이번 Git 사후 통합에서는 문법만 확인했다.
