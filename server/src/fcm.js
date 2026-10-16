// Доставка команды на телефон B через FCM data-push. Опционально:
// если firebase-admin не установлен или нет GOOGLE_APPLICATION_CREDENTIALS —
// сервер работает в режиме "только очередь" (B забирает команды через GET /pending).
let messaging = null;
let mode = 'disabled';

export async function initFcm() {
  const cred = process.env.GOOGLE_APPLICATION_CREDENTIALS;
  if (!cred) { mode = 'pull-only'; return mode; }
  try {
    const admin = (await import('firebase-admin')).default;
    if (!admin.apps.length) admin.initializeApp({ credential: admin.credential.applicationDefault() });
    messaging = admin.messaging();
    mode = 'fcm+pull';
  } catch (e) {
    console.warn('[fcm] firebase-admin недоступен, режим pull-only:', e.message);
    mode = 'pull-only';
  }
  return mode;
}

export function fcmMode() { return mode; }

// Отправляем ТОЛЬКО идентификаторы: commandId, pairingId, barRef, nonce, expiresAt.
// Номер шлагбаума НЕ передаётся — он хранится на телефоне B и сверяется с локальным allowlist.
export async function pushCommand(fcmToken, cmd) {
  if (!messaging || !fcmToken) return { delivered: false, reason: mode === 'fcm+pull' ? 'no-token' : 'pull-only' };
  try {
    const id = await messaging.send({
      token: fcmToken,
      android: { priority: 'high' },
      data: {
        type: 'open_command',
        commandId: cmd.id,
        pairingId: cmd.pairing_id,
        barRef: cmd.bar_ref,
        barLabel: cmd.bar_label,
        nonce: cmd.nonce,
        expiresAt: String(cmd.expires_at),
      },
    });
    return { delivered: true, messageId: id };
  } catch (e) {
    return { delivered: false, reason: e.code || e.message };
  }
}
