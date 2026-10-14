import test from 'node:test';
import assert from 'node:assert/strict';
import {createHmac} from 'node:crypto';
import {Miniflare,convertV4MiniflareOptions} from 'miniflare';
const secret=Buffer.alloc(32,7),origin='https://images.pebble-log.com';
const key='post/12345678-1234-1234-1234-123456789abc/thumbnail.webp',encoded=Buffer.from(key).toString('base64url');
function signed(expires=Math.floor(Date.now()/1000)+899){const sig=createHmac('sha256',secret).update(`v1\n${origin}\n${encoded}\n${expires}`).digest('base64url');return `${origin}/media/${encoded}?expires=${expires}&signature=${sig}`;}
test('workerd serves private R2 through signed requests and its Cache API',async()=>{
 const mf=new Miniflare(convertV4MiniflareOptions({modules:true,scriptPath:new URL('../src/worker.mjs',import.meta.url).pathname,compatibilityDate:'2026-10-04',r2Buckets:['MEDIA_BUCKET'],bindings:{MEDIA_CDN_BASE_URL:origin,MEDIA_CDN_SIGNING_KEY_BASE64:secret.toString('base64')}}));
 try{
  const bucket=await mf.getR2Bucket('MEDIA_BUCKET');await bucket.put(key,new Uint8Array([1,2,3]),{httpMetadata:{contentType:'image/webp'}});
  const url=signed();let response=await mf.dispatchFetch(url);assert.equal(response.status,200);assert.equal(response.headers.get('X-Pebble-Cache'),'MISS');assert.deepEqual([...new Uint8Array(await response.arrayBuffer())],[1,2,3]);
  response=await mf.dispatchFetch(url);assert.equal(response.headers.get('X-Pebble-Cache'),'HIT');await response.arrayBuffer();
  response=await mf.dispatchFetch(signed(Math.floor(Date.now()/1000)-1));assert.equal(response.status,403);
  response=await mf.dispatchFetch(origin+'/__media_cache/v1/'+encoded);assert.equal(response.status,404);
 }finally{await mf.dispose();}
});
