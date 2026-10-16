// Сквозной тест backend БЕЗ устройств и FCM: проверяет очередь команд и конечный автомат.
// Запуск: DB_PATH=:memory: node src/selftest.js
import { openDb } from './db.js';

const db = openDb(':memory:');
let pass = 0, fail = 0;
const ok = (cond, msg) => { if (cond) { pass++; console.log('  ✓', msg); } else { fail++; console.log('  ✗ FAIL:', msg); } };
const expectThrow = (fn, code, msg) => { try { fn(); fail++; console.log('  ✗ FAIL (не бросил):', msg); } catch (e) { ok(e.status === code, `${msg} (→ ${e.status})`); } };

console.log('1) Регистрация устройств A и B');
const A = db.registerDevice({ role: 'controller', label: 'Телефон A' });
const B = db.registerDevice({ role: 'receiver', label: 'Телефон B' });
db.setFcmToken(B.deviceId, 'fake-fcm-token-B');
ok(A.authToken && B.authToken, 'выданы authToken');
const devA = db.deviceByToken(A.authToken), devB = db.deviceByToken(B.authToken);

console.log('2) Привязка через приглашение (B создаёт код, A гасит)');
const inv = db.createInvite(devB, { barLabel: 'Шлагбаум во дворе', barRef: 'bar-local-001' });
ok(/^\d{6}$/.test(inv.code), 'код приглашения 6 цифр');
const pairing = db.redeemInvite(devA, inv.code);
ok(pairing.pairingId, 'создана привязка');
expectThrow(() => db.redeemInvite(devA, inv.code), 409, 'повторное погашение кода отклонено');

console.log('3) Команда открытия: CREATED→AUTHORIZED→DISPATCHED');
const c1 = db.createCommand(devA, { pairingId: pairing.pairingId, commandId: 'cmd-1', nonce: 'n1' });
ok(c1.status === 'DISPATCHED', 'статус DISPATCHED');

console.log('4) Идемпотентность: тот же commandId не создаёт второй звонок');
const c1b = db.createCommand(devA, { pairingId: pairing.pairingId, commandId: 'cmd-1', nonce: 'n1' });
ok(c1b.id === c1.id && c1b.status === 'DISPATCHED', 'вернулась та же команда');

console.log('5) B забирает очередь и проходит автомат до COMPLETED');
const pend = db.pendingForReceiver(devB);
ok(pend.length === 1 && pend[0].id === 'cmd-1', 'B видит 1 команду в очереди');
db.ackCommand(devB, 'cmd-1', 'RECEIVED');
db.ackCommand(devB, 'cmd-1', 'CALL_REQUESTED');
const done = db.ackCommand(devB, 'cmd-1', 'COMPLETED');
ok(done.status === 'COMPLETED', 'дошли до COMPLETED');

console.log('6) Недопустимый переход отклоняется');
const c2 = db.createCommand(devA, { pairingId: pairing.pairingId, commandId: 'cmd-2', nonce: 'n2' });
expectThrow(() => db.ackCommand(devB, 'cmd-2', 'COMPLETED'), 409, 'DISPATCHED→COMPLETED запрещён');
db.ackCommand(devB, 'cmd-2', 'RECEIVED');
const blocked = db.ackCommand(devB, 'cmd-2', 'CALL_BLOCKED', 'android_blocked');
ok(blocked.status === 'CALL_BLOCKED' && blocked.error_code === 'android_blocked', 'ветка CALL_BLOCKED с кодом');

console.log('7) Повторная доставка завершённой команды игнорируется (anti-replay)');
const again = db.ackCommand(devB, 'cmd-1', 'CALL_REQUESTED');
ok(again.status === 'COMPLETED', 'завершённая команда не оживает');

console.log('8) Истечение срока (expiry)');
const c3 = db.createCommand(devA, { pairingId: pairing.pairingId, commandId: 'cmd-3', nonce: 'n3', ttlMs: -1 });
const c3r = db.commandById('cmd-3');
ok(c3r.status === 'EXPIRED', 'просроченная команда → EXPIRED');

console.log('9) Отзыв привязки отменяет активные команды и блокирует новые');
const c4 = db.createCommand(devA, { pairingId: pairing.pairingId, commandId: 'cmd-4', nonce: 'n4' });
const rev = db.revokePairing(devB, pairing.pairingId);
ok(rev.cancelled >= 1, 'активная команда отменена при отзыве');
ok(db.commandById('cmd-4').status === 'REVOKED', 'cmd-4 → REVOKED');
expectThrow(() => db.createCommand(devA, { pairingId: pairing.pairingId, commandId: 'cmd-5', nonce: 'n5' }), 403, 'команда по отозванной привязке отклонена');

console.log('10) Чужой доступ: другой контроллер не может слать по этой паре');
const A2 = db.registerDevice({ role: 'controller', label: 'Чужой A' });
const devA2 = db.deviceByToken(A2.authToken);
expectThrow(() => db.createCommand(devA2, { pairingId: pairing.pairingId, commandId: 'cmd-x', nonce: 'nx' }), 403, 'чужая привязка отклонена');

console.log('11) Роли: приёмник не может слать команды, контроллер не может приглашать');
expectThrow(() => db.createCommand(devB, { pairingId: pairing.pairingId, commandId: 'cmd-r', nonce: 'nr' }), 403, 'receiver не шлёт команды');
expectThrow(() => db.createInvite(devA, { barLabel: 'x', barRef: 'y' }), 403, 'controller не приглашает');

console.log('12) Ограничение частоты (rate limit)');
const B2 = db.registerDevice({ role: 'receiver' }); const devB2 = db.deviceByToken(B2.authToken);
const inv2 = db.createInvite(devB2, { barLabel: 'bar2', barRef: 'ref2' });
const p2 = db.redeemInvite(devA, inv2.code);
let rlHit = false;
for (let i = 0; i < 8; i++) { try { db.createCommand(devA, { pairingId: p2.pairingId, commandId: `rl-${i}`, nonce: `z${i}` }); } catch (e) { if (e.status === 429) rlHit = true; } }
ok(rlHit, 'после 6 команд/мин срабатывает 429');

console.log('13) Журнал аудита не содержит токенов');
const audit = db.auditTail(200);
const leak = audit.some((a) => (a.detail || '').includes(A.authToken) || (a.detail || '').includes('fake-fcm-token-B'));
ok(!leak, 'в audit нет authToken/fcmToken');

console.log(`\nИТОГО: ${pass} пройдено, ${fail} провалено`);
process.exit(fail === 0 ? 0 : 1);
