# Telegram channels and Bot Hub

Follow Cablegram on Telegram:

- [Cablegram News](https://t.me/CablegramNews)
- [Cablegram Community](https://t.me/CablegramCommunity)
- [Cablegram Bot Hubs](https://t.me/CablegramBotHubs) — Discover useful Telegram bots that make managing, organizing, and enjoying your video library easier.

After connecting Telegram in the phone app, you can choose which channels to join. All three are
selected by default. Uncheck any you do not want, then tap **Save** to join only the selected channels.
Uncheck all and save to continue without joining any.

Next, Cablegram extracts bot recommendations from posts in Cablegram Bot Hubs and
asks about each bot individually. These are independent third-party bots and are not developed,
owned, or operated by Cablegram. Each addition requires reviewing the channel administrator access
and explicitly accepting the disclaimer below; its checkbox starts unchecked for every bot. You can
skip any or all bots. New bots receive posting permission and Telegram’s implied administrator access;
other optional permissions are off. Existing administrator permissions are preserved.

You can reopen the picker any time from **Import → Connected storage → Telegram → Check Bot Hub** while signed in
on this phone. Each check refreshes the recommendations and lets you reconsider previously skipped
bots. Each bot still needs a fresh disclaimer confirmation; existing bot administrator rights are kept.

Cablegram organizes the library channel, joined Cablegram channels, [@CablegramSupportBot](https://t.me/CablegramSupportBot),
and bots you added to the library in a **Cablegram** folder in Telegram. An existing folder is merged,
preserving its chats, pins, appearance, and filters. **Import → Connected storage → Telegram → Organize Telegram folder**
updates it again or retries after a folder-limit or connection error. If a bot has never been started,
Telegram requires you to open it and tap Start before it can appear in the folder; Cablegram reports
those bots and still organizes the other chats. Organizing does not join channels,
start bots, send messages, or add bots to the library.

> This recommendation is shared for informational purposes only. Using the bot is entirely optional and at the user's own discretion and responsibility.
>
> Cablegram is not responsible for the bot's operation, content, privacy practices, or any consequences arising from its use. Please review its permissions before adding it to your channel.

## Add videos from your other Telegram chats (Android phone)

**Import → Connected storage → Telegram → Add from your Telegram chats** lets you pick videos from your own
channels and groups and forward them into "Cablegram library".

- It never reads your chats without your permission. An "On this phone" notice asks before every scan, with no
  "don't ask again".
- A scan looks at the newest 100 messages in each of your channels and groups, on your phone, and counts the
  videos in them. It skips clips shorter than 10 minutes unless you change that under **Advanced settings**.
  Personal chats and Saved Messages stay off unless you turn them on there too. Secret chats and bots are never
  included.
- The picker loads messages only from the chats you tick, 50 at a time, and downloads only thumbnails. It never
  plays a video.
- Chat names, counts, messages and thumbnails stay on your phone. Only the videos you add are forwarded into your
  library channel, where they appear like any other video in it.
- Chats that don't allow saving are labelled and can't be added. Unticking a chat, turning a switch off or
  disconnecting deletes its cached thumbnails.
- The code is the `app.cablegram.discovery` package of the phone app. Its Telegram calls are pinned by
  `DiscoverySurfaceTest`, and the TV app has none of it.
