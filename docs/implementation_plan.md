# ElegenCashBook — Phased Implementation Plan

**Source of truth:** [implementation_specs.md](implementation_specs.md) v1.2.0
**Date:** 2026-07-03

## Ground rules

1. **A phase is DONE only when its Proof Gate passes** — every checkbox checked, every proof step executed on a real device/emulator, and the **regression list green** (all previous phases' proofs still pass). Only then start the next phase.
2. **The app must be fully usable after every phase.** Never merge a phase that leaves a screen broken or a flow half-wired.
3. **UI stays swappable.** Existing XML layouts (`app/src/main/res/layout/`) are reused as dumb shells. All screens follow the UI ⇄ ViewModel contract (spec §4 rule 6): Activity renders `StateFlow<UiState>`, sends `UiEvent`; no repository/DAO/Supabase imports in UI classes. Changing the UI later = touching `feature/*/ui` only.
4. Spec boundary rules (§4) are enforced from Phase 1 onward — violations are review-blockers.

**Existing UI inventory (reused, not rewritten):**
`activity_main` (business/book dashboard), `activity_add_business`, `activity_book_details`, `dialog_add_entry`, `bottom_sheet_add_book`, `bottom_sheet_book_details`, `bottom_sheet_business`, `item_book`, `item_business`, `popup_book_menu`.
**New layouts needed later (small):** login/register (P3), members & share sheet (P7), profile/contact edit (P8), sync status/dead-letter (P8).

---

## Phase 0 — Baseline & guardrails

**Goal:** current app still runs; build system ready for everything that follows. No feature change.

**Checklist**
- [x] Version catalog: coroutines, lifecycle, Room, KSP, Hilt, DataStore, Tink, WorkManager, supabase-kt+ktor, JUnit5/MockK/Turbine/Robolectric added. Coroutines+lifecycle wired; rest catalog-only until their phase (resolvability finally proven when wired).
- [x] Package dirs `elegenknox` → `elegencashbook` (git mv, 5 files); matches declared package + namespace.
- [x] `INTERNET` permission added.
- [x] `Logger` facade in `core/logging` (`AndroidLogger`, debug gated by `BuildConfig.DEBUG`; `buildConfig=true` enabled). No pre-existing `Log.*` calls to replace.
- [x] `./gradlew assembleDebug testDebugUnitTest` green (2026-07-03).

**Proof Gate P0**
- [x] `assembleDebug` + unit tests pass.
- [x] Installed on device; MainActivity resumed, crash buffer clean.
- [ ] Manual tap-through: create business → create book → add cash-in/cash-out entries → balances exactly as before. **(user)**

---

## Phase 1 — Money core (kill `Double`)

**Goal:** all money math goes through `Money` (Long paisa, HALF_UP). Existing screens keep working, now numerically exact.

**Checklist**
- [x] `core/money/Money.kt`: value class, plus/minus/unaryMinus (`*Exact` — overflow throws), `parse` (HALF_UP), `format` (whole → no decimals, else 2), `Iterable<Money>.sum()`.
- [x] 34 JUnit5 tests green (parse/rounding/overflow/format/round-trip). JUnit5 platform wired (`useJUnitPlatform` + launcher + vintage engine for legacy JUnit4).
- [x] `BookDetailsActivity`: `Entry.amount`, totals, running balances → `Money`; display via `format()` (was `toLong()` truncation — paisa now shown).
- [x] `Money.parse` replaces `toDoubleOrNull`; added guard: amount must be > 0.
- [x] Grep sweep: zero `Double`/`Float` in `app/src/main/java` (only doc comment).

**Proof Gate P1**
- [x] All `core/money` tests green (34 + 1 legacy).
- [x] Built, installed, launched — crash buffer clean.
- [ ] Manual: add entries `0.1` and `0.2` cash-in → Total In shows exactly `Rs 0.30`; running balance correct. **(user)**
- [ ] **Regression:** P0 tap-through still good. **(user)**

---

## Phase 2 — Room persistence + MVVM wiring (offline-first core)

**Goal:** data survives restart. In-memory lists die. Every existing screen re-wired to ViewModel + Room through the full spec layering. **This phase establishes the UI⇄VM contract for all future phases.**

**Checklist**
- [x] Room schema (spec §7): `Business`, `Book`, `Transaction`, `Category` (minimal), each with sync envelope columns (`version`, `updatedAt`, `deviceId`, `deletedAt`, `syncState` via `@Embedded SyncEnvelope`) — filled locally for now. Schema exported to `app/schemas/` (v1). Room 2.8.4 + KSP 2.3.9 + Hilt 2.60 wired on AGP 9.2 built-in Kotlin.
- [x] Indexes per spec §7: `Transaction(bookId, createdAt)`, `Transaction(bookId, type)`, `Book(businessId)`.
- [x] Domain models (pure Kotlin) + mappers entity⇄domain (spec §4 rules 1–3).
- [x] Repository interfaces in `domain/repository`; Room-only impls in `data/repository`; Hilt binds in `data/di` (rule 4). Flows of domain models only (rule 5). Every write stamps the sync envelope (version++, updatedAt, deviceId, PENDING) — P4-ready.
- [x] Use cases: `CreateBusiness`, `CreateBook`, `AddTransaction`, `UpdateTransaction`, `DeleteTransaction` (soft delete), `RestoreTransaction`, `GetBalance`, `ListBooks`, `ListMyBusinesses`, `SwitchBusiness` + `ObserveActiveBusinessId` (active-business + deviceId in DataStore).
- [x] ViewModels: `MainViewModel` (businesses+books, active business), `AddBusinessViewModel`, `BookDetailsViewModel` (entries, totals, running balance). Each: `StateFlow<UiState>` + `onEvent(UiEvent)`.
- [x] Re-wired `MainActivity`, `AddBusinessActivity`, `BookDetailsActivity` + bottom sheets/dialog to ViewModels. All in-memory state deleted (adapters keep render buffers only). UI classes: zero business logic (amount field validation via `core/money` stays for inline errors; use case re-guards).
- [x] Soft-delete + restore for entries (tombstone): long-press row → confirm → delete; snackbar Undo → restore.
- [x] Unit tests: 8 use-case/balance tests (fake repo, exact-paisa, overflow-throws, delete/restore) + 5 DAO tests (Robolectric 4.16.1 on JVM via vintage engine: counts/balances exclude tombstones, chronological order, zero-not-null empty balance). 48 total green.

**Proof Gate P2**
- [x] Create business → book → 5 entries → **force-kill app** → reopen: everything intact, balances identical. (user-verified 2026-07-04)
- [x] Switch between two businesses; each shows only its own books. Active business survives restart. (user-verified)
- [x] Delete entry (long-press) → Undo restore → balance correct at each step. (user-verified)
- [x] Grep proof: no `Room`/`Dao` import outside `data/`; no repository import in any Activity; no state `mutableListOf` in UI.
- [x] **Regression:** P0–P1 proofs pass. (user-verified)

---

## Phase 3 — Auth & session (Supabase email) — app stays offline-capable

**Goal:** optional sign-in (email). Constitution §1: app fully usable without account; auth is required only for sync/sharing (later phases).

**Checklist**
- [x] Supabase local stack up (Docker via `npx supabase start`; CLI 2.109). Config in gitignored `local.properties` (`supabase.url` = host LAN IP, `supabase.anonKey`) → BuildConfig; empty config ⇒ app runs guest-only. Debug builds allow cleartext HTTP for the LAN dev server.
- [x] `users` table migration (`supabase/migrations/20260704000001_users.sql`): CHECK email-or-phone, UNIQUE both, sync envelope, signup trigger copies display_name/phone from metadata, server-side updated_at/version trigger. **[DECIDED] email+password auth** (magic link needs deep-link plumbing, zero cost benefit); phone = optional unverified identifier at registration, E.164-normalized client-side.
- [x] RLS verified via live curl matrix: own-row select only; cross-user isolated; anon denied; client writes denied (no grant + no policy); duplicate phone rejected (23505).
- [x] Session security (spec §9): `core/security/TinkEncryptor` (AES256-GCM keyset wrapped by Android Keystore master key) + `EncryptedSessionStorage` implements supabase-kt `SessionManager` over its own DataStore file — JWT never on disk in plaintext. Corrupt session ⇒ silent sign-out, never a crash.
- [x] New layout `activity_login` (matches indigo design language): sign-in/register toggle, name+phone on register, error line, "Continue without account".
- [x] Signout (account dialog on top-bar person icon): "Sign out" keeps Room data, clears session + active-business; "Sign out & remove data" wipes Room + prefs (confirm dialog). Offline signout still clears local session.
- [x] Guest mode explicit: login auto-prompts once on first launch only; guest choice remembered; account button reopens login anytime.
- [x] Use cases: `SignIn`, `RegisterUser`, `SignOut`, `SignOutAndWipe`, `ObserveSession` + email/phone normalization (18 validation tests). supabase-kt 3.6.0 / ktor 3.4.3 / tink 1.22.0.

**Proof Gate P3**
- [x] Server side: register → row in `public.users` with phone+name; login token issued; RLS matrix green (curl-verified 2026-07-04).
- [ ] On device: register + login → app shows same local data. **(user + device reconnect)**
- [ ] Signout → local books still visible (guest). "Sign out & remove data" → clean slate. **(user)**
- [ ] Airplane mode: full app usable with no account and with a logged-in account. **(user)**
- [ ] Token stored encrypted — `adb shell run-as com.elegen.elegencashbook` inspect `files/datastore/elegen_session.preferences_pb`: no `eyJ` JWT prefix. (I run this when device attached.)
- [ ] **Regression:** P0–P2 proofs pass. **(user)**

> Dev-loop note: switched from local Docker stack to a **hosted Supabase project** (HTTPS, free tier) — device no longer needs same-Wi-Fi. `local.properties` holds hosted `supabase.url` + publishable key (gitignored). Schema applied via dashboard SQL Editor (auto-mode blocks DDL to a live DB — by design). Two hosted-project settings required: (1) run `supabase/migrations/20260704000001_users.sql` in SQL Editor; (2) Authentication → Sign In / Providers → Email → **disable "Confirm email"** so register auto-logs-in (hosted defaults it ON). Register must use a real email domain — hosted GoTrue rejects addresses whose domain has no MX record.
>
> P3 bug-fix pass (2026-07-04): stale APK lacked config (`serverConfigured=false` disabled sign-in/register buttons and suppressed the first-launch prompt) — fixed by rebuild. Added: register returns auto-login vs confirm-email state (shows "check your email" + switches to sign-in when confirmation is on); "email not confirmed" sign-in mapped to a clear message; account view is now a bottom sheet opened from the **Settings** bottom-nav tab (and top-bar person icon) — logged in shows email/phone + Log out + Log out & remove data; guest shows Sign in.
>
> **P3 identity-scoping (local data isolation) — 2026-07-04:** fixed a data-leak where all local rows shared one global bucket (`ownerUid="local"`, no per-user filter) so any account on the device saw every other's books. Now: `IdentityManager` (`data/identity`) tracks the active identity (`guest` sentinel vs real uid), stamps writes, and drives `BusinessRepository.observeBusinesses` via `activeUid.flatMapLatest` so the visible set switches on login/logout. On login it **claims** guest rows → uid (guest-created data becomes the account's); on logout the active id flips to guest, hiding the user's rows without deleting (retain-but-hidden; explicit "remove data" still wipes). One-time upgrade reassigns legacy `local`→`guest` at startup so pre-existing data survives. Books/transactions are scoped by parent (business/book) — the business list is the isolation gate, keeping the model P7-collaboration-ready. Supabase client init hardened to degrade to guest-only on failure (never crashes launch, now that `IdentityManager` forces client creation at app start). Verified: 68 unit tests green (incl. owner-isolation + claim DAO tests); installs + launches clean on device (Xiaomi/MIUI), no crash, Supabase init OK on-device (the earlier Robolectric `SettingsSessionManager` error was JVM-test-only). Full guest→login→logout on-device proof pending the hosted `users` SQL + email-confirmation toggle + a real sign-in.

---

## Phase 4 — Push sync (Outbox → Supabase)

**Goal:** local writes flow up. Nothing lost, ever (constitution §2).

**Checklist**
- [x] Postgres tables mirroring §7 (`businesses`, `books`, `transactions`) + `updated_at = now()` trigger + minimal RLS: *owner-only* access (`owner_uid`/`created_by_uid = auth.uid()`) — full RBAC comes in P6. **SQL written (`supabase/migrations/20260704000002_p4_sync_tables.sql`); apply via dashboard SQL Editor.** `categories` deferred — no CategoryEntity exists locally yet, so nothing to push (add with the entity, not speculatively).
- [x] `SyncQueue` Room table (spec §6.5: idempotencyKey, sequence=auto-inc id, status, retry, dead-letter) + non-destructive Migration(1,2).
- [x] Repository writes become: one Room tx = entity write + outbox row (spec §6.3) — via `OutboxWriter`. Push re-reads current entity state, so CREATE/UPDATE/DELETE all reduce to a PostgREST upsert (soft-delete tombstone carries the delete).
- [x] `SyncPushWorker`: drains in id (sequence) order; PostgREST upsert; exponential backoff; maxRetry → `DEAD_LETTER`; network-constrained; enqueued on every commit. (Periodic pull is P5; a periodic *push* is unnecessary — every commit already enqueues.)
- [x] Sync only when logged in; guest mode = outbox accumulates, **paused, drained on first login** (worker no-ops while guest; `IdentityManager` kicks a drain after claim-on-login).
- [x] Outbox unit tests: idempotent replay, ordering, dead-letter transition, markSynced version-guard, atomic-rollback-of-queue-row (79 tests, 0 fail).

**Proof Gate P4**
- [ ] Airplane ON → add 3 entries → airplane OFF → rows appear in Supabase (verify in Studio/psql) with server `updated_at`.
- [ ] Kill app mid-sync → relaunch → no duplicates (idempotency), queue drains.
- [ ] Stop Supabase (docker stop) → writes keep succeeding locally; start Supabase → auto-catch-up.
- [ ] Second user cannot read/write first user's rows (RLS owner check, psql test).
- [ ] **Regression:** P0–P3 proofs pass (esp. offline usability).

---

## Phase 5 — Pull sync + conflicts (multi-device)

**Goal:** same account on two devices converges. Restore-after-reinstall works (constitution §3).

**Checklist**
- [ ] `SyncPullWorker`: delta pull `updated_at > lastPulledAt` per table; cursor in DataStore (spec §6.4, §8.8).
- [ ] Conflict resolver (spec §6.6): LWW on server `updatedAt`, `deviceId` tiebreak; tombstones propagate deletes; table-driven unit tests.
- [ ] Initial hydration: fresh install + login → full pull rebuilds local DB.
- [ ] `CleanupWorker`: purge old tombstones + SUCCESS outbox rows (idle/charging).

**Proof Gate P5**
- [ ] Device A adds entry → appears on device B (two emulators, same account).
- [ ] Both edit same entry offline → both online → same winner on both (LWW), no duplicate.
- [ ] Delete on A → gone on B.
- [ ] Uninstall → reinstall → login → all data restored from cloud.
- [ ] **Regression:** P0–P4 proofs pass.

---

## Phase 6 — RBAC backend + permission core (no UI change yet)

**Goal:** full two-level permission machinery live server-side + mirrored client-side. Own-data behavior unchanged (owner has all caps).

**Checklist**
- [ ] Tables: `business_members`, `book_grants` (+ Room mirrors + mappers) (spec §7).
- [ ] SQL: `business_role()`, `effective_perms()`; **replace P4 owner-only RLS with full §8.6 policies**.
- [ ] RPCs: `lookup_user`, `invite_to_business`, `share_book`, `update_member_role`, `set_book_grant`, `revoke_member`, `revoke_book_grant` + `audit_log` + last-owner trigger (spec §8.4, §8.7).
- [ ] pgTAP suite (spec §13): non-member denied; VIEWER write denied; DENY book invisible; scoped ADMIN allow-list; grants/members/users/audit not client-writable; last-owner rejected.
- [ ] `core/permission`: `Permission` enum, role defaults, `effective(user, book)` resolver (spec §8.3) + table-driven tests. Room-cached perms, read-only, for UX gating.
- [ ] ViewModels expose caps in `UiState` (buttons enable/disable) — for owner everything stays enabled, so **no visible change**.

**Proof Gate P6**
- [ ] pgTAP all green.
- [ ] `core/permission` tests mirror pgTAP cases 1:1 (same table of expectations).
- [ ] psql as user B: select on A's business returns 0 rows; direct insert into `business_members` rejected.
- [ ] App as owner: identical behavior to P5 (full regression).
- [ ] **Regression:** P0–P5 proofs pass.

---

## Phase 7 — Sharing UI + collaboration

**Goal:** the user-facing sharing feature. Invite by email/phone, roles, per-book custom perms, viewer hiding, live updates.

**Checklist**
- [ ] New layouts: members list + invite sheet (email/phone field, role picker, per-book perms/scope editor) — reuse design language of existing sheets.
- [ ] `feature/members` + `feature/sharing` ViewModels calling RPC use cases (`InviteToBusiness`, `ShareBook`, `SetBookGrant`, `UpdateMemberRole`, `RevokeMember`, `RevokeBookGrant`, `ResolvePermissions`).
- [ ] `USER_NOT_REGISTERED` → friendly message ("must register first") per spec §8.4.
- [ ] Pull path includes shared businesses/books (RLS already filters); business switcher (`bottom_sheet_business`) lists shared businesses too.
- [ ] Supabase Realtime subscription on active business (foreground) → merge via resolver (spec §6.4).
- [ ] UI capability gating live: VIEWER sees no add/edit buttons; ADMIN without `TX_DELETE` on a book sees no delete; DENY-hidden books absent from lists.
- [ ] Sharing requires online (RPC): clear offline error, no fake success.

**Proof Gate P7** (two accounts, two devices/emulators)
- [ ] A invites B (by email) as ADMIN scoped to book X with `TX_ADD+TX_EDIT` only → B sees only X, can add/edit, **cannot delete** (button absent AND server rejects forced attempt).
- [ ] A invites C as VIEWER with book Y denied → C sees all books except Y, read-only everywhere.
- [ ] B adds entry → appears on A (Realtime while foreground; worker pull when backgrounded).
- [ ] Invite unknown email → "must register first".
- [ ] Revoke B → B loses access on next sync; B's local copy of A's data cleaned.
- [ ] Demote last owner → rejected with message.
- [ ] **Regression:** P0–P6 proofs pass (esp. offline single-user flows).

---

## Phase 8 — Profile, contact management, sync visibility, hardening

**Goal:** user self-management + operational polish. Release candidate.

**Checklist**
- [ ] Profile screen (new layout): display name, email, phone. Email change via Supabase verified flow; phone edit direct (unverified); invariant "≥1 contact" enforced (UX + DB).
- [ ] `update_contact` RPC wired; lookup-by-new-phone works for sharing.
- [ ] Settings → Sync screen: pending/dead-letter counts, manual retry (spec §6.5).
- [ ] Audit log view per business (read-only list).
- [ ] Reports minimal: monthly summary (`GetMonthlySummary`) on existing dashboard patterns.
- [ ] Sensitive-log audit: grep release build for amounts/emails/tokens in logs (spec §9); ProGuard/R8 config.
- [ ] Full test pass + constitution compliance review (spec §14).

**Proof Gate P8 (= release gate)**
- [ ] Change phone → second account finds user by new phone; old phone lookup fails.
- [ ] Attempt to clear both email+phone → blocked.
- [ ] Force a sync failure (bad row) → appears in dead-letter screen → manual retry succeeds after fix.
- [ ] Monthly summary numbers = hand-computed from entries (paisa-exact).
- [ ] **Full regression: every prior Proof Gate re-run end-to-end.**

---

## Regression matrix (run at every gate)

| # | Flow | Introduced |
|---|---|---|
| R1 | Build + unit tests green | P0 |
| R2 | Create business/book/entries, balances exact | P0/P1 |
| R3 | Kill app → data persists; switch business | P2 |
| R4 | Guest offline full usability | P3 |
| R5 | Offline writes → sync on reconnect, no dupes | P4 |
| R6 | Two devices converge; reinstall restores | P5 |
| R7 | RBAC: viewer read-only, scoped admin, hidden books | P7 |
| R8 | Contact change + lookup | P8 |

## Deferred (post-RC, spec future list)
Attachments upload (Supabase Storage), export Excel/PDF, recurring transactions, budgets, Compose migration (UI-layer swap only, thanks to §4 rule 6).
