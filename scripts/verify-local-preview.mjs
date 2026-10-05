import assert from 'node:assert/strict';

// 고정 로컬 주소에서 Guest GET만 수행한다. 운영 endpoint 입력을 받지 않는다.
const base = 'http://127.0.0.1:8080/api/v1';
const member = '910000000000001000';
const project = '910000000000003001';
async function read(path, expected = 200) {
  const response = await fetch(base + path, { headers: { Accept: 'application/json' }, signal: AbortSignal.timeout(8000) });
  assert.equal(response.status, expected, path);
  return (await response.json()).data;
}
const checks = [
  ['공개 목록·두 번째 페이지', async () => {
    const first = await read('/posts');
    const next = await read('/posts?page=1');
    assert.equal(first.content.filter(p => p.author.id === member).length, 20);
    assert.equal(next.content.filter(p => p.author.id === member).length, 4);
    assert.equal(first.totalElements, 24);
    assert.equal(first.hasNext, true);
    assert.equal(next.hasNext, false);
  }],
  ['검색·태그·분류 AND 조건', async () => {
    const tags = await read('/tags');
    const categories = await read('/categories');
    const spring = tags.find(t => t.slug === 'spring-boot').id;
    const react = tags.find(t => t.slug === 'react').id;
    const backend = categories.find(c => c.slug === 'backend').id;
    const result = await read(`/posts/search?q=Spring&tagId=${spring}&categoryId=${backend}`);
    assert.equal(result.totalElements, 21);
    assert.equal((await read(`/posts?tagId=${react}&categoryId=${backend}`)).totalElements, 0);
  }],
  ['TEXT/CODE 상세·문자열 ID', async () => {
    const post = await read('/blogs/local-preview/posts/integration-1');
    assert.equal(post.id, '910000000000001101');
    assert.equal(post.author.handle, 'local-preview');
    assert.deepEqual(post.blocks.map(b => b.type), ['TEXT', 'CODE']);
    assert.match(post.blocks[0].content, /<script>/);
    assert.equal(post.blocks[1].language, 'JAVA');
  }],
  ['숨김·삭제 글 미노출', async () => {
    await read('/blogs/local-preview/posts/integration-25', 404);
    await read('/blogs/local-preview/posts/integration-26', 404);
    await read('/posts/910000000000001125', 404);
  }],
  ['프로젝트 목록·상태·검색·상세', async () => {
    assert.equal((await read('/projects')).totalElements, 2);
    assert.equal((await read('/projects?lifecycleStatus=COMPLETED')).totalElements, 1);
    assert.equal((await read('/projects/search?q=React')).totalElements, 1);
    const detail = await read(`/projects/${project}`);
    assert.equal(detail.lifecycleStatus, 'COMPLETED');
    assert.equal(detail.features.length, 1);
    assert.equal(detail.links.length, 1);
    await read('/projects/910000000000003003', 404);
  }],
  ['프로젝트 관련 글·페이지 이동', async () => {
    const first = await read(`/projects/${project}/posts`);
    const next = await read(`/projects/${project}/posts?page=1`);
    assert.equal(first.totalElements, 21);
    assert.equal(first.content.length, 20);
    assert.equal(next.content.length, 1);
    assert.equal(next.content[0].urlKey, 'integration-21');
  }],
];
for (const [name, check] of checks) {
  await check();
  console.log(`PASS ${name}`);
}
console.log(`실제 로컬 API 검증 ${checks.length}개 통과 (전용 데이터만 있는 초기 개발 DB 기준)`);
