# Oracle API HTTPS 연결

2026-10-04: api.pebble-log.com A 레코드가 152.70.105.93을 가리키는 것을 확인했다. DNS only 상태로 origin 인증서를 발급하고 먼저 검증한다.

install-https.sh를 원격 root로 실행해 공식 저장소의 Caddy 2.11.7을 설치했다. Caddy는 `/api/v1/*`만 127.0.0.1:8080에 프록시하고 나머지 경로는 404로 처리한다. 기본 요청 access log는 활성화하지 않았다. Caddy 서비스는 활성화됐고 OS 방화벽에는 TCP 80/443만 추가했으며 기존 SSH 규칙은 유지했다. /etc/iptables/rules.v4의 원본 백업을 남기고 규칙을 저장했다. 서버의 비밀 환경변수 및 개인 키 파일은 읽지 않았다.

프론트는 Cloudflare Workers www.pebble-log.com에 배포됐다. API 외부 HTTPS는 아직 타임아웃으로, OCI 보안 목록 또는 NSG 인바운드 설정이 남아 있다. 서비스가 active라는 것만으로 인증서 발급 성공을 주장하지 않는다. TCP 80/443을 허용한 뒤 공개 HTTPS 인증서 검증과 공개 API·허용/불허 Origin·Secure cookie를 확인한다. 8080, PostgreSQL 5432, Redis 6379는 외부에 열지 않는다. Caddy 자동 갱신에 필요한 80/443 연결을 유지한다.

HTTPS 검증 뒤 Cloudflare API 프록시를 켠다면 SSL 모드는 Full(strict)로 설정한다. www DNS는 Oracle을 가리키지 않으며 Workers Custom Domain이 관리한다. apex 리다이렉트는 아직 미설정이다.

## 공개 연결 확인 (2026-10-04, 23시대 KST)

사용자가 OCI 수신 규칙에 TCP 80/443을 추가하고 22번의 전체 IP 허용을 제거해 본인 공인 IP /32만 허용했다. 변경 뒤 새 SSH 접속이 성공했다. 공개 API HTTPS `/api/v1/posts`는 인증서 검증을 끄지 않은 curl에서 200을 반환했다. 실제 www 홈·프로젝트·분류·로그인은 200이며 API 연결 실패 표시가 없고, 운영 `/dev/architecture`는 404다. OAuth authorization 경로의 CORS preflight는 www Origin 200 및 credentials=true, 불허 Origin은 403이다. 실제 Naver 로그인·회원 쓰기·미디어 저장의 운영 검증은 아직 완료하지 않았다. 앞의 HTTPS 타임아웃 기록은 규칙 추가 전 상태다.

## Cloudflare 프록시 및 원본 웹 포트 제한

사용자가 api 레코드를 Proxied로 바꾸고 Full(strict) 설정을 완료했다고 알려왔다. 이후 API는 인증서 검증을 유지한 HTTPS에서 200, server=cloudflare, CF-Ray, CF-Cache-Status=DYNAMIC을 반환했다.

restrict-web-origin.sh를 적용해 호스트 INPUT의 TCP 80/443을 PEBBLE_CF_WEB 체인으로 먼저 검사한다. 공식 https://www.cloudflare.com/ips-v4 에서 내려받아 CIDR을 검증한 IPv4 15개와 loopback만 허용하고 나머지는 거부한다. 기존 전체 IP 웹 포트 허용 규칙은 제거했다. SSH와 DB 규칙은 유지했고 전역 IPv6 주소는 없었다. /etc/iptables/rules.v4에 저장했다. 적용 전 백업을 남기고 API 연결 검증에 실패하면 원래 규칙으로 복구한다. 첫 적용 시 TCP reject 규칙의 명시적 프로토콜 누락으로 실패했고 자동 복구됐으며, 프로토콜 선언 수정 후 성공했다.

Cloudflare를 우회해 --resolve로 원본 IP에 직접 연결한 HTTPS는 연결 거부를 확인했다. OCI 보안 목록의 80/443 소스는 아직 전체 IP이며 이번 제한은 게스트 OS 방화벽 수준이다. OCI 계층의 CIDR 제한은 별도 작업으로 남아 있고, IP 허용 목록만으로 특정 Cloudflare 계정의 origin 요청임을 인증하지는 않는다. 공용 Cloudflare 네트워크를 통한 우회까지 강하게 제한하려면 AOP 또는 Tunnel 등의 추가 설계가 필요하다.

Cloudflare IP 목록은 2026-10-04 공식 목록 기준이며 변경될 때 검증 후 갱신해야 한다. 자동 갱신 작업을 설치하지 않았다. 웹 포트 제한 후 DNS only로 전환하면 일반 외부 접속이 차단되므로 프록시를 유지한다. Caddy의 인증서 갱신 도달 여부는 자동 갱신 때 모니터링한다.
