# SupportMama protocol

Two HTTP endpoints, plus an optional third for push. This is everything a client needs; the Android, iOS and
web SDKs in this repository implement it, and a Flutter or React Native client can too.

## Identity

On first use the client makes:

- `id`: a random UUID (lowercase, with dashes), and
- `secret`: 32+ random bytes, base64url without padding (32–128 chars of `[A-Za-z0-9_-]`).

It keeps both for the life of the install and sends them on every call:

```
Authorization: Bearer <id>.<secret>
```

The server creates the conversation on the first message and stores
SHA-256(`secret`). A different secret for a known `id` gets `401`. Losing the
pair means starting a new conversation, which is fine.

## App key

SupportMama serves many apps from one endpoint, `https://supportmama.pages.dev`. Each call names the
app with the public key from the dashboard:

```
X-App-Key: smk_…
```

The key is
public: it ships inside the app and identifies it, nothing more. The same
`id` under two app keys is two separate conversations.

## `POST /v1/messages`: send

JSON:

```json
{ "text": "The export button does nothing", "name": "Priya", "context": "MyApp 2.3.1 · iOS 18.1 · iPhone 15 · en-IN" }
```

or, with a screenshot, `multipart/form-data` with the same text fields plus an
`image` file part (JPEG, PNG or WebP, ≤ 5 MB).

- `text`: up to 4000 characters. May be empty only when an image is attached.
- `name`: optional, ≤ 40 characters. Names the user's Telegram topic; send it
  each time, and a change renames the topic.
- `context`: optional, ≤ 300 characters. Posted into the topic as `ℹ️ …` when
  it differs from the last one, so send the same string until something in it
  changes.

`200`:

```json
{ "message": { "id": 41, "from": "user", "text": "The export button does nothing", "at": 1795363800000, "image": true }, "tag": "d6cd" }
```

`image` is present only when one was attached. `tag` is the short code shown
in the topic's name.

## `GET /v1/messages?after=<id>`: receive

Returns the caller's messages with `id > after`, oldest first, at most 200:

```json
{ "messages": [ { "id": 42, "from": "team", "text": "Fixed in 1.0.1!", "at": 1795363900000 } ], "tag": "d6cd", "push": false }
```

`from` is `"user"` or `"team"`. Keep a read cursor and move it to the
largest `id` a GET returned. Don't move it with the ids of your own sends:
a team reply stored just before your send would be skipped. Drop ids you
already have. A user who never wrote gets `{"messages": []}`.

Suggested polling: every 5 s while the chat is on screen; on returning to the
app; and in the background (every 15–30 min) for a few days after the last
message, with a notification for new `team` messages.

`push` is `true` while the server will push this install's team replies (see
below). The background check can stop then. When it turns `false` (the token
died, or the app's push keys stopped working), start it again. Foreground
polling stays as it is either way.

## `PUT /v1/push`: push instead of background polling (optional)

For apps whose maker added FCM or APNs keys (dashboard → **Push notifications**). Register the
install's push token after the first message has been sent:

```json
{ "kind": "fcm", "token": "dXk3…" }
{ "kind": "apns", "token": "a1b2…", "env": "production" }
```

- `kind`: `fcm` (Android, or iOS apps on Firebase Messaging) or `apns` (the raw
  iOS device token, hex).
- `env`: APNs only, `sandbox` (debug builds) or `production`. A wrong guess is
  fine: the server tries the other host once and remembers the right one, and
  keeps that correction when the same token is registered again.

`200 { "push": true }` means the server will push, so stop background polling.
`200 { "push": false, "reason": "not_configured" | "failing" }` means keep
polling. The token is stored anyway, so pushes start when the app maker adds
keys. Register again whenever the token changes; once a launch is fine.

`DELETE /v1/push` forgets the token (`200 { "push": false }`): for sign-out,
or when the app turns support notifications off.

A push carries `supportmama: "reply"`, the message `id`, a `title` and the
reply's `text` (or "New reply", if the app maker turned previews off). FCM
sends it as `data`, plus an alert for iOS. APNs sends it as an alert with
`{ "supportmama": { "id": 42 } }`. Fetch new messages when one arrives; the
push is a nudge, and `GET /v1/messages` is the record.

## Errors

JSON `{ "error": "<code>" }`:

| Status | Code | |
| --- | --- | --- |
| 400 | `empty`, `bad_form` | No text and no image; unreadable multipart. |
| 400 | `bad_push_kind`, `bad_push_token` | `PUT /v1/push`: `kind` isn't `fcm` or `apns`, or the token isn't shaped like one. |
| 404 | `no_conversation` | `PUT /v1/push` before the first message. Register after the first send. |
| 401 | `unauthorized` | Missing or malformed `Authorization`, or the wrong secret. |
| 401 | `unknown_app` | Hosted: no `X-App-Key`, or one that doesn't exist (any more). |
| 403 | `origin_not_allowed` | A browser call from an origin the app doesn't list. |
| 413 | `too_long`, `too_big` | Text over 4000 characters; image over 5 MB. |
| 415 | `not_an_image` | The `image` part isn't JPEG, PNG or WebP. |
| 429 | `slow_down` | More than 20 messages in 10 minutes, or too many new conversations from one IP. Keep the message and let the user retry later. |
| 503 | `not_connected` | The app has no team channel yet. The message was **not** stored; keep it and retry later. |
| 5xx | `server` | Includes Telegram or Slack being unreachable. The message was **not** stored, so retry it. |

## Browsers

List the origins
under the app's "Web origins" in the dashboard. `/v1/*` then answers CORS
preflights and adds `Access-Control-Allow-Origin` for those origins; a call
from any other origin gets `403 origin_not_allowed`. Keep `id` and `secret` in
`localStorage`. Send `X-App-Key` as a header, not in the URL.
