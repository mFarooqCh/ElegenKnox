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
- [ ] Room schema (spec §7): `Business`, `Book`, `Transaction`, `Category` (minimal), each with sync envelope columns (`version`, `updatedAt`, `deviceId`, `deletedAt`, `syncState`) — filled locally for now.
- [ ] Indexes per spec §7.
- [ ] Domain models (pure Kotlin) + mappers entity⇄domain (spec §4 rules 1–3).
- [ ] Repository interfaces in `domain/repository`; Room-only impls in `data/repository`; Hilt binds (rule 4). Flows of domain models only (rule 5).
- [ ] Use cases: `CreateBusiness`, `CreateBook`, `AddTransaction`, `UpdateTransaction`, `DeleteTransaction` (soft delete), `GetBalance`, `ListBooks`, `ListMyBusinesses`, `SwitchBusiness` (local active-business in DataStore).
- [ ] ViewModels: `MainViewModel` (businesses+books, active business), `AddBusinessViewModel`, `BookDetailsViewModel` (entries, totals, running balance). Each: `StateFlow<UiState>` + `onEvent(UiEvent)`.
- [ ] Re-wire `MainActivity`, `AddBusinessActivity`, `BookDetailsActivity` + bottom sheets/dialog to ViewModels. Delete all in-memory `mutableListOf` state. UI classes contain **zero** business logic.
- [ ] Soft-delete + restore path for entries (tombstone) working locally.
- [ ] Unit tests: use cases + balance math with fake repository; DAO tests (Robolectric/instrumented).

**Proof Gate P2**
- [ ] Create business → book → 5 entries → **force-kill app** → reopen: everything intact, balances identical.
- [ ] Switch between two businesses; each shows only its own books. Active business survives restart.
- [ ] Delete entry → restore → balance correct at each step.
- [ ] Grep proof: no `Room`/`Dao` import outside `data/`; no repository import in any Activity.
- [ ] **Regression:** P0–P1 proofs pass.

---

## Phase 3 — Auth & session (Supabase email) — app stays offline-capable

**Goal:** optional sign-in (email). Constitution §1: app fully usable without account; auth is required only for sync/sharing (later phases).

**Checklist**
- [ ] Supabase project up (self-host Docker Compose per spec §2, or free tier for dev).
- [ ] `users` table + `CHECK`/`UNIQUE` constraints (spec §8.1); Supabase Auth email (magic link or email+password — pick one, magic link = least UI).
- [ ] `core/auth`: `SessionManager` — login, signout, session persisted via Keystore+Tink+DataStore (spec §9). Phone field stored as unverified identifier (no SMS).
- [ ] New layout: `activity_login` (or dialog) — matches existing design language.
- [ ] Signout: clears session material + active-business cache; local Room data retained (spec §8.2); "sign out & remove data" wipes.
- [ ] Guest mode explicit: skip login → everything local (P2 behavior unchanged).
- [ ] Use cases: `SignIn`, `RegisterUser`, `SignOut`.

**Proof Gate P3**
- [ ] Register new account (email arrives / password works). Login → app shows same local data.
- [ ] Signout → local books still visible (guest). "Sign out & remove data" → clean slate.
- [ ] Airplane mode: full app usable (create/edit/delete) with no account and with a logged-in account.
- [ ] Token stored encrypted (inspect: no plaintext token in any prefs/DataStore file).
- [ ] **Regression:** P0–P2 proofs pass.

---

## Phase 4 — Push sync (Outbox → Supabase)

**Goal:** local writes flow up. Nothing lost, ever (constitution §2).

**Checklist**
- [ ] Postgres tables mirroring §7 (`businesses`, `books`, `transactions`, `categories`) + `updated_at = now()` trigger + minimal RLS: *owner-only* access (`owner_uid = auth.uid()`) — full RBAC comes in P6.
- [ ] `SyncQueue` Room table (spec §6.5: idempotencyKey, sequence, status, retry, dead-letter).
- [ ] Repository writes become: one Room tx = entity write + outbox row (spec §6.3).
- [ ] `SyncPushWorker`: drains in sequence order; PostgREST upsert; exponential backoff; maxRetry → `DEAD_LETTER`; network-constrained; enqueued on every commit + periodic.
- [ ] Sync only when logged in; guest mode = outbox accumulates (or paused) — decide: **paused, drained on first login**.
- [ ] Outbox unit tests: idempotent replay, ordering, dead-letter transition.

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
