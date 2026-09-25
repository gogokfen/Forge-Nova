// @ts-check
// Plays the sound effects / music that Forge's SoundSystem asks for.

import { on, send } from '../net.js';

/** @type {AudioContext|null} */
let ctx = null;
/** @type {Map<string, Promise<AudioBuffer|null>>} */
const buffers = new Map();
const music = new Audio();
music.preload = 'auto';
let musicId = 0;
let musicVolume = 1; // Forge's part (fades), relative to the user's music volume
export const audioPrefs = { sounds: true, music: true, volSounds: 100, volMusic: 100 };

function ac() {
  if (!ctx) ctx = new AudioContext();
  if (ctx.state === 'suspended') ctx.resume().catch(() => {});
  return ctx;
}

function load(url) {
  let p = buffers.get(url);
  if (!p) {
    p = fetch(url)
      .then((r) => (r.ok ? r.arrayBuffer() : Promise.reject(new Error('404'))))
      .then((b) => ac().decodeAudioData(b))
      .catch(() => null);
    buffers.set(url, p);
  }
  return p;
}

on('sound', async (m) => {
  if (!audioPrefs.sounds) return;
  const buf = await load(m.url);
  if (!buf) return;
  const a = ac();
  const src = a.createBufferSource();
  src.buffer = buf;
  const gain = a.createGain();
  // the host sends volume relative to the saved setting; the slider value applies live
  gain.gain.value = Math.max(0, Math.min(1, (m.vol ?? 1) * (audioPrefs.volSounds / 100)));
  src.connect(gain).connect(a.destination);
  src.start();
});

on('music', (m) => {
  switch (m.op) {
    case 'play':
      musicId = m.id;
      musicVolume = m.vol ?? 1;
      music.src = m.url;
      music.volume = Math.max(0, Math.min(1, musicVolume * (audioPrefs.volMusic / 100)));
      if (audioPrefs.music) music.play().catch(() => {});
      break;
    case 'pause': if (m.id === musicId) music.pause(); break;
    case 'resume': if (m.id === musicId && audioPrefs.music) music.play().catch(() => {}); break;
    case 'stop': if (m.id === musicId) { music.pause(); music.removeAttribute('src'); } break;
    case 'volume':
      if (m.id === musicId) {
        musicVolume = m.vol;
        music.volume = Math.max(0, Math.min(1, musicVolume * (audioPrefs.volMusic / 100)));
      }
      break;
    default: break;
  }
});

music.addEventListener('ended', () => send({ t: 'musicEnded', id: musicId }));

const GUEST_KEY = 'nova-guest-audio';

/** A friend in a browser invite keeps their audio settings in this browser (music starts off: it streams from the host). */
export function loadGuestAudioPrefs() {
  let p = { sounds: true, music: false, volSounds: 80, volMusic: 60 };
  try { p = { ...p, ...JSON.parse(localStorage.getItem(GUEST_KEY) || '{}') }; } catch { /* defaults */ }
  applyAudioPrefs(p);
}

export function saveGuestAudioPrefs() {
  try { localStorage.setItem(GUEST_KEY, JSON.stringify(audioPrefs)); } catch { /* storage unavailable */ }
}

export function applyAudioPrefs(p) {
  Object.assign(audioPrefs, p);
  music.volume = Math.max(0, Math.min(1, musicVolume * (audioPrefs.volMusic / 100)));
  if (!audioPrefs.music) music.pause();
  else if (music.src && music.paused) music.play().catch(() => {});
}

// Browsers may block audio until the first interaction; unlock on the first click/key.
const unlock = () => {
  ac();
  if (audioPrefs.music && music.src && music.paused) music.play().catch(() => {});
  window.removeEventListener('pointerdown', unlock);
  window.removeEventListener('keydown', unlock);
};
window.addEventListener('pointerdown', unlock);
window.addEventListener('keydown', unlock);
