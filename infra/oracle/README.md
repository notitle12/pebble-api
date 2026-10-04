# Oracle API 배포 준비

현재 API는 로컬 8081에서 실행 중이다. Oracle 서버의 IP·운영체제·CPU 아키텍처·SSH 접속 정보는 아직 확인하지 않았으며 원격 서버 변경이나 API DNS 연결은 실행하지 않았다. 이미지 Worker의 원격 배포 완료와 Oracle API 배포 완료를 구분한다.

## 현재 준비한 운영 이미지

루트 Dockerfile은 Java 21 JRE에서 검증한 bootJar를 실행한다. UID/GID 10001의 전용 사용자로 실행하고 기본 프로필은 prod다. `.dockerignore`는 JAR와 Dockerfile만 허용해 `.env`·SSH 키·Worker 서명 키·Git·로컬 소스를 빌드 컨텍스트에서 제외한다. bootJar 작업은 기존 규칙대로 개인 application-local 설정을 제외한다.

```sh
./gradlew bootJar
docker build -t pebble-api:<검증한-커밋> .
```

Oracle 서버가 ARM64이면 Linux ARM64 이미지를, AMD64이면 Linux AMD64 이미지를 빌드한다. Java 이미지와 현재 WebP 라이브러리는 두 아키텍처의 배포 파일을 제공하지만 실제 서버 아키텍처에서 이미지 변환까지 확인해야 한다. 다른 아키텍처에서 만든 이미지를 확인 없이 원격 서버로 옮기지 않는다. 검증한 이미지의 digest와 이전 정상 버전을 릴리스 기록에 보존한다.

2026-10-04 로컬 `pebble-api:oracle-preflight` 이미지의 Linux ARM64 빌드가 성공했다. UID/GID 10001, read-only 루트, cap-drop=ALL, no-new-privileges와 실행 가능한 임시 tmpfs 조건에서 현재 운영 JAR에 포함된 WebP/Kotlin 라이브러리로 BufferedImage → WebP 변환(44 bytes)이 통과했다. 이는 Linux 네이티브 이미지 변환 확인이며 운영 prod DB·Redis 연결이나 Oracle 서버의 시작 검증을 대신하지 않는다. 검증 이미지 ID는 `sha256:d9def46329acf67677be9f1a563e552f8518e994e8f958cf1bb1bc26ac3c64d3`이다.

## 서버 확인 후 진행할 순서

1. 인스턴스 IP·OS·아키텍처·SSH 접속과 기존 서비스/포트를 읽기 전용으로 확인한다. 새 인스턴스 생성이나 기존 서비스 교체를 임의로 수행하지 않는다.
2. 운영 PostgreSQL과 Redis의 위치·인증·TLS·백업/복원 방식을 확정한다. 개발용 compose.yaml을 그대로 외부 서버에 실행하지 않는다. DB와 Redis는 인터넷에 공개하지 않는다.
3. `.env.prod.example`의 항목을 운영 Secret에 별도로 설정한다. 로컬 DB·Redis 비밀번호, OAuth 대역, 테스트 JWT 키·pepper를 운영으로 복사하지 않는다. 기존 이미지 Worker와 동일한 CDN 서명 키는 보호된 환경에서 API에 주입한다. R2 S3 자격 증명은 해당 버킷의 Object Read & Write만 사용한다.
4. 운영 이미지를 올리고 DB 백업 이후 Flyway 마이그레이션과 prod 시작을 확인한다. `ddl-auto=validate`를 유지한다. 이미 적용한 DB 마이그레이션은 이전 이미지로 돌아간다고 자동 복원되지 않는다.
5. HTTPS reverse proxy와 `api.pebble-log.com` 연결을 준비한다. 이 주소는 현재 제안이며 실제 DNS 등록은 아직 하지 않았다. 공개할 포트는 HTTPS와 인증서 발급에 필요한 HTTP로 제한하고 SSH는 관리 출처에 제한한다. API 8080은 외부로 직접 공개하지 않는다.
6. 프론트 Origin은 `https://www.pebble-log.com`, Naver callback은 프론트에서 사용하는 `/auth/naver/callback`을 기준으로 실제 등록 정보를 맞춘다. Secure·HttpOnly Cookie, SameSite와 필수 Origin 검증을 유지한다.
7. 실제 운영 Origin에서 로그인·refresh·로그아웃·공개 조회·이미지 업로드/CDN 조회를 검증한다. 초기 검증에는 테스트 계정을 사용하고 검증 객체만 정리한다.

컨테이너 실행에는 메모리 제한, 재시작 정책, 읽기 전용 루트 파일 시스템과 쓰기 가능한 임시 디렉터리·감사 로그 볼륨을 준비한다. 감사 로그 기본 경로는 `/app/logs/admin-audit`이며 UID 10001이 쓸 수 있어야 한다. WebP JNI는 임시 디렉터리에서 네이티브 라이브러리를 로드하므로 /tmp의 noexec 설정 여부를 실제 이미지 변환으로 확인한다. 감사 로그의 보관·백업·접근 통제는 단순 컨테이너 재시작으로 대체하지 않는다.

현재 API는 remoteAddr를 관리자 제한·감사에 사용하고 Forwarded/X-Forwarded-For를 신뢰하지 않는다. 프록시를 추가하면 같은 프록시 IP로 집계될 수 있다. 편의를 위해 전달 헤더를 모두 신뢰하는 설정을 켜지 않으며 신뢰할 프록시 범위와 기존 제한 정책을 별도 검토한다.

HTTPS reverse proxy는 서버 조건을 확인한 뒤 선택한다. Caddy를 선택하면 도메인과 외부 접속 조건에 따라 인증서 발급·갱신을 자동 관리할 수 있다. Cloudflare SSL/TLS는 origin HTTPS 확인 후 Full (strict)을 사용하고 Flexible로 바꾸지 않는다.

공식 참고: [Java 21 이미지](https://hub.docker.com/_/eclipse-temurin), [Caddy HTTPS](https://caddyserver.com/docs/quick-starts/https), [reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy).
