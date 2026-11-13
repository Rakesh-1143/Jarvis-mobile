'use strict';
// Saves encrypted Google tokens so a sign-in survives server restarts.
// Uses Render Key Value (REDIS_URL) when set, otherwise server memory only.
const memory = new Map();
let clientPromise = null;

function redisClient() {
  if (!process.env.REDIS_URL) return null;
  if (!clientPromise) {
    clientPromise = (async () => {
      const { createClient } = require('redis');
      const client = createClient({ url: process.env.REDIS_URL, socket: { connectTimeout: 5000, reconnectStrategy: (n) => (n > 3 ? new Error('Token storage unreachable') : n * 200) } });
      client.on('error', (e) => console.error('Token storage error:', e.message));
      client.on('end', () => { clientPromise = null; }); // reconnect on next use
      await client.connect();
      return client;
    })().catch((e) => { clientPromise = null; throw e; });
  }
  return clientPromise;
}

async function withRedis(fn, fallback) {
  try { const c = await redisClient(); if (c) return await fn(c); }
  catch (e) { console.error('Token storage unavailable, using memory:', e.message); }
  return fallback();
}

const PREFIX = 'jarvis:token:';
module.exports = {
  get: (k) => withRedis((c) => c.get(PREFIX + k), () => memory.get(k) ?? null),
  set: (k, v) => withRedis(async (c) => { await c.set(PREFIX + k, v); memory.set(k, v); }, () => { memory.set(k, v); }),
  delete: (k) => withRedis(async (c) => { await c.del(PREFIX + k); memory.delete(k); }, () => { memory.delete(k); })
};
