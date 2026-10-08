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

## 원격 배포 상태 — 2026-10-04

사용자가 도메인 소유 계정으로 Wrangler OAuth 인증을 완료했다. `pebble-log.com`의 active 상태와 소유 계정 `ae87d49be319ec8a092f90acc9554383`을 확인했고, `wrangler.jsonc`의 account_id를 이 계정으로 고정했다. 해당 계정에 기존 `pebble-media` 버킷이 있으며 공개 r2.dev 접근은 비활성이다. 이 버킷에 연결한 `pebble-images` Worker와 `images.pebble-log.com` Custom Domain을 배포하고 독립 서명 키를 Worker Secret으로 등록했다.

실제 HTTPS 도메인에서 임시 UUID WebP 객체 하나를 업로드해 200 MISS → 200 HIT, 이미지 바이트 일치, HEAD 본문 없음과 외부 no-store 헤더를 확인했다. 캐시가 채워진 뒤에도 만료·변조·무서명 요청은 403, 내부 캐시 경로 직접 접근은 404였다. 검증에 생성한 R2 객체는 삭제했다. 해당 테스트 바이트는 기존 정책대로 내부 캐시에 최대 900초 남을 수 있으며 새 접근 URL을 공유하지 않았다.

서명 키의 로컬 원본은 Git에서 제외된 `infra/image-worker/.dev.vars`에 파일 권한 0600으로 보존했다. 비밀 값은 명령 인수·문서·Git에 기록하지 않았다. backend를 연결할 때 이 값을 보호된 환경에서 `MEDIA_CDN_SIGNING_KEY_BASE64`에 동일하게 주입한다. 기존 키 파일을 덮어쓰거나 재생성하면 현재 Worker와 값이 달라질 수 있으므로 키 교체 절차 없이 재생성하지 않는다.

처음 인증되어 있던 다른 계정 `25eacbdaee77c3ab75b57c40fb64d8da`에도 준비 중 빈 `pebble-media` 버킷을 생성했으나 최종 배포에는 사용하지 않는다. 이 버킷에는 업로드·도메인 연결을 하지 않았으며 해당 계정의 기존 `bangsel-cards` 버킷은 변경하지 않았다. 다른 계정의 빈 준비 버킷은 아직 정리하지 않았다.

## 재배포 및 백엔드 활성화 순서

1. Cloudflare에서 실제 R2 버킷명을 확인하고 `wrangler.jsonc`의 `bucket_name`을 맞춘다. `pebble-media`는 기존 백엔드 기본값이다. 대상 계정과 `pebble-log.com` zone을 확인한다.
2. 보호된 환경에서 독립 서명 키를 생성하고 서버의 `MEDIA_CDN_SIGNING_KEY_BASE64`와 Worker Secret에 동일하게 설정한다. `npx wrangler secret put MEDIA_CDN_SIGNING_KEY_BASE64`는 입력 프롬프트를 사용한다. 값을 인수·코드·문서에 기록하지 않는다.
3. `WRANGLER_SEND_METRICS=false npx wrangler deploy`로 Worker를 배포한다. Worker와 `images.pebble-log.com` 연결은 완료했으며 이후 재배포에도 고정된 대상 계정을 확인한다.
4. 배포된 Worker의 테스트 이미지 조회에서 `X-Pebble-Cache: MISS` 다음 `HIT`, 변조·만료 URL 403을 확인한다. Cache API는 커스텀 도메인/route에서 확인해야 하며 workers.dev에서의 확인으로 대체하지 않는다.
5. Oracle 백엔드에 기존 R2 설정과 아래 CDN 환경변수를 설정한 뒤 배포한다. Worker와 비밀 설정이 준비되기 전에는 CDN을 켜지 않는다.

```text
R2_ENABLED=true
MEDIA_CDN_ENABLED=true
MEDIA_CDN_BASE_URL=https://images.pebble-log.com
MEDIA_CDN_SIGNING_KEY_BASE64=<보호된 환경에서 주입>
```

CDN 기본값은 false다. false일 때 기존 S3의 15분 presigned URL을 계속 사용하므로 설정 준비 전 로컬 기능을 바꾸지 않는다. 프론트는 기존 thumbnailUrl/url을 그대로 사용하므로 별도 비밀이나 도메인 하드코딩을 추가하지 않는다.

이미지 Worker 자체의 원격 배포와 HTTPS 검증은 완료했다. 기존 Git 제외 `.env`의 R2 S3 자격 증명과 endpoint가 도메인 소유 계정의 `pebble-media`를 가리키는 것을 값 노출 없이 확인했다. `.env`에 동일 CDN 서명 키·origin과 `MEDIA_CDN_ENABLED=true`를 보호해 저장하고, 현재 프론트가 연결하는 8081 로컬 API를 이 설정으로 재시작했다. 로컬 미리보기 OAuth는 기존 Naver 대역을 유지하며 실제 Naver 로그인 검증으로 해석하지 않는다. 재시작으로 이전 임시 JWT 세션은 다시 로그인해야 할 수 있다.

별도 8083 검증 API와 실제 8081 API에서 각각 PNG multipart 업로드 → Java WebP 변환 → 실제 비공개 R2 저장 → API가 발급한 CDN URL의 200·WebP 바이트와 HIT를 확인했다. 글을 HIDDEN으로 바꾼 뒤 작성자 조회에서 새 thumbnailUrl=null, 썸네일 삭제 API와 임시 글의 soft-delete도 확인했다. 검증에 만든 모든 R2 객체는 삭제했다. 로컬 임시 글은 서비스 삭제 계약에 따른 soft-delete로 남고, 삭제 큐는 기존 재시도 정책을 유지한다. 기존 사용자 글·이미지는 변경하지 않았다.

CDN HTTP 확인에는 Node 22 fetch를 사용했다. 같은 API URL을 Python urllib로 요청했을 때는 Worker의 `Image unavailable` 본문과 다른 Cloudflare 403을 받았으나, Java 서명과 독립 계산 서명은 일치했고 TTL도 900초였다. 외부 HTTP 클라이언트 차이의 원인은 확정하지 않았으며 보안 설정을 완화하지 않았다. 브라우저에서도 홈의 실제 공개 글 링크를 통해 상세에 진입해 이미지 host=images.pebble-log.com, complete=true, naturalWidth=8/naturalHeight=8을 확인하고 화면을 저장했다. 이후 해당 임시 글을 비공개 처리·soft-delete하고 객체를 삭제했다. 브라우저 작성기의 파일 선택 동작, Oracle API 배포, 실제 Naver 로그인, 운영 CPU 시간/요청량 확인은 별도 후속 검증이다.

## 공식 근거

- [R2와 Cache API](https://developers.cloudflare.com/r2/examples/cache-api/): 바이트 캐싱 및 커스텀 도메인/route 요구.
- [Workers Web Crypto](https://developers.cloudflare.com/workers/runtime-apis/web-crypto/): HMAC 검증.
- [R2 presigned URL](https://developers.cloudflare.com/r2/api/s3/presigned-urls/): 기존 S3 서명은 custom domain에서 사용 불가.
- [Workers 요금](https://developers.cloudflare.com/workers/platform/pricing/), [R2 요금](https://developers.cloudflare.com/r2/pricing/): 캐시 HIT도 Worker 요청을 소모한다. 무료 한도와 실제 운영 비용을 배포 전 확인한다.

## 본문 이미지 경로 (API #92)

글 본문의 `post/<UUID>/body.webp`도 썸네일과 동일하게 Java 서명 발급과 Worker 경로 검사에서 허용한다. 업로드 후 owner 미리보기와 발행된 공개 글 본문의 조회는 기존 API 소유권·본문 참조·공개 상태 검사를 따른다. UUID가 아닌 경로, 임의 확장자, 원본 파일, 만료·변조 서명은 계속 거절한다. 키·버킷 공개 설정 변경은 필요하지 않다.
