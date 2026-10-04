# Pebble 이미지 Worker

`images.pebble-log.com → Worker 서명 검증 → Cache API → 비공개 R2`를 제공한다. 별도의 이미지 CDN이며 Next.js web 배포 Worker와 분리한다. 파일 업로드·변환·소유권과 공개 상태 판정은 기존 Oracle의 pebble-api가 맡는다. Worker는 조회만 구현하며 공개 R2 도메인이나 r2.dev를 활성화하지 않는다.

## 접근과 캐시 계약

API가 공개·미차단·비탈퇴 소유자 조건을 확인한 뒤 15분 유효한 HMAC-SHA256 URL을 발급한다. Worker는 **캐시 조회 전 모든 요청의 서명과 만료를 검증**한다. 같은 이미지의 토큰이 달라도 내부 바이트 캐시는 공유하지만 인증 결과는 캐시하지 않는다. GET/HEAD만 허용하며 파일 경로·expiry·도메인·서명을 변조하거나 내부 캐시 경로에 직접 접근하면 차단한다.

기존 S3 서명 URL과 동일하게 이미 발급한 URL은 숨김·차단·삭제 이후에도 최대 남은 15분 동안 사용될 수 있다. API는 해당 상태에 새 URL을 발급하지 않는다. 캐시된 바이트가 남아 있어도 만료된 서명으로 조회할 수 없다. 즉시 회수는 현재 계약이 아니며 별도 온라인 공개 상태 검증·무효화 설계가 필요하다. R2가 삭제되어도 캐시에 남은 바이트를 기존 유효 URL로 읽을 수 있는 점을 이 정책에 포함한다.

캐시 TTL은 900초다. 외부 응답은 `Cache-Control: private, no-store`와 `Cloudflare-CDN-Cache-Control: no-store`를 반환해 Worker 밖에서 서명 검사를 우회하는 캐싱을 막는다. 이미지 바이트만 Cache API에 저장하고 쿠키·회원 DTO·응답 오류를 저장하지 않는다. Cloudflare의 모든 항목 캐싱 규칙 등으로 이 헤더를 덮어쓰지 않는다. 이미 내려받은 이미지는 화면에서 자동 회수되지 않는다.

URL 계약은 `/media/<base64url UTF-8 key>?expires=<epoch seconds>&signature=<base64url HMAC>`이다. 서명 입력은 정확히 `v1\n<HTTPS origin>\n<encoded key>\n<expires>`다. key는 기존 서버 생성 UUID WebP 경로만 허용한다. 인코딩된 경로 자체가 접근 권한은 아니며 서명이 권한이다. API와 Worker는 시각 동기화를 유지한다. Worker는 API 시계가 앞서는 경우 최대 30초만 발급 시각 편차를 허용하고, 이미 만료된 토큰은 허용하지 않는다.

서명 키는 기존 JWT·R2·네이버 키와 다른 **독립적인 난수 32바이트의 표준 Base64**다. backend와 Worker에 같은 값을 보호된 환경변수·Worker Secret으로 설정한다. 프론트·wrangler vars·Git·로그에는 넣지 않는다. 키 교체 시 이전 URL이 무효화되므로 두 실행 환경을 함께 갱신하고 새 URL을 조회한다. 이 Worker는 요청 URL이나 오류 상세를 출력하지 않으며 observability와 workers.dev/preview URL은 설정에서 비활성화했다. 별도 Cloudflare 로그 서비스 활성화 시 query 서명을 기록하지 않도록 검토한다.

## 검증과 빌드

Node 22 기준:

```sh
cd infra/image-worker
npm ci
npm test
npm run build
```

Wrangler 4.147.0을 이미지 Worker 전용 개발 의존성으로 고정했다. Miniflare는 Wrangler와 동일한 5.20261001.0-alpha 버전을 테스트용으로 고정하며 설치된 공식 V4 설정 변환 API를 사용한다. API 서버 운영 JAR에는 포함하지 않는다.

Node 테스트는 변조·만료·캐시 우회·HEAD·잘못된 설정·R2 오류·캐시 저장 오류를 확인한다. Miniflare/workerd 테스트는 로컬 R2와 실제 Cache API의 MISS→HIT·만료 차단·내부 경로 차단을 확인한다. Java와 JS는 동일한 고정 테스트 키·HMAC 벡터를 사용한다. 이 키는 테스트 전용이며 운영에서 사용하면 안 된다.

## 실제 배포 순서 — 아직 미실행

1. Cloudflare에서 실제 R2 버킷명을 확인하고 `wrangler.jsonc`의 `bucket_name`을 맞춘다. `pebble-media`는 기존 백엔드 기본값이다. 대상 계정과 `pebble-log.com` zone을 확인한다.
2. 보호된 환경에서 독립 서명 키를 생성하고 서버의 `MEDIA_CDN_SIGNING_KEY_BASE64`와 Worker Secret에 동일하게 설정한다. `npx wrangler secret put MEDIA_CDN_SIGNING_KEY_BASE64`는 입력 프롬프트를 사용한다. 값을 인수·코드·문서에 기록하지 않는다.
3. `WRANGLER_SEND_METRICS=false npx wrangler deploy`로 Worker와 `images.pebble-log.com` Custom Domain을 연결한다. 이 명령은 실제 계정/DNS/배포 변경이므로 현재 작업에서는 실행하지 않았다.
4. 배포된 Worker의 테스트 이미지 조회에서 `X-Pebble-Cache: MISS` 다음 `HIT`, 변조·만료 URL 403을 확인한다. Cache API는 커스텀 도메인/route에서 확인해야 하며 workers.dev에서의 확인으로 대체하지 않는다.
5. Oracle 백엔드에 기존 R2 설정과 아래 CDN 환경변수를 설정한 뒤 배포한다. Worker와 비밀 설정이 준비되기 전에는 CDN을 켜지 않는다.

```text
R2_ENABLED=true
MEDIA_CDN_ENABLED=true
MEDIA_CDN_BASE_URL=https://images.pebble-log.com
MEDIA_CDN_SIGNING_KEY_BASE64=<보호된 환경에서 주입>
```

CDN 기본값은 false다. false일 때 기존 S3의 15분 presigned URL을 계속 사용하므로 설정 준비 전 로컬 기능을 바꾸지 않는다. 프론트는 기존 thumbnailUrl/url을 그대로 사용하므로 별도 비밀이나 도메인 하드코딩을 추가하지 않는다.

현재 Oracle API는 로컬에만 있으며 Cloudflare 계정 배포·R2 원격 객체·도메인/DNS 연결·운영 CPU 시간/요청량 검증은 남아 있다. 이 문서는 코드와 배포 준비이며 CDN이 운영에서 활성화되었다는 의미가 아니다.

## 공식 근거

- [R2와 Cache API](https://developers.cloudflare.com/r2/examples/cache-api/): 바이트 캐싱 및 커스텀 도메인/route 요구.
- [Workers Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/): HMAC 검증.
- [R2 presigned URL](https://developers.cloudflare.com/r2/api/s3/presigned-urls/): 기존 S3 서명은 custom domain에서 사용 불가.
- [Workers 요금](https://developers.cloudflare.com/workers/platform/pricing/), [R2 요금](https://developers.cloudflare.com/r2/pricing/): 캐시 HIT도 Worker 요청을 소모한다. 무료 한도와 실제 운영 비용을 배포 전 확인한다.
