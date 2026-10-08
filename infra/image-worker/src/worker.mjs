const encoder = new TextEncoder();
const keyPattern = /^(?:member\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(?:profile|blog-logo)|post\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(?:thumbnail|body)|project\/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\/(?:display|thumbnail))\.webp$/;
let importedSecret, importedKey;
function base64url(bytes) { return btoa(String.fromCharCode(...bytes)).replaceAll('+','-').replaceAll('/','_').replace(/=+$/,''); }
function decode(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error();
  const bytes = Uint8Array.from(atob(value.replaceAll('-','+').replaceAll('_','/') + '='.repeat((4-value.length%4)%4)), c=>c.charCodeAt(0));
  if (base64url(bytes)!==value) throw new Error();
  return bytes;
}
function failure(status) {
  return new Response(status===503?'Image service unavailable':'Image unavailable', {status,headers:{'Cache-Control':'no-store','Cloudflare-CDN-Cache-Control':'no-store','X-Content-Type-Options':'nosniff'}});
}
async function signingKey(secret) {
  if (typeof secret!=='string'||!/^[A-Za-z0-9+/]{43}=$/.test(secret)) throw new Error();
  const bytes=Uint8Array.from(atob(secret),c=>c.charCodeAt(0));
  if(bytes.length!==32||btoa(String.fromCharCode(...bytes))!==secret)throw new Error();
  if(importedSecret!==secret){importedKey=await crypto.subtle.importKey('raw',bytes,{name:'HMAC',hash:'SHA-256'},false,['verify']);importedSecret=secret;}
  return importedKey;
}

export async function handleImage(request, env, context, {cache=globalThis.caches?.default,now=()=>Date.now()}={}) {
  if(!['GET','HEAD'].includes(request.method))return failure(405);
  const url=new URL(request.url);
  let origin;
  try {
    const configured=new URL(env.MEDIA_CDN_BASE_URL);
    if(configured.protocol!=='https:'||configured.port||configured.username||configured.password||configured.pathname!=='/'||configured.search||configured.hash)throw new Error();
    origin=configured.origin;
  }catch{return failure(503);}
  if(url.origin!==origin||url.hash)return failure(403);
  const match=/^\/media\/([A-Za-z0-9_-]{1,160})$/.exec(url.pathname);
  if(!match)return failure(404);
  if([...url.searchParams.keys()].some(key=>!['expires','signature'].includes(key))||url.searchParams.getAll('expires').length!==1||url.searchParams.getAll('signature').length!==1)return failure(403);
  const expiry=url.searchParams.get('expires'), signature=url.searchParams.get('signature');
  const second=Math.floor(now()/1000);
  if(!/^[1-9]\d{0,10}$/.test(expiry??'')||!Number.isSafeInteger(Number(expiry))||Number(expiry)<=second||Number(expiry)>second+930||!/^[-_A-Za-z0-9]{43}$/.test(signature??''))return failure(403);
  let objectKey,signatureBytes;
  try {
    objectKey=new TextDecoder('utf-8',{fatal:true}).decode(decode(match[1]));
    signatureBytes=decode(signature);
    if(!keyPattern.test(objectKey)||signatureBytes.length!==32)throw new Error();
  }catch{return failure(403);}
  let key;
  try{key=await signingKey(env.MEDIA_CDN_SIGNING_KEY_BASE64);}catch{return failure(503);}
  const message=`v1\n${origin}\n${match[1]}\n${expiry}`;
  if(!await crypto.subtle.verify('HMAC',key,signatureBytes,encoder.encode(message)))return failure(403);
  // 캐시 조회 전에 서명과 만료를 검증한다. 내부 캐시 경로는 공개 라우트에서 처리하지 않는다.
  if(!cache||!env.MEDIA_BUCKET)return failure(503);
  const cacheKey=new Request(`${origin}/__media_cache/v1/${match[1]}`,{method:'GET'});
  try {
    let response=await cache.match(cacheKey);
    const hit=!!response;
    if(!response){
      const object=await env.MEDIA_BUCKET.get(objectKey);
      if(!object)return failure(404);
      response=new Response(object.body,{headers:{'Content-Type':'image/webp','Cache-Control':'public, max-age=900'}});
      context.waitUntil(cache.put(cacheKey,response.clone()).catch(()=>{}));
    }
    // 외부 캐시는 서명 검증을 건너뛰지 못하게 한다. 바이트만 Worker 내부 캐시에 저장한다.
    return new Response(request.method==='HEAD'?null:response.body,{headers:{
      'Content-Type':'image/webp','Cache-Control':'private, no-store','Cloudflare-CDN-Cache-Control':'no-store',
      'X-Content-Type-Options':'nosniff','Referrer-Policy':'no-referrer','X-Pebble-Cache':hit?'HIT':'MISS'
    }});
  }catch{return failure(503);}
}
export default {fetch:handleImage};
