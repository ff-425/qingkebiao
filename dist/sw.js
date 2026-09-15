/* 清课表 Service Worker
   作用只有一个：装到主屏幕后，没网也能打开课表。

   策略是「网络优先，失败回落缓存」，不是反过来。
   缓存优先会让你改完代码重新部署后，手机上还是旧版本，要刷两次才更新 ——
   这个 App 只有 60KB 上下，联网时多一次请求无感，换来的是"部署完立刻是新的"。
   离线时全部走缓存，功能不受影响。 */

const CACHE = 'qingkebiao-v1';
const SHELL = [
  './',
  './index.html',
  './app.css',
  './app.js',
  './manifest.webmanifest',
  './icon-180.png',
  './icon-192.png',
  './icon-512.png'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(CACHE)
      .then(c => c.addAll(SHELL))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;

  // 只接管自己域名下的资源。字体等跨域请求交给浏览器的 HTTP 缓存；
  // 离线时字体回落到系统中文字体栈 —— app.css 里每个字族都写了 fallback。
  if (new URL(req.url).origin !== location.origin) return;

  e.respondWith(
    fetch(req)
      .then(res => {
        if (res.ok){
          const copy = res.clone();
          caches.open(CACHE).then(c => c.put(req, copy));
        }
        return res;
      })
      .catch(() =>
        // 断网：拿缓存；连缓存都没有（比如深链）就回到 App 本体
        caches.match(req).then(hit => hit || caches.match('./index.html'))
      )
  );
});
