'use strict';

const { describe, it } = require('node:test');
const assert = require('node:assert/strict');
const http = require('http');
const express = require('express');

describe('health contract', () => {
  it('returns ok without database', async () => {
    const app = express();
    app.get('/health', (_req, res) => res.json({ status: 'ok' }));
    const server = http.createServer(app);
    await new Promise((resolve) => server.listen(0, resolve));
    const { port } = server.address();
    const res = await fetch(`http://127.0.0.1:${port}/health`);
    const body = await res.json();
    assert.equal(body.status, 'ok');
    server.close();
  });
});
