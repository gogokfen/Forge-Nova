// @ts-check
// House rules: switches of the lobby's game setup and the online room, applied by the host's engine.

import { on } from '../net.js';

/** [key, label, explanation] */
export const HOUSE_RULES = [
  ['freeMulligan', 'Free mulligan on 0 or 7 lands',
    'The first time a player sends back a hand with no lands or with seven lands, that mulligan is free (once per game, for every player, the AI too).'],
];

/** what this computer's host can do (from its hello message): the house rules need Nova's engine patches */
export const hostCaps = { freeMullOk: true };
on('hello', (m) => { if (m.freeMullOk !== undefined) hostCaps.freeMullOk = !!m.freeMullOk; });
