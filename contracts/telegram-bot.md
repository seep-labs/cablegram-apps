# Cablegram Telegram Bot & TV Profile Lock Contract

Covers linking the Cablegram bot to a household, marking a TV as having the Telegram section active, and approving/denying TV profile unlock or lock-disable requests through Telegram.

## 1. Bot Link (`POST /api/telegram/bot/link-start`)

TV JWT (or Phone JWT). Starts a single-use bot link flow so the bot can message the household owner in Telegram.

Response (`201 Created`):

```json
{
  "id": "uuid",
  "bot_username": "CablegramBot",
  "start_param": "link_...",
  "expires_at": "2026-11-23T15:00:00.000Z"
}
```

The TV sends `/start <start_param>` to `@<bot_username>` in its own Telegram session (`sendBotStartMessage` on TDLib / `messages.StartBot` on GramJS).

When Telegram delivers `/start link_<param>` to `POST /api/telegram/bot/webhook`:
1. Verifies `from.id` matches the household's Telegram user (`telegram_links.telegram_user_id` or `user_identities('telegram', ...)`). On mismatch, refuses and replies in Telegram.
2. Upserts `telegram_bot_links (household_id, telegram_user_id, linked_at, blocked_at = null)`.
3. Replies once: `"Cablegram will ask you here before your account opens on <TV name>."`

## 2. TV Telegram Section Mark (`PUT /api/devices/me/telegram-section`)

TV JWT. Sets whether this TV has the Telegram discovery section enabled (which triggers bot profile lock for this TV).

Request body:

```json
{
  "on": true
}
```

Response (`200 OK`):

```json
{
  "device_id": "uuid",
  "telegram_section": true
}
```

Carries no chat ids, titles, or Telegram content. Clearing the mark (`on: false`, e.g., when disconnecting Telegram on the TV) falls back to the household's `profile_lock` setting.

## 3. Per-TV Profile Lock Override (`PUT /api/devices/me/profile-lock` & `PUT /api/devices/{deviceId}/profile-lock`)

- **TV (`PUT /api/devices/me/profile-lock`, TV JWT)**:
  Request body: `{ "on": false, "switch_request_id": "uuid" }` (to turn off, requires a fresh `approved` switch request for this TV approved via Telegram bot or phone; turning back `on: true` needs no approval).
- **Phone (`PUT /api/devices/{deviceId}/profile-lock`, Phone JWT)**:
  Request body: `{ "on": boolean }`.

Response (`200 OK`): `{ "device_id": "uuid", "profile_lock_disabled": boolean }`.

## 4. Profile Switch Requests with Bot (`POST /api/profiles/switch-requests`)

Existing route extended:
- A request starts as `pending` when the profile has no PIN AND (`household.profile_lock` is true OR the TV is shared OR (`device.telegram_section` is true AND `device.profile_lock_disabled` is false)).
- When `pending` and the household has an active (unblocked) `telegram_bot_links` row:
  - The server sends a Telegram bot message to `telegram_user_id`:
    `"Open <profile_name> on <tv_name>?"` with inline buttons **Allow** and **Don't allow** (`callback_data` signed with HMAC).
  - If Telegram returns `403` (bot blocked by user), the server marks `telegram_bot_links.blocked_at = now()` and the switch-request response / poll includes `"bot_blocked": true`.
  - When the user taps **Allow** or **Don't allow**, the webhook verifies the HMAC and `from.id`, approves/denies the switch request (notifying the polling TV), answers the callback query, and edits the Telegram message to `"Allowed"` or `"Not allowed"`.
  - On expiry (120 s), the message is edited to `"Expired"`.

`GET /api/me` includes `telegram_bot_linked: boolean`, `telegram_bot_blocked: boolean`, and on each device `telegram_section: boolean` and `profile_lock_disabled: boolean`.
