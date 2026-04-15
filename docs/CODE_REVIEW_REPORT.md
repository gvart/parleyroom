# Parley Room — Comprehensive Code Review Report

**Date:** 2026-04-14  
**Reviewed by:** Automated Code Review Agents (Quality, Bugs, Missing Features)

---

## Executive Summary

Three specialized review agents analyzed the entire Parley Room codebase — a Kotlin/Ktor backend for managing language tutoring. The review covered **all source files**, SQL migrations, configuration, tests, and API documentation.

| Review Area | Findings |
|---|---|
| Code Quality / Consistency / Simplification | 26 issues |
| Bugs | 30 issues (3 critical, 7 high, 10 medium, 10 low) |
| Missing Features | 38 gaps (15 P0, 17 P1, 9 P2) |
| **Total** | **94 findings** |

### Top 10 Most Critical Items

| # | Finding | Type | Severity |
|---|---------|------|----------|
| 3 | Teacher `getWords()` returns ALL vocabulary words (authorization bypass) | Bug | 🔴 CRITICAL |
| 4 | Registration doesn't create teacher-student relationship (core workflow broken) | Bug | 🟠 HIGH |
| 5 | Race condition in refresh token rotation (TOCTOU) | Bug | 🟠 HIGH |
| 7 | No CORS configuration | Missing | P0 |
| 9 | `LessonService` is 864-line God class with N+1 queries | Quality | 🔴 HIGH |
| 10 | No password complexity requirements | Missing | P0 |

---

## Implementation Status

**Implementation Date:** 2026-04-14  
**Total Items Fixed:** 22 bugs + quality issues across 30+ files  
**New Tests Added:** 40+ integration tests across 8 test files

### ✅ Implemented Fixes

#### Agent 1: Registration Security (7 fixes)
| ID | Fix | Files Changed |
|---|---|---|
| BUG-01 🔴 | Registration tokens hashed with SHA-256 before storage | `RegistrationService.kt`, `test-data.sql` |
| BUG-05 🟠 | Duplicate pending invitation check added | `RegistrationService.kt` |
| BUG-06 🟠 | Teacher-student relationship auto-created on registration | `RegistrationService.kt` |
| BUG-11 🟡 | Name trimming before initials computation | `RegistrationService.kt` |
| BUG-16 🟡 | Email format validation on invite | `InviteUserRequest.kt` |
| BUG-18 🟡 | Old password reset tokens invalidated | `PasswordResetService.kt` |
| Q-18 🟢 | Redundant conditional simplified | `HomeworkService.kt` |

#### Agent 2: Authentication Security (5 fixes)
| ID | Fix | Files Changed |
|---|---|---|
| BUG-02 🔴 | Warning logs for default JWT secret and admin password | `GeneralServerConfig.kt`, `AdminUserInitializer.kt` |
| BUG-04 🟠 | Refresh token rotation consolidated into single transaction | `AuthenticationService.kt` |
| BUG-09 🟠 | Password reset invalidates all refresh tokens | `PasswordResetService.kt` |
| BUG-12 🟡 | Admin email made configurable via application.conf | `AdminUserInitializer.kt`, `application.conf` |
| MF-10 P0 | Logout endpoint added (DELETE /api/v1/token) | `AuthenticationService.kt`, `Routing.kt`, `LogoutRequest.kt` |

#### Agent 3: Authorization Fixes (2 fixes + 17 tests)
| ID | Fix | Files Changed |
|---|---|---|
| BUG-03 🔴 | Teacher vocabulary query filtered by TeacherStudentTable | `VocabularyService.kt` |
| BUG-07 🟠 | UserService teacher query uses TeacherStudentTable instead of RegistrationTable | `UserService.kt` |
| MF-13 P0 | 17 vocabulary integration tests added | `VocabularyIntegrationTest.kt` (new) |

#### Agent 4: Validation Improvements (7 fixes)
| ID | Fix | Files Changed |
|---|---|---|
| BUG-08 🟠 | Password minimum 8 characters | `RegisterUserRequest.kt`, `ResetPasswordRequest.kt` |
| BUG-20 🟡 | Defense-in-depth status check in reviewHomework() | `HomeworkService.kt` |
| BUG-21 🟢 | SubmitHomeworkRequest requires submission content | `SubmitHomeworkRequest.kt` |
| BUG-22 🟢 | Missing request types registered for validation | `GeneralServerConfig.kt` |
| BUG-26 🟢 | ReflectLessonRequest requires at least one field | `ReflectLessonRequest.kt` |
| BUG-28 🟢 | MarkViewedRequest requires non-empty list | `MarkViewedRequest.kt` |
| BUG-30 🟢 | CreateLessonRequest validates future date | `CreateLessonRequest.kt` |

#### Agent 5: Infrastructure Fixes (3 fixes)
| ID | Fix | Files Changed |
|---|---|---|
| BUG-15 🟡 | SSE unsubscribe uses referential equality | `NotificationSseManager.kt`, `Routing.kt` |
| Q-06 🟡 | StorageService implements Closeable with shutdown hook | `StorageService.kt`, `MaterialModuleConfig.kt` |
| Q-14 🟡 | NotificationSseManager shutdown cleanup added | `NotificationSseManager.kt`, `NotificationModuleConfig.kt` |

#### Self-Review Fixes (4 fixes)
| Fix | Files Changed |
|---|---|
| Test status code mismatch (422→400) for email validation tests | `RegistrationIntegrationTest.kt` |
| Removed unnecessary `!!` operator | `RegistrationService.kt` |
| Removed unused imports | `AuthenticationIntegrationTest.kt`, `PasswordResetIntegrationTest.kt` |

### 🔲 Remaining Items (Not Yet Implemented)

#### Critical/High Priority
- Q-01: Split `LessonService` God class (864 lines)
- Q-02: Fix N+1 queries in `LessonService.toResponse()`
- BUG-10: Standardize `updatedAt` handling
- BUG-19: Validate student IDs exist in `createLesson()`
- BUG-25: Move seed migration to test-only

#### Missing Features (P0)
- MF-01: Rate limiting on auth endpoints
- MF-02: CORS configuration
- MF-03: Account lockout after failed logins
- MF-05: HTTPS enforcement
- MF-07: User profile update endpoint
- MF-08: Email delivery system
- MF-09: Pagination on list endpoints
- MF-12: Database backup strategy
- MF-14: Material integration tests
- MF-15: Authorization bypass tests

#### Missing Features (P1)
- MF-16 through MF-32 (see Part 3 for full list)

#### Missing Features (P2)
- MF-33 through MF-41 (see Part 3 for full list)

---

## Part 1: Code Quality, Consistency & Simplification

### 🔴 HIGH Severity (2)

#### Q-01: `LessonService` is a God Class (864 lines)
- **File:** [`LessonService.kt`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:1)
- 15+ public methods handling lesson CRUD, join requests, reschedule workflow, start/complete lifecycle, document sync, video access, and notification dispatch
- **Fix:** Extract into `LessonLifecycleService`, `LessonRescheduleService`, `LessonParticipantService`, `LessonDocumentService`

#### Q-02: N+1 queries in `LessonService.toResponse()`
- **File:** [`LessonService.toResponse()`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:803)
- For every lesson, 3 additional queries (students, document, pending reschedule). 50 lessons = 150+ extra queries
- **Fix:** Batch-load related data for all lesson IDs in the result set

### 🟡 MEDIUM Severity (13)

#### Q-03: Repeated "find-then-re-select" pattern
- **Files:** [`LessonService.createLesson()`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:161), [`HomeworkService.createHomework()`](../src/com/gvart/parleyroom/homework/service/HomeworkService.kt:86), [`GoalService.createGoal()`](../src/com/gvart/parleyroom/goal/service/GoalService.kt:85), [`VocabularyService.createWord()`](../src/com/gvart/parleyroom/vocabulary/service/VocabularyService.kt:85)
- After inserting, re-selects the same row to build response — doubles DB round-trips

#### Q-04: `RegistrationTable.email` lacks unique index
- **File:** [`RegistrationTable.kt`](../src/com/gvart/parleyroom/registration/data/RegistrationTable.kt:11)
- Allows multiple pending invitations for the same email

#### Q-05: `createdAt` set manually despite DB default
- **Files:** [`RegistrationService`](../src/com/gvart/parleyroom/registration/service/RegistrationService.kt:78), [`AdminUserInitializer`](../src/com/gvart/parleyroom/registration/initializer/AdminUserInitializer.kt:28)
- Dual source of truth between Kotlin `OffsetDateTime.now()` and DB `DEFAULT now()`

#### Q-06: S3 clients never closed
- **File:** [`StorageService`](../src/com/gvart/parleyroom/common/storage/StorageService.kt:39)
- `S3Client` and `S3Presigner` hold HTTP connection pools but are never closed

#### Q-07: Inconsistent module configuration pattern
- Registration module combines DI, initialization, and routing in one function instead of following the `config/XxxModuleConfig.kt` pattern

#### Q-08: Inconsistent date/time serialization
- All date/time fields are `String` types in DTOs, parsed with `OffsetDateTime.parse()` — no type safety

#### Q-09: Inconsistent `updatedAt` handling
- Some services set it manually, others rely on DB trigger — mixed approach

#### Q-10: Inconsistent authorization patterns
- Each module implements its own authorization checks differently instead of using a shared `AuthorizationHelper`

#### Q-11: Duplicated validation boilerplate
- Every request DTO has the same `validate()` pattern; all registered individually in `GeneralServerConfig`

#### Q-12: Duplicated `toResponse()` mapping
- Every service has a private `toResponse(row: ResultRow)` with verbose manual field mapping

#### Q-13: `GeneralServerConfig.kt` is a monolith (182 lines)
- **File:** [`GeneralServerConfig.kt`](../src/com/gvart/parleyroom/config/GeneralServerConfig.kt:1)
- Configures serialization, logging, validation for ALL modules, error handling, JWT auth, and OpenAPI in one file

#### Q-14: `NotificationSseManager` potential memory leak
- **File:** [`NotificationSseManager.kt`](../src/com/gvart/parleyroom/notification/service/NotificationSseManager.kt:10)
- No periodic cleanup or TTL for disconnected SSE flows

#### Q-15: `VocabularyService.getWords()` for TEACHER returns ALL words
- **File:** [`VocabularyService.getWords()`](../src/com/gvart/parleyroom/vocabulary/service/VocabularyService.kt:34)
- No WHERE clause when teacher has no `studentId` filter — returns entire vocabulary table

### 🟢 LOW Severity (11)
- Q-18: Redundant conditional in [`HomeworkService.createHomework()`](../src/com/gvart/parleyroom/homework/service/HomeworkService.kt:66) — both branches return `principal.id`
- Q-19: Inconsistent timestamp naming (`createdAt` vs `addedAt` vs `startedAt`)
- Q-20: Inconsistent response wrapping (some lists wrapped, most bare arrays)
- Q-21: Unused import in [`RegistrationTable.kt`](../src/com/gvart/parleyroom/registration/data/RegistrationTable.kt:7)
- Q-22: `LessonWordTable` and `LessonCorrectionTable` defined but never used
- Q-23: Flyway skipped when `existingDataSource` provided
- Q-24: Unnecessary nested transactions in [`PasswordResetService`](../src/com/gvart/parleyroom/registration/service/PasswordResetService.kt:28)
- Q-25: Multiple separate transactions in [`AuthenticationService.refresh()`](../src/com/gvart/parleyroom/user/service/AuthenticationService.kt:45)
- Q-26: JSON metadata built via string interpolation in [`VideoTokenService`](../src/com/gvart/parleyroom/video/service/VideoTokenService.kt:38)

---

## Part 2: Bug Report

### 🔴 CRITICAL (3)

#### BUG-03: Teacher `getWords()` Returns ALL Vocabulary Words
- **File:** [`VocabularyService.getWords()`](../src/com/gvart/parleyroom/vocabulary/service/VocabularyService.kt:34)
- Authorization bypass — teachers see all students' vocabulary, not just their own students'
- **Fix:** Filter by `TeacherStudentTable` relationship

### 🟠 HIGH (7)

#### BUG-04: Race Condition in Refresh Token Rotation
- **File:** [`AuthenticationService.refresh()`](../src/com/gvart/parleyroom/user/service/AuthenticationService.kt:45)
- Three separate transactions create TOCTOU vulnerability — concurrent requests can both succeed
- **Fix:** Single transaction with `SELECT ... FOR UPDATE`

#### BUG-05: No Check for Existing Pending Invitations
- **File:** [`RegistrationService.inviteUser()`](../src/com/gvart/parleyroom/registration/service/RegistrationService.kt:37)
- Multiple invitations for same email with different roles possible
- **Fix:** Check `RegistrationTable` for existing unused entries

#### BUG-06: Registration Doesn't Create Teacher-Student Relationship
- **File:** [`RegistrationService.registerUser()`](../src/com/gvart/parleyroom/registration/service/RegistrationService.kt:57)
- After student registers via teacher invitation, teacher cannot access student's data (403)
- **Fix:** Create `TeacherStudentTable` entry using `invitedBy` field

#### BUG-07: `UserService.findAllUsers()` Uses Wrong Join for Teachers
- **File:** [`UserService.findAllUsers()`](../src/com/gvart/parleyroom/user/service/UserService.kt:23)
- Joins via `RegistrationTable` instead of `TeacherStudentTable` — misses directly-added students
- **Fix:** Join via `TeacherStudentTable` for TEACHER role

#### BUG-08: No Password Strength Validation
- **File:** [`RegisterUserRequest.validate()`](../src/com/gvart/parleyroom/registration/transfer/RegisterUserRequest.kt:20)
- Single-character passwords accepted
- **Fix:** Minimum 8 characters + complexity requirements


#### BUG-10: `HomeworkService.updateHomework()` Doesn't Set `updatedAt`
- **File:** [`HomeworkService.updateHomework()`](../src/com/gvart/parleyroom/homework/service/HomeworkService.kt:100)
- Relies on DB trigger — fragile if trigger is removed

### 🟡 MEDIUM (10)

| # | Bug | File |
|---|-----|------|
| BUG-11 | Initials crash on whitespace-padded names | [`RegistrationService`](../src/com/gvart/parleyroom/registration/service/RegistrationService.kt:77) |
| BUG-13 | `GoalService` update methods don't set `updatedAt` | [`GoalService`](../src/com/gvart/parleyroom/goal/service/GoalService.kt:98) |
| BUG-14 | `acceptReschedule()` doesn't set `updatedAt` on lesson | [`LessonService`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:440) |
| BUG-15 | SSE `unsubscribe()` comparison fails — memory leak | [`NotificationSseManager`](../src/com/gvart/parleyroom/notification/service/NotificationSseManager.kt:20) |
| BUG-16 | No email format validation on invite | [`InviteUserRequest`](../src/com/gvart/parleyroom/registration/transfer/InviteUserRequest.kt:12) |
| BUG-17 | `requestResetForSelf()` doesn't verify user exists | [`PasswordResetService`](../src/com/gvart/parleyroom/registration/service/PasswordResetService.kt:23) |
| BUG-18 | Multiple active reset tokens per user allowed | [`PasswordResetService`](../src/com/gvart/parleyroom/registration/service/PasswordResetService.kt:37) |
| BUG-19 | `createLesson()` doesn't validate student IDs exist | [`LessonService`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:85) |
| BUG-20 | `reviewHomework()` lacks defense-in-depth status check | [`HomeworkService`](../src/com/gvart/parleyroom/homework/service/HomeworkService.kt:146) |

### 🟢 LOW (10)

| # | Bug | File |
|---|-----|------|
| BUG-21 | `SubmitHomeworkRequest` allows empty submission | [`SubmitHomeworkRequest`](../src/com/gvart/parleyroom/homework/transfer/SubmitHomeworkRequest.kt:6) |
| BUG-22 | Several request types not registered for validation | [`GeneralServerConfig`](../src/com/gvart/parleyroom/config/GeneralServerConfig.kt:93) |
| BUG-23 | N+1 queries in `toResponse()` | [`LessonService`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:803) |
| BUG-24 | `AuthorizationHelper` not wrapped in transaction | [`AuthorizationHelper`](../src/com/gvart/parleyroom/common/service/AuthorizationHelper.kt:13) |
| BUG-26 | `ReflectLessonRequest` allows both fields null | [`ReflectLessonRequest`](../src/com/gvart/parleyroom/lesson/transfer/ReflectLessonRequest.kt:6) |
| BUG-27 | `CompleteLessonRequest` allows all fields null | [`CompleteLessonRequest`](../src/com/gvart/parleyroom/lesson/transfer/CompleteLessonRequest.kt:6) |
| BUG-28 | `MarkViewedRequest.notificationIds` not validated | [`MarkViewedRequest`](../src/com/gvart/parleyroom/notification/transfer/MarkViewedRequest.kt:6) |
| BUG-29 | `joinLesson()` doesn't check student role | [`LessonService`](../src/com/gvart/parleyroom/lesson/service/LessonService.kt:258) |
| BUG-30 | `CreateLessonRequest` doesn't validate future date | [`CreateLessonRequest`](../src/com/gvart/parleyroom/lesson/transfer/CreateLessonRequest.kt:20) |

---

## Part 3: Missing Features

### P0 — Must-Have for Production (15)

#### Security (6)
| # | Feature | Impact |
|---|---------|--------|
| MF-04 | No password complexity requirements | Trivially weak passwords accepted |

#### Core Features (6)
| # | Feature | Impact |
|---|---------|--------|
| MF-07 | No user profile update endpoint | Users can't update name, locale after registration |
| MF-08 | No email delivery system | Invitation/reset tokens returned in API but never emailed |
| MF-09 | No pagination on most list endpoints | Unbounded result sets; OOM risk |
| MF-11 | Registration doesn't create teacher-student relationship | Core workflow broken |

#### Tests (3)
| # | Feature | Impact |
|---|---------|--------|
| MF-13 | No vocabulary integration tests | SRS logic untested |
| MF-14 | No material integration tests | S3/upload flow untested |
| MF-15 | No authorization bypass tests | Security vulnerabilities undetected |

### P1 — Should-Have (17)

| # | Feature | Category |
|---|---------|----------|
| MF-16 | Avatar upload mechanism | Documented-unimplemented |
| MF-17 | Lesson corrections/words tables unused | Documented-unimplemented |
| MF-18 | Users endpoint pagination (TODO in code) | Documented-unimplemented |
| MF-19 | Teacher-student relationship management endpoints | Documented-unimplemented |
| MF-20 | Sorting/ordering on list endpoints | Missing-core |
| MF-21 | Search/text filtering | Missing-core |
| MF-23 | Homework notification integration | Missing-core |
| MF-24 | Structured logging (JSON format) | Missing-infrastructure |
| MF-25 | Metrics/monitoring endpoint (Prometheus) | Missing-infrastructure |
| MF-26 | Configurable DB connection pool size | Missing-infrastructure |
| MF-29 | Input sanitization beyond basic validation | Missing-security |
| MF-30 | Email format validation | Missing-security |
| MF-31 | Notification integration tests | Missing-tests |
| MF-32 | Unit tests (none exist) | Missing-tests |

### P2 — Nice-to-Have (9)

| # | Feature | Category |
|---|---------|----------|
| MF-33 | `users.points` gamification column unused | Documented-unimplemented |
| MF-34 | `users.locale` no update mechanism | Documented-unimplemented |
| MF-36 | Vocabulary review reminders (scheduled job) | Missing-core |
| MF-38 | Graceful SSE shutdown | Missing-infrastructure |

---