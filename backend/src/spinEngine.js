'use strict';

const MIN_PLAYERS = 3;
const MAX_PLAYERS = 20;
const ELIMINATION_MS = 5000;
const WINNER_POINTS = 100;

class SpinError extends Error {
  constructor(code, message, status = 400) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

function assertPlayerCount(count) {
  if (count < MIN_PLAYERS) {
    throw new SpinError('INSUFFICIENT_PLAYERS', 'A spin requires at least 3 eligible users', 400);
  }
  if (count > MAX_PLAYERS) {
    throw new SpinError('TOO_MANY_PLAYERS', 'A spin allows at most 20 eligible users', 400);
  }
}

function startSpin({ eligibleUserIds, startedBy, now, rng = Math.random }) {
  assertPlayerCount(eligibleUserIds.length);
  const unique = [...new Set(eligibleUserIds)];
  if (unique.length !== eligibleUserIds.length) {
    throw new SpinError('DUPLICATE_PARTICIPANTS', 'Eligible users must be unique', 400);
  }
  return {
    status: 'running',
    startedBy,
    startedAt: now,
    nextEliminationAt: new Date(now.getTime() + ELIMINATION_MS),
    winnerId: null,
    virtualPoints: 0,
    abortReason: null,
    participants: unique.map((userId) => ({
      userId,
      status: 'active',
      eliminationOrder: null,
      eliminatedAt: null
    })),
    events: [
      {
        type: 'spin_started',
        at: now,
        payload: { eligible: unique, startedBy }
      }
    ],
    rng
  };
}

function remaining(spin) {
  return spin.participants.filter((p) => p.status === 'active');
}

function nextOrder(spin) {
  const used = spin.participants
    .map((p) => p.eliminationOrder)
    .filter((n) => typeof n === 'number');
  return used.length === 0 ? 1 : Math.max(...used) + 1;
}

function completeWithWinner(spin, winner, now) {
  winner.status = 'winner';
  spin.status = 'completed';
  spin.winnerId = winner.userId;
  spin.virtualPoints = WINNER_POINTS;
  spin.completedAt = now;
  spin.nextEliminationAt = null;
  spin.events.push({
    type: 'winner_announced',
    at: now,
    payload: { winnerId: winner.userId, virtualPoints: WINNER_POINTS }
  });
  return spin;
}

function abortSpin(spin, reason, now) {
  if (spin.status !== 'running') {
    throw new SpinError('INVALID_TRANSITION', `Cannot abort a ${spin.status} spin`, 409);
  }
  spin.status = 'aborted';
  spin.abortReason = reason;
  spin.completedAt = now;
  spin.nextEliminationAt = null;
  spin.events.push({
    type: 'spin_aborted',
    at: now,
    payload: { reason }
  });
  return spin;
}

function removeParticipant(spin, userId, now, asLeave = true) {
  if (spin.status !== 'running') {
    return spin;
  }
  const participant = spin.participants.find((p) => p.userId === userId);
  if (!participant || participant.status !== 'active') {
    return spin;
  }
  participant.status = asLeave ? 'left' : 'eliminated';
  participant.eliminationOrder = nextOrder(spin);
  participant.eliminatedAt = now;
  spin.events.push({
    type: 'user_eliminated',
    at: now,
    payload: {
      userId,
      reason: asLeave ? 'left' : 'eliminated',
      remaining: remaining(spin).map((p) => p.userId)
    }
  });

  const left = remaining(spin);
  if (left.length === 1) {
    return completeWithWinner(spin, left[0], now);
  }
  if (left.length === 0) {
    return abortSpin(spin, 'last_players_left', now);
  }
  spin.nextEliminationAt = new Date(now.getTime() + ELIMINATION_MS);
  return spin;
}

function eliminateRandom(spin, now, rng = Math.random) {
  if (spin.status !== 'running') {
    throw new SpinError('INVALID_TRANSITION', 'Spin is not running', 409);
  }
  const active = remaining(spin);
  if (active.length <= 1) {
    if (active.length === 1) {
      return completeWithWinner(spin, active[0], now);
    }
    return abortSpin(spin, 'last_players_left', now);
  }
  const pick = active[Math.floor(rng() * active.length)];
  return removeParticipant(spin, pick.userId, now, false);
}

function dueEliminations(spin, now, rng = Math.random) {
  const out = [];
  let current = spin;
  while (
    current.status === 'running' &&
    current.nextEliminationAt &&
    now.getTime() >= current.nextEliminationAt.getTime()
  ) {
    current = eliminateRandom(current, now, rng);
    out.push(current);
    if (current.status === 'running') {
      current.nextEliminationAt = new Date(now.getTime() + ELIMINATION_MS);
    }
  }
  return current;
}

module.exports = {
  MIN_PLAYERS,
  MAX_PLAYERS,
  ELIMINATION_MS,
  WINNER_POINTS,
  SpinError,
  assertPlayerCount,
  startSpin,
  remaining,
  abortSpin,
  removeParticipant,
  eliminateRandom,
  dueEliminations
};
