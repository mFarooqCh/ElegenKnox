# Research

## UI implementation approach

Decision: Use XML layouts with ConstraintLayout and AppCompat/Material widgets.
Rationale: The project already uses View-based layouts and has no Compose setup.
Alternatives considered: Jetpack Compose (adds new setup and dependencies).

## Full-screen lock presentation

Decision: Use a black root background and hide system bars via WindowInsets
controller in the activity to achieve immersive UI.
Rationale: Ensures the lock screen is full-screen across OEMs while keeping
rotation behavior consistent.
Alternatives considered: Theme-only flags (insufficient on some devices).

## Input validation behavior

Decision: Apply an 8-character InputFilter, trim leading/trailing whitespace for
comparison, and map results to three distinct states (success, error, empty).
Rationale: Matches requirements and keeps logic explicit.
Alternatives considered: Regex-only validation or numeric-only input (not required).
