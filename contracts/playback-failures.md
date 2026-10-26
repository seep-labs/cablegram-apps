# Playback failure reasons

Before starting the video player, TV clients (`getPlayback` / `resolvePlayback`) resolve the title's
available sources. If every source fails, resolution finishes before the player starts with `status = "denied"`
and a specific reason code.

## Reason codes

| Code | Meaning | User action |
|---|---|---|
| `telegram_not_connected` | No device in the household can read the Telegram library channel (the TV has no session, and the phone either is absent, unreachable, or reports `telegram_not_signed_in`). | Connect Telegram |
| `phone_lost_file` | The phone answered, but the file is not in its library (`not_in_library`). | Add the video again on your phone |
| `phone_offline` | The phone that stored this file is unreachable over LAN and relay. Stays in a waiting state (`status = "preparing"`, `prepare_stage = "phone_offline"`). If unresolvable on error, offers Retry. | Open Cablegram on your phone / Retry |
| `phone_replaced` | The serving phone record was revoked (`revoked_at != null`) following a newer install or device replacement. A replaced phone that was not revoked is not detected yet. | Add the video again on your phone |
| `cloud_disconnected` | Cloud storage is disconnected or the file is no longer present (server error `source_unavailable`). | Reconnect your storage on your phone |
| `cloud_credentials_rejected` | Cloud storage credentials were rejected by the provider (server error `credentials_rejected`). | Reconnect your storage on your phone |
| `cloud_limited` | Cloud storage provider is rate limiting downloads (server error code `429` / HTTP 429). | Try again in a little while |
| `unknown` | Unclassified failure, network error, or 5xx server error. | Back to library / Retry |

## Phone stream link and direct endpoints

The phone media server exposes `GET /links/<path>` over LAN (with `Authorization: Bearer <lan_capability>`).
The relay does not forward `Authorization`, so `GET /links/<path>` is purely a LAN endpoint.
Direct requests (`GET /media/<id>`, `GET /telegram/<uniqueId>`) are available on LAN and through the relay.

### Success

```http
HTTP/1.1 200 OK
Content-Type: application/json

{
  "link": "stream-link-id",
  "expires_in_seconds": 120
}
```

### Errors

- For `telegram/<uniqueId>` when the phone has no usable Telegram session:

```http
HTTP/1.1 503 Service Unavailable
Content-Type: application/json

{
  "error": "telegram_not_signed_in"
}
```

  "No usable session" means: Telegram was never linked on this phone, or the session is signed out, failed, or
  waiting for the user (phone number, code, password, approval). A session that is still **starting** (the phone was
  just woken) is not refused: the link is issued, and the stream waits up to 20 s for it to become ready. If it ends
  in one of the states above instead, the stream answers this 503; otherwise a file that can't be found is `404`.

- For `media/<id>` when `<id>` is not found in the phone library:

```http
HTTP/1.1 404 Not Found
Content-Type: application/json

{
  "error": "not_in_library"
}
```

### How the TV asks

Clients decide on the `error` code, never on the status alone. For a Telegram title the TV can't read itself:

- On the same Wi-Fi, the link request (`GET /links/telegram/<uniqueId>`) answers.
- Otherwise the TV requests `Range: bytes=0-0` of the relay URL before handing it to the player, with a short
  timeout. Only `503 telegram_not_signed_in` gives `telegram_not_connected`; a timeout (a phone still starting
  Telegram) or any other answer leaves the relay URL to the player as before.

The Samsung/LG app plays Telegram only in its direct-Telegram build. Without it, a Telegram title is
`status = "unsupported"` ("Telegram playback isn't available on this TV yet"), not `telegram_not_connected`.

## Source priority

When a title has multiple sources that all fail, `getPlayback` selects the single most actionable failure
reason according to the priority:

1. **Telegram** (`telegram_not_connected`)
2. **Cloud** (`cloud_credentials_rejected`, `cloud_disconnected`, `cloud_limited`)
3. **Phone** (`phone_lost_file`, `phone_replaced`, `phone_offline`)
4. **Unknown** (`unknown`)

## Cloud error code mapping

Cloud playback failures are mapped by the server's error code:
- `credentials_rejected` → `cloud_credentials_rejected`
- `source_unavailable` → `cloud_disconnected`
- `429` / `provider_rate_limited` / HTTP 429 → `cloud_limited`
- Network errors, 5xx, or unclassified server responses → `unknown`

## TV error screen presentation

When playback resolution fails with `status = "denied"` and a reason, the TV displays a clear message
and focused action button(s):

- `telegram_not_connected`: Single button "Connect Telegram" (opens Settings → Telegram).
- `phone_lost_file` / `phone_replaced`: Single button "Back to library".
- `cloud_credentials_rejected` / `cloud_disconnected`: Single button "Back to library".
- `cloud_limited` / `phone_offline` / `unknown`: "Back to library" with a "Retry" button next to it.
