'use strict';

const express = require('express');
const cors = require('cors');
const pinoHttp = require('pino-http');
const store = require('./store');
const { pool } = require('./db');
const { SpinError } = require('./spinEngine');

function createApp({ io, logger, scheduleSpin }) {
  const app = express();
  app.use(cors({ origin: process.env.CORS_ORIGIN || true }));
  app.use(express.json({ limit: '1mb' }));
  app.use(
    pinoHttp({
      logger,
      customProps: (req) => ({ requestId: req.id, roomId: req.params && req.params.id })
    })
  );

  function userId(req) {
    return req.header('x-user-id');
  }

  function requireUser(req, res, next) {
    if (!userId(req)) {
      return res.status(401).json({ code: 'UNAUTHORIZED', message: 'X-User-Id header required' });
    }
    next();
  }

  app.get('/health', (_req, res) => res.json({ status: 'ok' }));

  app.get('/ready', async (_req, res) => {
    try {
      await pool.query('SELECT 1');
      res.json({ status: 'ready' });
    } catch {
      res.status(503).json({ status: 'not_ready' });
    }
  });

  app.post('/users', async (req, res, next) => {
    try {
      const displayName = (req.body && req.body.displayName || '').trim();
      if (!displayName) {
        return res.status(400).json({ code: 'VALIDATION', message: 'displayName is required' });
      }
      const user = await store.createUser(displayName);
      res.status(201).json({ id: user.id, displayName: user.display_name, createdAt: user.created_at });
    } catch (err) {
      next(err);
    }
  });

  app.post('/rooms', requireUser, async (req, res, next) => {
    try {
      const room = await store.createRoom(userId(req));
      const snapshot = await store.getRoomSnapshot(room.id);
      res.status(201).json(snapshot);
    } catch (err) {
      next(err);
    }
  });

  app.post('/rooms/:code/join', requireUser, async (req, res, next) => {
    try {
      const room = await store.joinRoom(req.params.code.toUpperCase(), userId(req));
      const snapshot = await store.getRoomSnapshot(room.id);
      io.to(`room:${room.id}`).emit('user_joined', {
        userId: userId(req),
        members: snapshot.members
      });
      io.to(`room:${room.id}`).emit('room_state', snapshot);
      res.json(snapshot);
    } catch (err) {
      next(err);
    }
  });

  app.post('/rooms/:id/leave', requireUser, async (req, res, next) => {
    try {
      const snapshotBefore = await store.getRoomSnapshot(req.params.id);
      if (!snapshotBefore) {
        return res.status(404).json({ code: 'ROOM_NOT_FOUND', message: 'Room not found' });
      }
      await store.leaveRoom(req.params.id, userId(req));
      const spinUpdate = await store.applyLeaveToSpin(req.params.id, userId(req));
      const snapshot = await store.getRoomSnapshot(req.params.id);
      io.to(`room:${req.params.id}`).emit('user_left', {
        userId: userId(req),
        members: snapshot.members
      });
      if (spinUpdate) {
        broadcastSpinEvents(io, req.params.id, spinUpdate.inserted, snapshot);
      }
      io.to(`room:${req.params.id}`).emit('room_state', snapshot);
      res.json(snapshot);
    } catch (err) {
      next(err);
    }
  });

  app.get('/rooms/:id', async (req, res, next) => {
    try {
      const snapshot = await store.getRoomSnapshot(req.params.id);
      if (!snapshot) return res.status(404).json({ code: 'ROOM_NOT_FOUND', message: 'Room not found' });
      res.json(snapshot);
    } catch (err) {
      next(err);
    }
  });

  app.get('/rooms/:id/state', async (req, res, next) => {
    try {
      const snapshot = await store.getRoomSnapshot(req.params.id);
      if (!snapshot) return res.status(404).json({ code: 'ROOM_NOT_FOUND', message: 'Room not found' });
      res.json(snapshot);
    } catch (err) {
      next(err);
    }
  });

  app.post('/rooms/:id/drafts', requireUser, async (req, res, next) => {
    try {
      if (!req.body || !req.body.name) {
        return res.status(400).json({ code: 'VALIDATION', message: 'name is required' });
      }
      const draft = await store.shareDraft(req.params.id, userId(req), req.body);
      const snapshot = await store.getRoomSnapshot(req.params.id);
      io.to(`room:${req.params.id}`).emit('draft_shared', {
        draft: snapshot.drafts.find((d) => d.id === draft.id)
      });
      res.status(201).json(draft);
    } catch (err) {
      next(err);
    }
  });

  app.post('/rooms/:id/spins', requireUser, async (req, res, next) => {
    try {
      const result = await store.startSpin(req.params.id, userId(req));
      const snapshot = await store.getRoomSnapshot(req.params.id);
      if (!result.duplicate) {
        io.to(`room:${req.params.id}`).emit('spin_started', {
          spin: snapshot.spin
        });
        scheduleSpin(result.spinId);
      }
      res.status(result.duplicate ? 200 : 201).json({
        duplicate: result.duplicate,
        spin: snapshot.spin
      });
    } catch (err) {
      next(err);
    }
  });

  app.get('/spins/:id', async (req, res, next) => {
    try {
      const spin = await store.getSpinById(req.params.id);
      if (!spin) return res.status(404).json({ code: 'SPIN_NOT_FOUND', message: 'Spin not found' });
      res.json(spin);
    } catch (err) {
      next(err);
    }
  });

  app.use((err, req, res, _next) => {
    if (err instanceof SpinError || err.code) {
      req.log.warn({ err }, err.message);
      return res.status(err.status || 400).json({ code: err.code, message: err.message });
    }
    req.log.error({ err }, 'unhandled');
    res.status(500).json({ code: 'INTERNAL', message: 'Internal server error' });
  });

  return app;
}

function broadcastSpinEvents(io, roomId, events, snapshot) {
  for (const event of events) {
    if (event.type === 'user_eliminated') {
      io.to(`room:${roomId}`).emit('user_eliminated', event.payload);
    } else if (event.type === 'winner_announced') {
      io.to(`room:${roomId}`).emit('winner_announced', event.payload);
    } else if (event.type === 'spin_started') {
      io.to(`room:${roomId}`).emit('spin_started', event.payload);
    } else if (event.type === 'spin_aborted') {
      io.to(`room:${roomId}`).emit('room_state', snapshot);
    }
  }
}

module.exports = { createApp, broadcastSpinEvents };
