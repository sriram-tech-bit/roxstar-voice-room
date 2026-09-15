'use strict';

const store = require('./store');
const { broadcastSpinEvents } = require('./app');
const { ELIMINATION_MS } = require('./spinEngine');

const timers = new Map();

function createScheduler(io) {
  function clear(spinId) {
    const t = timers.get(spinId);
    if (t) clearTimeout(t);
    timers.delete(spinId);
  }

  async function run(spinId) {
    clear(spinId);
    try {
      const result = await store.tickSpin(spinId, new Date());
      if (!result) return;
      const spin = await store.getSpinById(spinId);
      if (!spin) return;
      const snapshot = await store.getRoomSnapshot(spin.roomId);
      broadcastSpinEvents(io, spin.roomId, result.inserted, snapshot);
      io.to(`room:${spin.roomId}`).emit('room_state', snapshot);
      if (result.status === 'running') {
        schedule(spinId);
      }
    } catch (err) {
      console.error('spin tick failed', spinId, err);
      schedule(spinId, ELIMINATION_MS);
    }
  }

  function schedule(spinId, delay = ELIMINATION_MS) {
    clear(spinId);
    timers.set(spinId, setTimeout(() => run(spinId), delay));
  }

  async function recover() {
    const ids = await store.listRunningSpinIds();
    for (const id of ids) {
      const spin = await store.getSpinById(id);
      const delay = spin.nextEliminationAt
        ? Math.max(0, new Date(spin.nextEliminationAt).getTime() - Date.now())
        : 0;
      schedule(id, delay);
    }
  }

  return { schedule, recover, run, clear };
}

module.exports = { createScheduler };
