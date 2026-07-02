# ElegenCashBook — Implementation Specification

**Status:** Approved
**Version:** 1.1.0
**Date:** 2026-07-03
**Governs:** Full rebuild to native Kotlin, offline-first, with multi-user businesses, two-level RBAC, and book sharing.
**Changelog:**
- 1.2.0 — **Fully free / open-source backend.** Replaced Firebase (Cloud Functions need the paid Blaze plan) with **Supabase** (open-source; self-host $0 or free tier). RBAC now enforced by Postgres **Row-Level Security + SECURITY DEFINER functions** (free, server-authoritative). Auth = **email only** (free); **phone is an unverified identifier** (verified SMS costs money on every provider, so it is out of scope). No paid service anywhere in the stack.
- 1.1.0 — Business as first-class container; two-level RBAC (business role Owner/Admin/Viewer + per-book grant overrides); business-and-book sharing; user session (login/signout, switch business); identity invariant (email or phone, ≥1 required).
**Supersedes:** Ad-hoc Activity/XML implementation. Aligns with [constitution.md](constitution.md).

---

## 0. How to read this document

Every prior architecture finding has been **decided** (not left open). Decisions are marked **[DECIDED]** with the reason. This spec is the single source of truth for implementation. Where it conflicts with the earlier draft architecture, this spec wins.

---

## 1. Scope

A native Android cashbook that:

1. Records income/expense entries fully offline (Room = source of truth).
2. Backs up and syncs to the cloud when online.
3. Organizes books under **Businesses**. A **Business** is a first-class container that owns many books and has members. A **Book** may belong to a business, or be a standalone **individual/personal** book (no business).
4. Manages **users**: login / signout, edit profile with **email or phone (at least one required)**, and **switch between businesses**.
5. Lets an authorized member **share** either a **whole business** or a **single book** with **another registered user**, addressed by **email or phone number**, under a **two-level permission model** (§8):
   - **Owner** — all rights, including create/delete the business itself.
   - **Admin** — everything except create/delete the business; can add/delete/edit books; **customizable per-book permissions** and **restricted to only the books granted**.
   - **Viewer** — read-only, and **can be denied visibility of specific books**.
6. Never loses a transaction and never stores money as float/double.

---

## 2. Tech Stack (final)

| Layer | Choice |
|---|---|
| Language | Kotlin only (new code). Legacy Java: none exists — no exceptions needed. |
| UI | **XML Views + Material 3** (existing layouts in `app/src/main/res/layout` are reused). UI is a thin shell behind the ViewModel contract (§4 rule 6) — a later Jetpack Compose migration swaps only the `feature/*/ui` layer. |
| Architecture | Clean Architecture + MVVM + Repository pattern |
| DI | Hilt |
| Local DB | Room (**single source of truth**) |
| Concurrency | Coroutines + Flow (no LiveData) |
| Cloud backend | **Supabase** (open-source): Postgres + PostgREST + Realtime + Auth (as a **dumb remote** — see §6) |
| Cloud auth | **Supabase Auth (email only)** — magic-link or email+password. **Phone = unverified identifier**, not an auth channel |
| **Server logic** | **Postgres RLS + SECURITY DEFINER functions (RPC)** — sharing, user lookup, invariants run in the DB. Free. |
| Client SDK | `supabase-kt` (supabase-community, Apache-2.0, OSS) |
| Background | WorkManager |
| Security | Android Keystore + DataStore (+ Google Tink). **Not** EncryptedSharedPreferences. |
| Testing | JUnit5 + MockK + Turbine (domain/VM); Robolectric/instrumented (Room DAO); Supabase local (Docker) + pgTAP for RLS |

**[DECIDED] Backend = Supabase, not Firebase.** Firebase Cloud Functions require the paid **Blaze** plan; the free Spark plan cannot deploy them. Supabase is open-source, **self-hostable for $0** (Docker) or usable on its **free managed tier**, and its Postgres **Row-Level Security + SECURITY DEFINER functions** give real *server-authoritative* RBAC and invariants **without any paid service**. Everything else in the stack was already free/OSS.

**[DECIDED] No verified phone auth.** SMS OTP costs money on every provider (Firebase/Twilio/Supabase alike). Auth rides on **email** (free). Phone is stored as an **unverified identifier** used only for share-lookup. The "email or phone, ≥1" invariant still holds; auth just always uses email.

**Hosting:** reference deployment is **self-hosted Supabase via Docker Compose** (fully OSS, $0, full data ownership). The free managed tier is acceptable for early development (note: it pauses a project after ~1 week of inactivity). Because Room is the source of truth and the backend is a dumb sync target (§6), the backend is swappable — this choice touches only `data/remote` + SQL, not domain/UI.

---

## 3. Module / package structure (single Gradle module for v1)

```
com.elegen.elegencashbook/
  core/
    money/          Money value class + rounding policy
    security/       Keystore wrapper, encrypted DataStore
    sync/           Outbox engine, pull engine, conflict resolver
    common/         Result types, dispatchers, clock
    logging/        Logger abstraction
  domain/
    model/          PURE Kotlin models (no Room, no Supabase)
    repository/     Repository INTERFACES only
    usecase/        One job each
  data/
    local/
      dao/
      entity/       Room @Entity classes (never leave data/)
      mapper/       entity <-> domain mappers
    remote/
      supabase/     DTOs + PostgREST/Realtime access (never leave data/)
      rpc/          Supabase RPC (Postgres function) clients
    repository/     Repository IMPLEMENTATIONS
  core/
    auth/           Session, active-business context, identity invariant
    permission/     Capability enum + permission resolver (server-mirrored, read-only client cache)
  feature/
    auth/  business/  dashboard/  book/  transaction/  members/  sharing/  reports/  settings/
  worker/
    SyncPushWorker  SyncPullWorker  CleanupWorker
  di/
  MainActivity
```

**[DECIDED] Single module now.** Multi-module only if build time hurts. (Constitution §5 Simplicity.)

---

## 4. Clean Architecture boundary rules (non-negotiable)

These rules are what make the DB swappable and keep layers decoupled. CI/code review must reject violations.

1. `domain/` imports **nothing** from Room, Supabase, or Android framework.
2. Room `@Entity`/`@Dao` and Supabase DTOs **never** appear outside `data/`.
3. Domain model ≠ Room entity ≠ Supabase DTO. **Three classes, two mappers.** Boilerplate is accepted as the price of swappability.
4. Repository **interfaces** live in `domain/repository`; **implementations** in `data/repository`; Hilt binds them.
5. Repository methods return **domain models / Flows of domain models** only:
   ```kotlin
   // ✅ swap Room -> anything, nothing above changes
   fun observeTransactions(bookId: String): Flow<List<Transaction>>
   // ❌ leaks Room upward — forbidden
   fun observeTransactions(bookId: String): Flow<List<TransactionEntity>>
   ```
6. **UI ⇄ ViewModel contract (UI swappability).** Activities/Fragments/XML are dumb shells: they render a `StateFlow<UiState>` and emit `UiEvent`s — nothing else. No repository, DAO, Supabase, or domain-logic imports in any Activity/Fragment/adapter. ViewModels never import View/Activity types. Result: replacing XML with Compose (or any redesign) rewrites only `feature/*/ui`, zero backend change.

Result: swapping Room out = rewrite `data/local` only. UseCases, ViewModels, UI untouched.

---

## 5. Money handling

**[DECIDED] `Long` paisa, wrapped in a value class. Rounding = HALF_UP.**

```kotlin
@JvmInline
value class Money(val paisa: Long) {
    operator fun plus(o: Money) = Money(paisa + o.paisa)
    operator fun minus(o: Money) = Money(paisa - o.paisa)
    companion object { val ZERO = Money(0) }
}
```

Rules:
- Storage: `Long` paisa. Never `Double`/`Float`/`BigDecimal` in DB. (Constitution §4.)
- All arithmetic through `Money`. No raw `/100` scattered in UI.
- Display formatting + rounding done in one place (`core/money`), rounding mode **HALF_UP**.
- Aggregations use `Long` (max ~9.2×10¹⁸ paisa — safe). Sum overflow treated as a data-integrity error, logged, surfaced.
- Net Balance = Total CashIn − Total CashOut, deterministic. (Constitution §4.)

**Migration:** existing `Entry.amount: Double` → `Long paisa` (`round(amount * 100)`). Legacy `Double` code is deleted in the rebuild.

---

## 6. Sync architecture (the core of data integrity)

### 6.1 The one rule

```
Repository ALWAYS reads/writes Room. It NEVER touches Supabase directly.
Supabase is touched ONLY by the sync layer.
```

**[DECIDED] Killed the earlier "Repository decides local or remote."** It contradicts offline-first. Reads never hit the network.

### 6.2 Supabase = dumb remote

**[DECIDED] The `supabase-kt` client is used as a stateless remote only — no client-side cache/store.**
Room + Outbox is the single brain. All writes go out through the Outbox (§6.3); all reads come from Room. The remote never becomes a second write queue. (Same reasoning as the earlier "Firestore offline OFF" decision, now applied to Supabase.)

### 6.3 Write path (push) — Outbox pattern

```
User action
  -> UseCase
  -> Repository: single Room transaction {
        write/patch entity (set updatedAt, version++, syncState=PENDING)
        insert SyncQueue row (the outbox entry)
     } COMMIT
  -> return Success immediately (offline-safe)
  ...
SyncPushWorker (WorkManager, network-constrained):
  read PENDING outbox rows in sequence order
  push to Supabase (PostgREST upsert, or RPC for privileged ops); server sets updated_at = now()
  on ack -> mark row SUCCESS, delete row, entity.syncState=SYNCED
  on fail -> backoff + retry; after maxRetry -> DEAD_LETTER (surfaced, not silently dropped)
```

Ordinary entity writes (transactions, books you own) go via **PostgREST upsert** under RLS. Privileged operations (membership, grants, contact changes) go via **RPC** — see §8.

### 6.4 Read path (pull) — required by sharing & multi-device

**[DECIDED] Bidirectional sync.** The earlier draft was push-only; sharing means other users change books you can see, so we must pull.

```
SyncPullWorker (delta poll) / Supabase Realtime (foreground):
  fetch rows where updated_at > lastPulledAt  (RLS auto-filters to rows I may read)
  merge into Room via Conflict Resolver (§6.6)
  advance lastPulledAt cursor; Room Flow emits -> UI refreshes
```

**Realtime** (Postgres logical replication over websocket) streams changes while the app is foregrounded on the active business. **`SyncPullWorker`** does periodic **delta polls** — `updated_at > lastPulledAt` via PostgREST — for background/missed updates. RLS (§8.6) means the query returns only rows the user may read, so no `readerUids` denormalization is needed — Postgres enforces scope server-side on every read.

### 6.5 SyncQueue (outbox) schema

```
SyncQueue
  id              (PK)
  idempotencyKey  (dedupe replays — {entityId}:{version})
  sequence        (monotonic; enforces ordering, e.g. create-before-child, delete-before-recreate)
  entityType      BOOK | TRANSACTION | CATEGORY | MEMBERSHIP | ...
  entityId
  operation       CREATE | UPDATE | DELETE
  payloadVersion
  retryCount
  maxRetry        (default 5)
  lastAttempt
  status          PENDING | UPLOADING | SUCCESS | DEAD_LETTER
```

Backoff: exponential (WorkManager `BackoffPolicy.EXPONENTIAL`). `DEAD_LETTER` rows appear in a Settings → Sync screen for manual retry.

### 6.6 Conflict resolution

**[DECIDED] Last-Write-Wins on server-anchored timestamp; `deviceId` as deterministic tiebreaker.**

- Winner = higher `updatedAt`. `updatedAt` is set **server-side by a Postgres `BEFORE INSERT/UPDATE` trigger (`now()`)** on push (device clocks lie — do not trust them for ordering).
- Tie on timestamp → higher `deviceId` wins (deterministic, so all devices converge).
- Every record carries: `id (UUID)`, `version`, `updatedAt`, `deviceId`.
- Soft delete: `deletedAt` (tombstone), never hard-delete synced rows — a delete must propagate. `CleanupWorker` purges old tombstones after a grace period.

LWW is correct for single-user-multi-device and casual shared editing. Field-level merge/CRDT is explicitly **out of scope** until concurrent heavy co-editing is a real requirement.

---

## 7. Data model (Room entities — local source of truth)

All entities carry the sync envelope: `id (UUID String)`, `version: Long`, `updatedAt: Long (epoch ms)`, `deviceId: String`, `deletedAt: Long?`, `syncState: SyncState`.

### User (local cache of directory)
```
id (uid)  displayName  email(normalized)?  phone(E.164)?  photoUrl
```
**Identity invariant:** `email != null || phone != null` MUST always hold (§8.1). Both are unique across users.

### Business  (first-class container — owns books, has members)
```
id  name  ownerUid  currency  createdAt
+ sync envelope
```

### Book
```
id  businessId?  ownerUid  name  currency  createdAt
+ sync envelope
```
- `businessId != null` → the book belongs to that business; access derives from business membership + per-book grants.
- `businessId == null` → **individual / personal book**; access comes solely from `BookGrant` (owner has all).

### BusinessMembership  (a user's role in a business)
```
id  businessId  userUid  role(OWNER|ADMIN|VIEWER)
  invitedByUid  joinedAt  status(ACTIVE|REVOKED)
+ sync envelope
```
Role is the **coarse default** capability bundle (§8.3). Business always keeps ≥1 ACTIVE OWNER (§8.7).

### BookGrant  (per-book override on top of business role; also the ACL for personal books)
```
id  bookId  userUid
  access(ALLOW|DENY)                 # DENY = Viewer hidden / Admin excluded from this book
  permsOverride: Set<Permission>?    # null = inherit business-role default; non-null = custom per-book caps
  grantedByUid  createdAt
+ sync envelope
```
- Admin "customized per-book add/edit/delete" → `permsOverride`.
- Admin "only these books" → `access=ALLOW` rows are the allow-list; books without a grant are inaccessible when the business scopes that admin.
- Viewer "cannot see this book" → `access=DENY`.

**[DECIDED] Visibility is modeled as an allow-list internally**, not a deny-list. Postgres RLS is default-deny (a table with RLS on and no matching policy returns nothing), so a newly created book is invisible until access is granted — safe by construction. UI may present it as "hide these books" for Viewers while writing the equivalent grants.

### Transaction
```
id  bookId  type(CASH_IN|CASH_OUT)  amountPaisa: Long  categoryId?  description
  createdAt  createdByUid
+ sync envelope
```

### Category, Tag, Attachment, AuditLog, Settings
- `Category(id, bookId, name, type)`
- `Tag(id, bookId, name)` + `TransactionTag` join
- `Attachment(id, transactionId, localPath, remotePath, mime)`
- `AuditLog(id, bookId, actorUid, action, entityType, entityId, timestamp)` — append-only, powers "who changed what"
- `Settings` — app/user prefs

**Indexes** (Constitution §8): `Transaction(bookId, createdAt)`, `Transaction(bookId, type)`, `Book(businessId)`, `BusinessMembership(userUid)`, `BusinessMembership(businessId)`, `BookGrant(bookId)`, `BookGrant(userUid)`, `SyncQueue(status, sequence)`.

---

## 8. Users, businesses, and access control

This section drives identity, session, the two-level permission model, sharing, the Postgres schema, and Row-Level Security.

### 8.1 Identity & the email/phone invariant

- **Auth = email only** (Supabase Auth: magic-link or email+password). Free.
- **Phone is an unverified identifier**, not an auth channel (verified SMS costs money on every provider — out of scope). It is stored so a user can be *found* by phone when sharing, and edited by the user, but it is never used to log in.
- **Invariant (enforced everywhere): `email != null || phone != null` must always be true.** Since auth requires email, email is effectively always present; the invariant still guards the phone-edit path. Enforced by a Postgres **`CHECK (email is not null or phone is not null)`** constraint (authoritative) plus a client-side UX check.
- Both email and phone are **unique** across users — enforced by Postgres **`UNIQUE`** constraints (authoritative; far stronger than an app-maintained index).
- Normalization (single source, applied on write and lookup):
  - email → `trim().lowercase()`
  - phone → **E.164** (e.g. `+923001234567`)
- **User lookup for sharing (no enumeration leak).** A `lookup_user(email_or_phone text)` **RPC** (`SECURITY DEFINER`) does the normalization + exact-match lookup inside the DB and returns only `uid` or "not found". The `users` table is **not directly selectable** by other users (RLS denies it); only this function can resolve an address, and only for an *exact* match the caller already knows — so no listing/enumeration is possible. Rate-limited server-side.
- **Change flow (contact edit).** `update_contact(new_email?, new_phone?)` RPC (`SECURITY DEFINER`):
  1. Enforce the invariant + uniqueness on the resulting row (DB constraints do this authoritatively; the function returns a clean error).
  2. Email change goes through Supabase Auth's own verified email-change flow. Phone change is applied directly (unverified identifier).
  3. Write an `audit_log` row (channel changed, not the value).

### 8.2 User session

- **Login / Signout.** Login via Supabase Auth (email). Signout clears Keystore/DataStore session material (§9) incl. the Supabase refresh token, the cached active-business id, and stops all Realtime subscriptions. Local Room data of the signed-out user is retained only if a re-login is expected; otherwise wiped on explicit "sign out & remove data".
- **Active-business context.** The currently selected `businessId` is persisted in DataStore. Dashboard, book lists, reports, and pull queries are **scoped to the active business** (plus the user's personal/individual books).
- **Switch business.** `SwitchBusiness(businessId)` sets the active context (must be an ACTIVE member). `ListMyBusinesses` returns all businesses where the user is an ACTIVE member. A user with zero businesses still has their personal books.

### 8.3 Permission model (two-level, capability-based)

**[DECIDED] Authorization unit is a capability, not a role.** Roles are just named default bundles of capabilities; per-book grants override them. Enforcement is **server-authoritative** (Postgres RLS + RPC, §8.6); the client caches the resolved permissions in Room **read-only, for UX gating only** — never trusts them for security.

Capabilities:
```
Permission = { BOOK_VIEW, TX_ADD, TX_EDIT, TX_DELETE,
               BOOK_ADD, BOOK_EDIT, BOOK_DELETE,
               MEMBER_MANAGE, BUSINESS_EDIT, BUSINESS_DELETE }
```

Business-role → default capability bundle:

| Business role | Default capabilities |
|---|---|
| **OWNER** | **all**, including `BUSINESS_EDIT`, `BUSINESS_DELETE`, `BOOK_ADD/DELETE`, `MEMBER_MANAGE` |
| **ADMIN** | all **except** `BUSINESS_EDIT` and `BUSINESS_DELETE`. Can `BOOK_ADD/EDIT/DELETE`, `TX_*`, `MEMBER_MANAGE`. Scoped to granted books and customizable per book (below) |
| **VIEWER** | `BOOK_VIEW` only; excluded from `access=DENY` books |

Effective-permission resolution (implemented in `core/permission` for UX, and mirrored authoritatively by a Postgres `effective_perms(uid, book_id)` SQL function used inside RLS policies):
```
effective(user, book):
  if book.businessId == null:                       # personal / individual book
      grant = BookGrant(user, book)
      return grant?.permsOverride
             ?? (book.ownerUid == user ? ALL : DENY)
  else:
      m = BusinessMembership(user, book.businessId)
      if m == null || m.status != ACTIVE: return DENY
      grant = BookGrant(user, book)
      if grant?.access == DENY: return DENY          # Viewer hidden / Admin excluded
      base = roleDefaults(m.role)
      if adminIsScoped(m) && grant?.access != ALLOW: return DENY   # Admin "only these books"
      return grant?.permsOverride ?? base            # per-book custom caps override role default
```

Every mutating operation checks the required capability against `effective(user, book)` (or business-level caps for business ops). VIEWER writes, and any capability not in the effective set, are denied.

### 8.4 Sharing (business or single book)

**[DECIDED] Recipient must be an existing registered user** (per requirement). Invites resolve at send time; no invitations to strangers. All membership/grant writes go through **Postgres `SECURITY DEFINER` RPC functions** (server-authoritative); RLS makes the `business_members` and `book_grants` tables **not directly writable** by clients — the only write path is these functions.

RPC functions (each re-checks caller capability via `auth.uid()`, enforces invariants §8.7, and writes an `audit_log` row):
- `invite_to_business(business_id, email_or_phone, role, book_scope?, per_book_perms?)`
  1. Caller must hold `MEMBER_MANAGE` on the business (OWNER, or ADMIN where permitted).
  2. Resolve target via `lookup_user` (§8.1). **Not found → `USER_NOT_REGISTERED`** ("This person must install and register first").
  3. Upsert `business_members(business_id, user_uid, role, status=ACTIVE)`.
  4. Optional `book_scope` (allow-list of book ids) and `per_book_perms` → write `book_grants` rows (ALLOW + `perms_override`). This is how an **Admin is restricted to specific books with custom add/edit/delete**, and how a **Viewer is denied specific books**.
- `share_book(book_id, email_or_phone, perms_or_role)` — share a **single** book (a personal book, or a one-off cross-cutting grant). Same lookup rule; writes a `book_grants(access=ALLOW, perms_override)` row.
- `update_member_role(business_id, target_uid, role)`
- `set_book_grant(book_id, target_uid, access, perms_override?)` — adjust per-book access/permissions (Admin scoping, Viewer hide).
- `revoke_member(business_id, target_uid)` / `revoke_book_grant(book_id, target_uid)`

### 8.5 Postgres schema (Supabase)

**[DECIDED] Flat, top-level tables.** A book owned under a single user can't be shared. Every table has RLS enabled. Column names are `snake_case` (Postgres); domain models stay `camelCase` (mappers bridge, §4).

```
users(uid PK = auth.uid, display_name, email unique, phone unique, photo_url,
      CHECK (email is not null or phone is not null))          -- RLS: self-select; others via lookup_user() only

businesses(id PK, name, owner_uid, currency, + sync envelope)  -- RLS: select if member
business_members(id PK, business_id FK, user_uid, role, status,
                 invited_by_uid, joined_at, + sync envelope,
                 UNIQUE(business_id, user_uid))                -- RLS: select if co-member; write: RPC only

books(id PK, business_id FK NULL, owner_uid, name, currency, + sync envelope)
                                                               -- RLS: select/write via effective_perms()
book_grants(id PK, book_id FK, user_uid, access, perms_override jsonb,
            granted_by_uid, created_at, + sync envelope,
            UNIQUE(book_id, user_uid))                         -- RLS: select if can-read book; write: RPC only

transactions(id PK, book_id FK, type, amount_paisa bigint, category_id FK NULL,
             description, created_at, created_by_uid, + sync envelope)
categories(id PK, book_id FK, name, type, + sync envelope)
tags(id PK, book_id FK, name, + sync envelope)   transaction_tags(transaction_id, tag_id)
attachments(id PK, transaction_id FK, storage_path, mime, + sync envelope)  -- Supabase Storage (free/OSS)
audit_log(id PK, business_id FK NULL, book_id FK NULL, actor_uid, action,
          entity_type, entity_id, at)            -- append-only; insert via RPC, no update/delete
```

No `reader_uids` denormalization is needed — RLS evaluates access per row on every read/write, server-side (§8.6, §8.8).

### 8.6 Row-Level Security (Supabase) — intent

Two authoritative SQL helpers (`STABLE`, used by policies):
```sql
-- business role of the current user, or NULL
create function business_role(bid uuid) returns text language sql stable as $$
  select role from business_members
  where business_id = bid and user_uid = auth.uid() and status = 'ACTIVE'
$$;

-- effective capability set for current user on a book (mirrors §8.3)
create function effective_perms(book uuid) returns text[] language sql stable as $$ ... $$;
```

Policies (coarse gate in RLS; fine-grained capability check inside RPC + `effective_perms`):
```sql
-- users: self only; discovery is via lookup_user() SECURITY DEFINER, never a direct select
alter table users enable row level security;
create policy sel_self on users for select using (uid = auth.uid());
create policy upd_self on users for update using (uid = auth.uid());   -- contact via update_contact() RPC

-- businesses / members: readable by members; writes only through RPC (definer)
create policy sel_biz     on businesses       for select using (business_role(id) is not null);
create policy sel_members on business_members for select using (business_role(business_id) is not null);
-- (no INSERT/UPDATE/DELETE policies for clients on business_members / book_grants → RPC-only)

-- books & children: gated by effective_perms()
create policy sel_book on books for select using ('BOOK_VIEW' = any(effective_perms(id)));
create policy ins_tx  on transactions for insert with check ('TX_ADD'    = any(effective_perms(book_id)));
create policy upd_tx  on transactions for update using      ('TX_EDIT'   = any(effective_perms(book_id)));
create policy del_tx  on transactions for delete using      ('TX_DELETE' = any(effective_perms(book_id)));
create policy sel_tx  on transactions for select using      ('BOOK_VIEW' = any(effective_perms(book_id)));

-- audit_log: readable by book/business members; append only via RPC
alter table audit_log enable row level security;   -- no client insert/update/delete policies
```

Because `effective_perms` runs **inside** the policy, VIEWER writes, Admin excluded-books (`DENY`), Admin scoping (allow-list), and per-book custom capabilities are all enforced **at the row level in the database** — not merely in app code. Membership, grants, the users table, and the audit log are **never client-writable**. Constitution §6 satisfied. This is strictly stronger than the earlier Firestore-rules design (real SQL, real constraints, real transactions).

### 8.7 Invariants

Enforced authoritatively in Postgres (triggers/constraints), not just app code:
- A business always has **≥1 ACTIVE OWNER** — a `BEFORE UPDATE/DELETE` trigger on `business_members` rejects removing/demoting the last owner.
- Deleting a business (`BUSINESS_DELETE`, OWNER only) requires explicit UI confirmation when it contains books with transactions; child books/transactions are soft-deleted (tombstones) via a Postgres function within one transaction.
- A user's identity keeps **≥1 contact channel** — the `CHECK` constraint (§8.1).
- Admin scoping: an admin created with a `book_scope` allow-list can only access books with an `access=ALLOW` grant, even as ADMIN — enforced by `effective_perms` (§8.6).

### 8.8 Pull for shared data

The client simply pulls (delta by `updated_at`, §6.4):
- `businesses` (`ListMyBusinesses`), and
- `books` + their `transactions/categories/tags` for `business_id == activeBusinessId` **or** `business_id is null` (personal).

**RLS auto-filters every row to what the caller may read** (`effective_perms`), so the client sends a plain `updated_at > cursor` query and the database returns only the permitted rows — no `reader_uids` array to maintain, no client-side access logic. Remote changes merge via LWW (§6.6).

---

## 9. Security

- **[DECIDED] Drop EncryptedSharedPreferences (deprecated Jetpack Security).**
  Use **Android Keystore** (AES key, non-exportable) + **DataStore** with values encrypted via **Google Tink**. Store only: the Supabase session/refresh token, device id, sync cursors. No passwords (Supabase Auth owns those).
- Room DB is **not** whole-DB encrypted for v1 (threat model = casual device access; Postgres RLS protects the cloud). SQLCipher noted as the upgrade path if a stronger threat model is adopted.
- **Self-hosted Supabase:** keep the `service_role` key server-side only — it bypasses RLS and must never ship in the app. The app uses only the anon/publishable key + the user's JWT.
- **Never log** balances, amounts, user ids, emails, phones, or tokens (Constitution §6). Enforced by the `Logger` abstraction.

---

## 10. Logging

Single `Logger` interface: `debug/info/warn/error`. Debug disabled in release builds (BuildConfig gate). No direct `android.util.Log` calls in feature code (lint rule).

---

## 11. Background workers (trimmed)

**[DECIDED] Only three workers for v1.** Dropped AnalyticsWorker & NotificationWorker (no requirement — Constitution §5).

| Worker | Job | Constraints |
|---|---|---|
| `SyncPushWorker` | Drain outbox → Supabase (PostgREST/RPC) | Network connected |
| `SyncPullWorker` | Delta pull shared/owned books → Room | Network connected, periodic |
| `CleanupWorker` | Purge old tombstones, SUCCESS outbox rows, stale attachments | Idle/charging |

---

## 12. Use cases (one job each)

**Auth/session:** `SignIn`, `RegisterUser`, `SignOut`, `UpdateContact` (email/phone, invariant-enforced), `ListMyBusinesses`, `SwitchBusiness`.
**Business/books:** `CreateBusiness`, `RenameBusiness`, `DeleteBusiness`, `CreateBook`, `UpdateBook`, `DeleteBook`, `ListBooks` (active business + personal).
**Transactions/reports:** `AddTransaction`, `UpdateTransaction`, `DeleteTransaction`, `RestoreTransaction`, `GetBalance`, `GetMonthlySummary`.
**Sharing/RBAC:** `InviteToBusiness`, `ShareBook`, `UpdateMemberRole`, `SetBookGrant`, `RevokeMember`, `RevokeBookGrant`, `ResolvePermissions` (effective caps for UX gating).
**Infra:** `SyncPendingChanges`, `BackupData`, `RestoreData`.

Each authorization-bearing use case checks the required `Permission` via `core/permission` before acting (UX gate); Postgres RLS + the RPC re-check authoritatively. Sharing/RBAC use cases call RPC functions (§8.4); the rest write locally to Room and sync via the Outbox.

---

## 13. Testing strategy (Constitution §9)

- **Domain/ViewModel:** JUnit5 + MockK + Turbine. **Balance math has mandatory unit tests** (every CashIn/CashOut permutation, overflow, rounding).
- **Conflict resolver:** table-driven unit tests (LWW winner, timestamp tie → deviceId, tombstone vs update).
- **Room DAOs:** Robolectric or instrumented (JUnit4 — JUnit5 does not run instrumented tests).
- **Outbox:** idempotency (replay same key twice = one effect), ordering, dead-letter transition.
- **Permission resolver (`core/permission`):** table-driven — OWNER/ADMIN/VIEWER defaults; Admin per-book `permsOverride`; Admin scoped allow-list (no grant → denied); Viewer `access=DENY` hidden; personal-book owner = all; non-member = denied.
- **Identity invariant:** DB `CHECK`/`UNIQUE` reject empty-both and duplicate email/phone; `lookup_user`/`update_contact` behavior on exact match vs not-found.
- **RLS / RBAC (Supabase local via Docker + pgTAP):** non-member select denied; VIEWER insert/update denied; Admin excluded-book (`DENY`) denied; Admin scoped allow-list; direct client write to `business_members`/`book_grants`/`users`/`audit_log` denied (RPC-only); last-owner trigger rejects removal.

---

## 14. Constitution compliance check

| Principle | How this spec satisfies it |
|---|---|
| §1 Offline First | Repository = Room only; Supabase used as stateless remote; reads never network |
| §2 Data Integrity | Save persists to Room before any sync; Outbox + retry + dead-letter; no lost writes |
| §3 Data Ownership | Cloud backup + planned export/import; user owns businesses/books and shares under two-level RBAC |
| §4 Financial Accuracy | `Long` paisa via `Money`; HALF_UP; deterministic net balance |
| §5 Simplicity | Single module; 3 workers; no premature features |
| §6 Security | Supabase email Auth; two-level RBAC enforced in Postgres RLS + RPC; membership/grants/users/audit never client-writable; no sensitive logging |
| §7 Maintainability | MVVM + repository + Clean layering with enforced boundaries |
| §8 Performance | Indexed queries; remote reads minimized via delta pull (`updated_at` cursor) + Realtime |
| §9 Testing | Balance + conflict + rules covered |

---

## 15. Action items (build order)

1. [ ] Project skeleton: modules/packages (§3), Hilt, Compose, Navigation.
2. [ ] `core/money` — `Money` value class + tests.
3. [ ] Room schema (§7: User, Business, Book, BusinessMembership, BookGrant, Transaction, …) + DAOs + mappers; enforce boundary rules (§4).
4. [ ] `core/permission` — `Permission` enum + role defaults + effective-permission resolver (§8.3) + table tests.
5. [ ] Repository interfaces (domain) + impls (data), Room-only.
6. [ ] Outbox engine + `SyncPushWorker` + idempotency/sequence/backoff/dead-letter.
7. [ ] Supabase backend: self-host via Docker Compose (or free tier); Postgres schema (§8.5) + indexes + `CHECK`/`UNIQUE` constraints + `updated_at` triggers.
8. [ ] Supabase Auth (email) + `lookup_user` / `update_contact` RPC (invariant + uniqueness, §8.1). Phone = unverified column.
9. [ ] RLS policies + `business_role`/`effective_perms` SQL functions (§8.6) + pgTAP tests on Supabase local.
10. [ ] RBAC RPC functions: `invite_to_business`, `share_book`, `update_member_role`, `set_book_grant`, `revoke_member`, `revoke_book_grant` + `audit_log` + last-owner trigger (§8.4, §8.7).
11. [ ] `core/auth` — session (login/signout), active-business context in DataStore, `SwitchBusiness`/`ListMyBusinesses` (§8.2).
12. [ ] Pull engine + Realtime + conflict resolver (§6.6) + `SyncPushWorker`/`SyncPullWorker` (delta by `updated_at`, §8.8).
13. [ ] Feature UIs: auth, business switcher, dashboard, book, transaction, members, sharing, reports, settings.
14. [ ] Security layer: Keystore + Tink + DataStore (§9).
15. [ ] `CleanupWorker`, dead-letter Settings screen.
16. [ ] Migrate legacy `Double` data → `Long` paisa; delete legacy Activity code.
17. [ ] Full test pass (§13); constitution compliance review before RC.
