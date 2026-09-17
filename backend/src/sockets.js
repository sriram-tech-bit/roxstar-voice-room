'use strict';

const store = require('./store');

function attachSockets(io) {
  io.on('connection', (socket) => {
    socket.data.userId = null;
    socket.data.roomId = null;

    socket.on('join_room', async ({ roomId, userId } = {}, ack) => {
      try {
        if (!roomId || !userId) throw new Error('roomId and userId required');
        socket.data.userId = userId;
        socket.data.roomId = roomId;
        socket.join(`room:${roomId}`);
        await store.setConnected(roomId, userId, true);
        const snapshot = await store.getRoomSnapshot(roomId);
        socket.emit('room_state', snapshot);
        socket.to(`room:${roomId}`).emit('user_joined', snapshot);
        if (typeof ack === 'function') ack({ ok: true });
      } catch (err) {
        if (typeof ack === 'function') ack({ ok: false, message: err.message });
      }
    });

    socket.on('leave_room', async (ack) => {
      const { roomId, userId } = socket.data;
      if (roomId && userId) {
        socket.leave(`room:${roomId}`);
        await store.setConnected(roomId, userId, false);
        const snapshot = await store.getRoomSnapshot(roomId);
        socket.to(`room:${roomId}`).emit('user_left', snapshot);
      }
      if (typeof ack === 'function') ack({ ok: true });
    });

    socket.on('reconnect_state', async ({ roomId, userId } = {}, ack) => {
      try {
        socket.data.userId = userId;
        socket.data.roomId = roomId;
        socket.join(`room:${roomId}`);
        await store.setConnected(roomId, userId, true);
        const snapshot = await store.getRoomSnapshot(roomId);
        socket.emit('room_state', snapshot);
        if (typeof ack === 'function') ack({ ok: true, snapshot });
      } catch (err) {
        if (typeof ack === 'function') ack({ ok: false, message: err.message });
      }
    });

    socket.on('disconnect', async () => {
      const { roomId, userId } = socket.data;
      if (!roomId || !userId) return;
      const graceMs = Number(process.env.DISCONNECT_GRACE_MS || 15000);
      setTimeout(async () => {
        try {
          const snapshot = await store.getRoomSnapshot(roomId);
          const member = snapshot && snapshot.members.find((m) => m.userId === userId);
          if (!member) return;
          const sockets = await io.in(`room:${roomId}`).fetchSockets();
          const stillHere = sockets.some((s) => s.data.userId === userId);
          if (stillHere) return;
          await store.setConnected(roomId, userId, false);
        } catch {
          /* ignore */
        }
      }, graceMs);
    });
  });
}

module.exports = { attachSockets };


