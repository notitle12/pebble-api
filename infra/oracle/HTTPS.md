# Oracle API HTTPS 연결

2026-10-04: api.pebble-log.com A 레코드가 152.70.105.93을 가리키는 것을 확인했다. DNS only 상태로 origin 인증서를 발급하고 먼저 검증한다.

install-https.sh를 원격 root로 실행해 공식 저장소의 Caddy 2.11.7을 설치했다. Caddy는 `/api/v1/*`만 127.0.0.1:8080에 프록시하고 나머지 경로는 404로 처리한다. 기본 요청 access log는 활성화하지 않았다. Caddy 서비스는 활성화됐고 OS 방화벽에는 TCP 80/443만 추가했으며 기존 SSH 규칙은 유지했다. /etc/iptables/rules.v4의 원본 백업을 남기고 규칙을 저장했다. 서버의 비밀 환경변수 및 개인 키 파일은 읽지 않았다.

프론트는 Cloudflare Workers www.pebble-log.com에 배포됐다. API 외부 HTTPS는 아직 타임아웃으로, OCI 보안 목록 또는 NSG 인바운드 설정이 남아 있다. 서비스가 active라는 것만으로 인증서 발급 성공을 주장하지 않는다. TCP 80/443을 허용한 뒤 공개 HTTPS 인증서 검증과 공개 API·허용/불허 Origin·Secure cookie를 확인한다. 8080, PostgreSQL 5432, Redis 6379는 외부에 열지 않는다. Caddy 자동 갱신에 필요한 80/443 연결을 유지한다.

HTTPS 검증 뒤 Cloudflare API 프록시를 켠다면 SSL 모드는 Full(strict)로 설정한다. www DNS는 Oracle을 가리키지 않으며 Workers Custom Domain이 관리한다. apex 리다이렉트는 아직 미설정이다.
