/* Synco Android bridge – runs at document start in Synco frames only (origin-restricted by the app).
   Gives the web app what a WebView lacks: file downloads (blob:/data:), share sheet, screen wake lock. */
(function () {
  if (window.__syncoBridge) return; window.__syncoBridge = 1;
  var N = window.SyncoNative; if (!N || !N.postMessage) return;
  var MAX = 25 * 1024 * 1024;
  function send(o) { try { N.postMessage(JSON.stringify(o)); } catch (e) {} }
  function toB64(blob) { return new Promise(function (res, rej) { var r = new FileReader(); r.onload = function () { res(String(r.result).split(',')[1] || ''); }; r.onerror = rej; r.readAsDataURL(blob); }); }
  function save(href, name) {
    fetch(href).then(function (r) { return r.blob(); }).then(function (bl) {
      if (bl.size > MAX) { send({ t: 'toast', m: 'הקובץ גדול מדי להורדה באפליקציה' }); return; }
      return toB64(bl).then(function (b) { send({ t: 'save', name: name || 'Synco', mime: bl.type || 'application/octet-stream', b64: b }); });
    }).catch(function () { send({ t: 'toast', m: 'ההורדה נכשלה' }); });
  }
  // replies from the app (fingerprint / face sign-in)
  var cbs = {}, seq = 0;
  N.onmessage = function (e) { var d; try { d = JSON.parse(e.data); } catch (x) { return; } if (d && d.rid && cbs[d.rid]) { var f = cbs[d.rid]; delete cbs[d.rid]; f(d); } };
  window.SyncoBio = { call: function (op, extra) { return new Promise(function (res) {
    var rid = 'b' + (++seq); cbs[rid] = res; var o = { t: 'bio', op: op, rid: rid }; extra = extra || {}; for (var k in extra) o[k] = extra[k]; send(o);
    setTimeout(function () { if (cbs[rid]) { delete cbs[rid]; res({ ok: false, timeout: true }); } }, op === 'status' || op === 'clear' ? 5000 : 120000);
  }); } };
  // downloads: <a download href="blob:…"> (Excel, PDF, backups)
  document.addEventListener('click', function (e) {
    var a = e.target && e.target.closest ? e.target.closest('a[download]') : null; if (!a) return;
    var h = a.href || ''; if (!/^(blob:|data:)/.test(h)) return;
    e.preventDefault(); e.stopImmediatePropagation(); save(h, a.getAttribute('download') || '');
  }, true);
  // share sheet (WhatsApp, mail…), with files
  try {
    var share = function (d) {
      d = d || {}; var fs = Array.prototype.slice.call(d.files || []), out = [];
      return fs.reduce(function (p, f) { return p.then(function () { if (f.size > MAX) return; return toB64(f).then(function (b) { out.push({ name: f.name || 'file', mime: f.type || 'application/octet-stream', b64: b }); }); }); }, Promise.resolve())
        .then(function () { send({ t: 'share', title: String(d.title || ''), text: [d.text, d.url].filter(Boolean).join('\n'), files: out }); });
    };
    Object.defineProperty(navigator, 'share', { value: share, configurable: true });
    Object.defineProperty(navigator, 'canShare', { value: function (d) { return !!d; }, configurable: true });
  } catch (e) {}
  // keep the screen on (cooking mode, scanning)
  try {
    Object.defineProperty(navigator, 'wakeLock', { configurable: true, value: { request: function () {
      send({ t: 'wake', on: true });
      var s = { released: false, type: 'screen', onrelease: null, addEventListener: function () {}, removeEventListener: function () {},
        release: function () { if (!s.released) { s.released = true; send({ t: 'wake', on: false }); } return Promise.resolve(); } };
      return Promise.resolve(s);
    } } });
  } catch (e) {}
})();
