// End-to-end test of online rooms, run against a dev host: forge-nova started with --dev (token "dev").
// A "host" bot uses the local socket + API like the app window; a "guest" bot joins through the online
// listener like a friend's browser; both play passively next to an AI. Usage (from the Forge folder):
//   node nova/bench/online-test.mjs    (env DECK=<a Forge .dck file> to import it as the friend's deck, RUN=seconds of play)
// It opens and closes rooms on port 36743 without UPnP (PORT=... to change; the room remembers the port as Forge's network port).
import fs from 'node:fs';

const HOST = process.env.NOVA || 'http://127.0.0.1:7777';
const ONLINE_PORT = Number(process.env.PORT || 36743);
const TOKEN = 'dev';
const DECK_FILE = process.env.DECK || '';
const RUN_SECONDS = Number(process.env.RUN || 40);

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const check = (ok, what) => { results.push([ok, what]); console.log(`${ok ? 'PASS' : 'FAIL'}  ${what}`); };

async function api(path, body) {
  const r = await fetch(`${HOST}/api/${path}`, {
    method: body === undefined ? 'GET' : 'POST',
    headers: { 'X-Nova-Token': TOKEN, 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(`${path}: ${r.status} ${j.error || ''}`);
  return j;
}

class Bot {
  constructor(name, url) {
    this.name = name;
    this.url = url;
    this.cards = new Map();
    this.players = new Map();
    this.local = [];
    this.waiters = [];
    this.counts = {};
    this.autoplay = false;
    this.prompt = null;
    this.violations = [];
    this.lastSent = '';
    this.game = {};
    this.all = [];
  }

  connect() {
    return new Promise((resolve, reject) => {
      this.closed = null;
      const ws = new WebSocket(this.url);
      this.ws = ws;
      ws.onopen = () => resolve();
      ws.onerror = (e) => reject(new Error(`${this.name}: websocket error`));
      ws.onmessage = (ev) => this.onMsg(JSON.parse(ev.data));
      ws.onclose = (ev) => { this.closed = ev.code; this.onMsg({ t: '_close', code: ev.code, reason: ev.reason }); };
    });
  }

  send(m) { if (this.ws.readyState === 1) this.ws.send(JSON.stringify(m)); }

  waitFor(pred, ms = 30000, what = '') {
    for (const m of this.all) if (pred(m)) return Promise.resolve(m);
    return new Promise((resolve, reject) => {
      const w = { pred, resolve };
      this.waiters.push(w);
      setTimeout(() => { const i = this.waiters.indexOf(w); if (i >= 0) { this.waiters.splice(i, 1); reject(new Error(`${this.name}: timeout waiting for ${what}`)); } }, ms);
    });
  }

  /** waits only for messages arriving after this call */
  next(pred, ms = 30000, what = '') {
    return new Promise((resolve, reject) => {
      const w = { pred, resolve };
      this.waiters.push(w);
      setTimeout(() => { const i = this.waiters.indexOf(w); if (i >= 0) { this.waiters.splice(i, 1); reject(new Error(`${this.name}: timeout waiting for ${what}`)); } }, ms);
    });
  }

  onMsg(m) {
    this.all.push(m);
    if (this.all.length > 5000) this.all.splice(0, 2000);
    this.counts[m.t] = (this.counts[m.t] || 0) + 1;
    switch (m.t) {
      case 'match': this.local = m.local; this.matchMsg = m; break;
      case 'state':
        if (m.full) { this.cards.clear(); this.players.clear(); }
        if (m.game) this.game = m.game;
        if (m.players) for (const p of m.players) this.players.set(p.id, p);
        if (m.cards) for (const c of m.cards) this.cards.set(c.id, c);
        if (m.gone) for (const id of m.gone) this.cards.delete(id);
        this.checkHidden();
        break;
      case 'prompt': this.prompt = m; if (this.autoplay) setTimeout(() => this.act(), 25); break;
      case 'sel': this.sel = m; break;
      case 'dialog': if (this.autoplay) setTimeout(() => this.answer(m), 25); break;
      default: break;
    }
    for (const w of [...this.waiters]) {
      if (w.pred(m)) { this.waiters.splice(this.waiters.indexOf(w), 1); w.resolve(m); }
    }
  }

  /** Another player's hand and library must never reach us with faces. */
  checkHidden() {
    const me = this.local[0];
    if (me === undefined) return;
    for (const p of this.players.values()) {
      if (p.id === me) continue;
      for (const zone of ['Hand', 'Library']) {
        for (const id of p.z?.[zone] || []) {
          const c = this.cards.get(id);
          if (c && !c.hid && c.n) this.violations.push(`${this.name} sees ${zone} card ${c.n} of ${p.n}`);
        }
      }
    }
  }

  act() {
    const p = this.prompt;
    if (!p) return;
    const key = JSON.stringify([p.msg, p.b1, p.b2, p.input]);
    if (p.b1?.on) {
      if (key === this.lastSent && this.lastSentAt > Date.now() - 400) return;
      this.lastSent = key;
      this.lastSentAt = Date.now();
      this.send({ t: 'ok' });
    } else if (/InputSelect/.test(p.input || '') || this.sel?.ids?.length) {
      // pick something so OK becomes possible: a selectable card, else ourselves (e.g. "who starts?")
      if (key === this.lastSent && this.lastSentAt > Date.now() - 400) return;
      this.lastSent = key;
      this.lastSentAt = Date.now();
      if (this.sel?.ids?.length) this.send({ t: 'card', id: this.sel.ids[0], btn: 1 });
      else if (this.local[0] !== undefined) this.send({ t: 'player', id: this.local[0], btn: 1 });
    } else if (p.b2?.on && /Mulligan|Attack|Block/.test(p.input || '') === false && p.input) {
      if (key === this.lastSent) return;
      this.lastSent = key;
      this.send({ t: 'cancel' });
    }
  }

  answer(d) {
    let v = null;
    switch (d.kind) {
      case 'choose': v = d.min < 0 ? [] : [...Array(Math.max(0, d.min)).keys()]; break;
      case 'option': v = d.def >= 0 ? d.def : 0; break;
      case 'order': {
        const src = d.srcCount, dst = d.items.length - src;
        const keep = d.remMax < 0 ? src : Math.min(src, d.remMax);
        const move = Math.max(0, src - keep);
        v = { order: [...Array(dst).keys()].map((i) => i + src).concat([...Array(move).keys()]) };
        break;
      }
      case 'input': v = d.options?.[0] ?? d.initial ?? '0'; break;
      case 'message': v = true; break;
      case 'assignDamage': {
        let left = d.damage;
        const b = d.blockers.map((x) => { const n = Math.min(left, Math.max(0, x.lethal)); left -= n; return n; });
        let def = 0;
        if (left > 0) { if (d.defender) def = left; else b[b.length - 1] += left; }
        v = { blockers: b, defender: def };
        break;
      }
      case 'assignAmount': {
        const vals = d.targets.map(() => 0);
        let left = d.amount;
        for (let i = 0; left > 0; i = (i + 1) % vals.length) { if (vals[i] < d.targets[i].max) { vals[i]++; left--; } else if (d.targets.every((t, j) => vals[j] >= t.max)) break; }
        v = vals;
        break;
      }
      case 'arrange': v = d.items.map((_, i) => i); break;
      case 'sideboard': v = null; break;
      default: v = null;
    }
    this.send({ t: 'reply', id: d.id, v });
  }
}

async function main() {
  // --- wait for the host to be ready
  for (let i = 0; ; i++) {
    try { const s = await api('status'); if (s.ready) break; } catch { /* starting */ }
    if (i > 120) throw new Error('host not ready');
    await sleep(1000);
  }
  const host = new Bot('host', `${HOST.replace('http', 'ws')}/ws?token=${TOKEN}`);
  await host.connect();
  await host.waitFor((m) => m.t === 'hello' && m.ready, 10000, 'hello');
  // a room may still be open from an earlier run
  try { await api('room/close', {}); await sleep(500); } catch { /* none */ }

  // --- security: nothing of the API on the online port before a room exists
  await api('room/open', {
    port: ONLINE_PORT, upnp: false, format: 'commander', life: null, games: 1, shareDecks: true,
    hostDeck: { src: 'gen', name: 'randomCommanderPrecon' }, seats: [{ kind: 'friend' }, { kind: 'ai' }],
  });
  const roomMsg = await host.waitFor((m) => m.t === 'room' && m.invite?.code && m.seats.length === 3, 10000, 'room state');
  const code = roomMsg.invite.code;
  check(!!code && roomMsg.isHost, 'host gets the room state with an invite code');
  check(roomMsg.seats.length === 3 && roomMsg.seats[1].kind === 'friend' && roomMsg.seats[1].open, 'room has host, an open friend seat and an AI seat');
  check((roomMsg.invite.links || []).some((l) => l.url.includes('#join=' + code)), 'invite links carry the code');

  const base = `http://127.0.0.1:${ONLINE_PORT}`;
  let r = await fetch(`${base}/api/decks`);
  check(r.status === 404 || r.status === 403, `online port has no API (/api/decks → ${r.status})`);
  r = await fetch(`${base}/api/quit`, { method: 'POST' });
  check(r.status === 404 || r.status === 403 || r.status === 405, `online port can't quit the host (${r.status})`);
  r = await fetch(`${base}/img?key=${encodeURIComponent('i:../../../../../Windows/win')}&k=${code}`);
  check(r.status === 404, `image keys can't escape the image cache (${r.status})`);
  r = await fetch(`${base}/img?key=${encodeURIComponent('c:Sol Ring')}&k=wrong`);
  check(r.status === 403, `images need the invite code (${r.status})`);
  r = await fetch(`${base}/`);
  const html = await r.text();
  check(r.status === 200 && html.includes('js/main.js'), 'online port serves the client page');
  r = await fetch(`${base}/js/net.js`, { headers: { 'Accept-Encoding': 'gzip' } });
  check(r.status === 200, `client scripts served (content-encoding: ${r.headers.get('content-encoding')})`);
  try {
    const bad = new Bot('intruder', `ws://127.0.0.1:${ONLINE_PORT}/ws?room=nope`);
    await bad.connect();
    check(false, 'a wrong invite code is refused');
  } catch {
    check(true, 'a wrong invite code is refused');
  }

  // --- a friend joins
  const guest = new Bot('guest', `ws://127.0.0.1:${ONLINE_PORT}/ws?room=${code}&v=1`);
  await guest.connect();
  const info = await guest.waitFor((m) => m.t === 'roomInfo', 5000, 'roomInfo');
  check(!!info.hostName && info.open === 1, `friend sees the room preview (${info.hostName}, ${info.open} open)`);
  guest.send({ t: 'join', name: 'Human Torch' });
  const err = await guest.waitFor((m) => m.t === 'joinError', 5000, 'joinError');
  check(/human/i.test(err.msg), 'names containing "human" are refused (Forge would rename them)');
  guest.send({ t: 'join', name: 'Bob' });
  const welcome = await guest.waitFor((m) => m.t === 'welcome', 5000, 'welcome');
  check(!!welcome.key, 'friend gets a seat key');
  const gRoom = await guest.waitFor((m) => m.t === 'room' && m.you === welcome.seat, 5000, 'guest room');
  check(!gRoom.invite && !gRoom.isHost, 'friends don\'t get the invite details');

  // chat both ways
  guest.send({ t: 'chat', msg: 'hi from Bob' });
  await host.waitFor((m) => m.t === 'chat' && m.from === 'Bob' && m.msg === 'hi from Bob', 5000, 'chat at host');
  host.send({ t: 'chat', msg: 'welcome <b>Bob</b>' });
  const hc = await guest.waitFor((m) => m.t === 'chat' && m.from && m.from !== 'Bob', 5000, 'chat at guest');
  check(hc.msg === 'welcome <b>Bob</b>', 'chat reaches both sides (text is sent raw, escaped by the client)');

  // deck: import a Forge .dck file (or a pasted list)
  let text = DECK_FILE ? fs.readFileSync(DECK_FILE, 'utf8') : '';
  if (!text) text = 'Commander\n1 Atraxa, Praetors\' Voice\nDeck\n1 Sol Ring\n1 Command Tower\n' + ['Forest', 'Island', 'Plains', 'Swamp'].map((l) => `24 ${l}`).join('\n');
  guest.send({ t: 'deck', mode: 'text', text, name: 'My deck', ref: 'r1' });
  const dres = await guest.waitFor((m) => m.t === 'deckResult' && m.ref === 'r1', 20000, 'deckResult');
  check(dres.ok && dres.deck?.cmdrs?.length > 0, `deck import: ${dres.ok ? `${dres.deck.name}, ${dres.deck.count} cards, commander ${dres.deck.cmdrs?.map((c) => c.n).join(' + ')}` : dres.error}${dres.unknown?.length ? `, unknown: ${dres.unknown.join('; ')}` : ''}`);
  // a pasted Moxfield-style list with the commander in the sideboard
  const mox = '1 Sol Ring\n1 Arcane Signet\n98 Forest\n\n1 Omnath, Locus of Mana';
  guest.send({ t: 'deck', mode: 'text', text: mox, name: 'Mox', check: true, ref: 'r2' });
  const d2 = await guest.waitFor((m) => m.t === 'deckResult' && m.ref === 'r2', 20000, 'deckResult 2');
  check(d2.ok && d2.check, `check-only import works (${d2.deck?.name}, ${d2.deck?.count} cards, cmdrs ${JSON.stringify(d2.deck?.cmdrs?.map((c) => c.n) || [])})`);

  guest.send({ t: 'ready', on: true });
  await host.waitFor((m) => m.t === 'room' && m.seats.find((s) => s.name === 'Bob')?.ready && !m.why, 10000, 'start allowed');
  check(true, 'host may start once the friend is ready');

  // --- the match
  host.autoplay = true;
  guest.autoplay = true;
  await api('room/start', {});
  const gm = await guest.waitFor((m) => m.t === 'match', 30000, 'guest match');
  const hm = await host.waitFor((m) => m.t === 'match', 30000, 'host match');
  check(gm.online && hm.online, 'both get an online match');
  check(gm.local.length === 1 && hm.local.length === 1 && gm.local[0] !== hm.local[0], `each controls their own player (host ${hm.local}, friend ${gm.local})`);
  await guest.waitFor((m) => m.t === 'state' && m.players, 20000, 'guest state');
  const gp = guest.players.get(gm.local[0]);
  check(!!gp && gp.n === 'Bob', `friend's player is called ${gp?.n}`);
  await sleep(4000);

  const hostPlayerAtGuest = guest.players.get(hm.local[0]);
  check((hostPlayerAtGuest?.z?.Hand || []).length === 0 && (hostPlayerAtGuest?.['z']?.['Hand#'] || hostPlayerAtGuest?.z?.['Hand#']) > 0, `host's hand is only a count for the friend (${hostPlayerAtGuest?.z?.['Hand#']} hidden)`);
  const peers = await host.waitFor((m) => m.t === 'peers' && m.list.length === 2, 20000, 'peers');
  check(peers.list.some((p) => p.name === 'Bob' && p.conn), 'peer status lists the friend as connected');

  // let the game run
  const until = Date.now() + RUN_SECONDS * 1000;
  while (Date.now() < until) {
    await sleep(2000);
    if (guest.all.some((m) => m.t === 'gameOver') || (guest.game.turn || 0) >= 7) break;
  }
  console.log(`  turn ${guest.game.turn}, phase ${guest.game.phase}; guest msgs ${JSON.stringify(guest.counts)}`);
  for (const b of [host, guest]) console.log(`  ${b.name} last prompt: ${JSON.stringify(b.prompt).slice(0, 300)}
  ${b.name} sel: ${JSON.stringify(b.sel).slice(0, 200)}`);
  console.log(`  host msgs ${JSON.stringify(host.counts)}`);
  const guestHand = guest.players.get(gm.local[0])?.z?.Hand || [];
  check(guestHand.length > 0 && guestHand.every((id) => guest.cards.get(id)?.n), `friend sees their own hand (${guestHand.length} cards)`);
  check(guest.violations.length === 0, `no hidden cards leaked to the friend (${guest.violations.slice(0, 3).join('; ')})`);
  check(host.violations.length === 0, `no hidden cards leaked to the host (${host.violations.slice(0, 3).join('; ')})`);
  check((guest.game.turn || 0) >= 2, `the game advanced (turn ${guest.game.turn})`);
  check((guest.counts.sound || 0) > 0, `friend hears the game's sounds (${guest.counts.sound || 0})`);

  // --- reconnect: the friend's connection drops and comes back
  guest.autoplay = false;
  guest.ws.close();
  await host.waitFor((m) => m.t === 'chat' && /lost the connection/.test(m.msg), 10000, 'disconnect notice');
  check(true, 'host is told the friend lost the connection');
  const peersOff = await host.next((m) => m.t === 'peers' && m.list.some((p) => p.name === 'Bob' && !p.conn), 15000, 'peer offline');
  check(!!peersOff, 'peer status shows the friend offline');
  const again = new Bot('guest2', `ws://127.0.0.1:${ONLINE_PORT}/ws?room=${code}&v=1&key=${encodeURIComponent(welcome.key)}`);
  again.autoplay = true;
  await again.connect();
  await again.waitFor((m) => m.t === 'welcome', 5000, 'welcome again');
  const m2 = await again.waitFor((m) => m.t === 'match', 10000, 'match after reconnect');
  const s2 = await again.waitFor((m) => m.t === 'state' && m.full, 10000, 'full state after reconnect');
  check(m2.local[0] === gm.local[0] && s2.full, 'reconnected friend gets the same seat and the full game state');

  // --- the AI takes over for the friend
  const bobSeat = (await host.waitFor((m) => m.t === 'room' && m.seats.some((s) => s.name === 'Bob'), 5000, 'room')).seats.find((s) => s.name === 'Bob').id;
  await api('room/takeover', { id: bobSeat });
  await again.waitFor((m) => m.t === 'state' && m.players?.some((p) => p.id === gm.local[0] && p.ai), 15000, 'AI flag');
  check(true, 'after an AI takeover the friend\'s player is AI-controlled');
  await sleep(3000);

  // --- the host ends the match: everyone is back in the room
  host.send({ t: 'leave' });
  const back = await again.waitFor((m) => m.t === 'room' && m.state === 'lobby', 30000, 'room after match');
  check(!!back, 'friend returns to the room when the host ends the match');
  const backHost = await host.waitFor((m) => m.t === 'room' && m.state === 'lobby' && m.seats.some((s) => s.name === 'Bob' && !s.ready), 30000, 'host room after match');
  check(!!backHost, 'host is back in the room; the friend has to get ready again');

  // --- closing the room
  console.log(`  before close: again.closed=${again.closed} last msgs ${JSON.stringify(again.all.slice(-4).map((m) => m.t))}`);
  await api('room/close', {});
  const closed = await again.waitFor((m) => m.t === 'roomClosed', 5000, 'roomClosed').catch((e) => { console.log(`  again.closed=${again.closed}, last ${JSON.stringify(again.all.slice(-6)).slice(0, 400)}`); return null; });
  await sleep(500);
  check(!!closed && again.closed === 4001, `friend is told the room closed (close code ${again.closed})`);
  let refused = false;
  try { const late = new Bot('late', `ws://127.0.0.1:${ONLINE_PORT}/ws?room=${code}`); await late.connect(); } catch { refused = true; }
  check(refused, 'the online port is closed after the room');

  host.ws.close();
  const failed = results.filter(([ok]) => !ok).length;
  console.log(`\n${results.length - failed}/${results.length} checks passed`);
  process.exit(failed ? 1 : 0);
}

main().catch((e) => { console.error('ERROR', e.message); process.exit(2); });
