// Хранилище и конечный автомат операций. SQLite как источник истины (очередь команд).
// Секреты (authToken, fcmToken) хранятся, но НИКОГДА не попадают в журнал audit.
import Database from 'better-sqlite3';
import crypto from 'node:crypto';

export const COMMAND_STATES = [
  'CREATED', 'AUTHORIZED', 'DISPATCHED', 'RECEIVED', 'CALL_REQUESTED', 'COMPLETED',
  // терминальные ветки ошибок/таймаутов:
  'EXPIRED', 'REVOKED', 'DUPLICATE_IGNORED', 'NOT_ALLOWED', 'DEVICE_OFFLINE', 'CALL_BLOCKED', 'ERROR',
];
const TERMINAL = new Set(['COMPLETED', 'EXPIRED', 'REVOKED', 'DUPLICATE_IGNORED', 'NOT_ALLOWED', 'DEVICE_OFFLINE', 'CALL_BLOCKED', 'ERROR']);

// Разрешённые переходы, инициируемые приёмником (телефон B). Сервер делает CREATED→AUTHORIZED→DISPATCHED сам.
const RECEIVER_TRANSITIONS = {
  DISPATCHED:     new Set(['RECEIVED', 'CALL_BLOCKED', 'ERROR']),
  RECEIVED:       new Set(['CALL_REQUESTED', 'CALL_BLOCKED', 'ERROR']),
  CALL_REQUESTED: new Set(['COMPLETED', 'CALL_BLOCKED', 'ERROR']),
};

export const now = () => Date.now();
export const uuid = () => crypto.randomUUID();
const token = (n = 32) => crypto.randomBytes(n).toString('base64url');
const shortCode = () => (crypto.randomInt(0, 1e6)).toString().padStart(6, '0'); // 6-значный код приглашения

export function openDb(path = process.env.DB_PATH || './data.sqlite') {
  const db = new Database(path);
  db.pragma('journal_mode = WAL');
  db.exec(`
    CREATE TABLE IF NOT EXISTS devices (
      id TEXT PRIMARY KEY, role TEXT NOT NULL, label TEXT,
      auth_token TEXT NOT NULL UNIQUE, fcm_token TEXT,
      created_at INTEGER NOT NULL, last_seen_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS invites (
      code TEXT PRIMARY KEY, receiver_id TEXT NOT NULL,
      bar_label TEXT NOT NULL, bar_ref TEXT NOT NULL,
      created_at INTEGER NOT NULL, expires_at INTEGER NOT NULL, redeemed_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS pairings (
      id TEXT PRIMARY KEY, controller_id TEXT NOT NULL, receiver_id TEXT NOT NULL,
      bar_label TEXT NOT NULL, bar_ref TEXT NOT NULL,
      created_at INTEGER NOT NULL, revoked_at INTEGER
    );
    CREATE TABLE IF NOT EXISTS commands (
      id TEXT PRIMARY KEY, pairing_id TEXT NOT NULL,
      controller_id TEXT NOT NULL, receiver_id TEXT NOT NULL,
      bar_ref TEXT NOT NULL, bar_label TEXT NOT NULL, nonce TEXT NOT NULL,
      status TEXT NOT NULL, error_code TEXT,
      created_at INTEGER NOT NULL, authorized_at INTEGER, dispatched_at INTEGER,
      received_at INTEGER, call_requested_at INTEGER, completed_at INTEGER,
      expires_at INTEGER NOT NULL, last_update_at INTEGER NOT NULL
    );
    CREATE INDEX IF NOT EXISTS idx_cmd_receiver ON commands(receiver_id, status);
    CREATE TABLE IF NOT EXISTS audit (
      id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL,
      command_id TEXT, event TEXT NOT NULL, detail TEXT
    );
  `);

  const audit = (command_id, event, detail = '') =>
    db.prepare('INSERT INTO audit(ts,command_id,event,detail) VALUES(?,?,?,?)').run(now(), command_id, event, String(detail));

  return {
    raw: db,

    registerDevice({ role, label, fcmToken }) {
      if (role !== 'controller' && role !== 'receiver') throw httpErr(400, 'bad role');
      const id = uuid(), auth = token();
      db.prepare('INSERT INTO devices(id,role,label,auth_token,fcm_token,created_at,last_seen_at) VALUES(?,?,?,?,?,?,?)')
        .run(id, role, label || null, auth, fcmToken || null, now(), now());
      audit(null, 'device.register', `role=${role}`);
      return { deviceId: id, authToken: auth, role };
    },

    deviceByToken(auth) {
      if (!auth) return null;
      const d = db.prepare('SELECT * FROM devices WHERE auth_token=?').get(auth);
      if (d) db.prepare('UPDATE devices SET last_seen_at=? WHERE id=?').run(now(), d.id);
      return d || null;
    },
    deviceById: (id) => db.prepare('SELECT * FROM devices WHERE id=?').get(id) || null,

    setFcmToken(deviceId, fcmToken) {
      db.prepare('UPDATE devices SET fcm_token=?, last_seen_at=? WHERE id=?').run(fcmToken || null, now(), deviceId);
    },

    // --- Привязка: приёмник создаёт приглашение (код), контроллер его погашает ---
    createInvite(receiver, { barLabel, barRef, ttlMs = 15 * 60 * 1000 }) {
      if (receiver.role !== 'receiver') throw httpErr(403, 'only receiver can invite');
      if (!barLabel || !barRef) throw httpErr(400, 'barLabel and barRef required');
      let code;
      for (let i = 0; i < 5; i++) { code = shortCode(); if (!db.prepare('SELECT 1 FROM invites WHERE code=? AND redeemed_at IS NULL').get(code)) break; }
      db.prepare('INSERT INTO invites(code,receiver_id,bar_label,bar_ref,created_at,expires_at) VALUES(?,?,?,?,?,?)')
        .run(code, receiver.id, barLabel, barRef, now(), now() + ttlMs);
      audit(null, 'invite.create', `receiver=${receiver.id}`);
      return { code, barLabel, expiresAt: now() + ttlMs };
    },

    redeemInvite(controller, code) {
      if (controller.role !== 'controller') throw httpErr(403, 'only controller can redeem');
      const inv = db.prepare('SELECT * FROM invites WHERE code=?').get(code);
      if (!inv) throw httpErr(404, 'invite not found');
      if (inv.redeemed_at) throw httpErr(409, 'invite already used');
      if (inv.expires_at < now()) throw httpErr(410, 'invite expired');
      const id = uuid();
      const tx = db.transaction(() => {
        db.prepare('UPDATE invites SET redeemed_at=? WHERE code=?').run(now(), code);
        db.prepare('INSERT INTO pairings(id,controller_id,receiver_id,bar_label,bar_ref,created_at) VALUES(?,?,?,?,?,?)')
          .run(id, controller.id, inv.receiver_id, inv.bar_label, inv.bar_ref, now());
      });
      tx();
      audit(null, 'pairing.create', `pairing=${id}`);
      return { pairingId: id, barLabel: inv.bar_label, barRef: inv.bar_ref, receiverId: inv.receiver_id };
    },

    pairingsForDevice(dev) {
      return db.prepare('SELECT * FROM pairings WHERE (controller_id=? OR receiver_id=?) ORDER BY created_at DESC')
        .all(dev.id, dev.id);
    },
    pairingById: (id) => db.prepare('SELECT * FROM pairings WHERE id=?').get(id) || null,

    revokePairing(dev, pairingId) {
      const p = this_pairing(db, pairingId);
      if (p.controller_id !== dev.id && p.receiver_id !== dev.id) throw httpErr(403, 'not your pairing');
      db.prepare('UPDATE pairings SET revoked_at=? WHERE id=? AND revoked_at IS NULL').run(now(), pairingId);
      // отменяем ещё не выполненные команды этой пары
      const active = db.prepare(`SELECT id FROM commands WHERE pairing_id=? AND status NOT IN (${terminalPlaceholders()})`).all(pairingId, ...TERMINAL);
      for (const c of active) setStatus(db, audit, c.id, 'REVOKED', 'pairing revoked');
      audit(null, 'pairing.revoke', `pairing=${pairingId}`);
      return { revoked: true, cancelled: active.length };
    },

    // --- Команды ---
    createCommand(controller, { pairingId, commandId, nonce, ttlMs = 60 * 1000, rateMaxPerMin = 6 }) {
      if (controller.role !== 'controller') throw httpErr(403, 'only controller can send commands');
      const p = this_pairing(db, pairingId);
      if (p.controller_id !== controller.id) throw httpErr(403, 'not your pairing');
      if (p.revoked_at) throw httpErr(403, 'pairing revoked');

      // Идемпотентность: тот же commandId → вернуть существующую, не плодить звонки.
      if (commandId) {
        const existing = db.prepare('SELECT * FROM commands WHERE id=?').get(commandId);
        if (existing) { audit(commandId, 'command.duplicate', 'same id resubmitted'); return existing; }
      }
      // Ограничение частоты
      const windowStart = now() - 60 * 1000;
      const recent = db.prepare('SELECT COUNT(*) n FROM commands WHERE pairing_id=? AND created_at>?').get(pairingId, windowStart).n;
      if (recent >= rateMaxPerMin) throw httpErr(429, 'rate limit');

      const id = commandId || uuid();
      const nz = nonce || token(16);
      const t = now();
      const rec = {
        id, pairing_id: pairingId, controller_id: controller.id, receiver_id: p.receiver_id,
        bar_ref: p.bar_ref, bar_label: p.bar_label, nonce: nz,
        status: 'DISPATCHED', error_code: null,
        created_at: t, authorized_at: t, dispatched_at: t,
        received_at: null, call_requested_at: null, completed_at: null,
        expires_at: t + ttlMs, last_update_at: t,
      };
      db.prepare(`INSERT INTO commands(id,pairing_id,controller_id,receiver_id,bar_ref,bar_label,nonce,status,error_code,
        created_at,authorized_at,dispatched_at,received_at,call_requested_at,completed_at,expires_at,last_update_at)
        VALUES(@id,@pairing_id,@controller_id,@receiver_id,@bar_ref,@bar_label,@nonce,@status,@error_code,
        @created_at,@authorized_at,@dispatched_at,@received_at,@call_requested_at,@completed_at,@expires_at,@last_update_at)`).run(rec);
      audit(id, 'command.created', 'CREATED→AUTHORIZED→DISPATCHED');
      return rec;
    },

    commandById: (id) => expireIfNeeded(db, audit, db.prepare('SELECT * FROM commands WHERE id=?').get(id) || null),

    pendingForReceiver(receiver) {
      if (receiver.role !== 'receiver') throw httpErr(403, 'only receiver');
      const rows = db.prepare(`SELECT * FROM commands WHERE receiver_id=? AND status IN ('DISPATCHED','RECEIVED','CALL_REQUESTED') ORDER BY created_at ASC`).all(receiver.id);
      return rows.map((r) => expireIfNeeded(db, audit, r)).filter((r) => !TERMINAL.has(r.status));
    },

    ackCommand(receiver, id, nextStatus, errorCode) {
      const c = expireIfNeeded(db, audit, db.prepare('SELECT * FROM commands WHERE id=?').get(id));
      if (!c) throw httpErr(404, 'command not found');
      if (c.receiver_id !== receiver.id) throw httpErr(403, 'not your command');
      if (TERMINAL.has(c.status)) { audit(id, 'command.ack.ignored', `already ${c.status}`); return c; } // идемпотентность
      const allowed = RECEIVER_TRANSITIONS[c.status];
      if (!allowed || !allowed.has(nextStatus)) throw httpErr(409, `bad transition ${c.status}→${nextStatus}`);
      return setStatus(db, audit, id, nextStatus, errorCode);
    },

    auditTail: (n = 50) => db.prepare('SELECT ts,command_id,event,detail FROM audit ORDER BY id DESC LIMIT ?').all(n),
  };
}

function this_pairing(db, id) { const p = db.prepare('SELECT * FROM pairings WHERE id=?').get(id); if (!p) throw httpErr(404, 'pairing not found'); return p; }
function terminalPlaceholders() { return ['COMPLETED', 'EXPIRED', 'REVOKED', 'DUPLICATE_IGNORED', 'NOT_ALLOWED', 'DEVICE_OFFLINE', 'CALL_BLOCKED', 'ERROR'].map(() => '?').join(','); }

function setStatus(db, audit, id, status, errorCode = null) {
  const col = { RECEIVED: 'received_at', CALL_REQUESTED: 'call_requested_at', COMPLETED: 'completed_at' }[status];
  const t = now();
  if (col) db.prepare(`UPDATE commands SET status=?, ${col}=?, error_code=?, last_update_at=? WHERE id=?`).run(status, t, errorCode, t, id);
  else db.prepare('UPDATE commands SET status=?, error_code=?, last_update_at=? WHERE id=?').run(status, errorCode, t, id);
  audit(id, 'command.status', status + (errorCode ? ` (${errorCode})` : ''));
  return db.prepare('SELECT * FROM commands WHERE id=?').get(id);
}

function expireIfNeeded(db, audit, c) {
  if (!c) return c;
  if (!TERMINAL.has(c.status) && c.expires_at < now()) return setStatus(db, audit, c.id, 'EXPIRED', 'ttl');
  return c;
}

export function httpErr(status, msg) { const e = new Error(msg); e.status = status; return e; }
