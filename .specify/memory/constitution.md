<!--
Sync Impact Report
- Version change: N/A (template) -> 1.0.0
- Modified principles: Template placeholders -> 10 named principles
- Added sections: Technical Constraints, Development Workflow
- Removed sections: None
- Templates requiring updates: .specify/templates/plan-template.md (updated), .specify/templates/spec-template.md (updated), .specify/templates/tasks-template.md (updated)
- Follow-up TODOs: None
-->
# Android Device Control System (Financed Device Enforcement) Constitution

## Core Principles

### 1. Control Over UI
System-level control (Device Owner, LockTask, policy enforcement) MUST be the
primary enforcement path. UI-only restrictions are secondary and MUST NOT be the
sole enforcement mechanism. Rationale: UI can be bypassed or replaced.

### 2. Native-First Implementation
The system MUST use Kotlin and Android SDK APIs directly. Cross-platform
frameworks (React Native, Flutter, etc.) are prohibited for enforcement logic.
Rationale: OS-level control depends on native APIs and lifecycle guarantees.

### 3. Incremental Enforcement
Enforcement MUST progress in explicit phases: UI + triggers, kiosk (LockTask),
Device Owner + policy control, and reset resistance. Each phase requires
documented exit criteria and real-device validation before advancing. Rationale:
incremental rollout reduces risk and isolates failures.

### 4. Real-Device Validation
Every enforcement feature MUST be tested on physical devices. Emulator testing
is allowed only as a supplement. Test records MUST include device model, OEM,
and Android version. Rationale: OEM and policy behaviors diverge from emulators.

### 5. OEM-Aware Design
Behavior MUST be validated across target OEMs (e.g., Samsung vs others). The
team MUST maintain an OEM compatibility matrix and document deviations and
mitigations. Rationale: OEM policy stacks can override or alter enforcement.

### 6. Failure-Aware Design
Design MUST assume users will attempt factory reset, uninstall, and bypass.
Each release MUST include mitigations and tests for these scenarios, with
documented residual risks. Rationale: adversarial behavior is expected.

### 7. Minimal Abstraction
Prefer direct implementation over heavyweight architecture patterns. Introduce
abstractions only when they reduce complexity with a written justification.
Rationale: early overengineering slows enforcement iteration.

### 8. Security Realism
Absolute enforcement is impossible within Android constraints. Claims MUST be
bounded to realistic deterrence and friction. Rationale: overpromising breaks
trust and may violate platform policies.

### 9. Offline-First Enforcement
Core locking MUST function without internet connectivity. Server checks only
enhance control; they MUST NOT be required for basic enforcement. Rationale:
offline conditions are common and exploitable.

### 10. Controlled Scope Expansion
New enforcement layers or features MUST be added only after the prior layer is
validated on real devices with documented stability. Rationale: proven control
beats breadth.

## Technical Constraints

- Language: Kotlin only
- Platform: Native Android only; no cross-platform runtimes
- Min SDK: 26+
- Target SDK: latest stable
- IDE: Android Studio
- Debugging: real devices (USB or wireless) as primary
- No undocumented APIs or security model bypasses
- No malware-like behavior; removal is allowed when Device Owner is not active

## Development Workflow

- Every feature spec MUST state the enforcement phase and exit criteria.
- Each feature MUST include a real-device validation plan and OEM matrix.
- Each feature MUST define offline behavior and failure/bypass tests.
- Scope expansion requires completion and validation of the prior phase.
- Deviations from principles require explicit written justification.

## Governance

- This constitution supersedes all other guidance for this project.
- Amendments require a documented change rationale, impact analysis, and an
	explicit version bump following semantic versioning.
- All plans, specs, and task lists MUST include a constitution compliance
	check; deviations require approval and documentation.
- Compliance is reviewed at each phase gate and before release candidates.

**Version**: 1.0.0 | **Ratified**: 2026-04-29 | **Last Amended**: 2026-04-29
