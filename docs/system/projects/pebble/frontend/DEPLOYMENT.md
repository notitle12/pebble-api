# Pebble 프론트엔드 배포 설계

작성일: 2026-10-03. 배포 전 검토 문서이며 실제 서비스 도메인·계정 설정·배포 완료를 뜻하지 않는다.

## 1. 목표 구성

| 구성 | 방향 | 상태 |
|---|---|---|
| API | Oracle Cloud의 Spring Boot | 사용자 배포 예정 |
| DNS | Cloudflare | 도메인 준비됨, hostname 미확정 |
| 미디어 | Cloudflare R2 | 사용자 사용 중 |
| 사용자 web | Workers에서 공개 SSR + 브라우저 기능 | 호환성·실배포 검증 전 |
| adminweb | Pages 또는 Workers 정적 배포의 SPA | 저장소·스택·플랫폼 미확정 |

web에 example.com, adminweb에 admin.example.com, API에 api.example.com을 사용할 수 있다. 이는 설명용 예시이며 실제 도메인이 아니다.

## 2. Pages와 Workers

Pages는 웹사이트 빌드 결과 배포 중심이며 Functions로 서버 코드를 실행할 수도 있다. Workers는 서버 코드 실행과 Static Assets 배포를 함께 제공한다. R2를 사용한다는 사실만으로 Workers가 필수는 아니다. 공개 SSR의 프레임워크 지원과 운영 방식이 사용자 web의 주요 선택 기준이다.

Workers는 범용 Node 서버와 완전히 동일한 환경이 아니다. 배포 시 공식 문서의 현재 권장 경로와 설치된 Next.js 버전 호환성을 확인한다. 특정 adapter를 확정·설치된 상태로 기록하지 않는다. Next 개발 서버나 next build 성공을 Workers 런타임 검증으로 대체하지 않는다.

## 3. 최소 배포 검증

1. 공개 API를 조회해 실제 본문과 메타데이터가 첫 HTML에 들어가는 화면을 배포한다.
2. 실제 hostname·HTTPS·API 도달과 404/5xx를 확인한다.
3. Naver callback·Origin·Secure cookie와 세션 복구를 실제 브라우저에서 확인한다.
4. R2 signed URL 이미지·null·만료·접근 거부를 확인한다.
5. 브라우저 번들에 서버 비밀이 없고 개인 응답이 공유 캐시에 남지 않는지 확인한다.
6. 마지막 정상 배포로 되돌릴 수 있는 배포 기록과 환경 설정을 확인한다.

이 검증 뒤 본격 기능 구현의 배포 경로를 확정한다. 전체 사이트를 만들기 전에 배포 불확실성을 줄이는 목적이다.

## 4. 환경과 설정

web의 공개 API 주소와 서버에서 사용할 API 주소가 같은지 확인한다. 공개 NEXT_PUBLIC_*는 브라우저에 포함되므로 비밀을 넣지 않는다. SSR 서버가 Spring Boot API를 호출하도록 구성하며 DB·Redis·R2 credential은 프론트에 복제하지 않는다.

production·preview·로컬 설정을 구분한다. production Origin에 임의 preview hostname을 추가하지 않는다. 별도 테스트 환경을 사용하거나 제한된 hostname과 callback을 설정한다. 같은 사이트 cookie를 검증할 개발 HTTPS 환경도 별도로 마련한다.

## 5. 성능·캐시·미디어

SSR 서버의 API 요청은 Oracle Cloud 응답을 기다리므로 Workers 선택만으로 모든 요청이 빨라지는 것은 아니다. API 위치·응답 시간·호출 횟수·실제 사용자 지연을 측정한다. Project 목록에 없는 media를 얻기 위해 모든 항목의 상세를 추가 호출하는 구조를 기본으로 채택하지 않는다.

초기에는 콘텐츠 장기 캐시를 두지 않고 정적 JS·CSS 캐시와 동적·개인 데이터 캐시를 구분한다. 차단·탈퇴 정책과 signed URL 만료에 맞는 무효화가 준비된 뒤 캐시를 검토한다. 이미지 최적화가 원본 URL보다 오래 접근을 허용하는지 확인한다.

R2 업로드·삭제·URL 발급은 기존 API가 담당한다. 프론트 hosting 선택 때문에 별도 직접 업로드 endpoint나 미디어 공개 프록시를 추가하지 않는다.

## 6. 비용과 운영

상업 운영 조건·요청량·CPU·빌드·이미지·추가 저장소 과금은 출시 시 공식 약관·가격표로 확인한다. 무료나 고정 비용을 영구 전제로 삼지 않는다. 예산 알림은 하드 지출 제한과 다를 수 있으므로 실제 제한 기능을 확인한다.

로그는 traceId와 비민감 실패 정보를 중심으로 구성하고 토큰·OAuth code·signed URL·개인 입력을 수집하지 않는다. frontend·API 배포는 독립적으로 버전 관리하되 API 호환성 변화는 함께 검토한다.
