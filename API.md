# New Backend Endpoints

## Vocabulary (`/api/v1/vocabulary`)

Student SRS (spaced repetition) flashcard system for German-English word pairs.

```
GET /api/v1/vocabulary?studentId=UUID&status=NEW|REVIEW|LEARNED
```
- **Student** -> own words only
- **Teacher** -> words for their students (use `studentId` filter)
- **Admin** -> all

```
POST /api/v1/vocabulary
Body: { studentId, german, english, category, lessonId?, exampleSentence?, exampleTranslation? }
```
- **Student** -> can only add for themselves
- **Teacher** -> can add for students in their `teacher_students` relationship
- `category`: NOUN, VERB, ADJECTIVE, ADVERB, GRAMMAR
- Unique constraint on `(studentId, german)` -> 409 on duplicate

```
GET    /api/v1/vocabulary/{id}
PUT    /api/v1/vocabulary/{id}    Body: { german?, english?, exampleSentence?, exampleTranslation?, category? }
DELETE /api/v1/vocabulary/{id}
```
Owner student, their teacher, or admin.

```
POST /api/v1/vocabulary/{id}/review
```
Marks word as reviewed:
- Increments `reviewCount`
- Status: NEW -> REVIEW (first review), REVIEW -> LEARNED (after 5 reviews)
- Sets `nextReviewAt` = now + 2^reviewCount days (capped at 64 days)

### Response shape
```json
{
  "id": "uuid",
  "studentId": "uuid",
  "lessonId": "uuid | null",
  "german": "Haus",
  "english": "house",
  "exampleSentence": "string | null",
  "exampleTranslation": "string | null",
  "category": "NOUN",
  "status": "NEW | REVIEW | LEARNED",
  "nextReviewAt": "ISO8601 | null",
  "reviewCount": 0,
  "addedAt": "ISO8601"
}
```

---

## Homework (`/api/v1/homework`)

Teacher-assigned tasks with a submission/review workflow.

Status flow: `OPEN -> SUBMITTED -> DONE` or `-> REJECTED -> SUBMITTED -> ...`

```
GET /api/v1/homework?studentId=UUID&status=OPEN|SUBMITTED|IN_REVIEW|DONE|REJECTED
```
- **Student** -> own homework
- **Teacher** -> homework they assigned
- **Admin** -> all

```
POST /api/v1/homework
Body: { studentId, title, category, description?, dueDate? (YYYY-MM-DD), lessonId?, attachmentType?, attachmentUrl?, attachmentName? }
```
- **Teacher/Admin only** -- students cannot create
- `category`: WRITING, READING, GRAMMAR, VOCABULARY, LISTENING
- `attachmentType`: FILE, LINK
- Teacher must have `teacher_students` relationship with the student

```
GET    /api/v1/homework/{id}
PUT    /api/v1/homework/{id}    Body: { title?, description?, category?, dueDate? }
DELETE /api/v1/homework/{id}
```
PUT/DELETE: assigning teacher or admin only.

```
POST /api/v1/homework/{id}/submit
Body: { submissionText?, submissionUrl? }
```
- **Assigned student only**
- Only works when status is OPEN or REJECTED (re-submit after rejection)
- Sets status -> SUBMITTED

```
POST /api/v1/homework/{id}/review
Body: { status: "DONE"|"REJECTED", teacherFeedback? }
```
- **Assigning teacher or admin only**
- Only works when status is SUBMITTED or IN_REVIEW
- REJECTED homework can be re-submitted by the student

### Response shape
```json
{
  "id": "uuid",
  "lessonId": "uuid | null",
  "studentId": "uuid",
  "teacherId": "uuid",
  "title": "Write an essay",
  "description": "string | null",
  "category": "WRITING",
  "dueDate": "2026-04-15 | null",
  "status": "OPEN | SUBMITTED | IN_REVIEW | DONE | REJECTED",
  "submissionText": "string | null",
  "submissionUrl": "string | null",
  "teacherFeedback": "string | null",
  "attachmentType": "FILE | LINK | null",
  "attachmentUrl": "string | null",
  "attachmentName": "string | null",
  "createdAt": "ISO8601",
  "updatedAt": "ISO8601"
}
```

---

## Learning Goals (`/api/v1/goals`)

Progress-tracked learning goals set by students or teachers.

Status flow: `ACTIVE -> COMPLETED` or `-> ABANDONED`

```
GET /api/v1/goals?studentId=UUID&status=ACTIVE|COMPLETED|ABANDONED
```
- **Student** -> own goals
- **Teacher** -> goals they created for their students
- **Admin** -> all

```
POST /api/v1/goals
Body: { studentId, description, targetDate? (YYYY-MM-DD) }
```
- **Student** -> creates for themselves, `setBy` = STUDENT
- **Teacher** -> creates for their students, `setBy` = TEACHER, `teacherId` auto-set
- **Admin** -> creates for anyone, `setBy` = TEACHER

```
GET    /api/v1/goals/{id}
PUT    /api/v1/goals/{id}         Body: { description?, targetDate? }
DELETE /api/v1/goals/{id}
```
Anyone with access to the student. PUT only works on ACTIVE goals.

```
PUT /api/v1/goals/{id}/progress
Body: { progress: 0-100 }
```
Updates progress percentage. Only on ACTIVE goals. Validated 0-100.

```
POST /api/v1/goals/{id}/complete
```
Sets status -> COMPLETED, progress -> 100. Only on ACTIVE goals.

```
POST /api/v1/goals/{id}/abandon
```
Sets status -> ABANDONED. Only on ACTIVE goals.

### Response shape
```json
{
  "id": "uuid",
  "studentId": "uuid",
  "teacherId": "uuid | null",
  "description": "Pass B1 exam",
  "progress": 50,
  "setBy": "TEACHER | STUDENT",
  "targetDate": "2026-06-01 | null",
  "status": "ACTIVE | COMPLETED | ABANDONED",
  "createdAt": "ISO8601",
  "updatedAt": "ISO8601"
}
```

---

## Lesson Changes

### New: Cancel endpoint
```
POST /api/v1/lessons/{id}/cancel
Body: { reason? }
```
Any participant can cancel. Works on REQUEST, CONFIRMED, or IN_PROGRESS lessons. Resolves any pending reschedule. Status -> CANCELLED.

### New: IN_PROGRESS status
Starting a lesson now sets status to `IN_PROGRESS` (was staying CONFIRMED before). Sync/complete only work on IN_PROGRESS lessons.

### New: Pending reschedule in response
`LessonResponse` now includes:
```json
{
  "pendingReschedule": {
    "newScheduledAt": "ISO8601",
    "note": "string | null",
    "requestedBy": "uuid"
  }
}
```
Null when no pending reschedule.

### New: Student can see teachers
`GET /api/v1/users` now works for students -- returns their teachers (via `teacher_students` relationship).

---

## Materials (`/api/v1/materials`)

Teacher-owned study resources stored in S3-compatible storage (MinIO locally, S3 on AWS). Clients upload bytes to the API; the server streams them to storage. Non-LINK materials expose a `downloadUrl` pointing at `GET /api/v1/materials/{id}/file`, which streams the stored bytes through the authenticated API.

Upload flow for `PDF`, `AUDIO`, `VIDEO`: `POST /api/v1/materials` as `multipart/form-data` with two parts:
- `metadata` (`application/json`): `{ name, type, studentId?, lessonId? }`
- `file` (binary): the file bytes; `Content-Type` and `Content-Length` headers required

For `LINK` materials, send only the `metadata` part with `{ name, type: "LINK", url, studentId?, lessonId? }` (no `file` part).

The `metadata` part MUST come before the `file` part — the server streams the file straight to storage and parses metadata first to authorize the request. Most HTTP clients (browser `FormData`, curl `-F`) preserve append/argument order naturally.

Max file size: 100 MiB by default (configurable via `STORAGE_MAX_FILE_SIZE`, in bytes).

```
GET /api/v1/materials?studentId=UUID&lessonId=UUID&type=PDF|AUDIO|VIDEO|LINK
```
- **Student** -> materials assigned to them or attached to a lesson they are a CONFIRMED participant of
- **Teacher** -> materials they own
- **Admin** -> all

```
POST /api/v1/materials                Content-Type: multipart/form-data
  metadata (JSON part): { name, type, studentId?, lessonId?, url? }
  file     (binary part, required for PDF/AUDIO/VIDEO; omit for LINK)
```
- **Teacher/Admin only**
- If `studentId` set, teacher must have a `teacher_students` relationship with that student
- `type`: PDF, AUDIO, VIDEO, LINK
- Returns `MaterialResponse` (see below) with status 201

Example:
```
curl -X POST http://localhost:8080/api/v1/materials \
  -H "Authorization: Bearer $TOKEN" \
  -F 'metadata={"name":"Chapter 1","type":"PDF"};type=application/json' \
  -F 'file=@chapter1.pdf;type=application/pdf'
```

```
GET    /api/v1/materials/{id}
GET    /api/v1/materials/{id}/file
PUT    /api/v1/materials/{id}    Body: { name? }
DELETE /api/v1/materials/{id}
```
PUT/DELETE: owning teacher or admin only. DELETE also removes the stored object. GET `/file` streams the stored object for non-LINK materials (same access rules as GET by id).

### Material response
```json
{
  "id": "uuid",
  "teacherId": "uuid",
  "studentId": "uuid | null",
  "lessonId": "uuid | null",
  "name": "Chapter 1",
  "type": "PDF | AUDIO | VIDEO | LINK",
  "contentType": "application/pdf | null",
  "fileSize": 12345,
  "downloadUrl": "/api/v1/materials/{id}/file for PDF/AUDIO/VIDEO, external URL for LINK, null if no file",
  "createdAt": "ISO8601"
}
```
