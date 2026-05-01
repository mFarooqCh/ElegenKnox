---

description: "Task list for minimal lock screen UI"

---

# Tasks: Minimal Lock Screen UI

**Input**: Design documents from `/specs/001-minimal-lockscreen-ui/`
**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, quickstart.md

**Tests**: Automated tests are optional and not requested. Real-device validation is required for this project.

**Organization**: Tasks are grouped by user story to enable independent implementation and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2)
- Include exact file paths in descriptions

## Path Conventions

- **Android app**: `app/src/main/` for source, `app/src/main/res/` for resources

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Project readiness for the feature

- [x] T001 Verify Material and ConstraintLayout dependencies in app/build.gradle.kts (add if missing)
- [x] T002 Confirm the app theme resources are referenced by app/src/main/AndroidManifest.xml and exist in app/src/main/res/values/themes.xml and app/src/main/res/values-night/themes.xml

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Base UI scaffold required by all user stories

- [x] T003 Create the lock screen layout skeleton with centered input, Unlock button, and feedback TextView in app/src/main/res/layout/activity_main.xml
- [x] T004 Wire MainActivity to use activity_main.xml and bind view references in app/src/main/java/com/elegen/elegenknox/MainActivity.kt

**Checkpoint**: Base lock screen UI renders without validation logic

---

## Phase 3: User Story 1 - Enter Code and Get Feedback (Priority: P1) 🎯 MVP

**Goal**: Validate input and show success, error, or empty feedback

**Independent Test**: Launch the app, enter "1234" and a non-matching value, and confirm the correct feedback appears

### Implementation for User Story 1

- [x] T005 [P] [US1] Add input hint and feedback strings in app/src/main/res/values/strings.xml
- [x] T006 [P] [US1] Implement 8-character input filter, trim comparison, and feedback mapping in app/src/main/java/com/elegen/elegenknox/MainActivity.kt
- [x] T007 [P] [US1] Apply string resources to the input hint and feedback TextView in app/src/main/res/layout/activity_main.xml

**Checkpoint**: User Story 1 is fully functional and testable independently

---

## Phase 4: User Story 2 - Full-Screen Lock Presentation (Priority: P2)

**Goal**: Launch directly into a full-screen lock UI with no app chrome

**Independent Test**: Launch the app on multiple devices and verify the lock screen is full screen, centered, and free of system UI

### Implementation for User Story 2

- [x] T008 [US2] Ensure MainActivity is the launcher and no other screens are exposed in app/src/main/AndroidManifest.xml
- [x] T009 [P] [US2] Update the app theme to NoActionBar and black background in app/src/main/res/values/themes.xml and app/src/main/res/values-night/themes.xml
- [x] T010 [US2] Hide system bars for immersive full-screen mode in app/src/main/java/com/elegen/elegenknox/MainActivity.kt
- [x] T011 [US2] Set a black background and verify centered constraints for rotation in app/src/main/res/layout/activity_main.xml

**Checkpoint**: User Story 2 is fully functional and testable independently

---

## Phase 5: Polish & Cross-Cutting Concerns

**Purpose**: Validation and documentation updates

- [ ] T012 Run the manual validation matrix from specs/001-minimal-lockscreen-ui/quickstart.md and record results in specs/001-minimal-lockscreen-ui/checklists/requirements.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies - can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion - blocks all user stories
- **User Stories (Phase 3-4)**: Depend on Foundational completion
- **Polish (Phase 5)**: Depends on user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational - no dependencies on other stories
- **User Story 2 (P2)**: Can start after Foundational - independent of US1

### Parallel Opportunities

- After T005, T006 and T007 can proceed in parallel
- After T008, T009 and T010 can proceed in parallel (different files)

---

## Parallel Example: User Story 1

```bash
Task: "Add input hint and feedback strings in app/src/main/res/values/strings.xml"
Task: "Apply string resources to the input hint and feedback TextView in app/src/main/res/layout/activity_main.xml"
```

---

## Parallel Example: User Story 2

```bash
Task: "Update the app theme to NoActionBar and black background in app/src/main/res/values/themes.xml and app/src/main/res/values-night/themes.xml"
Task: "Hide system bars for immersive full-screen mode in app/src/main/java/com/elegen/elegenknox/MainActivity.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational
3. Complete Phase 3: User Story 1
4. Stop and validate User Story 1 independently

### Incremental Delivery

1. Setup + Foundational
2. User Story 1 -> validate
3. User Story 2 -> validate
4. Polish and device validation
