export function chooseRoute({directReachable, mainlandHint}) {
  if (directReachable) return {route:'direct', reason:'GitHub 可以连接，优先使用原站（包括可直连的代理网络）。'};
  return {route:'mirror',reason:mainlandHint ? '当前环境可能在中国大陆，GitHub 连接未通过，已选择加速线路。' : 'GitHub 连接未通过，已选择备用加速线路；也可以手动尝试原站。'};
}
export function deviceLabel(ua) {
  if (/android/i.test(ua)) return 'Android 设备 · 请确认手机支持 ARM64';
  if (/iphone|ipad|ipod/i.test(ua)) return 'iOS 设备 · 无法安装 Android APK，可下载后转交安卓手机';
  return '电脑或其他设备 · 下载 APK 后传到安卓手机安装';
}
