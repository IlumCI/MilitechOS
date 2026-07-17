// App-shell service worker: pre-caches the static shell, serves same-origin GETs
// cache-first with background refresh. API calls (github.com, ollama) are cross-origin
// and never intercepted.

const CACHE = 'surgeon-v1';
const SHELL = [
  '.',
  'index.html',
  'manifest.webmanifest',
  'css/app.css',
  'icons/icon.svg',
  'icons/icon-maskable.svg',
  'js/main.js',
  'js/models.js',
  'js/store.js',
  'js/github.js',
  'js/ollama.js',
  'js/prompts.js',
  'js/agent.js',
  'js/validators.js',
  'js/utils.js',
  'js/engine.js',
  'js/scheduler.js',
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()),
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

self.addEventListener('fetch', (event) => {
  const url = new URL(event.request.url);
  if (event.request.method !== 'GET' || url.origin !== self.location.origin) return;

  event.respondWith(
    caches.match(event.request).then((cached) => {
      const refresh = fetch(event.request).then((resp) => {
        if (resp.ok) {
          const copy = resp.clone();
          caches.open(CACHE).then((cache) => cache.put(event.request, copy));
        }
        return resp;
      }).catch(() => cached);
      return cached || refresh;
    }),
  );
});
