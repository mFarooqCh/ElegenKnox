<!--
Sync Impact Report
- Version change: 1.0.0 -> 2.0.0
- Modified principles: Android Device Control System Constitution -> Cashbook Constitution
- Added sections: Purpose, Governance
- Removed sections: None; previous device-enforcement guidance was replaced by cashbook-specific principles
- Templates requiring updates: .specify/templates/plan-template.md (updated), .specify/templates/spec-template.md (updated), .specify/templates/tasks-template.md (updated)
- Follow-up TODOs: None
-->
# Constitution

## Purpose
Build a reliable offline-first Android cashbook application for recording income and expenses in PKR with automatic cloud backup.

## Core Principles

### 1. Offline First
Application MUST work fully without internet. All user operations MUST be performed against the local Room database, and network access MUST be used only for synchronization.

### 2. Data Integrity
No transaction MAY be lost. Every successful save MUST persist locally before any cloud sync, and failed syncs MUST retry automatically.

### 3. User Data Ownership
Users MUST own their financial data. Data MUST be recoverable after device loss through authenticated cloud backup, and future export/import support MUST be planned for.

### 4. Financial Accuracy
Monetary values MUST be stored as integer paisa or BigDecimal, never float or double. Net Balance MUST be calculated as Total Income minus Total Expense, and all calculations MUST be deterministic.

### 5. Simplicity
The UI MUST be fast, interactions MUST remain minimal, and the app MUST avoid unnecessary permissions.

### 6. Security
Users MUST authenticate before cloud synchronization. Firestore security rules MUST prevent access to other users' data, and sensitive data MUST never be logged.

### 7. Maintainability
The application MUST follow MVVM architecture with a repository pattern. Room MUST be used for local storage, and Firebase Authentication, Firestore, and WorkManager MUST be used for cloud sync and background synchronization.

### 8. Performance
UI interactions MUST remain under 100ms where possible. Database queries MUST be indexed, and unnecessary Firestore reads MUST be avoided.

### 9. Testing
Business logic MUST be covered by unit tests, and balance calculations MUST be verified with automated tests.

## Governance

This constitution governs the ElegenCashBook cashbook project and supersedes conflicting guidance for this product area. Amendments MUST include a documented rationale, impact analysis, and a semantic version bump. All plans, specs, and task lists MUST include a constitution compliance check, and any deviations MUST be approved and documented. Compliance MUST be reviewed at implementation milestones and before release candidates.

**Version**: 1.0.0 | **Ratified**: 2026-07-01 | **Last Amended**: 2026-07-01
