// HTTP API минимального управляемого backend (Вариант C).
// Запуск: npm install && npm start   (PORT, DB_PATH, GOOGLE_APPLICATION_CREDENTIALS — через env)
import express from 'express';
import { openDb, httpErr } from './db.js';
import { initFcm, fcmMode, pushCommand } from './fcm.js';

const db = openDb();
const app = express();
app.use(express.json({ limit: '16kb' }));

// Аутентификация устройства по Bearer-токену. Токены в логи не пишем.
function auth(req, res, next) {
  const h = req.get('authorization') || '';
  const tok = h.startsWith('Bearer ') ? h.slice(7) : null;
  const dev = db.deviceByToken(tok);
  if (!dev) return res.status(401).json({ error: 'unauthorized' });
  req.device = dev;
  next();
}
const wrap = (fn) => async (req, res) => {
  try { await fn(req, res); }
  catch (e) { res.status(e.status || 500).json({ error: e.message || 'internal' }); }
};

app.get('/v1/health', (req, res) => res.json({ ok: true, fcm: fcmMode(), time: Date.now() }));

// Регистрация устройства -> выдаём authToken (роль: controller | receiver)
app.post('/v1/devices/register', wrap(async (req, res) => {
  const { role, label, fcmToken } = req.body || {};
  res.json(db.registerDevice({ role, label, fcmToken }));
}));

// Обновить FCM-токен / отметить "в сети"
app.post('/v1/devices/token', auth, wrap(async (req, res) => {
  db.setFcmToken(req.device.id, (req.body || {}).fcmToken);
  res.json({ ok: true });
}));

// Приёмник (B) создаёт приглашение (6-значный код) для привязки
app.post('/v1/pairings/invite', auth, wrap(async (req, res) => {
  const { barLabel, barRef, ttlMs } = req.body || {};
  res.json(db.createInvite(req.device, { barLabel, barRef, ttlMs }));
}));

// Контроллер (A) погашает код -> создаётся привязка
app.post('/v1/pairings/redeem', auth, wrap(async (req, res) => {
  const { code } = req.body || {};
  res.json(db.redeemInvite(req.device, String(code || '')));
}));

app.get('/v1/pairings', auth, wrap(async (req, res) => {
  res.json({ pairings: db.pairingsForDevice(req.device) });
}));

app.post('/v1/pairings/:id/revoke', auth, wrap(async (req, res) => {
  res.json(db.revokePairing(req.device, req.params.id));
}));

// Контроллер (A) отправляет команду "открыть". Сервер авторизует, ставит в очередь и шлёт push.
app.post('/v1/commands', auth, wrap(async (req, res) => {
  const { pairingId, commandId, nonce, ttlMs } = req.body || {};
  const cmd = db.createCommand(req.device, { pairingId, commandId, nonce, ttlMs });
  let delivery = { delivered: false, reason: 'queued' };
  if (cmd.status === 'DISPATCHED') {
    const receiver = db.deviceById(cmd.receiver_id);
    delivery = await pushCommand(receiver?.fcm_token, cmd);
  }
  res.json({ command: publicCmd(cmd), delivery });
}));

// B забирает незавершённые команды (fallback-доставка, если push не дошёл / B был офлайн).
// ВАЖНО: этот маршрут должен идти ДО '/v1/commands/:id', иначе 'pending' попадёт в :id.
app.get('/v1/commands/pending', auth, wrap(async (req, res) => {
  res.json({ commands: db.pendingForReceiver(req.device).map(publicCmd) });
}));

// A опрашивает статус команды
app.get('/v1/commands/:id', auth, wrap(async (req, res) => {
  const c = db.commandById(req.params.id);
  if (!c || (c.controller_id !== req.device.id && c.receiver_id !== req.device.id)) throw httpErr(404, 'not found');
  res.json({ command: publicCmd(c) });
}));

// B подтверждает стадии: RECEIVED / CALL_REQUESTED / COMPLETED / CALL_BLOCKED / ERROR
app.post('/v1/commands/:id/ack', auth, wrap(async (req, res) => {
  const { status, errorCode } = req.body || {};
  res.json({ command: publicCmd(db.ackCommand(req.device, req.params.id, status, errorCode)) });
}));

app.get('/v1/audit', auth, wrap(async (req, res) => res.json({ audit: db.auditTail(100) })));

// В ответах наружу не отдаём nonce (одноразовый секрет команды).
function publicCmd(c) {
  if (!c) return c;
  const { nonce, ...rest } = c;
  return rest;
}

const PORT = process.env.PORT || 8080;
if (process.env.NODE_ENV !== 'test') {
  initFcm().then((m) => {
    app.listen(PORT, () => console.log(`shlagbaum-relay-server на :${PORT} (fcm=${m})`));
  });
}

export { app, db };
