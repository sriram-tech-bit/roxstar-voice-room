'use strict';

const { query, withTransaction } = require('./db');
const engine = require('./spinEngine');

function roomCode() {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  let out = '';
  for (let i = 0; i < 6; i += 1) out += chars[Math.floor(Math.random() * chars.length)];
  return out;
}

async function createUser(displayName) {
  const { rows } = await query(
    'INSERT INTO users (display_name) VALUES ($1) RETURNING id, display_name, created_at',
    [displayName]
  );
  return rows[0];
}

async function getUser(id) {
  const { rows } = await query('SELECT * FROM users WHERE id = $1', [id]);
  return rows[0] || null;
}

async function createRoom(ownerId) {
  const owner = await getUser(ownerId);
  if (!owner) {
    const err = new Error('User not found');
    err.status = 404;
    err.code = 'USER_NOT_FOUND';
    throw err;
  }
  return withTransaction(async (client) => {
    let code = roomCode();
    for (let i = 0; i < 5; i += 1) {
      const clash = await client.query('SELECT 1 FROM rooms WHERE code = $1', [code]);
      if (!clash.rowCount) break;
      code = roomCode();
    }
    const room = await client.query(
      `INSERT INTO rooms (code, owner_id, status) VALUES ($1, $2, 'open')
       RETURNING id, code, owner_id, status, created_at`,
      [code, ownerId]
    );
    await client.query(
      `INSERT INTO room_members (room_id, user_id, role, connected)
       VALUES ($1, $2, 'owner', TRUE)`,
      [room.rows[0].id, ownerId]
    );
    return room.rows[0];
  });
}

async function activeMembers(roomId, client = null) {
  const q = client ? client.query.bind(client) : query;
  const { rows } = await q(
    `SELECT rm.*, u.display_name
     FROM room_members rm
     JOIN users u ON u.id = rm.user_id
     WHERE rm.room_id = $1 AND rm.left_at IS NULL
     ORDER BY rm.joined_at ASC`,
    [roomId]
  );
  return rows;
}

async function getRoomById(id) {
  const { rows } = await query('SELECT * FROM rooms WHERE id = $1', [id]);
  return rows[0] || null;
}

async function getRoomByCode(code) {
  const { rows } = await query('SELECT * FROM rooms WHERE code = $1', [code]);
  return rows[0] || null;
}

async function joinRoom(code, userId) {
  const user = await getUser(userId);
  if (!user) {
    const err = new Error('User not found');
    err.status = 404;
    err.code = 'USER_NOT_FOUND';
    throw err;
  }
  return withTransaction(async (client) => {
    const { rows } = await client.query('SELECT * FROM rooms WHERE code = $1 FOR UPDATE', [code]);
    const room = rows[0];
    if (!room) {
      const err = new Error('Room not found');
      err.status = 404;
      err.code = 'ROOM_NOT_FOUND';
      throw err;
    }
    if (room.status !== 'open') {
      const err = new Error('Room is closed');
      err.status = 409;
      err.code = 'ROOM_CLOSED';
      throw err;
    }
    const existing = await client.query(
      'SELECT * FROM room_members WHERE room_id = $1 AND user_id = $2',
      [room.id, userId]
    );
    if (existing.rowCount) {
      await client.query(
        `UPDATE room_members SET left_at = NULL, connected = TRUE, joined_at = CASE
           WHEN left_at IS NOT NULL THEN NOW() ELSE joined_at END
         WHERE room_id = $1 AND user_id = $2`,
        [room.id, userId]
      );
    } else {
      const members = await client.query(
        'SELECT COUNT(*)::int AS n FROM room_members WHERE room_id = $1 AND left_at IS NULL',
        [room.id]
      );
      if (members.rows[0].n >= engine.MAX_PLAYERS) {
        const err = new Error('Room is full');
        err.status = 409;
        err.code = 'ROOM_FULL';
        throw err;
      }
      await client.query(
        `INSERT INTO room_members (room_id, user_id, role, connected)
         VALUES ($1, $2, 'member', TRUE)`,
        [room.id, userId]
      );
    }
    return room;
  });
}

async function leaveRoom(roomId, userId) {
  const { rowCount } = await query(
    `UPDATE room_members
     SET left_at = NOW(), connected = FALSE
     WHERE room_id = $1 AND user_id = $2 AND left_at IS NULL`,
    [roomId, userId]
  );
  if (!rowCount) {
    const err = new Error('Not a member of this room');
    err.status = 409;
    err.code = 'NOT_A_MEMBER';
    throw err;
  }
  return getRoomSnapshot(roomId);
}

async function setConnected(roomId, userId, connected) {
  await query(
    `UPDATE room_members SET connected = $3
     WHERE room_id = $1 AND user_id = $2 AND left_at IS NULL`,
    [roomId, userId, connected]
  );
}

async function shareDraft(roomId, userId, body) {
  const members = await activeMembers(roomId);
  if (!members.find((m) => m.user_id === userId)) {
    const err = new Error('Not a member of this room');
    err.status = 403;
    err.code = 'NOT_A_MEMBER';
    throw err;
  }
  const { rows } = await query(
    `INSERT INTO drafts (user_id, room_id, name, duration_ms, storage_url, effect)
     VALUES ($1, $2, $3, $4, $5, $6)
     RETURNING *`,
    [
      userId,
      roomId,
      body.name,
      body.durationMs || 0,
      body.storageUrl || null,
      body.effect || null
    ]
  );
  return rows[0];
}

async function runningSpin(roomId, client = null) {
  const q = client ? client.query.bind(client) : query;
  const { rows } = await q(
    `SELECT * FROM spins WHERE room_id = $1 AND status = 'running' LIMIT 1`,
    [roomId]
  );
  return rows[0] || null;
}

async function loadSpinGraph(spinId) {
  const spinRes = await query('SELECT * FROM spins WHERE id = $1', [spinId]);
  const spin = spinRes.rows[0];
  if (!spin) return null;
  const parts = await query('SELECT * FROM spin_participants WHERE spin_id = $1', [spinId]);
  const events = await query(
    'SELECT * FROM spin_events WHERE spin_id = $1 ORDER BY seq ASC',
    [spinId]
  );
  return { spin, participants: parts.rows, events: events.rows };
}

function dbSpinToEngine(row, participants) {
  return {
    status: row.status,
    startedBy: row.started_by,
    startedAt: row.started_at,
    nextEliminationAt: row.next_elimination_at,
    winnerId: row.winner_id,
    virtualPoints: row.virtual_points,
    abortReason: row.abort_reason,
    completedAt: row.completed_at,
    participants: participants.map((p) => ({
      userId: p.user_id,
      status: p.status,
      eliminationOrder: p.elimination_order,
      eliminatedAt: p.eliminated_at
    })),
    events: []
  };
}

async function persistEngineSpin(client, spinId, engineSpin, existingEventCount) {
  await client.query(
    `UPDATE spins SET
      status = $2,
      winner_id = $3,
      virtual_points = $4,
      completed_at = $5,
      next_elimination_at = $6,
      abort_reason = $7
     WHERE id = $1`,
    [
      spinId,
      engineSpin.status,
      engineSpin.winnerId,
      engineSpin.virtualPoints,
      engineSpin.completedAt || null,
      engineSpin.nextEliminationAt,
      engineSpin.abortReason
    ]
  );
  for (const p of engineSpin.participants) {
    await client.query(
      `UPDATE spin_participants
       SET status = $3, elimination_order = $4, eliminated_at = $5
       WHERE spin_id = $1 AND user_id = $2`,
      [spinId, p.userId, p.status, p.eliminationOrder, p.eliminatedAt]
    );
  }
  let seq = existingEventCount;
  const inserted = [];
  for (const event of engineSpin.events) {
    seq += 1;
    const res = await client.query(
      `INSERT INTO spin_events (spin_id, type, payload, seq)
       VALUES ($1, $2, $3, $4)
       ON CONFLICT (spin_id, seq) DO NOTHING
       RETURNING *`,
      [spinId, event.type, event.payload, seq]
    );
    if (res.rows[0]) inserted.push(res.rows[0]);
  }
  engineSpin.events = [];
  return inserted;
}

async function startSpin(roomId, userId) {
  return withTransaction(async (client) => {
    const roomRes = await client.query('SELECT * FROM rooms WHERE id = $1 FOR UPDATE', [roomId]);
    const room = roomRes.rows[0];
    if (!room) {
      const err = new Error('Room not found');
      err.status = 404;
      err.code = 'ROOM_NOT_FOUND';
      throw err;
    }
    if (room.owner_id !== userId) {
      const err = new Error('Only the room owner can start a spin');
      err.status = 403;
      err.code = 'NOT_OWNER';
      throw err;
    }
    const existing = await client.query(
      `SELECT * FROM spins WHERE room_id = $1 AND status = 'running' LIMIT 1`,
      [roomId]
    );
    if (existing.rowCount) {
      return { duplicate: true, spinId: existing.rows[0].id };
    }
    const members = await client.query(
      `SELECT user_id FROM room_members WHERE room_id = $1 AND left_at IS NULL`,
      [roomId]
    );
    const eligible = members.rows.map((r) => r.user_id);
    const now = new Date();
    const started = engine.startSpin({ eligibleUserIds: eligible, startedBy: userId, now });
    const spinIns = await client.query(
      `INSERT INTO spins (room_id, status, started_by, started_at, next_elimination_at, virtual_points)
       VALUES ($1, 'running', $2, $3, $4, 0)
       RETURNING *`,
      [roomId, userId, now, started.nextEliminationAt]
    );
    const spin = spinIns.rows[0];
    for (const p of started.participants) {
      await client.query(
        `INSERT INTO spin_participants (spin_id, user_id, status) VALUES ($1, $2, 'active')`,
        [spin.id, p.userId]
      );
    }
    await client.query(
      `INSERT INTO spin_events (spin_id, type, payload, seq)
       VALUES ($1, 'spin_started', $2, 1)`,
      [spin.id, { eligible, startedBy: userId }]
    );
    return { duplicate: false, spinId: spin.id };
  });
}

async function applyLeaveToSpin(roomId, userId) {
  return withTransaction(async (client) => {
    const { rows } = await client.query(
      `SELECT * FROM spins WHERE room_id = $1 AND status = 'running' FOR UPDATE`,
      [roomId]
    );
    if (!rows[0]) return null;
    const spinRow = rows[0];
    const parts = await client.query('SELECT * FROM spin_participants WHERE spin_id = $1', [spinRow.id]);
    const events = await client.query('SELECT COUNT(*)::int AS n FROM spin_events WHERE spin_id = $1', [
      spinRow.id
    ]);
    const engineSpin = dbSpinToEngine(spinRow, parts.rows);
    engine.removeParticipant(engineSpin, userId, new Date(), true);
    const inserted = await persistEngineSpin(client, spinRow.id, engineSpin, events.rows[0].n);
    return { spinId: spinRow.id, inserted, status: engineSpin.status };
  });
}

async function tickSpin(spinId, now = new Date(), rng = Math.random) {
  return withTransaction(async (client) => {
    const spinRes = await client.query('SELECT * FROM spins WHERE id = $1 FOR UPDATE', [spinId]);
    const spinRow = spinRes.rows[0];
    if (!spinRow || spinRow.status !== 'running') return null;
    const parts = await client.query('SELECT * FROM spin_participants WHERE spin_id = $1', [spinId]);
    const events = await client.query('SELECT COUNT(*)::int AS n FROM spin_events WHERE spin_id = $1', [
      spinId
    ]);
    const engineSpin = dbSpinToEngine(spinRow, parts.rows);
    engine.dueEliminations(engineSpin, now, rng);
    const inserted = await persistEngineSpin(client, spinId, engineSpin, events.rows[0].n);
    return { spinId, inserted, status: engineSpin.status, engineSpin };
  });
}

async function listRunningSpinIds() {
  const { rows } = await query(`SELECT id FROM spins WHERE status = 'running'`);
  return rows.map((r) => r.id);
}

async function getRoomSnapshot(roomId) {
  const room = await getRoomById(roomId);
  if (!room) return null;
  const members = await activeMembers(roomId);
  const drafts = await query(
    `SELECT d.*, u.display_name AS owner_name
     FROM drafts d JOIN users u ON u.id = d.user_id
     WHERE d.room_id = $1 ORDER BY d.created_at DESC`,
    [roomId]
  );
  const running = await runningSpin(roomId);
  let spin = null;
  if (running) {
    spin = await loadSpinGraph(running.id);
  } else {
    const last = await query(
      `SELECT * FROM spins WHERE room_id = $1 ORDER BY created_at DESC LIMIT 1`,
      [roomId]
    );
    if (last.rows[0]) spin = await loadSpinGraph(last.rows[0].id);
  }
  return {
    room: {
      id: room.id,
      code: room.code,
      ownerId: room.owner_id,
      status: room.status,
      createdAt: room.created_at
    },
    members: members.map((m) => ({
      userId: m.user_id,
      displayName: m.display_name,
      role: m.role,
      connected: m.connected,
      joinedAt: m.joined_at
    })),
    drafts: drafts.rows.map(mapDraft),
    spin: spin ? mapSpin(spin) : null
  };
}

function mapDraft(d) {
  return {
    id: d.id,
    userId: d.user_id,
    roomId: d.room_id,
    name: d.name,
    durationMs: d.duration_ms,
    storageUrl: d.storage_url,
    effect: d.effect,
    createdAt: d.created_at,
    ownerName: d.owner_name
  };
}

function mapSpin(graph) {
  const { spin, participants, events } = graph;
  return {
    id: spin.id,
    roomId: spin.room_id,
    status: spin.status,
    startedBy: spin.started_by,
    startedAt: spin.started_at,
    completedAt: spin.completed_at,
    winnerId: spin.winner_id,
    virtualPoints: spin.virtual_points,
    nextEliminationAt: spin.next_elimination_at,
    abortReason: spin.abort_reason,
    participants: participants.map((p) => ({
      userId: p.user_id,
      status: p.status,
      eliminationOrder: p.elimination_order,
      eliminatedAt: p.eliminated_at
    })),
    events: events.map((e) => ({
      seq: e.seq,
      type: e.type,
      payload: e.payload,
      createdAt: e.created_at
    }))
  };
}

async function getSpinById(id) {
  const graph = await loadSpinGraph(id);
  return graph ? mapSpin(graph) : null;
}

module.exports = {
  createUser,
  getUser,
  createRoom,
  getRoomById,
  getRoomByCode,
  joinRoom,
  leaveRoom,
  setConnected,
  shareDraft,
  startSpin,
  applyLeaveToSpin,
  tickSpin,
  listRunningSpinIds,
  getRoomSnapshot,
  getSpinById,
  runningSpin
};
