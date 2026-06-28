# Implementation Plan: Minimal Lock Screen UI

**Branch**: `001-minimal-lockscreen-ui` | **Date**: 2026-04-29 | **Spec**: specs/001-minimal-lockscreen-ui/spec.md
**Input**: Feature specification from `/specs/001-minimal-lockscreen-ui/spec.md`

**Note**: This template is filled in by the `/speckit.plan` command. See `.specify/templates/plan-template.md` for the execution workflow.

## Summary

Deliver a full-screen, black lock screen UI on app launch with a centered input
and Unlock button, validate the input against "1234" after trimming whitespace
and enforcing an 8-character limit, and show distinct success/error/empty
messages.

## Technical Context

<!--
  ACTION REQUIRED: Replace the content in this section with the technical details
  for the project. The structure here is presented in advisory capacity to guide
  the iteration process.
-->

**Language/Version**: Kotlin (Android, Java 11 toolchain)  
**Primary Dependencies**: AndroidX AppCompat, Core KTX, Activity KTX, ConstraintLayout, Material  
**Storage**: N/A (no persistence)  
**Testing**: JUnit4 (unit), AndroidX JUnit + Espresso (instrumented)  
**Target Platform**: Android 12-14 devices (minSdk 29, targetSdk 36)
**Project Type**: Android mobile app (single module)  
**Performance Goals**: Lock screen visible within 2 seconds of launch  
**Constraints**: Full-screen black UI, offline-capable, 8-char input limit, centered layout on rotation  
**Scale/Scope**: Single screen, one activity

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

- Control-first enforcement path is documented (Phase 1 UI-only; system enforcement planned next).
- Native-first approach confirmed (Kotlin + Android SDK only).
- Incremental enforcement phases and exit criteria are defined (Phase 1 UI + validation exit).
- Real-device validation plan and OEM matrix are included (Pixel 7, Galaxy A52, Moto G Power).
- Failure/bypass scenarios and offline-first behavior are covered (uninstall/bypass documented with residual risk).
- Scope expansion is justified by prior phase validation (no expansion in this phase).

## Project Structure

### Documentation (this feature)

```text
specs/[###-feature]/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)
<!--
  ACTION REQUIRED: Replace the placeholder tree below with the concrete layout
  for this feature. Delete unused options and expand the chosen structure with
  real paths (e.g., apps/admin, packages/something). The delivered plan must
  not include Option labels.
-->

```text
app/
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml
│   │   ├── java/com/elegen/elegencashbook/MainActivity.kt
│   │   └── res/
│   │       ├── layout/activity_main.xml
│   │       ├── values/
│   │       └── values-night/
│   ├── androidTest/java/
│   └── test/java/
```

**Structure Decision**: Single Android app module under `app/` with XML layouts
and AppCompat activity; no backend or additional modules for this phase.

## Complexity Tracking

No constitution violations requiring complexity justification in this phase.
