# ElegenCashBook — Handoff Notes

**Date:** 2026-07-04
**Branch:** master, 1 commit ahead of `origin/master` (`89d66ef` unpushed)
**Governing docs:** [constitution.md](constitution.md) (rules), [implementation_specs.md](implementation_specs.md) v1.2.0 (spec), [implementation_plan.md](implementation_plan.md) (phased plan, proof-gated)

---

## 1. Current state

Offline-first Android cashbook. Phases P0–P4 done. **P5 (pull sync + conflicts) code done, on-device proof gate not yet run.**

| Phase | Status |
|---|---|
| P0 Baseline | done, gate passed |
| P1 Money core (`Long` paisa) | done, gate passed |
| P2 Room + MVVM | done, gate passed |
| P3 Supabase auth + identity scoping | code done; **on-device proof gate incomplete** (device keeps dropping off wireless adb) — server-side (RLS, curl matrix) verified, on-device tap-through not yet re-confirmed by user |
| Post-P3 hardening | code-review pass (7 findings) fixed + verified; 3 user-reported bugs (backdated-entry grouping, book-details 3-dot menu, stale "Updated" label) fixed today |
| P4 Push sync | **done.** Outbox table + Migration(1,2), atomic entity+outbox writes (`OutboxWriter`), `SyncPushWorker` (PostgREST upsert, backoff, dead-letter), `WorkManagerSyncScheduler`, `RemotePush`. Server tables/RLS in `supabase/migrations/20260704000002_p4_sync_tables.sql` (applied). Migration(2,3) backfills pre-outbox local data; `SyncQueueDao.pending()` tiers BUSINESS→BOOK→TRANSACTION (not just insertion id) so backfilled parents always push before already-queued children. Proof gate: airplane-mode round trip PASS (34→52 transactions synced), kill-mid-sync PASS (0 pending post-restart, 52/52 distinct ids, zero dead-letters), cross-user RLS isolation PASS (curl+2 real JWTs: spoofed insert 403, cross-user update 0 rows, cross-user delete 403 — DELETE not even granted to `authenticated`, soft-delete-only by design). Supabase-down resilience not tested (hosted project, not local Docker — would need pausing via dashboard/MCP) and P0–P3 regression not formally re-run — accepted as-is per explicit call, not blocking. |
| P5 Pull sync + conflicts | **code done 2026-07-04; on-device proof gate not yet run.** `RemotePull` (delta select `updated_at > lastPulledAt`, cursor per entity type in `AppPreferences`), `ConflictResolver` (pure LWW: higher `updatedAt` wins, `deviceId` tiebreak on tie — 7 unit tests), `SyncPullWorker` (same Hilt-`EntryPoint`-on-`CoroutineWorker` pattern as `SyncPushWorker`), `CleanupWorker` (purges tombstones >30 days old, idle+charging, daily). Pulled one-time on login/app-start plus a 15-min periodic backstop (WorkManager's minimum interval — Realtime foreground push is P7, deliberately not built yet). Fresh-install hydration needs no special code: cursor defaults to 0, so the first pull is naturally a full pull. No new Room migration needed (no new columns/tables). Not yet verified end-to-end on two real devices — that's the concrete next step before calling P5 done. |

Commits on top of P2: `956473a` (P3), `16129d5` (review fixes), `89d66ef` (today's 3 bugs). Local test suite: 46 tests, 0 failures. `assembleDebug` clean. `installDebug` currently fails — **no device connected**; reconnect before trusting anything beyond compile+test.

---

## 2. Architecture

Clean Architecture + MVVM + Repository, enforced by grep-checkable rules (constitution/spec §4):

```
UI (Activity)  →  ViewModel (StateFlow<UiState> / onEvent(UiEvent))  →  UseCase  →  Repository (interface, domain/)  →  RepositoryImpl (Room-only, data/)  →  Room
```

- **domain/** is pure Kotlin — no Android, no Room, no Supabase imports. This was tested mid-session: a code-review suggestion to expose `AuthRepository.handleDeepLink(intent)` was rejected on implementation because `Intent` is an Android type; instead a `data`-layer-only `AuthDeepLinkHandler` was added. **If a fix needs an Android/SDK type in a repository method, the fix belongs in a new `data/` class, not the domain interface.**
- **UI never imports Room/DAO/Supabase directly.** Existing XML layouts are reused as dumb shells (`activity_main`, `activity_book_details`, `activity_add_business`, bottom sheets, `popup_book_menu`) — UI is swappable later (Compose migration is a deferred, UI-layer-only swap).
- Every entity carries a `SyncEnvelope` (`version`, `updatedAt`, `deviceId`, `deletedAt`, `syncState`) — this is P4's outbox foundation, already stamped on every write since P2.
- Money is always `Long` paisa via `@JvmInline value class Money` — zero `Double`/`Float` in `app/src/main/java` (grep-verified at P1 gate, still true).

---

## 3. Key decisions and why

- **Email+password auth, not magic link.** Magic link needs deep-link plumbing for zero benefit here; email+password was simpler and got its own deep-link handling anyway (email confirmation flow).
- **Hosted Supabase, not local Docker.** Switched mid-P3 so the device doesn't need to be on the same Wi-Fi as the dev machine. `local.properties` holds hosted URL + publishable key (gitignored). DDL only applied via dashboard SQL Editor — **auto-mode blocks DDL against a live DB by design, do not bypass this.**
- **Identity-scoping model** (`IdentityManager`): guest sentinel `"guest"` vs real uid. Guest-created data is claimed (reassigned) on login; on logout the active id flips back to guest, hiding — not deleting — the user's rows (retain-but-hidden). This was a direct fix for a real data-leak bug (all local rows shared one bucket, any account saw everyone's books). Legacy `"local"` owner rows are migrated to `"guest"` once at startup.
- **`ActiveIdentity` interface seam.** `IdentityManager` was hard to fake in tests (needs a full auth graph). Extracted a narrow `ActiveIdentity` interface (`activeUid: StateFlow<String>`, `current(): String`) so repo tests can inject a `FakeIdentity` without touching DI. Minimal seam, not a general abstraction — don't grow it beyond what a repo actually needs.
- **`db.withTransaction` for multi-row writes.** `BookRepositoryImpl.duplicate()` copies a book + all its entries; wrapped in a Room transaction so a crash mid-copy can't leave a half-duplicated book. Proven with a real regression test: the fix was temporarily reverted, the test was confirmed to fail specifically (not something else), then restored.
- **Ownership guard on every book mutation.** `ownedBook(bookId)` checks `ownerUid == identity.current()` before rename/delete/restore/move/duplicate — belt-and-suspenders alongside the UI's own scoping, cheap enough to always do.
- **`lastEntryAt` = real wall-clock time, not the user-picked entry date.** The book-list "Updated X" label was reading `MAX(t.createdAt)` (the date/time the user picked in the entry-date picker), so backdating an entry could make the whole book look artificially old or fresh. Fixed to `MAX(b.updatedAt, MAX(t.updatedAt))` — the sync envelope's actual write time, folding in the book's own last-touch so a rename/move updates it even with zero entries.
- **Per-date entry-list headers instead of one static header.** Same root cause class as above: a single `tv_date_header` always showed the newest entry's date, so backdated entries visually appeared "under today." Replaced with dynamic per-group headers built in `refreshEntryList`, keyed off `entry.dateText` changing between consecutive (already date-sorted) entries.
- **No Undo snackbar for book-delete from inside `BookDetailsActivity`.** Main's book-list delete has Undo because the screen stays open; book-details has to `finish()` once its own book is gone, so Undo would show on a screen that no longer exists. Simplified to toast + finish. Flagged as a conscious simplification, not an oversight — add if a cross-screen undo is ever wanted.
- **Guest-mode choice is in-memory, not persisted.** `AppPreferences.guestModeChosen` was wrongly a DataStore-persisted flag, so "continue as guest" survived forever, including across cold app kills — the spec wants a fresh login prompt on every cold launch until the user chooses again. Now `MutableStateFlow(false)`, reset every process start.

---

## 4. Coding conventions established

- **Repository pattern:** interface in `domain/repository/Repositories.kt`, impl in `data/repository/RepositoryImpls.kt`, Hilt `@Binds` wiring in `data/di/AppModules.kt`. New repo methods always go interface → impl → binding, in that order.
- **Use cases:** one class per operation, `operator fun invoke(...)`, constructor-injects only repository interfaces (never DAOs directly). Validation (`require(...)`) lives in the use case, not the repository or UI.
- **ViewModel contract:** `sealed interface XUiEvent`, `data class XUiState`, single `val state: StateFlow<XUiState>`, single `fun onEvent(event: XUiEvent)`. No other public surface.
- **Soft delete everywhere:** `deletedAt` tombstone + `restore()`, never a hard `DELETE`. Every soft-delete path pairs with a restore path and (in UI) a confirm dialog.
- **Sync envelope bump on every write:** `newEnvelope(...)` for creates, `.bumped(...)` for updates — always stamps `version++`, `updatedAt = now()`, `deviceId`, `syncState = PENDING`. Don't hand-roll a write that skips this; P4's outbox depends on it being universal.
- **Test split:** Robolectric + JUnit4 vintage engine for anything touching Room/Android (`*Test.kt` under `data/`), JUnit5 (Jupiter) + MockK/Turbine for pure-Kotlin domain/usecase tests. Don't mix — check an existing sibling test file for which runner a new test belongs under.
- **Testability seams via narrow interfaces, not broad ones.** `ActiveIdentity` is the model: extract exactly the surface a test needs, nothing speculative.
- **Regression-proof risky fixes.** For anything non-obvious (the transaction-atomicity fix), temporarily break the fix, confirm the new test catches it specifically, then restore. Don't just trust that a test "looks right."
- **Never log** balances, uids, emails, phones, or tokens (constitution security rule) — `Logger` facade in `core/logging`, debug-gated by `BuildConfig.DEBUG`.
- **Never write DB passwords to any file; never ship `service_role`/`SECRET_KEY` client-side.**
- Comments only for non-obvious *why* (a hidden constraint, a workaround, a subtle invariant) — not restating what the code does. This session's diffs follow that: e.g. the `ownedBook()` and `lastEntryAt` query comments explain the reason, not the mechanics.

---

## 5. Concrete next steps

1. **Reconnect the device and re-run the P3 proof gate** (still open in the plan): register+login shows same local data; signout keeps guest data visible, "remove data" wipes clean; airplane-mode usability both logged-in and guest; encrypted-token check via `adb shell run-as com.elegen.elegencashbook` on `files/datastore/elegen_session.preferences_pb` (must not contain a raw `eyJ` JWT). Also manually re-verify today's 3 bug fixes on-device (grouped date headers, book-details 3-dot menu actions, "Updated" label under a backdated entry).
2. **Push the local commit** (`89d66ef`) to `origin/master` once the above is confirmed — currently unpushed, only local.
3. **Start Phase 4 (push sync)** per [implementation_plan.md](implementation_plan.md#phase-4--push-sync-outbox--supabase):
   - Postgres tables mirroring `businesses`/`books`/`transactions`/`categories` + `updated_at` trigger + owner-only RLS (full RBAC is P6, not now).
   - New Room `SyncQueue` table (idempotencyKey, sequence, status, retry, dead-letter) — spec §6.5.
   - Repository writes become one Room transaction = entity write + outbox row (spec §6.3) — the `db.withTransaction` pattern from today's `duplicate()` fix is the template.
   - `SyncPushWorker`: WorkManager, network-constrained, drains outbox in order, PostgREST upsert, exponential backoff, `DEAD_LETTER` after max retries.
   - Decided already: sync is **paused while guest, drained on first login** — don't relitigate this.
   - Outbox unit tests: idempotent replay, ordering, dead-letter transition — follow the existing Robolectric DAO-test pattern.
4. Per the plan's ground rules: P4 isn't "done" until its Proof Gate passes on a real device (airplane-mode round trip, kill-mid-sync no-duplicate check, Supabase-down resilience, cross-user RLS isolation) **and** the full P0–P3 regression list still passes.
