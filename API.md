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

**Assign targets**: `studentIds` ∪ current members of `groupId`; if both are empty and `lessonId` is set,
the lesson's confirmed attendees (`lesson_students`, e.g. a club session's participants). With `lessonId`, the entry is also linked to the lesson's words.
Students who already have the word are counted in `skipped`.

### Student vocabulary (`/api/v1/vocabulary`)

```
GET /api/v1/vocabulary?studentId=&status=&topicId=&level=&lessonId=&q=&page=&pageSize=
    -> { words: [StudentVocab], total, page, pageSize }
```
- **Student** -> own words; **Teacher** -> their students' words; **Admin** -> all
- `status`: NEW | LEARNING | REVIEW | LEARNED; `lessonId` = lesson in which the student got the word
- `level` matches the entry's single level exactly; `topicId` matches entries tagged with that exact topic (no subtree)
- `pageSize` max is **500** here (100 elsewhere) so clients can load a whole vocabulary for topic folders

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
Resolution per word: **lesson override** (applies to the student's words whose `lessonId` is that lesson) > **teacher–student
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

## Documents (`/api/v1/documents`)

A document is an ordered list of **typed blocks** owned by a teacher. Documents replace the
lesson's old free-text `sharedDocument`. Every document is part of its owner's library;
`audience` only records what it was made for. **Writes are teacher-only (owner)**; admins can
read and delete; students read documents shared with them (answer keys stripped). Another
teacher's document is 403; a student without access gets 404 `DOCUMENT_NOT_FOUND`.

### Block format

The JSON Schema (draft 2020-12) is served at `GET /api/v1/documents/schema` (source:
`resources/document-blocks.schema.json`). Every write is validated against it.

Common fields on **every** block: `id`, `type`, `interactive` (bool, default false: students
answer in the app — P6). Block, item, option and row ids are **client-generated uuids**; the server
checks the format and uniqueness within the document (400 `DOCUMENT_DUPLICATE_ID`) and never
rewrites them (except on duplicate). Homework answers reference `blockId + itemId`.

**Solutions**: everything under a `solution` key is the answer key. Teachers/admins always get it
(editing, export with solutions). It is removed from every response served to a student.

**Rich text** (`RichText`): TipTap / ProseMirror JSON, a strict subset:
`{ "type": "doc", "content": [ … ] }`. Nodes: `paragraph`, `heading` (`attrs.level` 1–3),
`bulletList`, `orderedList` (`attrs.start`, `attrs.type`), `listItem`, `blockquote`, `hardBreak`,
`text` (non-empty, as ProseMirror requires). Marks: `bold`, `italic`, `underline`, `strike`,
`highlight`, `link` (`attrs.href` must be `https://`, `http://` or `mailto:`; optional
`target: "_blank"|null`, `rel`, `title`, `class: null`). No other node, mark or attribute is
accepted (400) — configure the TipTap editor with exactly these extensions. Text is text: no HTML
is ever stored or rendered. `{ "type": "doc" }`, `content: []` or one empty paragraph are valid.
Plain strings are used for short fields (questions, options, sentences).

Gaps in `gap_fill` text are written as `___` (three underscores; a longer run is one gap);
`solution.answers[i]` lists the accepted answers for the i-th gap.

| type | fields (besides id/type/interactive) | stripped for students |
|---|---|---|
| `heading` | `text`, `level` 1–3 | – |
| `rich_text` | `content: RichText` | – |
| `vocab_table` | `title?`, `topicId?`, `rows: [{ id, vocabEntryId }]` | – (entries rendered per viewer, see below) |
| `grammar_box` | `variant: TIP\|OVERVIEW`, `title?`, `content?: RichText`, `table?: { headers: [str], rows: [[str]] }`, `examples?: [str]` | – |
| `gap_fill` | `instructions?`, `wordBox?: [str]` (cloze), `items: [{ id, text, hint?, solution?: { answers: [[str]] } }]` | `items[].solution` |
| `multiple_choice` | `instructions?`, `items: [{ id, question, multiple?: bool, options: [{ id, text }], solution?: { correctOptionIds: [id] } }]` | `items[].solution` |
| `error_correction` | `instructions?`, `items: [{ id, sentence, solution?: { corrected, explanation? } }]` | `items[].solution` |
| `free_sentences` | `instructions?`, `purpose?: SPEAKING\|SENTENCE_BUILDING\|USE_WORDS\|OTHER`, `items: [{ id, prompt, solution?: { sampleAnswer } }]` | `items[].solution` |
| `writing_task` | `instructions?`, `instructionsTranslation?: { ru?, en? }`, `items: [{ id, prompt, register?: INFORMAL\|FORMAL, points: [str], minWords?, maxWords?, solution?: { sampleAnswer } }]` | `items[].solution` |
| `reading` | `title?`, `text: RichText`, `questions: [Question]` | `questions[].solution` |
| `media` | `kind: AUDIO\|VIDEO`, at most one of `url` (https) / `materialId`, `task?: RichText`, `questions: [Question]` | `questions[].solution` |
| `exam_part` | `exam` ("telc B1"), `part` ("Lesen Teil 2"), `instructions?`, `timeMinutes?`, `content?: RichText`, `questions: [Question]` | `questions[].solution` |
| `free_form` | `instructions?`, `content: RichText`, `items: [{ id, prompt, solution?: { sampleAnswer } }]` | `items[].solution` |

`Question` = `{ id, kind: OPEN|TRUE_FALSE|CHOICE, question, options?: [{ id, text }] (CHOICE only),
solution?: { sampleAnswer (OPEN) | isTrue (TRUE_FALSE) | correctOptionIds (CHOICE) } }`.

`free_sentences.purpose` labels the exercise (speaking questions, building sentences from prompts,
"use the new words"); `SPEAKING` means no written answer is expected even when `interactive`.

#### Validation on save is structural (lenient)

Autosave sends half-finished blocks, so `POST` / `PUT` only enforce structure: known block types,
field types, no unknown fields, uuid ids unique in the document, the rich-text whitelist, `https`
media urls, limits, and library ownership of `vocabEntryId` / `materialId` / tags. Allowed while
editing:
- empty strings in every text field (heading text, question, option text, sentence, prompt, exam,
  part, writing points, answers);
- empty lists (`items`, `questions`, `options`, `rows`, `correctOptionIds`, answer lists);
- `gap_fill`: `solution.answers` may have **fewer** groups than gaps (more is 400);
- `media` with neither `url` nor `materialId` (null / `""` / absent) — but never both;
- single-choice MC items with 0 or 1 correct option (more than one needs `multiple: true`).
`correctOptionIds` must always reference options of the same item.

A **strict "complete" profile** (all required texts non-empty, ≥ 1 item per exercise, every gap
text has a gap and exactly one non-empty answer group per gap, media source set, MC / CHOICE ≥ 2
options and ≥ 1 correct) exists server-side (`DocumentBlockValidator.completenessIssues`) and is
used for AI output (P4); it is not enforced on save.

#### Vocab tables

`vocab_table` rows point at the owner's library entries (400 `DOCUMENT_INVALID_BLOCK` for a
foreign/unknown id). Every document response carries a `vocab` side-list of the referenced entries,
resolved server-side for the viewer:

`DocumentVocabEntry = { id (= vocabEntryId), lemma, article?, plural?, wordType, forms?, government?,
exampleSentence?, level?, display?, translations, explanationDe?, revealTranslations? }`

For a student, `display` is their effective setting (the document's source-lesson override >
teacher–student setting > level default) and `translations` / `explanationDe` / `revealTranslations`
follow the StudentVocab rules. Teachers/admins get every field and `display: null`. A row whose
entry was deleted later has no match in `vocab`.

#### Media materials

`media.materialId` must be one of the owner's materials. A student who can read a document may
also get and download (`GET /api/v1/materials/{id}`, `/file`) every material referenced by its
media blocks; this is checked at access time, so unsharing the document revokes it.

Examples:
```json
{ "id": "…", "type": "gap_fill", "interactive": true, "instructions": "Ergänze die Verben.",
  "wordBox": ["musst", "kümmern"],
  "items": [{ "id": "…", "text": "Darum ___ du dich ___.", "solution": { "answers": [["musst"], ["kümmern"]] } }] }
{ "id": "…", "type": "multiple_choice", "interactive": true,
  "items": [{ "id": "…", "question": "Ich ___ gestern geblieben.", "options": [{ "id": "…", "text": "habe" }, { "id": "…", "text": "bin" }],
              "solution": { "correctOptionIds": ["<id of bin>"] } }] }
{ "id": "…", "type": "vocab_table", "interactive": false, "title": "Haushalt", "rows": [{ "id": "…", "vocabEntryId": "…" }] }
{ "id": "…", "type": "rich_text", "interactive": false,
  "content": { "type": "doc", "content": [{ "type": "paragraph", "content": [{ "type": "text", "text": "Hallo", "marks": [{ "type": "bold" }] }] }] } }
```

Limits: ≤ 200 blocks, ≤ 200 items/questions/rows per block (writing tasks ≤ 20, options ≤ 12),
title ≤ 255 chars, serialized `blocks` ≤ 1 MiB (400 `DOCUMENT_TOO_LARGE`).

### Document

```json
{
  "id": "uuid", "ownerId": "uuid", "title": "Haushalt – Wortschatz",
  "level": "B1 | null", "topicIds": ["uuid"], "grammarTopicIds": ["uuid"],
  "audience": "STUDENT | GROUP | LIBRARY",
  "studentIds": ["uuid"], "groupIds": ["uuid"], "lessonIds": ["uuid"],
  "createdFromLessonId": "uuid | null",
  "revision": 7,
  "blocks": [Block], "vocab": [DocumentVocabEntry],
  "createdAt": "ISO8601", "updatedAt": "ISO8601"
}
```
For students `studentIds` / `groupIds` are `[]` (other students are not exposed) and
every `solution` is removed. `DocumentSummary` (list) = the same without `blocks`/`vocab`,
plus `blockCount`.

**Revision (optimistic concurrency)**: `revision` starts at 1 and is bumped by every successful
write to the document (`PUT`, share, unshare, restore). `PUT` must send the revision it was based
on; if it is not the current one the PUT is rejected with **409 `DOCUMENT_CONFLICT`** and the
ProblemDetail carries `currentRevision`. The response of every write returns the new `revision`
and `updatedAt`.

### Endpoints

```
GET    /api/v1/documents/schema            -> JSON Schema (any authenticated user)
GET    /api/v1/documents?level=&topicId=&grammarTopicId=&audience=&lessonId=&studentId=&groupId=&q=&page=&pageSize=
       -> { documents: [DocumentSummary], total, page, pageSize }   updatedAt desc; q = title contains (ci); pageSize ≤ 100
POST   /api/v1/documents                   Body: CreateDocument -> 201 Document
GET    /api/v1/documents/{id}              -> Document
PUT    /api/v1/documents/{id}              Body: DocumentInput + { revision } (full replace, the autosave target) -> Document
DELETE /api/v1/documents/{id}              -> 204 (owner or admin)
POST   /api/v1/documents/{id}/duplicate    Body: { title? } -> 201 Document (new block/item ids, not shared, not linked to lessons)
POST   /api/v1/documents/{id}/share        Body: { studentIds?, groupIds? } add targets -> Document
POST   /api/v1/documents/{id}/unshare      Body: { studentIds?, groupIds? } remove targets -> Document
GET    /api/v1/documents/{id}/versions     -> [DocumentVersionSummary] newest first (owner/admin)
GET    /api/v1/documents/{id}/versions/{versionId} -> DocumentVersion
POST   /api/v1/documents/{id}/versions/{versionId}/restore -> Document
GET    /api/v1/lessons/{id}/documents      -> [DocumentSummary] linked documents the caller can read
POST   /api/v1/lessons/{id}/documents      Body: { documentId } link (lesson teacher = document owner) -> 204
DELETE /api/v1/lessons/{id}/documents/{documentId}  unlink -> 204
```
`DocumentInput = { title, level?, topicIds: [], grammarTopicIds: [], audience, blocks: [Block] }`
(blank title or > 255 chars → 400 `VALIDATION_FAILED`).
`CreateDocument = DocumentInput + { studentIds?: [], groupIds?: [], lessonIds?: [], createdFromLessonId? }`
(`createdFromLessonId` is also linked; create-with-lesson = `lessonIds: [lessonId]`).

Roles: create / PUT / duplicate / share / unshare / restore / lesson links = the owning teacher;
DELETE and versions = owner or admin; GET = owner, admin, or a student with read access.
Share targets: students must be linked to the owner (400 `STUDENT_NOT_LINKED`); groups must be
the owner's (404 `GROUP_NOT_FOUND`). Tags must be in the owner's library (404 `TOPIC_NOT_FOUND` /
`GRAMMAR_TOPIC_NOT_FOUND`); `createdFromLessonId`/`lessonIds` must be the owner's lessons
(404 `LESSON_NOT_FOUND`).

**Student read access**: a document is readable by a student iff they are in `studentIds`, OR
a member of one of `groupIds`, OR a CONFIRMED participant of a linked lesson.
For a **student**, `GET /documents` returns only readable documents (filters `level`, `topicId`,
`grammarTopicId`, `audience`, `lessonId`, `q` apply; `studentId` / `groupId` are for teachers),
and `GET /lessons/{id}` / `GET /lessons/{id}/documents` list only the linked documents they can read.
Teachers list their own library; admins list all.

### Versions

A version is a snapshot of `title + blocks` (tags and share targets are not versioned).
`DocumentVersionSummary = { id, number, reason, title, blockCount, createdBy (user id | null), createdAt }`;
`DocumentVersion` = summary + `documentId`, `blocks`. `number` increases per document.
Snapshots are taken of the state **before** a change:
- `AUTOSAVE`: on `PUT` when there is no snapshot yet or the latest is ≥ 10 min old (so the
  1.5 s autosave does not create versions);
- `SHARE`: on every `share` (the state that was shared);
- `RESTORE`: on restore, the current state before it is overwritten;
- `DUPLICATE`: on duplicate, a snapshot of the source.
Only the newest 30 versions per document are kept.

### Lessons

`lesson_documents.shared_document`, `LessonResponse.sharedDocument`,
`LessonDocumentResponse.sharedDocument` and the `sharedDocument` field of
`PUT /api/v1/lessons/{id}/sync` are removed (sync keeps the notes/reflection fields).
`LessonResponse.documents: [{ id, title, revision, updatedAt }]` lists the linked documents the
caller can read (students: once they are CONFIRMED on the lesson).
**Live sync** stays poll-based: clients poll `GET /lessons/{id}` (every 10 s) or
`GET /documents/{id}`; when `revision` / `updatedAt` changes they refetch the document. The teacher
edits via `PUT /documents/{id}` (in any lesson status).

### Error codes

| Code | Status | Notes |
|---|---|---|
| `DOCUMENT_NOT_FOUND` | 404 | also for students without access |
| `DOCUMENT_INVALID_BLOCK` | 400 | `pointer` = JSON pointer of the bad value, e.g. `/blocks/3/items/0/solution/answers`; `detail` says what is wrong |
| `DOCUMENT_DUPLICATE_ID` | 400 | an id is used twice in the document; `pointer` set |
| `DOCUMENT_TOO_LARGE` | 400 | serialized blocks > 1 MiB |
| `DOCUMENT_CONFLICT` | 409 | stale `revision` on PUT; `currentRevision` set |
| `DOCUMENT_VERSION_NOT_FOUND` | 404 | |


---

## Materials (`/api/v1/materials`)

Teacher-owned study resources stored in S3-compatible storage (MinIO locally, S3 on AWS). Clients upload bytes to the API; the server streams them to storage. Non-LINK materials expose a `downloadUrl` pointing at `GET /api/v1/materials/{id}/file`, which streams the stored bytes through the authenticated API.

Upload flow for `PDF`, `AUDIO`, `VIDEO`: `POST /api/v1/materials` as `multipart/form-data` with two parts:
- `metadata` (`application/json`): `{ name, type, folderId?, level?, skill?, topicIds?: [], grammarTopicIds?: [] }`
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

Optional extension members, only present when they apply: `pointer` (JSON pointer of the bad
value in the request body, e.g. `/blocks/3/items/0/solution/answers`) and `currentRevision`
(on `DOCUMENT_CONFLICT`).

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
| Documents | `DOCUMENT_NOT_FOUND`, `DOCUMENT_INVALID_BLOCK` (+ `pointer`), `DOCUMENT_DUPLICATE_ID` (+ `pointer`), `DOCUMENT_TOO_LARGE`, `DOCUMENT_CONFLICT` (409, + `currentRevision`), `DOCUMENT_VERSION_NOT_FOUND` |
| Library / groups | `TOPIC_NOT_FOUND`, `TOPIC_DUPLICATE`, `TOPIC_HAS_CHILDREN`, `TOPIC_CYCLE`, `GRAMMAR_TOPIC_NOT_FOUND`, `GRAMMAR_TOPIC_DUPLICATE`, `GROUP_NOT_FOUND`, `STUDENT_NOT_LINKED` |

---

## User locale

`UserResponse.locale` is the user's interface language (`en` | `de`, default `en`).
Set it with `PATCH /api/v1/users/me { "locale": "de" }` (admins: `PATCH /api/v1/admin/users/{id}`).
Unsupported values return 400 `UNSUPPORTED_LOCALE`. The supported list lives in
`user/data/SupportedLocale.kt`; adding a language is a one-line change there.
