'use strict';

const http = require('http');
const pino = require('pino');
const { Server } = require('socket.io');
const { createApp } = require('./app');
const { attachSockets } = require('./sockets');
const { createScheduler } = require('./scheduler');
const { migrate } = require('./migrate');
const { pool } = require('./db');

const logger = pino({ level: process.env.LOG_LEVEL || 'info' });

async function main() {
  if (!process.env.DATABASE_URL) {
    throw new Error('DATABASE_URL is required');
  }
  await migrate();

  let io;
  let scheduler;

  const app = createApp({
    io: {
      to(room) {
        return io.to(room);
      }
    },
    logger,
    scheduleSpin: (spinId) => scheduler.schedule(spinId)
  });

  const server = http.createServer(app);
  io = new Server(server, {
    cors: { origin: process.env.CORS_ORIGIN || true }
  });

  attachSockets(io);
  scheduler = createScheduler(io);
  await scheduler.recover();

  const port = Number(process.env.PORT || 8080);
  server.listen(port, () => {
    logger.info({ port }, 'roxstar backend listening');
  });

  const shutdown = async () => {
    server.close();
    await pool.end();
    process.exit(0);
  };
  process.on('SIGTERM', shutdown);
  process.on('SIGINT', shutdown);
}

main().catch((err) => {
  logger.error(err);
  process.exit(1);
});
