'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const {
  startSpin,
  eliminateRandom,
  removeParticipant,
  abortSpin,
  dueEliminations,
  remaining,
  SpinError,
  ELIMINATION_MS
} = require('../src/spinEngine');

function ids(n) {
  return Array.from({ length: n }, (_, i) => `u${i + 1}`);
}

describe('spinEngine', () => {
  it('rejects fewer than 3 players', () => {
    assert.throws(
      () => startSpin({ eligibleUserIds: ids(2), startedBy: 'u1', now: new Date() }),
      (err) => err instanceof SpinError && err.code === 'INSUFFICIENT_PLAYERS'
    );
  });

  it('rejects more than 20 players', () => {
    assert.throws(
      () => startSpin({ eligibleUserIds: ids(21), startedBy: 'u1', now: new Date() }),
      (err) => err instanceof SpinError && err.code === 'TOO_MANY_PLAYERS'
    );
  });

  it('starts with running status and schedules first elimination', () => {
    const now = new Date('2026-01-01T00:00:00Z');
    const spin = startSpin({ eligibleUserIds: ids(3), startedBy: 'u1', now });
    assert.equal(spin.status, 'running');
    assert.equal(spin.events[0].type, 'spin_started');
    assert.equal(spin.nextEliminationAt.getTime(), now.getTime() + ELIMINATION_MS);
    assert.equal(remaining(spin).length, 3);
  });

  it('eliminates until exactly one winner', () => {
    const now = new Date();
    let spin = startSpin({
      eligibleUserIds: ids(4),
      startedBy: 'u1',
      now,
      rng: () => 0
    });
    spin = eliminateRandom(spin, now, () => 0);
    spin = eliminateRandom(spin, now, () => 0);
    spin = eliminateRandom(spin, now, () => 0);
    assert.equal(spin.status, 'completed');
    assert.equal(spin.winnerId, 'u4');
    assert.equal(spin.events.filter((e) => e.type === 'user_eliminated').length, 3);
    assert.equal(spin.events.at(-1).type, 'winner_announced');
    assert.equal(spin.participants.filter((p) => p.status === 'winner').length, 1);
  });

  it('leave during spin can produce a winner', () => {
    const now = new Date();
    let spin = startSpin({ eligibleUserIds: ids(3), startedBy: 'u1', now });
    spin = removeParticipant(spin, 'u1', now, true);
    spin = removeParticipant(spin, 'u2', now, true);
    assert.equal(spin.status, 'completed');
    assert.equal(spin.winnerId, 'u3');
  });

  it('last remaining player leaving aborts the spin', () => {
    const now = new Date();
    const spin = startSpin({ eligibleUserIds: ids(3), startedBy: 'u1', now });
    spin.participants.forEach((p) => {
      if (p.userId !== 'u3') p.status = 'eliminated';
    });
    removeParticipant(spin, 'u3', now, true);
    assert.equal(spin.status, 'aborted');
    assert.equal(spin.abortReason, 'last_players_left');
  });

  it('delayed timer eliminates once then waits 5s from now', () => {
    const start = new Date('2026-01-01T00:00:00Z');
    let spin = startSpin({ eligibleUserIds: ids(5), startedBy: 'u1', now: start, rng: () => 0 });
    const late = new Date(start.getTime() + 20000);
    spin = dueEliminations(spin, late, () => 0);
    assert.equal(spin.status, 'running');
    assert.equal(remaining(spin).length, 4);
    assert.equal(spin.nextEliminationAt.getTime(), late.getTime() + ELIMINATION_MS);
  });

  it('cannot abort a completed spin', () => {
    const now = new Date();
    let spin = startSpin({ eligibleUserIds: ids(3), startedBy: 'u1', now, rng: () => 0 });
    spin = eliminateRandom(spin, now, () => 0);
    spin = eliminateRandom(spin, now, () => 0);
    assert.equal(spin.status, 'completed');
    assert.throws(() => abortSpin(spin, 'x', now), (err) => err.code === 'INVALID_TRANSITION');
  });
});
