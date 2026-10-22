import {chooseRoute,deviceLabel} from './routing.mjs';
const element=id=>document.getElementById(id);
async function reachable(url,timeout=6500) {
  const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),timeout);
  try {
    // Opaque responses prove only network reachability, not file integrity or HTTP status.
    await fetch(url,{mode:'no-cors',cache:'no-store',signal:controller.signal,credentials:'omit'});
    return true;
  } catch {return false;} finally {clearTimeout(timer);}
}
try {
  const response=await fetch('./release.json',{cache:'no-store'});
  if(!response.ok)throw new Error('下载配置暂不可用');
  const release=await response.json();
  const direct=`https://github.com/${release.repository}/releases/download/${release.tag}/${release.asset}`;
  const mirror=`https://gh-proxy.com/${direct}`;
  element('direct').href=direct;element('mirror').href=mirror;
  element('device').textContent=deviceLabel(navigator.userAgent);
  element('version').textContent=`${release.tag} · 开发预览版 · ${(release.bytes/1048576).toFixed(2)} MB`;
  element('checksum').textContent=release.sha256;
  const mainlandHint=Intl.DateTimeFormat().resolvedOptions().timeZone==='Asia/Shanghai' && /^zh/i.test(navigator.language);
  const choice=chooseRoute({directReachable:await reachable(direct),mainlandHint});
  element('status').textContent=choice.reason;
  const button=element('automatic');button.disabled=false;button.textContent=choice.route==='direct'?'从 GitHub 下载 APK':'通过加速线路下载 APK';
  button.onclick=()=>{location.href=choice.route==='direct'?direct:mirror;};
} catch(error) {
  element('status').textContent=`${error.message}，请从 GitHub Release 页面下载。`;
  element('direct').href='https://github.com/Lucifer-cgl/EchoCraft/releases/tag/v0.1.0';
  element('mirror').hidden=true;
}
