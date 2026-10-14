import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {handleImage} from '../src/worker.mjs';
const secret=Buffer.alloc(32,7),origin='https://images.pebble-log.com',objectKey='post/12345678-1234-1234-1234-123456789abc/thumbnail.webp';
const encoded=Buffer.from(objectKey).toString('base64url'),time=1800000000000;
function signed(expires=Math.floor(time/1000)+900,path=encoded){const signature=createHmac('sha256',secret).update(`v1\n${origin}\n${path}\n${expires}`).digest('base64url');return `${origin}/media/${path}?expires=${expires}&signature=${signature}`;}
function setup(){
 let reads=0,cacheReads=0;const entries=new Map(),pending=[];
 const env={MEDIA_CDN_BASE_URL:origin,MEDIA_CDN_SIGNING_KEY_BASE64:secret.toString('base64'),MEDIA_BUCKET:{get:async key=>{reads++;assert.equal(key,objectKey);return {body:new Uint8Array([1,2,3])};}}};
 const cache={match:async key=>{cacheReads++;return entries.get(key.url)?.clone();},put:async(key,response)=>{entries.set(key.url,response);}};
 const context={waitUntil:promise=>pending.push(promise)};
 const call=(url=signed(),options={},clock=time)=>handleImage(new Request(url,options),env,context,{cache,now:()=>clock});
 return {call,env,context,cache,entries,pending,counts:()=>({reads,cacheReads})};
}
test('signed images use R2 once and verify authorization before cache hits',async()=>{
 const s=setup();let r=await s.call();assert.equal(r.status,200);assert.equal(r.headers.get('X-Pebble-Cache'),'MISS');assert.equal(r.headers.get('Cache-Control'),'private, no-store');assert.equal(r.headers.get('Cloudflare-CDN-Cache-Control'),'no-store');await r.arrayBuffer();await Promise.all(s.pending);
 r=await s.call(signed(Math.floor(time/1000)+899));assert.equal(r.headers.get('X-Pebble-Cache'),'HIT');assert.equal(s.counts().reads,1);
 const baseline=s.counts();assert.equal((await s.call(signed(),{},time+900000)).status,403);assert.deepEqual(s.counts(),baseline);
 const tampered=new URL(signed());tampered.searchParams.set('expires',String(Math.floor(time/1000)+800));assert.equal((await s.call(tampered)).status,403);assert.deepEqual(s.counts(),baseline);
});
test('HEAD still authenticates and never returns image bytes',async()=>{const s=setup(),r=await s.call(signed(),{method:'HEAD'});assert.equal(r.status,200);assert.equal(await r.text(),'');assert.equal((await s.call(signed(),{method:'POST'})).status,405);});
test('malformed, unsigned, host mismatched and internal paths never read cache or R2',async()=>{
 const s=setup();for(const url of [origin+'/media/'+encoded,signed()+'&expires=1',signed()+'&extra=1',signed().replace(origin,'https://evil.example'),signed().replace(encoded,Buffer.from(objectKey.replace('12345678','abcdefab')).toString('base64url')),origin+'/__media_cache/v1/'+encoded,signed(Math.floor(time/1000)+931),signed(Math.floor(time/1000)),signed(Math.floor(time/1000)+899,Buffer.from('../private').toString('base64url'))])assert.notEqual((await s.call(url)).status,200);
 assert.deepEqual(s.counts(),{reads:0,cacheReads:0});
});
test('invalid secret fails closed even when the bytes are cached',async()=>{const s=setup();await s.call();await Promise.all(s.pending);s.env.MEDIA_CDN_SIGNING_KEY_BASE64='invalid';assert.equal((await s.call()).status,503);assert.equal(s.counts().cacheReads,1);});
test('R2 errors and missing objects do not create cached successes or leak errors',async()=>{
 const s=setup();s.env.MEDIA_BUCKET.get=async()=>null;assert.equal((await s.call()).status,404);assert.equal(s.entries.size,0);
 s.env.MEDIA_BUCKET.get=async()=>{throw new Error('secret detail');};const r=await s.call();assert.equal(r.status,503);assert.equal((await r.text()).includes('secret detail'),false);assert.equal(s.entries.size,0);
});
test('cache write failure does not make a valid image unavailable',async()=>{const s=setup();s.cache.put=async()=>{throw new Error('cache failed');};assert.equal((await s.call()).status,200);await Promise.all(s.pending);});

// 프로필도 동일한 서명을 검증한 뒤에만 저장소를 조회한다.
test('member profile photos use signed authorization before R2 access',async()=>{
 const key='member/12345678-1234-1234-1234-123456789abc/profile.webp',path=Buffer.from(key).toString('base64url');
 const s=setup();let reads=0;s.env.MEDIA_BUCKET.get=async value=>{assert.equal(value,key);reads++;return {body:new Uint8Array([1,2,3])};};
 assert.equal((await s.call(signed(undefined,path))).status,200);assert.equal(reads,1);
 const tampered=signed(undefined,path).replace(/signature=[^&]+/,'signature='+Buffer.alloc(32).toString('base64url'));
 assert.equal((await s.call(tampered)).status,403);assert.equal(reads,1);
 const unexpected=Buffer.from(key.replace('profile.webp','original.png')).toString('base64url');
 assert.equal((await s.call(signed(undefined,unexpected))).status,403);assert.equal(reads,1);
});

test('uploaded blog logos require a valid signature before R2 access',async()=>{
 const key='member/12345678-1234-1234-1234-123456789abc/blog-logo.webp',path=Buffer.from(key).toString('base64url');const s=setup();let reads=0;s.env.MEDIA_BUCKET.get=async value=>{assert.equal(value,key);reads++;return {body:new Uint8Array([1,2,3])};};
 assert.equal((await s.call(signed(undefined,path))).status,200);assert.equal(reads,1);
 const tampered=signed(undefined,path).replace(/signature=[^&]+/,'signature='+Buffer.alloc(32).toString('base64url'));assert.equal((await s.call(tampered)).status,403);assert.equal(reads,1);
 const unexpected=Buffer.from(key.replace('blog-logo.webp','original.svg')).toString('base64url');assert.equal((await s.call(signed(undefined,unexpected))).status,403);assert.equal(reads,1);
});


test('body images require signed, unexpired URLs and an exact WebP path', async () => {
 const key=objectKey.replace('thumbnail.webp','body.webp'), path=Buffer.from(key).toString('base64url');
 const s=setup();let reads=0;s.env.MEDIA_BUCKET.get=async value=>{assert.equal(value,key);reads++;return {body:new Uint8Array([1,2,3])};};
 assert.equal((await s.call(signed(undefined,path))).status,200);assert.equal(reads,1);
 assert.equal((await s.call(signed(undefined,path),{},time+900000)).status,403);
 for(const invalid of [key.replace('.webp','xwebp'),key.replace('body.webp','original.png')])
   assert.equal((await s.call(signed(undefined,Buffer.from(invalid).toString('base64url')))).status,403);
 assert.equal(reads,1);
});
