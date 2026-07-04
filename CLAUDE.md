# ElegenCashBook — CLAUDE.md

Offline-first Android cashbook. Income/expense tracking, cloud backup.
Full detail docs: [constitution.md](docs/constitution.md) (rules), [implementation_specs.md](docs/implementation_specs.md) (spec), [implementation_plan.md](docs/implementation_plan.md) (phased plan), [handoff_notes.md](docs/handoff_notes.md) (latest session state).

## Non-negotiable rules (constitution)

- Offline first. All ops hit local Room DB. Network only for sync.
- No transaction lost. Save local before cloud sync. Failed sync auto-retry.
- Money = `Long` paisa via `Money` value class. Never `Double`/`Float`. Zero exceptions, grep-verified.
- Auth required before cloud sync. RLS blocks cross-user access. Never log sensitive data.
- MVVM + Repository pattern mandatory. Room = local source of truth.
- UI interactions <100ms. DB queries indexed.
- Business logic needs unit tests, balance calc especially.

## Tech stack

- Kotlin only. XML Views + Material 3 (Compose migration deferred, UI-layer-only swap later).
- Hilt DI. Room (single source of truth). Coroutines + Flow, no LiveData.
- Backend: Supabase (Postgres + PostgREST + Realtime + Auth), treated as **dumb remote**. Not Firebase (needs paid Blaze plan).
- Auth: email only (magic-link or email+password). Phone = unverified identifier, not auth channel.
- WorkManager for background sync. Android Keystore + DataStore for security, not EncryptedSharedPreferences.
- Single Gradle module (multi-module only if build time hurts).

## Package structure

```
com.elegen.elegencashbook/
  core/money/       Money value class
  core/security/     Keystore, encrypted DataStore
  core/sync/         Outbox engine, pull engine, conflict resolver
  core/auth/         Session, identity invariant
  core/permission/   Capability enum, permission resolver
  core/logging/      Logger abstraction
  domain/model/      Pure Kotlin, NO Room/Supabase imports
  domain/repository/ Interfaces only
  domain/usecase/    One job each
  data/local/dao|entity|mapper/   Room entities never leave data/
  data/remote/supabase|rpc/       DTOs, PostgREST/RPC, never leave data/
  data/repository/   Repository impls
  feature/*/ui       auth, business, dashboard, book, transaction, members, sharing, reports, settings
  worker/            SyncPushWorker, SyncPullWorker, CleanupWorker
```

## Architecture boundary (non-negotiable)

```
UI (Activity) → ViewModel (StateFlow<UiState>/onEvent) → UseCase → Repository interface (domain/) → RepositoryImpl (data/) → Room
```

- `domain/` pure Kotlin. No Android/Room/Supabase import ever. Need Android SDK type (e.g. `Intent`) in a fix → new `data/` class, NOT domain interface.
- UI never imports Room/DAO/Supabase directly.
- Repo pattern flow always: interface in `domain/repository/Repositories.kt` → impl in `data/repository/RepositoryImpls.kt` → Hilt `@Binds` in `data/di/AppModules.kt`.
- Use cases: one class, `operator fun invoke(...)`, inject repo interfaces only (never DAOs). Validation (`require(...)`) lives in use case.
- ViewModel contract: `sealed interface XUiEvent`, `data class XUiState`, single `state: StateFlow<XUiState>`, single `onEvent(event)`. No other public surface.

## Conventions

- Soft delete everywhere: `deletedAt` tombstone + `restore()`. Never hard `DELETE`.
- Every write bumps sync envelope (`version`, `updatedAt`, `deviceId`, `syncState`): `newEnvelope()` on create, `.bumped()` on update. Never skip — P4 outbox depends on universality.
- Testability seams: narrow interfaces only (e.g. `ActiveIdentity`), extract exactly what a test needs. No speculative abstraction.
- Regression-proof risky fixes: break fix temporarily, confirm new test catches it specifically, restore.
- Never log balances/uids/emails/phones/tokens. Use `Logger` facade (`core/logging`), debug-gated by `BuildConfig.DEBUG`.
- Never write DB passwords to any file. Never ship `service_role`/`SECRET_KEY` client-side.
- Comments: only non-obvious *why* (hidden constraint, workaround, invariant). Never restate what code does.
- Test split: Robolectric + JUnit4 vintage for anything touching Room/Android (`data/` tests). JUnit5 + MockK/Turbine for pure-Kotlin domain/usecase tests. Check sibling test file before picking runner.
- `db.withTransaction` for multi-row writes (entity + outbox row together).

## Key decisions (don't relitigate)

- Email+password auth, not magic link (simpler, deep-link needed anyway for email confirm).
- Hosted Supabase, not local Docker (device doesn't need dev-machine Wi-Fi). `local.properties` = hosted URL + publishable key, gitignored. DDL via dashboard SQL Editor only — auto-mode blocks DDL against live DB, don't bypass.
- Identity-scoping (`IdentityManager`): guest sentinel `"guest"` vs real uid. Guest data claimed on login. Logout hides (not deletes) user rows. Legacy `"local"` owner migrated to `"guest"` once at startup.
- Ownership guard (`ownedBook(bookId)`) on every book mutation — belt-and-suspenders with UI scoping.
- `lastEntryAt` = wall-clock sync time (`MAX(b.updatedAt, MAX(t.updatedAt))`), never user-picked entry date — backdating must not distort book freshness.
- Sync paused while guest, drained on first login.
- Guest-mode choice in-memory only (`MutableStateFlow`), never persisted — fresh login prompt every cold launch.

## Current state (see handoff_notes.md for full detail)

P0–P4 done. **P5 (pull sync + conflicts) code done, on-device proof gate not yet run** — `RemotePull`, `ConflictResolver` (pure LWW, 7 unit tests), `SyncPullWorker`, `CleanupWorker` in place; not yet verified two-device.

## Testing / gates

- Constitution compliance check required in every plan/spec/task list. Deviations need approval + documentation.
- Regression matrix re-run at every phase gate (see implementation_plan.md §Regression matrix).
- P5 not "done" until proof gate passes on real devices: device A add → appears on B, concurrent edit → same LWW winner both sides, delete propagates, uninstall/reinstall/login → full restore — plus P0–P4 regression still green.
