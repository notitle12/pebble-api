# 로컬 공통 에이전트 문서

공통 규칙과 영역별 지침은 코드 저장소 상위의 `pebble/docs`에서 관리한다. GitHub에 별도 문서 저장소를 만들지 않는다.

```text
pebble/
  docs/          # AGENTS, common, projects/pebble/{backend,frontend,adminweb}
  pebble-api/    # 백엔드 코드와 docs의 제품·DB·API·보안 계약
  pebble-web/    # 프론트 코드와 docs/frontend의 화면·상태 계약
```

각 저장소의 AGENTS.md에서 `../docs/AGENTS.md`를 읽고 담당 영역 경로를 따른다. 현재 로컬 원본은 `/Users/su/Desktop/ai-coding/pebble/docs/AGENTS.md`다. 별도 워크트리에서는 이 위치를 사용하며, 다른 컴퓨터나 클라우드에서는 공통 문서 폴더도 함께 제공한다. 문서가 없으면 알리고 저장소에서 확인 가능한 계약만 따른다. 관련 없는 개인 디렉터리를 탐색하지 않는다.

Obsidian의 AGENTS.md·common·projects는 이 로컬 원본을 심볼릭 링크로 연결한다. 제품 계약 원본은 각 코드 저장소에 남기며, 공통 문서의 backend/contracts와 frontend/source는 해당 원본을 상대 링크로 참조한다. 이전 공통 문서 묶음은 상위 `.document-layout-backup/2026-10-08/system`에 보존하고 현재 기준으로 읽지 않는다.

코드 변경은 제품 저장소의 이슈 → 번호가 있는 브랜치 → 검증·push → PR → 검토·CI → 병합 순서를 따른다. 로컬 공통 문서를 함께 수정하면 관련 이슈/PR과 로컬 문서 CHANGELOG에 변경을 기록한다. API 계약 변경은 이 저장소 docs를 갱신한다.
