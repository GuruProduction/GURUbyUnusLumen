// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.bridge

/**
 * The Spaces room for the CLI bridge. Issue #176.
 *
 * WHY THE COMPOSITION LIVES IN CODE. A room is HTML, CSS and JS stored on the
 * module row, and GURU normally authors those through conversation. This one is
 * the room that makes the conversation between GURU and a CLI visible, so it has
 * to exist before there is anything to talk about, and it must render correctly
 * the first time. Authoring it here means it is reviewed, versioned and rebuilt
 * identically on every install.
 *
 * The room reads its transcript and channel state from the module data space,
 * which the app writes. The compose box POSTs back through the module bridge, so
 * sending from the room uses the same path as sending from the app.
 *
 * NO EXTERNAL ASSETS, NO FRAMEWORK. One self-contained document, because the room
 * renders inside a WebView with no network access on purpose.
 */
object CliBridgeRoom {

    /** Module identity, chosen so a re-install finds the same room. */
    const val MODULE_NAME = CliBridgeService.ROOM_NAME
    const val DISPLAY_NAME = "CLI Bridge"
    const val CATEGORY = "Comms"
    const val DESCRIPTION =
        "A live chat channel between GURU and a CLI agent on another machine. " +
            "Works over your local network or through a Tor onion address."

    fun html(): String = """
<div class="bridge">
  <header>
    <div class="title">
      <span class="dot" id="dot"></span>
      <h1>CLI Bridge</h1>
    </div>
    <div class="meta">
      <span id="agentCount">no agents connected</span>
      <button id="toggle" class="ghost">Start</button>
    </div>
  </header>

  <section class="pair" id="pair">
    <h2>Pair a CLI</h2>
    <p class="hint">Give the CLI the address and the secret below. They are shown once.</p>
    <div class="row"><label>LAN</label><code id="lan">not listening</code></div>
    <div class="row"><label>Onion</label><code id="onion">not published</code></div>
    <div class="row"><label>Secret</label><code id="token">hidden</code></div>
    <div class="row"><button id="reveal" class="ghost">Reveal secret</button>
      <button id="reset" class="danger">New secret</button></div>
    <p class="warn" id="onionWarn"></p>
  </section>

  <section class="transcript" id="transcript"></section>

  <footer>
    <input id="compose" placeholder="Message the CLI agent" autocomplete="off" />
    <button id="send">Send</button>
  </footer>
</div>
"""

    fun css(): String = """
:root {
  --ink: #1f1b16;
  --paper: #faf6ef;
  --rule: #e0d6c6;
  --accent: #7a4a2b;
  --muted: #8a7f70;
}
* { box-sizing: border-box; }
body { margin: 0; background: var(--paper); color: var(--ink);
  font-family: -apple-system, "Segoe UI", Roboto, sans-serif; }
.bridge { display: flex; flex-direction: column; height: 100vh; }
header { display: flex; justify-content: space-between; align-items: center;
  padding: 14px 16px; border-bottom: 1px solid var(--rule); }
.title { display: flex; align-items: center; gap: 10px; }
.title h1 { font-size: 17px; margin: 0; font-weight: 600; letter-spacing: -0.01em; }
.dot { width: 9px; height: 9px; border-radius: 50%; background: var(--muted); }
.dot.on { background: #2f7d4f; }
.meta { display: flex; align-items: center; gap: 12px; font-size: 12px; color: var(--muted); }
button { font: inherit; border-radius: 8px; padding: 7px 12px; cursor: pointer;
  border: 1px solid var(--rule); background: #fff; color: var(--ink); }
button.ghost { background: transparent; }
button.danger { border-color: #c25b52; color: #c25b52; background: transparent; }
.pair { padding: 14px 16px; border-bottom: 1px solid var(--rule); background: #fffdf8; }
.pair h2 { font-size: 13px; margin: 0 0 4px; font-weight: 600; }
.hint { font-size: 12px; color: var(--muted); margin: 0 0 10px; }
.row { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.row label { width: 56px; font-size: 12px; color: var(--muted); }
code { font-family: ui-monospace, Menlo, monospace; font-size: 12px;
  background: var(--paper); padding: 3px 6px; border-radius: 5px;
  word-break: break-all; flex: 1; }
.warn { font-size: 12px; color: #a8562b; margin: 8px 0 0; }
.transcript { flex: 1; overflow-y: auto; padding: 16px; display: flex;
  flex-direction: column; gap: 10px; }
.msg { max-width: 76%; padding: 9px 12px; border-radius: 12px; font-size: 14px; line-height: 1.45; }
.msg.guru { align-self: flex-end; background: var(--accent); color: #fff; }
.msg.agent { align-self: flex-start; background: #fff; border: 1px solid var(--rule); }
.msg .who { display: block; font-size: 10px; opacity: 0.7; margin-bottom: 3px;
  text-transform: uppercase; letter-spacing: 0.06em; }
.empty { color: var(--muted); font-size: 13px; text-align: center; margin-top: 40px; }
footer { display: flex; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--rule); background: #fff; }
footer input { flex: 1; font: inherit; padding: 9px 12px; border-radius: 9px;
  border: 1px solid var(--rule); background: var(--paper); color: var(--ink); }
footer input:focus { outline: 2px solid var(--rule); }
"""

    fun js(): String = """
(function () {
  var state = { messages: [], bridge: {} };

  function el(id) { return document.getElementById(id); }

  function render() {
    var t = el('transcript');
    var msgs = state.messages || [];
    if (!msgs.length) {
      t.innerHTML = '<div class="empty">No messages yet. Start the bridge and pair a CLI.</div>';
      return;
    }
    var html = '';
    for (var i = 0; i < msgs.length; i++) {
      var m = msgs[i];
      var mine = m.from === 'guru';
      html += '<div class="msg ' + (mine ? 'guru' : 'agent') + '">' +
        '<span class="who">' + escapeHtml(m.from || 'agent') + '</span>' +
        escapeHtml(m.text || '') + '</div>';
    }
    t.innerHTML = html;
    t.scrollTop = t.scrollHeight;
  }

  function escapeHtml(s) {
    return String(s).replace(/[&<>"']/g, function (c) {
      return ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c];
    });
  }

  function paintBridge() {
    var b = state.bridge || {};
    el('dot').className = 'dot' + (b.listening ? ' on' : '');
    el('lan').textContent = b.lanAddress ? (b.lanAddress + ':' + b.lanPort) : 'not listening';
    el('onion').textContent = b.onionAddress || 'not published';
    el('token').textContent = 'hidden';
    el('agentCount').textContent = (b.connectedAgents && b.connectedAgents.length)
      ? b.connectedAgents.join(', ')
      : 'no agents connected';
    el('onionWarn').textContent = b.onionError || '';
    el('toggle').textContent = b.listening ? 'Stop' : 'Start';
  }

  // The app pushes the whole state through this global.
  window.onRoomData = function (data) {
    state = data || { messages: [], bridge: {} };
    render();
    paintBridge();
  };

  function post(type, payload) {
    var body = { type: type, payload: payload || {} };
    if (window.GuruBridge && window.GuruBridge.post) {
      window.GuruBridge.post(JSON.stringify(body));
    } else if (window.ReactNativeWebView && window.ReactNativeWebView.postMessage) {
      window.ReactNativeWebView.postMessage(JSON.stringify(body));
    }
  }

  function send() {
    var input = el('compose');
    var text = (input.value || '').trim();
    if (!text) return;
    post('bridge_send', { text: text });
    input.value = '';
  }

  el('send').addEventListener('click', send);
  el('compose').addEventListener('keydown', function (e) {
    if (e.key === 'Enter') { e.preventDefault(); send(); }
  });
  el('toggle').addEventListener('click', function () {
    post((state.bridge && state.bridge.listening) ? 'bridge_stop' : 'bridge_start');
  });
  el('reveal').addEventListener('click', function () {
    post('bridge_reveal_token');
  });
  el('reset').addEventListener('click', function () {
    if (confirm('Replace the current secret? Any paired CLI will need re-pairing.')) {
      post('bridge_reset_token');
    }
  });

  post('bridge_state');
  paintBridge();
})();
"""
}
