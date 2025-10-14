// syncService.js
require('dotenv').config();
const { createClient } = require('@supabase/supabase-js');
// In Node, provide a WebSocket implementation for supabase-js realtime
try {
  // prefer isomorphic-ws which works in Node and browsers
  if (typeof global.WebSocket === 'undefined') {
    global.WebSocket = require('isomorphic-ws');
  }
} catch (e) {
  console.warn('isomorphic-ws not installed; Supabase realtime may not work in Node. Run `npm install isomorphic-ws`');
}
const sql = require('mssql');
const express = require('express');
const cron = require('node-cron');

const SUPABASE_URL = process.env.SUPABASE_URL;
const SUPABASE_KEY = process.env.SUPABASE_KEY;

const poolConfig = {
  user: process.env.MSSQL_USER,
  password: process.env.MSSQL_PASSWORD,
  server: process.env.MSSQL_SERVER,
  database: process.env.MSSQL_DATABASE,
  port: Number(process.env.MSSQL_PORT || 1433),
  options: {
    encrypt: false,
    trustServerCertificate: true
  },
  pool: {
    max: 10,
    min: 0,
    idleTimeoutMillis: 30000
  }
};

const BATCH_SIZE = Number(process.env.BATCH_SIZE || 50);
const FLUSH_INTERVAL_MS = Number(process.env.FLUSH_INTERVAL_MS || 2000);

// We'll initialize Supabase client after we validate env vars
let supabase;

function validateConfig() {
  const missing = [];
  if (!SUPABASE_URL) missing.push('SUPABASE_URL');
  if (!SUPABASE_KEY) missing.push('SUPABASE_KEY');
  if (!process.env.MSSQL_USER) missing.push('MSSQL_USER');
  if (!process.env.MSSQL_PASSWORD) missing.push('MSSQL_PASSWORD');
  if (!process.env.MSSQL_SERVER) missing.push('MSSQL_SERVER');
  if (!process.env.MSSQL_DATABASE) missing.push('MSSQL_DATABASE');
  if (missing.length) {
    console.error('Missing required environment variables:', missing.join(', '));
    console.error('Create a `.env` file based on `.env.example` and set these values.');
    process.exit(1);
  }
}
let mssqlPool;

// module-scoped supabase channel for diagnostics
let supabaseChannel = null;

// Periodic channel state logger (helps debug stuck 'joining' state)
setInterval(() => {
  try {
    if (!supabaseChannel) return;
    const info = {
      topic: supabaseChannel.topic || null,
      state: supabaseChannel.state || supabaseChannel.status || null,
      joinedOnce: supabaseChannel.joinedOnce || false,
      pushBufferLength: (supabaseChannel.pushBuffer && supabaseChannel.pushBuffer.length) || 0
    };
    console.log('Channel debug:', info);
  } catch (e) {}
}, 5000);

// buffer for inserts
let insertBuffer = [];
// track last processed timestamp (ms) to support polling fallback
let lastProcessedTimestamp = Date.now();
// concurrency control for immediate upserts
let inFlightUpserts = 0;
let MAX_CONCURRENT_UPSERTS = Number(process.env.MAX_CONCURRENT_UPSERTS || 25);
const ORDERED_WRITES = (process.env.ORDERED_WRITES === '1');
if (ORDERED_WRITES) {
  // when ordered writes are requested, force single-threaded upserts
  MAX_CONCURRENT_UPSERTS = 1;
}

// connect to mssql
async function initMssql() {
  // try to connect and assign pool
  mssqlPool = await sql.connect(poolConfig);
  console.log('Connected to MSSQL');
}

// Ensure we have a working MSSQL connection; try reconnecting a few times on failure
async function ensureMssqlConnected(retries = 3, delayMs = 2000) {
  for (let attempt = 1; attempt <= retries; attempt++) {
    try {
      if (!mssqlPool) {
        await initMssql();
        return;
      }
      // quick health check
      await mssqlPool.request().query('SELECT 1');
      return;
    } catch (err) {
      console.error(`MSSQL health check attempt ${attempt} failed:`, err && err.message ? err.message : err);
      try { await new Promise(r => setTimeout(r, delayMs * attempt)); } catch (e) {}
      // attempt to reconnect on next loop
      try { await initMssql(); } catch (e) {
        console.error('initMssql reconnect attempt failed:', e && e.message ? e.message : e);
      }
    }
  }
  throw new Error('Unable to connect to MSSQL after multiple attempts');
}

// insert batch into SQL Server
async function flushBuffer() {
  if (insertBuffer.length === 0) return;
  await ensureMssqlConnected().catch(err => { console.error('flushBuffer: ensureMssqlConnected failed', err); throw err; });
  // take the current buffer snapshot
  const batch = insertBuffer.splice(0, insertBuffer.length);

  // sort by timestampMs (ascending) to apply records in chronological order
  batch.sort((a, b) => {
    const ta = (a.timestampMs !== undefined && a.timestampMs !== null) ? Number(a.timestampMs) : (a.timestamp instanceof Date ? a.timestamp.getTime() : 0);
    const tb = (b.timestampMs !== undefined && b.timestampMs !== null) ? Number(b.timestampMs) : (b.timestamp instanceof Date ? b.timestamp.getTime() : 0);
    return ta - tb;
  });

  // Safer: perform upsert per-record using MERGE to avoid PK conflicts
  let success = 0;
  for (const r of batch) {
    try {
      const request = mssqlPool.request()
        .input('id', sql.BigInt, r.id)
        .input('deviceId', sql.NVarChar(200), r.deviceId)
        .input('userName', sql.NVarChar(200), r.userName || null)
        .input('latitude', sql.Float, r.latitude)
        .input('longitude', sql.Float, r.longitude)
        .input('timestamp', sql.DateTime2, r.timestamp);

      const mergeSql = `
        MERGE dbo.locations AS target
        USING (SELECT @id AS id, @deviceId AS deviceId, @userName AS userName, @latitude AS latitude, @longitude AS longitude, @timestamp AS [timestamp]) AS src
        ON target.id = src.id
        -- only update when incoming timestamp is newer (or target timestamp is NULL)
        WHEN MATCHED AND (src.[timestamp] >= ISNULL(target.[timestamp], '1900-01-01')) THEN
          UPDATE SET deviceId = src.deviceId, userName = src.userName, latitude = src.latitude, longitude = src.longitude, [timestamp] = src.[timestamp]
        WHEN NOT MATCHED THEN
          INSERT (id, deviceId, userName, latitude, longitude, [timestamp])
          VALUES (src.id, src.deviceId, src.userName, src.latitude, src.longitude, src.[timestamp]);
      `;

      const result = await request.query(mergeSql);
      // rowsAffected is an array of counts for each statement executed; sum it
      const rowsAffected = (result && result.rowsAffected) ? result.rowsAffected.reduce((a, b) => a + b, 0) : 0;
      if (rowsAffected === 0) {
        console.log('Skipped flush upsert (incoming older) id=', r.id);
      } else {
        // Log the upserted record's fields
        console.log('Flushed record:', {
          id: r.id,
          latitude: r.latitude,
          longitude: r.longitude,
          timestamp: r.timestampRaw !== undefined ? r.timestampRaw : (r.timestamp instanceof Date ? r.timestamp.toISOString() : r.timestamp),
          deviceId: r.deviceId,
          userName: r.userName
        });
      }
      success++;
    } catch (err) {
      console.error('Error upserting record in flushBuffer id=', r.id, err.message || err);
      // push back failed record for retry later
      insertBuffer.unshift(r);
      // small backoff
      await new Promise(r => setTimeout(r, 500));
      break; // stop processing further so we don't spin on errors
    }
  }
  if (success > 0) console.log(`Flushed ${success} records to MSSQL`);
}

// schedule flush by interval
setInterval(() => {
  flushBuffer().catch(e => console.error(e));
}, FLUSH_INTERVAL_MS);

// Drain insertBuffer respecting concurrency limit; sort by timestampMs
async function processBuffer() {
  if (insertBuffer.length === 0) return;
  // sort buffer by timestamp
  insertBuffer.sort((a, b) => (a.timestampMs || 0) - (b.timestampMs || 0));
  while (insertBuffer.length > 0 && inFlightUpserts < MAX_CONCURRENT_UPSERTS) {
    const item = insertBuffer.shift();
    inFlightUpserts++;
    upsertToMssql(item, 'buffer')
      .catch(e => console.error('processBuffer upsert error id=', item && item.id, e))
      .finally(() => {
        inFlightUpserts--;
        // continue draining if possible
        if (insertBuffer.length > 0 && inFlightUpserts < MAX_CONCURRENT_UPSERTS) {
          processBuffer().catch(err => console.error('processBuffer error', err));
        }
      });
  }
}

// start supabase realtime subscription with simple retry/backoff
async function startSubscription() {
  console.log('Starting Supabase realtime subscription for public.locations');
  // will assign to module-scoped supabaseChannel for diagnostics

  const subscribeWithRetry = async (attempt = 1) => {
    try {
      const channel = supabase.channel('locations')
        .on('postgres_changes', { event: 'INSERT', schema: 'public', table: 'locations' }, payload => {
          try {
            // log minimal info for visibility
            const id = payload?.new?.id;
            const ts = payload?.new?.timestamp;
            console.log('Realtime INSERT payload id=', id, 'timestamp=', ts);

            const normalized = normalizeRecord(payload.new);
            if (!normalized) return;
            // update lastProcessedTimestamp early so polling fallback won't re-fetch older rows
            if (normalized.timestampMs) lastProcessedTimestamp = Math.max(lastProcessedTimestamp, normalized.timestampMs);

            // If we have capacity, upsert immediately; otherwise push to buffer
            // If ORDERED_WRITES is enabled, always buffer to preserve global timestamp order
            if (ORDERED_WRITES) {
              insertBuffer.push(normalized);
            } else if (inFlightUpserts < MAX_CONCURRENT_UPSERTS) {
              inFlightUpserts++;
              upsertToMssql(normalized, 'realtime')
                .catch(e => console.error('Realtime insert upsert error for id=', normalized.id, e))
                .finally(() => {
                  inFlightUpserts--;
                  // try draining buffer
                  processBuffer().catch(err => console.error('processBuffer error', err));
                });
            } else {
              insertBuffer.push(normalized);
            }
          } catch (err) {
            console.error('Error handling insert payload:', err);
          }
        })
        .on('postgres_changes', { event: 'UPDATE', schema: 'public', table: 'locations' }, payload => {
          const record = payload.new;
          console.log('Realtime UPDATE payload id=', record?.id);
          if (ORDERED_WRITES) insertBuffer.push(normalizeRecord(record));
          else upsertToMssql(record, 'realtime').catch(e => console.error('Realtime update upsert error:', e));
        })
        .on('postgres_changes', { event: 'DELETE', schema: 'public', table: 'locations' }, payload => {
          const record = payload.old;
          console.log('Realtime DELETE payload id=', record?.id);
          deleteFromMssql(record).catch(e => console.error(e));
        });

      // attach lifecycle events if available
      channel.on('broadcast', ({ event, payload }) => {
        console.log('Supabase broadcast event', event, payload && payload.type);
      });

      const { error } = await channel.subscribe();
      if (error) {
        throw error;
      }
      // Some supabase client versions do not return a `status` field from subscribe();
      // success is indicated by absence of `error`. Log the channel state if available for debugging.
      try {
        supabaseChannel = channel;
        console.log('Supabase realtime channel topic:', channel && (channel.topic || '(no topic)'));
        console.log('Supabase realtime channel state:', channel && (channel.state || channel.status || '(no state)'));
        // print the internal channel object shape for debugging (truncated)
        const c = Object.keys(channel || {}).filter(k => !k.startsWith('_')).slice(0, 20);
        console.log('Supabase channel keys:', c.join(', '));
      } catch (e) {}
      console.log('Subscribed to Supabase realtime on locations');
      return channel;
    } catch (err) {
      console.error(`Supabase subscribe attempt ${attempt} failed:`, err?.message || err);
      const backoff = Math.min(30000, 1000 * attempt);
      console.log(`Retrying subscribe in ${backoff}ms`);
      await new Promise(r => setTimeout(r, backoff));
      return subscribeWithRetry(attempt + 1);
    }
  };

  // start and don't block
  // launch initial subscribe and start a monitor that will re-subscribe if channel errors
  subscribeWithRetry().catch(e => console.error('subscribeWithRetry unhandled error', e));

  // monitor channel health and attempt self-heal when channel goes into errored/closed state
  const monitorInterval = 5000;
  let monitorId = setInterval(async () => {
    try {
      if (!supabaseChannel) return;
      const st = supabaseChannel.state || supabaseChannel.status || null;
      if (st === 'errored' || st === 'closed') {
        console.log('Detected channel state', st, '- attempting to resubscribe');
        try {
          if (typeof supabaseChannel.unsubscribe === 'function') {
            await supabaseChannel.unsubscribe().catch(() => {});
          }
        } catch (e) {}
        supabaseChannel = null;
        // call subscribeWithRetry to recreate and subscribe a new channel
        subscribeWithRetry().catch(err => console.error('resubscribe failed', err));
      }
    } catch (e) {
      console.error('channel monitor unexpected error', e && e.message ? e.message : e);
    }
  }, monitorInterval);
}

// upsert helper (using MERGE)
async function upsertToMssql(record, source = 'unknown') {
  try {
    // ensure connection
    await ensureMssqlConnected().catch(err => { console.error('upsertToMssql: ensureMssqlConnected failed', err); throw err; });
    const normalized = normalizeRecord(record);
    if (!normalized) throw new Error('Record normalization failed');

    const request = mssqlPool.request()
      .input('id', sql.BigInt, normalized.id)
      .input('deviceId', sql.NVarChar(200), normalized.deviceId)
      .input('userName', sql.NVarChar(200), normalized.userName || null)
      .input('latitude', sql.Float, normalized.latitude)
      .input('longitude', sql.Float, normalized.longitude)
      .input('timestamp', sql.DateTime2, normalized.timestamp);

    const mergeSql = `
      MERGE dbo.locations AS target
      USING (SELECT @id AS id, @deviceId AS deviceId, @userName AS userName, @latitude AS latitude, @longitude AS longitude, @timestamp AS [timestamp]) AS src
      ON target.id = src.id
      WHEN MATCHED AND (src.[timestamp] >= ISNULL(target.[timestamp], '1900-01-01')) THEN
        UPDATE SET deviceId = src.deviceId, userName = src.userName, latitude = src.latitude, longitude = src.longitude, [timestamp] = src.[timestamp]
      WHEN NOT MATCHED THEN
        INSERT (id, deviceId, userName, latitude, longitude, [timestamp])
        VALUES (src.id, src.deviceId, src.userName, src.latitude, src.longitude, src.[timestamp]);
    `;
    const result = await request.query(mergeSql);
    const rowsAffected = (result && result.rowsAffected) ? result.rowsAffected.reduce((a, b) => a + b, 0) : 0;
    if (rowsAffected === 0) {
      console.log('Skipped upsert (incoming older) id=', normalized.id, 'source=', source);
    } else {
      // Log all relevant fields (from Supabase) after successful upsert
      console.log('Upserted record:', {
        id: normalized.id,
        latitude: normalized.latitude,
        longitude: normalized.longitude,
        timestamp: normalized.timestampRaw !== undefined ? normalized.timestampRaw : (normalized.timestamp instanceof Date ? normalized.timestamp.toISOString() : normalized.timestamp),
        deviceId: normalized.deviceId,
        userName: normalized.userName,
        source
      });
    }
    // update last processed timestamp (ms)
    try { lastProcessedTimestamp = Math.max(lastProcessedTimestamp, Number(normalized.timestampMs || normalized.timestamp)); } catch(e){}
  } catch (err) {
    console.error('upsertToMssql error:', err);
    throw err;
  }
}

// Normalize and validate a Supabase record to match MSSQL schema
// Chuẩn hóa dữ liệu từ Supabase để đẩy lên SQL Server
function normalizeRecord(record) {
  if (!record) return null;

  const id = record.id || record.deviceId || record.device_id || null;
  const deviceId = record.deviceId || record.device_id || null;
  const userName = record.userName || record.username || record.user_name || null;
  const latitude = Number(record.latitude) || 0;
  const longitude = Number(record.longitude) || 0;

  const timestampRaw = record.timestamp;
  let timestampMs;

  if (timestampRaw === undefined || timestampRaw === null) {
    timestampMs = Date.now();
  } else if (typeof timestampRaw === 'number') {
    timestampMs = Number(timestampRaw);
    // Nếu là giây (ví dụ 1e9..1e10) thì nhân 1000 để chuyển sang mili-giây
    if (timestampMs > 0 && timestampMs < 1e12) {
      const before = timestampMs;
      timestampMs = timestampMs * 1000;
      console.log(`normalizeRecord: converted seconds→ms for id=${id} raw=${before} -> ${timestampMs}`);
    }
  } else if (typeof timestampRaw === 'string') {
    // Nếu là chuỗi ISO hoặc số dạng text
    const parsedNum = Number(timestampRaw);
    if (!isNaN(parsedNum)) {
      timestampMs = parsedNum;
      if (timestampMs > 0 && timestampMs < 1e12) timestampMs = timestampMs * 1000;
    } else {
      const parsed = Date.parse(timestampRaw);
      if (!isNaN(parsed)) {
        timestampMs = parsed;
      } else {
        console.warn(`normalizeRecord: cannot parse timestamp string=${timestampRaw}`);
        timestampMs = Date.now();
      }
    }
  } else {
    timestampMs = Date.now();
  }

  // ✅ Chuyển sang múi giờ Việt Nam (UTC+7)
  const timestamp = new Date(Number(timestampMs) + 7 * 60 * 60 * 1000);

  // Trả về dữ liệu chuẩn hóa
  return {
    id,
    deviceId,
    userName,
    latitude,
    longitude,
    timestamp,
    timestampMs,
    timestampRaw
  };
}


async function deleteFromMssql(record) {
  try {
    if (!record || !record.id) return;
    await ensureMssqlConnected().catch(err => { console.error('deleteFromMssql: ensureMssqlConnected failed', err); throw err; });
    await mssqlPool.request()
      .input('id', sql.BigInt, record.id)
      .query('DELETE FROM dbo.locations WHERE id = @id');
    console.log('Deleted record id=', record.id);
  } catch (err) {
    console.error('deleteFromMssql error:', err);
    throw err;
  }
}

// Express API for front-end to query history from MSSQL
const app = express();
app.use(express.json());

app.get('/api/locations', async (req, res) => {
  try {
    const { deviceId, start, end, limit = 1000, offset = 0 } = req.query;
    if (!deviceId || !start || !end) {
      return res.status(400).json({ error: 'deviceId, start, end are required' });
    }
    const request = mssqlPool.request()
      .input('deviceId', sql.NVarChar(200), deviceId)
      .input('start', sql.DateTime2, new Date(start))
      .input('end', sql.DateTime2, new Date(end))
      .input('limit', sql.Int, Number(limit))
      .input('offset', sql.Int, Number(offset));

    const query = `
      SELECT id, deviceId, userName, latitude, longitude, [timestamp]
      FROM dbo.locations
      WHERE deviceId = @deviceId AND [timestamp] BETWEEN @start AND @end
      ORDER BY [timestamp] ASC
      OFFSET @offset ROWS FETCH NEXT @limit ROWS ONLY;
    `;
    const result = await request.query(query);
    res.json(result.recordset);
  } catch (err) {
    console.error('/api/locations error', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

// Debug endpoint
app.get('/api/debug', async (req, res) => {
  try {
    const channelInfo = {};
    try {
      channelInfo.keys = supabaseChannel ? Object.keys(supabaseChannel).slice(0, 50) : null;
      channelInfo.state = supabaseChannel ? (supabaseChannel.state || supabaseChannel.status || null) : null;
    } catch (e) {}
    res.json({
      insertBufferLength: insertBuffer.length,
      inFlightUpserts,
      lastProcessedTimestamp,
      ORDERED_WRITES,
      MAX_CONCURRENT_UPSERTS,
      channelInfo
    });
  } catch (err) {
    res.status(500).json({ error: 'internal' });
  }
});

// Manual sync trigger - fetch recent rows from Supabase and upsert to MSSQL
app.post('/api/sync', async (req, res) => {
  try {
    // Support numeric milliseconds or ISO date string in req.body.since
    let since = req.body.since;
    if (!since) {
      since = Date.now() - 1000 * 60 * 60; // default last 1 hour (ms)
    } else {
      // if string and parseable as date, convert to ms
      if (typeof since === 'string') {
        const parsed = Date.parse(since);
        if (!Number.isNaN(parsed)) since = parsed;
        else if (/^\d+$/.test(since)) since = Number(since);
      }
      // if it's numeric-like string or number, ensure Number
      if (typeof since !== 'number') since = Number(since);
    }

    console.log('/api/sync: querying supabase with since=', since, 'type=', typeof since);
    const { data, error } = await supabase
      .from('locations')
      .select('*')
      .gte('timestamp', since)
      .order('timestamp', { ascending: true })
      .limit(10000);

    if (error) return res.status(500).json({ error });
    if (!data || data.length === 0) return res.json({ synced: 0 });

    let count = 0;
    for (const row of data) {
      const normalized = normalizeRecord(row);
      if (!normalized) continue;
        try {
        await upsertToMssql(normalized, 'manual');
        count++;
      } catch (e) {
        console.error('Manual sync upsert failed for id=', normalized.id, e.message || e);
      }
    }
  // attempt to drain any buffered realtime items
  processBuffer().catch(err => console.error('processBuffer after manual sync error', err));
    res.json({ synced: count });
  } catch (err) {
    console.error('/api/sync error', err);
    res.status(500).json({ error: 'Internal server error' });
  }
});

async function main() {
  try {
    // Validate required env and create clients
    validateConfig();
    supabase = createClient(SUPABASE_URL, SUPABASE_KEY);

    await initMssql();
    await startSubscription();

    const port = process.env.PORT || 3000;
    app.listen(port, () => {
      console.log(`API server running on port ${port}`);
    });
    // flushBuffer is already scheduled at module scope; processBuffer is module-scoped too

    scheduleSupabaseCleanup();
    // start a periodic poll fallback in case realtime drops
    startPollingFallback();

  } catch (err) {
    console.error('Fatal error', err);
    process.exit(1);
  }
}

main();

// Supabase cleanup cron (delete rows older than 2 days)
function scheduleSupabaseCleanup() {
  // Run daily at 02:00 server time
  cron.schedule('0 2 * * *', async () => {
    try {
      const twoDaysAgo = new Date(Date.now() - 2 * 24 * 60 * 60 * 1000).toISOString();
      const { error } = await supabase
        .from('locations')
        .delete()
        .lt('timestamp', twoDaysAgo);

      if (error) {
        console.error('Supabase cleanup error:', error);
      } else {
        console.log('Supabase cleanup done - deleted rows older than', twoDaysAgo);
      }
    } catch (err) {
      console.error('Supabase cleanup unexpected error:', err);
    }
  });
}

// Polling fallback: periodically fetch recent rows from Supabase and upsert them
function startPollingFallback() {
  const POLL_INTERVAL_MS = Number(process.env.POLL_INTERVAL_MS || 60 * 1000);
  console.log('Starting polling fallback every', POLL_INTERVAL_MS, 'ms. Initial lastProcessedTimestamp=', lastProcessedTimestamp);

  // run an initial poll immediately
  runPollingOnce().catch(e => console.error('Initial polling error', e));

  setInterval(() => {
    runPollingOnce().catch(e => console.error('Periodic polling error', e));
  }, POLL_INTERVAL_MS);
}

async function runPollingOnce(){
  try {
    const since = lastProcessedTimestamp - 5000; // small overlap window to be safe
  console.log('Polling fallback: fetching rows since', since);
  const { data, error } = await supabase.from('locations').select('*').gte('timestamp', since).order('timestamp', { ascending: true }).limit(1000);
    if (error) {
      console.error('Polling fallback supabase error:', error);
      return;
    }
    if (!data || data.length === 0) return;
    for (const row of data) {
  try {
  await upsertToMssql(row, 'polling');
        // after successful upsert, update lastProcessedTimestamp
        let ts = null;
        if (row && row.timestamp !== undefined && row.timestamp !== null) {
          ts = (typeof row.timestamp === 'number') ? Number(row.timestamp) : (Number(Date.parse(row.timestamp)) || null);
        }
        if (ts) lastProcessedTimestamp = Math.max(lastProcessedTimestamp, ts);
      } catch (e) {
        console.error('Polling fallback upsert error for id=', row && row.id, e);
      }
    }
    // after polling upserts, drain any buffered realtime items
    processBuffer().catch(err => console.error('processBuffer after polling error', err));
  } catch (err) {
    console.error('runPollingOnce unexpected error:', err);
  }
}
