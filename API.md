# New Backend Endpoints

## Teacher library: topics & grammar (`/api/v1/topics`, `/api/v1/grammar-topics`)

Each teacher owns their library; it starts empty. Writes are **teacher only**; students
can read their teachers' topics / grammar topics; admins read all.

```
GET    /api/v1/topics                    -> [Topic]   flat list, build the tree from parentId
POST   /api/v1/topics                    Body: { name, parentId?, levels?: [A1..C2] }
PATCH  /api/v1/topics/{id}               Body: { name?, parentId?, moveToRoot?: bool, levels? }
DELETE /api/v1/topics/{id}               409 TOPIC_HAS_CHILDREN if it has sub-topics
```
Topic: `{ id, teacherId, parentId?, name, levels: [Level], createdAt }`. Sibling names are
unique case-insensitively (409 `TOPIC_DUPLICATE`); moving under a descendant is 400 `TOPIC_CYCLE`.

```
GET    /api/v1/grammar-topics?level=B1   ordered by level, then name
POST   /api/v1/grammar-topics            Body: { name, level?, category?, explanation?, examples: [string] }
GET    /api/v1/grammar-topics/{id}
PUT    /api/v1/grammar-topics/{id}       full replace, same body
DELETE /api/v1/grammar-topics/{id}
```

## Groups / clubs (`/api/v1/groups`)

Teacher only (admins see all; students 403). Members must be the teacher's students
(400 `STUDENT_NOT_LINKED`).

```
GET    /api/v1/groups
POST   /api/v1/groups                     Body: { name, level?, type: SPEECH|READING, studentIds?: [] }
GET    /api/v1/groups/{id}
PUT    /api/v1/groups/{id}                Body: { name, level?, type }   (members untouched)
DELETE /api/v1/groups/{id}                lessons keep existing, groupId cleared
PUT    /api/v1/groups/{id}/members        Body: { studentIds }  replace
POST   /api/v1/groups/{id}/members        Body: { studentIds }  add (existing ignored)
DELETE /api/v1/groups/{id}/members/{studentId}
```
Group: `{ id, teacherId, name, level?, type, members: [{ id, firstName, lastName, level? }], createdAt }`.
`POST /api/v1/lessons` accepts an optional `groupId` for club lessons (open self-join is unchanged).

## Vocabulary

Words live in a **per-teacher library** of `VocabEntry`s. Entries are deduplicated within a
teacher's library on `lower(lemma) + article + wordType` ("das Essen" NOUN and "essen" VERB are
different entries). Students get words through `StudentVocab` rows (status + FSRS scheduling columns).

### Library entries (`/api/v1/vocab-entries`, teacher only; admins can list, edit and delete)

```
GET    /api/v1/vocab-entries?q=&topicId=&level=&wordType=&page=&pageSize=  -> { entries, total, page, pageSize }
GET    /api/v1/vocab-entries/lookup?lemma=&article=&wordType=              -> [VocabEntry]  (exact, case-insensitive)
POST   /api/v1/vocab-entries            Body: VocabEntryInput   409 VOCAB_ENTRY_DUPLICATE
GET    /api/v1/vocab-entries/{id}
PUT    /api/v1/vocab-entries/{id}       Body: VocabEntryInput   full replace
DELETE /api/v1/vocab-entries/{id}       also removes the word from every student
POST   /api/v1/vocab-entries/{id}/assign  Body: { studentIds?: [], groupId?, lessonId? } -> { assigned, skipped }
```

VocabEntryInput:
```json
{
  "lemma": "Wort", "article": "DER | DIE | DAS | null (nouns only)", "plural": "Wörter",
  "wordType": "NOUN | VERB | ADJECTIVE | ADVERB | PREPOSITION | CONJUNCTION | PRONOUN | PHRASE | OTHER",
  "forms": "ist geblieben", "government": "sich kümmern um + Akk.",
  "translations": { "ru": "слово", "en": "word" },
  "explanationDe": "…", "exampleSentence": "…", "level": "A2",
  "topicIds": ["uuid"], "synonyms": ["Begriff"], "sourceLessonId": "uuid | null"
}
```
`VocabEntry` = input + `id, teacherId, createdAt, updatedAt`. Lemma case is kept as written.
`translations` is keyed by language code; supported codes are `ru`, `en`
(`vocabulary/service/VocabDisplay.kt`; others -> 400 `VOCAB_LANGUAGE_UNSUPPORTED`).

**Assign targets**: `studentIds` ∪ members of `groupId`; if both are empty and `lessonId` is set,
the lesson's confirmed students. With `lessonId`, the entry is also linked to the lesson's words.
Students who already have the word are counted in `skipped`.

### Student vocabulary (`/api/v1/vocabulary`)

```
GET /api/v1/vocabulary?studentId=&status=&topicId=&level=&lessonId=&q=&page=&pageSize=
    -> { words: [StudentVocab], total, page, pageSize }
```
- **Student** -> own words; **Teacher** -> their students' words; **Admin** -> all
- `status`: NEW | LEARNING | REVIEW | LEARNED; `lessonId` = lesson in which the student got the word

```
POST /api/v1/vocabulary   (teacher quick-add)
Body: { entry: VocabEntryInput, studentIds?: [], groupId?, lessonId? }
-> 201 { entry: VocabEntry, reused: bool, assigned, skipped }
```
Finds the entry by dedupe key or creates it (`sourceLessonId` defaults to `lessonId`), links it
to the lesson and assigns it (same target rules as `/assign`).

```
GET    /api/v1/vocabulary/{id}
PUT    /api/v1/vocabulary/{id}          Body: { status }
DELETE /api/v1/vocabulary/{id}          removes the word from the student (library entry stays)
POST   /api/v1/vocabulary/{id}/review
```
`{id}` is the student-vocab id. Review (until FSRS lands): `reps+1`, interval `2^reps` days (max 64)
into `due`/`scheduledDays`, `lastReview = now`; status LEARNING (<3 reps) -> REVIEW (3–4) -> LEARNED (5+).

StudentVocab:
```json
{
  "id": "uuid", "studentId": "uuid", "entryId": "uuid",
  "lemma": "Wort", "article": "DAS", "plural": "Wörter", "wordType": "NOUN",
  "forms": null, "government": null, "exampleSentence": "…", "level": "A2",
  "topicIds": [], "synonyms": [], "lessonId": "uuid | null",
  "status": "NEW", "due": "ISO8601 | null", "reps": 0, "lapses": 0, "lastReview": null, "addedAt": "ISO8601",
  "display": { "fields": ["de_explanation"], "allowTranslationToggle": true },
  "translations": {},
  "explanationDe": "…",
  "revealTranslations": { "ru": "слово", "en": "word" }
}
```

### Display setting (translation / explanation)

`fields` ⊆ `ru | en | de_explanation` (combinable) + `allowTranslationToggle`.
Resolution per word: **lesson override** (the lesson the student got it in) > **teacher–student
setting** > **level default** (no level / A1–A2: `["ru"]`, toggle off; B1+: `["de_explanation"]`,
toggle on).

For **students**, `translations` / `explanationDe` only contain allowed fields; when the toggle is
allowed, the hidden translations are in `revealTranslations` (client shows them on tap), otherwise
it is null. Teachers/admins always get every field.

```
GET    /api/v1/students/{studentId}/vocab-settings?teacherId=   (teacher, the student, admin)
PUT    /api/v1/students/{studentId}/vocab-settings   Body: { fields, allowTranslationToggle }   teacher only
DELETE /api/v1/students/{studentId}/vocab-settings   back to the level default                  teacher only
PUT    /api/v1/students/{studentId}/level            Body: { level }                            teacher only
-> { studentId, teacherId, level?, fields, allowTranslationToggle, isDefault }
```
Unknown fields -> 400 `VOCAB_DISPLAY_FIELD_UNSUPPORTED`. `teacherId` is only needed by a student/admin
when the student has several teachers (defaults to the earliest).

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

### New: Teacher in lesson responses
Every `LessonResponse` (list, detail, and the lesson returned by mutations) now includes
`teacher: { id, firstName, lastName }` next to the existing `teacherId`.

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

### New: Lesson content (v2)
`LessonResponse` also includes:
```json
{
  "groupId": "uuid | null",
  "rawNotes": "string | null (teacher/admin only; null for students)",
  "promptUsed": "string | null (teacher/admin only; null for students)",
  "topics": [{ "id": "uuid", "name": "Haushalt" }],
  "grammarTopics": [{ "id": "uuid", "name": "Perfekt", "level": "A2" }],
  "vocab": [{ "id": "entry uuid", "lemma": "Wäsche", "article": "DIE", "plural": null, "wordType": "NOUN" }],
  "correctedSentences": [{ "id": "uuid", "incorrect": "Ich habe geblieben", "correct": "Ich bin geblieben" }],
  "vocabDisplayOverride": { "fields": ["en"], "allowTranslationToggle": false } | null
}
```

```
PATCH /api/v1/lessons/{id}/content
Body: { rawNotes?, promptUsed?, groupId?, clearGroup?: bool, topicIds?, grammarTopicIds?,
        vocabEntryIds?, correctedSentences?: [{ incorrect, correct }] }
```
Lesson teacher or admin. `null` = unchanged; lists replace (`[]` clears). Ids must belong to the
lesson teacher's library (404 `TOPIC_NOT_FOUND` / `GRAMMAR_TOPIC_NOT_FOUND` / `VOCAB_ENTRY_NOT_FOUND`).

```
PUT    /api/v1/lessons/{id}/vocab-display   Body: { fields, allowTranslationToggle }
DELETE /api/v1/lessons/{id}/vocab-display
```
Per-lesson override of the vocab display setting for words students received in this lesson.

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
PUT    /api/v1/materials/{id}    Body: { name?, folderId?, level?, skill?, topicIds?, grammarTopicIds? }
DELETE /api/v1/materials/{id}
```
`topicIds` / `grammarTopicIds` replace the material's tags (`[]` clears); `GET /api/v1/materials`
filters by `topicId` and `grammarTopicId`. PUT/DELETE: owning teacher or admin only. DELETE also removes the stored object. GET `/file` streams the stored object for non-LINK materials (same access rules as GET by id).

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
  "topicIds": ["uuid"],
  "grammarTopicIds": ["uuid"],
  "createdAt": "ISO8601"
}
```

---

## Error responses

Every error body is a ProblemDetail with a stable machine-readable `code`
(UPPER_SNAKE). Clients translate by `code`; `detail` is English debug text.

```json
{ "type": "about:blank", "title": "Not Found", "status": 404, "detail": "Lesson not found", "code": "LESSON_NOT_FOUND" }
```

Generic fallbacks (used when no specific code applies): `BAD_REQUEST`, `VALIDATION_FAILED`,
`MALFORMED_REQUEST`, `UNAUTHORIZED`, `FORBIDDEN`, `NOT_FOUND`, `CONFLICT`, `INTERNAL_ERROR`.

| Area | Codes |
|---|---|
| Auth | `INVALID_CREDENTIALS`, `ACCOUNT_LOCKED`, `INVALID_REFRESH_TOKEN`, `REFRESH_TOKEN_EXPIRED` |
| Telegram | `TELEGRAM_NOT_LINKED`, `TELEGRAM_ALREADY_LINKED`, `TELEGRAM_NOT_CONFIGURED`, `TELEGRAM_AUTH_INVALID`, `TELEGRAM_AUTH_EXPIRED` |
| Users | `USER_NOT_FOUND`, `TEACHER_NOT_FOUND`, `EMAIL_ALREADY_EXISTS`, `UNSUPPORTED_LOCALE`, `AVATAR_INVALID`, `AVATAR_NOT_FOUND`, `ADMIN_SELF_ACTION` |
| Registration / reset | `INVITATION_ALREADY_PENDING`, `REGISTRATION_LINK_INVALID`, `REGISTRATION_LINK_EXPIRED`, `REGISTRATION_LINK_USED`, `RESET_TOKEN_INVALID`, `RESET_TOKEN_EXPIRED`, `RESET_TOKEN_USED` |
| Lessons | `LESSON_NOT_FOUND`, `LESSON_INVALID_STATE`, `LESSON_FULL`, `LESSON_NOT_JOINABLE`, `LESSON_ALREADY_STARTED`, `LESSON_NOT_STARTED`, `ALREADY_PARTICIPANT`, `STUDENT_NOT_IN_LESSON`, `JOIN_REQUEST_ALREADY_PENDING`, `JOIN_REQUEST_NOT_FOUND`, `RESCHEDULE_ALREADY_PENDING`, `RESCHEDULE_NOT_FOUND`, `VIDEO_ROOM_NOT_READY` |
| Availability | `AVAILABILITY_SLOT_BLOCKED`, `AVAILABILITY_MIN_NOTICE`, `AVAILABILITY_OVERLAP`, `AVAILABILITY_BUFFER_CONFLICT`, `AVAILABILITY_EXCEPTION_NOT_FOUND` |
| Materials | `MATERIAL_NOT_FOUND`, `MATERIAL_FILE_NOT_FOUND`, `FOLDER_NOT_FOUND`, `TARGET_FOLDER_NOT_FOUND`, `FOLDER_NOT_EMPTY`, `FOLDER_NAME_TAKEN`, `FOLDER_CYCLE`, `FILE_TOO_LARGE` |
| Homework / goals | `HOMEWORK_NOT_FOUND`, `HOMEWORK_INVALID_STATE`, `GOAL_NOT_FOUND`, `GOAL_NOT_ACTIVE` |
| Vocabulary | `VOCABULARY_WORD_NOT_FOUND`, `VOCAB_ENTRY_NOT_FOUND`, `VOCAB_ENTRY_DUPLICATE`, `VOCAB_LANGUAGE_UNSUPPORTED`, `VOCAB_DISPLAY_FIELD_UNSUPPORTED`, `TEACHER_STUDENT_NOT_FOUND` |
| Library / groups | `TOPIC_NOT_FOUND`, `TOPIC_DUPLICATE`, `TOPIC_HAS_CHILDREN`, `TOPIC_CYCLE`, `GRAMMAR_TOPIC_NOT_FOUND`, `GRAMMAR_TOPIC_DUPLICATE`, `GROUP_NOT_FOUND`, `STUDENT_NOT_LINKED` |

---

## User locale

`UserResponse.locale` is the user's interface language (`en` | `de`, default `en`).
Set it with `PATCH /api/v1/users/me { "locale": "de" }` (admins: `PATCH /api/v1/admin/users/{id}`).
Unsupported values return 400 `UNSUPPORTED_LOCALE`. The supported list lives in
`user/data/SupportedLocale.kt`; adding a language is a one-line change there.
