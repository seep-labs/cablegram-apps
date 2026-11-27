# Standalone TV Sign-In with Telegram & Bot Account Contract

Covers standalone TV sign-in (QR / phone / 2FA on the TV, verified via the Cablegram Telegram bot), nullable `users.email` for Telegram-only accounts, adding an email on the phone, and bot-confirmed account deletion.

## 1. Auth Methods (`GET /api/auth/methods`)

Unauthenticated. Returns available sign-in methods for this server.

When `TELEGRAM_BOT_USERNAME` and `TELEGRAM_BOT_TOKEN` and `TELEGRAM_BOT_WEBHOOK_SECRET` are configured on the control plane, the response includes:

```json
{
  "telegram_bot": {
    "username": "CablegramBot"
  }
}
```

Community servers (and control planes without bot configuration) omit `telegram_bot`. TVs MUST show **Sign in with Telegram** only when `telegram_bot` is present.

## 2. Start Standalone Telegram Sign-In (`POST /api/auth/device/telegram-start`)

Unauthenticated. Called by a TV that already started a pairing session (`POST /api/auth/device`).

Request body (strict: unknown fields return `400`):

```json
{
  "session_id": "uuid",
  "pairing_token": "string",
  "accepted": {
    "terms_version": "string",
    "privacy_version": "string"
  },
  "age_confirmed": true
}
```

Checks:
1. Pairing session exists, is `pending`, not expired, and `pairing_token` matches.
2. `age_confirmed === true` and `accepted.terms_version` / `accepted.privacy_version` match the server's current legal versions (`409 legal_version_stale` or `400` otherwise).
3. Rate limits: max 20/hour per IP, max 5 per pairing session.
4. Telegram client credentials (`TELEGRAM_API_ID`, `TELEGRAM_API_HASH`) are configured; otherwise `503 {"error": "telegram_client_unavailable"}`.

Response (`201 Created`):

```json
{
  "id": "uuid",
  "api_id": 123456,
  "api_hash": "...",
  "bot_username": "CablegramBot",
  "start_param": "43-char-base64url-token",
  "expires_at": "2026-11-23T15:00:00.000Z"
}
```

`start_param` is 32 random bytes encoded as base64url (43 chars, within Telegram's 64-char `[A-Za-z0-9_-]` limit), single-use, 10-minute TTL, stored as SHA-256 hash.

## 3. Poll Telegram Sign-In State (`GET /api/auth/device/telegram-start/:id`)

Authorization: `Pairing <pairing_token>` (same pairing token as `GET /api/auth/device/:id`).

Response (`200 OK`):

```json
{
  "state": "pending"
}
```

`state` is one of `pending`, `claimed`, `ambiguous`, `expired`.
- On `ambiguous`, the TV stops polling and shows: "This Telegram account is used by more than one Cablegram account. Pair the TV with your phone instead."
- On `claimed`, the TV continues polling `GET /api/auth/device/:id` for its JWT.

## 4. Bot Webhook (`POST /api/telegram/bot/webhook`)

Called only by Telegram.
- Requires `X-Telegram-Bot-Api-Secret-Token` matching `TELEGRAM_BOT_WEBHOOK_SECRET` (`401` otherwise).
- Always returns `200 OK` when the secret matches so Telegram does not retry.
- Handles private-chat `message` with `/start <param>`:
  1. Matches pending, unexpired `telegram_tv_sign_ins` row by `SHA-256(param)`.
  2. Resolves account in order:
     a. `user_identities` with `provider = 'telegram'`, `subject = String(from.id)` → that user's household.
     b. `telegram_links` with `telegram_user_id = from.id`:
        - 1 household → add `('telegram', String(from.id))` identity to its creator and use it.
        - >1 households → mark row `ambiguous`, reply "This Telegram account is used by more than one Cablegram account. Pair the TV with your phone instead."
     c. Otherwise → create new user (`email = null`, `name = from.first_name || "Cablegram"`), household, default profile, record legal acceptance, insert `user_identities ('telegram', String(from.id))`.
  3. Marks row `claimed` and claims the pairing session (`claimOwnSession`) in the same transaction, also creating/updating `telegram_bot_links` for the household.
  4. Sends reply: `"Your TV <name> is signed in to Cablegram."` (reply failure does not roll back sign-in).

## 5. Post-Pairing Identity Verification (`GET /api/me`)

`GET /api/me` includes:
- `user.email`: `string | null` (null for Telegram-only accounts until an email is added)
- `user.telegram_user_id`: `string | null` (the Telegram user id from `user_identities('telegram', ...)` or `telegram_links`, when present)
- `household.telegram_bot_linked`: `boolean`

After pairing via standalone Telegram sign-in, the TV calls `GET /api/me` and verifies `user.telegram_user_id` matches its own Telegram session's user id. If it does not match, the TV unpairs (`DELETE /api/devices/:id` or clears local session) and shows `"Signed in to a different Telegram account"`.

## 6. Adding an Email to a Telegram-Only Account (Phone)

- `POST /api/me/email/start` (Phone JWT): `{ "email": "user@example.com" }` → sends verification code to email (409 `email_in_use` if taken).
- `POST /api/me/email/verify` (Phone JWT): `{ "email": "user@example.com", "code": "123456" }` → sets `users.email` on the account.

## 7. Account Deletion for Telegram-Only Accounts

- **From TV**: `POST /api/account/delete/telegram-start` (TV JWT, only when user has no email/password or wants bot confirmation) → `{ "id": "uuid", "bot_username": "...", "start_param": "del_...", "expires_at": "..." }`.
  TV sends `/start del_<param>` via `sendBotStartMessage` / `messages.StartBot`, and polls `GET /api/account/delete/telegram-status/:id` (`{ "status": "pending" | "deleted" | "expired" }`).
- **From Web (`/delete-account`)**: `POST /api/account/delete/web-telegram-start` (unauthenticated) → `{ "id": "uuid", "deep_link": "https://t.me/<bot>?start=del_...", "expires_at": "..." }` + `GET /api/account/delete/telegram-status/:id` (with poll token). When the user taps Start in Telegram, the webhook verifies the Telegram user owns a Telegram-only (or matching) account and deletes it.
