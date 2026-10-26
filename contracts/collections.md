# Collections (version 1)

Collections are the account's own groups of titles (Kids, Favourites…). They belong to the account, not to a TV or a phone: every
phone signed in to the account sees the same collections, and the catalog tells TVs which collection each title is in. There is one
account per person, so nothing here is shared between people.

## Routes

All routes need a phone token (`scope=phone`); the catalog list also answers TVs.

| Route | Body | Answer |
| --- | --- | --- |
| `GET /api/catalog/collections` | – | `{ "collections": [{ "id", "name", "sort_order", "item_count" }] }`, by `sort_order` then name |
| `POST /api/catalog/collections` | `{ "name" }` | 201 `{ "id", "name", "item_count": 0 }`; 409 `collection_exists` |
| `PATCH /api/catalog/collections/:id` | `{ "name" }` | 200 `{ "id", "name", "sort_order", "item_count" }`; 404; 409 `collection_exists` |
| `DELETE /api/catalog/collections/:id` | – | 204; 404. Titles stay in the library. |
| `PUT /api/catalog/items/:itemId/collections/:id` | `{ "present": true \| false }` | 200 `{ "collection_id", "item_id", "present" }`; 404 |

`name` is trimmed, 1–100 characters, and unique per account **ignoring case** (`Kids` and `kids` clash). Renaming a collection to its own
name, or only changing its case, is fine. `PATCH` refuses other fields. The catalog list (`GET /api/catalog/items`) gives every title a
`collections: [{ "id", "name" }]` array, and accepts `?collection_id=` to list one collection.

`PATCH` is new in this version. A server without it answers 404 or 405; clients keep the local name and retry on a later sync.

## What the phones do

The phone keeps its collections and each title's collection ids in its library file as a cache, and works without a connection. Changes
are made locally first and reconciled with the server during the library sync, after the titles have been pushed:

- **Create:** a collection made on the phone is *unsynced* until the sync creates it. If the account already has a collection with the same
  name (ignoring case), the phone uses that one instead of creating a second: **merge by name**. Titles in the local collection move to the
  server's collection id.
- **Rename:** the new name is *dirty* until `PATCH` succeeds. A 409 (name taken) gives the server's name back; a 404 (deleted elsewhere)
  makes the collection again under the new name. A 405 (a server without the rename route) leaves the rename pending.
- **Delete:** the id of a synced collection is remembered until `DELETE` succeeds (a 404 counts as done). Titles keep everything else.
- **Membership:** editing a title's collections marks the title *pending*. The sync sends the difference with `PUT … present` for each
  collection that changed, then clears the mark. A title that is not pending takes the server's list, so changes made on another phone arrive.
  A title the server does not know yet stays pending until it has been pushed.
- **Collections made elsewhere** appear on the phone; collections deleted elsewhere disappear, unless the phone still has an unsent
  change for them (then they are made again in the same sync). Nothing the phone has not sent is ever dropped by a sync.

Each sync does these in order: deletes, renames, collections deleted elsewhere, uploads (merging by name), collections made elsewhere,
membership. A call that fails leaves its change pending; every step can be repeated, so a sync cut short is simply run again.

### Migration from phone-only collections

Earlier versions kept collections on the phone only, with ids made on the phone. After the update those collections are unsynced, and
their titles pending, so the first sync uploads them once (merging by name as above) and marks them synced. No separate switch is needed,
and running it again changes nothing.

## Not in this version

Reordering (`sort_order` is always 0), sharing a collection with another account, and smart collections (saved filters).
