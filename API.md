# New Backend Endpoints

## Teacher library: topics & grammar (`/api/v1/topics`, `/api/v1/grammar-topics`)

Each teacher owns their library; it starts empty. Writes are **teacher only**; students
can read their teachers' topics / grammar topics; admins read all.

```
GET    /api/v1/topics                    -> [Topic]   flat list, build the tree from parentId
POST   /api/v1/topics                    Body: { name, parentId?, levels?: [A1..C2] }
PATCH  /api/v1/topics/{id}               Body: { name?, parentId?, moveToRoot?: bool, levels? }   rename / move / re-level
DELETE /api/v1/topics/{id}?force=true    409 TOPIC_HAS_CHILDREN if it has sub-topics (also with force);
                                         409 TOPIC_HAS_CONTENT if anything is tagged with it, unless force=true
POST   /api/v1/topics/{id}/merge         Body: { targetId } -> 200 Topic (the target)   merge {id} INTO targetId
POST   /api/v1/topics/{id}/merge?dryRun=true   same body -> 200 MergePreview, nothing is changed
```
Topic: `{ id, teacherId, parentId?, name, levels: [Level], createdAt }`. Sibling names are
unique case-insensitively (409 `TOPIC_DUPLICATE`); moving under a descendant is 400 `TOPIC_CYCLE`.

```
GET    /api/v1/grammar-topics?level=B1   ordered by level (nulls last), then position, then name
POST   /api/v1/grammar-topics            Body: { name, level?, category?, explanation?, examples: [string] }
GET    /api/v1/grammar-topics/{id}
PUT    /api/v1/grammar-topics/{id}       full replace, same body
DELETE /api/v1/grammar-topics/{id}?force=true   409 GRAMMAR_TOPIC_HAS_CONTENT if tagged anywhere, unless force=true
POST   /api/v1/grammar-topics/{id}/merge Body: { targetId } -> 200 GrammarTopic (the target)
POST   /api/v1/grammar-topics/{id}/merge?dryRun=true   same body -> 200 MergePreview, nothing is changed
PUT    /api/v1/grammar-topics/order      Body: { level: A1..C2 | null, ids: [uuid] } -> [GrammarTopic] of that level, in order
```
GrammarTopic: `{ id, teacherId, name, level?, category?, explanation?, examples, position, createdAt }`.
`position` orders the checklist **within a level** (0-based, per teacher + level). A new grammar
topic (POST, or created by Nachbereitung publish) is appended at the end of its level; a PUT
that changes `level` moves it to the end of the new level. Reorder: `ids` must be **exactly** the
teacher's grammar topics of that `level` (`null` = the ones without level), each once, else 400
`GRAMMAR_ORDER_INVALID`; positions become the list index.

**Content** of a topic = its tags on vocab entries, documents, materials and lessons (grammar
topic: documents, materials, lessons). Deleting never deletes content:
- without `force`: 409 `TOPIC_HAS_CONTENT` / `GRAMMAR_TOPIC_HAS_CONTENT`; the ProblemDetail carries
  `usage: { words, documents, materials, lessons }` (grammar: `words` is always 0) for the confirm dialog;
- with `force=true`: the tags are removed, then the topic is deleted (sub-topics still block: 409 `TOPIC_HAS_CHILDREN`).

**Merge** (`POST /topics/{A}/merge { targetId: B }`), one transaction, teacher (owner) only:
1. A and B must be the caller's (404 `TOPIC_NOT_FOUND`); A = B, or B inside A's subtree → 400 `TOPIC_MERGE_INVALID`.
2. Every tag on A (vocab entry, document, material, lesson) is re-pointed to B; rows that already
   exist for B are dropped (no duplicates, `INSERT … ON CONFLICT DO NOTHING` + delete).
3. A's children move under B. A child whose name clashes (case-insensitive) with a child of B is
   **merged recursively** into that child (so `Alltag > Haushalt` merged into `Wohnen` joins `Wohnen > Haushalt`).
4. `B.levels = B.levels ∪ A.levels`; B keeps its name and parent.
5. `vocab_table.topicId` in A's owner's documents is rewritten to B. Each rewritten document gets
   `revision + 1` and a new `updatedAt` (no version snapshot), so an open editor's next autosave
   gets 409 `DOCUMENT_CONFLICT` and reloads instead of writing the old id back.
6. A is deleted. Response: B.

`MergePreview` (`dryRun=true`, same validation and errors as the real merge) = what would move
from A to B: `{ words, documents, materials, lessons, children, childClashes }` — tag rows that
would be re-pointed (rows that already exist on B are not counted), `children` = A's direct
sub-topics, `childClashes` = how many of them would be merged recursively. Grammar: `words`,
`children`, `childClashes` are 0.

Grammar merge (`POST /grammar-topics/{A}/merge { targetId: B }`): same checks (404
`GRAMMAR_TOPIC_NOT_FOUND`, 400 `GRAMMAR_TOPIC_MERGE_INVALID` for A = B); document / material /
lesson tags re-pointed with dedupe; B keeps its name, level and position; B's empty
`category` / `explanation` are filled from A; `examples = B.examples ∪ A.examples` (order kept,
duplicates dropped); A is deleted. Response: B.

Job results of earlier AI runs (Nachbereitung / tag suggestions) are not rewritten: an
`existingId` pointing at a merged/deleted topic just 404s (`TOPIC_NOT_FOUND`) if it is sent back.

## Library views (`/api/v1/library`, teacher only)

Read-only aggregates over the caller's own library for the portal's "Bibliothek" (brief §5.5).
**Teacher only** (students and admins: 403). Every endpoint runs a fixed number of grouped
queries (no per-topic queries).

```
GET /api/v1/library/summary?level=B1          -> LibrarySummary
GET /api/v1/library/topics/{id}?level=B1      -> TopicLibrary          404 TOPIC_NOT_FOUND (also another teacher's)
GET /api/v1/library/grammar?level=B1          -> [GrammarLevelGroup]   the checklist
GET /api/v1/library/grammar/{id}              -> GrammarTopicLibrary   404 GRAMMAR_TOPIC_NOT_FOUND
```
`level` (optional) restricts **word / document / material** counts and lists to items with exactly
that level (items without a level only appear when `level` is omitted). Lessons, covered-by and the
`levels` / `totals` blocks of the summary are never level-filtered. On `/library/grammar`, `level`
only selects that level's group (its counts are not level-filtered).

```
LibrarySummary {
  levels: [{ level: A1..C2 | null,              // null bucket = items without level; always all 7 buckets
             words, documents, materials,        // by the item's own level
             topics,                             // topics whose `levels` contain it (null: topics with no levels)
             grammarTopics }],                   // by grammar topic level
  totals: { words, documents, materials, topics, grammarTopics },
  topics: [{ topicId,
              words, documents, materials, lessons, coveredStudents,     // DIRECT tags on this topic only
              subtree: { words, documents, materials } }]                // this topic + all descendants, distinct items
}                                                                         // (an item tagged twice in the subtree counts once)
TopicLibrary {
  topic: Topic,
  path: [TopicRef],                   // ancestors, root first (excludes the topic)
  children: [TopicRef],               // name asc
  words: [VocabEntry],                // tagged entries, lemma asc
  documents: [DocumentSummary],       // tagged, updatedAt desc
  materials: [MaterialResponse],      // tagged, name asc
  lessons: [LibraryLessonRef],        // tagged lessons, scheduledAt desc
  coveredBy: [CoveredStudent]
}
GrammarLevelGroup { level: A1..C2 | null, topics: [GrammarChecklistItem] }   // A1..C2 then null; empty levels omitted;
                                                                             // with ?level= only that group
GrammarChecklistItem { grammarTopic: GrammarTopic, documents, materials, lessons, coveredStudents }   // counts, ordered by position
GrammarTopicLibrary {
  grammarTopic: GrammarTopic,         // explanation + examples = the "explanation" link target
  documents: [DocumentSummary], materials: [MaterialResponse], lessons: [LibraryLessonRef],
  coveredBy: [CoveredStudent]
}
LibraryLessonRef { id, title, scheduledAt, status, groupId? }
CoveredStudent  { studentId, firstName, lastName, via: [LESSON | VOCAB], lastAt }   // lastName asc, firstName asc
```
**Covered by** (topic): a student is listed if
- **LESSON**: a lesson of this teacher tagged with the topic **took place** for them; or
- **VOCAB**: they have a `student_vocab` row for an entry of this teacher's library tagged with the topic.

**Took place** (one shared rule, also used by Student progress — `LessonAttendance.TOOK_PLACE`): the
student is a `CONFIRMED` participant (`lesson_students`), the lesson is not `CANCELLED` / `REQUEST`, and
it is `COMPLETED` or `IN_PROGRESS` (so a lesson started early counts at once) **or** its
`scheduledAt ≤ now`.

One row per student; `via` lists every source that applies; `lastAt` = the latest of the lesson's
`scheduledAt` / the student_vocab `addedAt` over all matching rows. Direct tags only (a student
covered `Haushalt` is not listed under its parent `Alltag`). Grammar topics: the LESSON rule only
(`via` is always `[LESSON]`). `coveredStudents` counts in the summary / checklist use the same rules.

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
POST   /api/v1/vocab-entries            Body: VocabEntryInput   409 VOCAB_ENTRY_DUPLICATE
GET    /api/v1/vocab-entries/{id}
PUT    /api/v1/vocab-entries/{id}       Body: VocabEntryInput   full replace
DELETE /api/v1/vocab-entries/{id}       also removes the word from every student
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

**Assign targets** (quick-add below; Nachbereitung publish uses the lesson rule): `studentIds` ∪ current members of `groupId`; if both are empty and `lessonId` is set,
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
to the lesson and assigns it (assign targets above).

```
DELETE /api/v1/vocabulary/{id}          removes the word from the student (library entry stays)
POST   /api/v1/vocabulary/{id}/review           Body: { rating, mode, responseMs? }   see Practice
POST   /api/v1/vocabulary/{id}/article          Body: { article, responseMs? }        see Practice
POST   /api/v1/vocabulary/{id}/sentences        Body: { sentence }                    see Practice
GET    /api/v1/vocabulary/{id}/sentences
```
`{id}` is the student-vocab id. `PUT … { status }` is a manual override (teacher or student); the
next review recomputes the status from FSRS.

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

### Practice: flashcards, article trainer, own sentences (brief §5.7)

**Scheduler.** FSRS-6 with the official default parameters, desired retention 0.9, learning steps
1 min / 10 min, relearning step 10 min, max interval 36500 days, **no fuzz** (deterministic). It is a
Kotlin port of `py-fsrs` v6.3.2 (`practice/service/Fsrs.kt`), unit-tested against py-fsrs
reference values. The only JVM library (`io.github.open-spaced-repetition:fsrs` 1.0.0, 07/2025) ships
outdated defaults and pulls in Jackson + a shared seeded `Random`, so it is not used.

FSRS card state per `student_vocab` row: `state` 0 NEW (never reviewed), 1 LEARNING, 2 REVIEW,
3 RELEARNING; `step` (learning step index), `stability`, `difficulty`, `due`, `lastReview`,
`reps`, `lapses` (REVIEW → AGAIN), `elapsedDays` / `scheduledDays` (informational).

**Status** is derived after every review:

| FSRS state after the review | status |
|---|---|
| LEARNING / RELEARNING | `LEARNING` |
| REVIEW, stability < 21 days | `REVIEW` |
| REVIEW, stability ≥ 21 days (`practice.learned_stability_days`) | `LEARNED` |

21 days is Anki's "mature" threshold: the student is predicted to still know the word with 90 %
probability three weeks later. `LEARNED` words keep being scheduled (long intervals); an AGAIN on
one drops it back to `LEARNING`.

**Who.** Reviews, article checks and sentences are **student only, own words** (a teacher's click
would corrupt the student's schedule): others get 403 `PRACTICE_STUDENT_ONLY`. Stats and sentence
lists are readable by the student, their teachers and admins.

#### Modes

| Mode | Front | Back | Cards |
|---|---|---|---|
| `DE_TO_MEANING` | German (article, lemma, plural, forms, government) | meaning per display setting + example | all words |
| `MEANING_TO_DE` | meaning per display setting | German | words with at least one visible meaning field |
| `ARTICLE` | noun **without** article (`article`, `plural`, `exampleSentence` are null) + meaning per display setting | server checks der/die/das | `NOUN` with an article |

All modes grade the **same FSRS card** per word. The display setting (§5.3, same resolution as the
vocabulary list) applies: a B1 student with `["de_explanation"]` gets `explanationDe`; translations
only in `revealTranslations` when the toggle is allowed.

#### Queue

```
GET /api/v1/practice/queue?mode=DE_TO_MEANING&topicId=&lessonId=&level=&limit=20     student only
-> { mode, cards: [PracticeCard], dueCount, newCount, newLimit, newIntroducedToday }
```
- **Due first**, oldest `due` first: review cards (`state = REVIEW`) due **any time today**
  (`due <` the student's next local midnight — as in Anki, a card due at 23:00 can be practised at
  09:00; FSRS grades it from the real elapsed time), and learning / relearning cards
  (`state = LEARNING | RELEARNING`) only once their short intraday step is due (`due <= now`).
- **Then new** (`state = 0`), oldest `addedAt` first, at most `newLimit − newIntroducedToday`
  (`practice.new_cards_per_day`, default **15**; "today" = the student's timezone; a card counts
  as introduced by its first review in any mode). Filters don't change the daily budget.
- Filters: `topicId` (that topic **and its subtopics**, so a folder practises its whole subtree),
  `lessonId`, `level` (entry level). `limit` 1..100, default 20.
- `dueCount` / `newCount` = totals for these filters (before `limit`; without filters `dueCount`
  equals `stats.dueNow`), so the client can show
  "12 due · 5 new". Cards failed with AGAIN come back after 1–10 min: the client re-appends them
  to the end of the session and/or refetches the queue when it runs out.
- Unknown mode → 400 `PRACTICE_MODE_INVALID`.

PracticeCard:
```json
{
  "mode": "DE_TO_MEANING", "isNew": true,
  "word": { /* StudentVocab, display-filtered, see mode table */ },
  "intervals": {
    "AGAIN": { "dueAt": "ISO8601", "seconds": 60 },
    "HARD":  { "dueAt": "ISO8601", "seconds": 330 },
    "GOOD":  { "dueAt": "ISO8601", "seconds": 600 },
    "EASY":  { "dueAt": "ISO8601", "seconds": 691200 }
  }
}
```
`intervals` previews the next due date if the card were rated now with each rating, for button
labels ("1 Min / 6 Min / 10 Min / 8 Tage"). The client formats `seconds` in the UI language (no
server label). `ARTICLE` cards only have `AGAIN` and `GOOD`.

#### Review (flashcards)

```
POST /api/v1/vocabulary/{id}/review
Body: { "rating": "AGAIN | HARD | GOOD | EASY", "mode": "DE_TO_MEANING | MEANING_TO_DE", "responseMs": 4200 }
-> 200 StudentVocab (new status, due, reps, lapses, lastReview)
```
`mode` is required; `ARTICLE` → 400 `PRACTICE_MODE_INVALID` (use `/article`). `responseMs` optional,
0..600000 (else 400 `VALIDATION_FAILED`). Missing / unknown rating → 400. The body is now
**required** (the old body-less "knew it" review is gone; the portal's quick-review card sends
`GOOD`).

#### Article trainer

```
POST /api/v1/vocabulary/{id}/article
Body: { "article": "DER | DIE | DAS", "responseMs": 1800 }
-> 200 { "correct": false, "correctArticle": "DIE", "rating": "AGAIN", "word": StudentVocab }
```
Correct → `GOOD`, wrong → `AGAIN`, applied to the word's FSRS card like a review.
Not a noun / no article on the entry → 400 `NOT_A_NOUN`.

Every review and article check writes a `vocab_reviews` log row (rating, mode, state before,
responseMs) and a `VOCAB_REVIEW` learning activity (streak).

#### Stats (dashboard card)

```
GET /api/v1/practice/stats?studentId=      student (own; param ignored), teacher of the student, admin
-> {
  "dueNow": 7,            // practicable now, same rule as the queue: REVIEW due before the end of
                          // today, LEARNING / RELEARNING due <= now (show this on the dashboard tile)
  "dueToday": 9,          // … due before the end of today (student's timezone)
  "newAvailable": 5,      // min(unseen words, newLimit − newIntroducedToday)
  "newTotal": 40,         // unseen words
  "newLimit": 15, "newIntroducedToday": 10,
  "reviewedToday": 23,    // distinct words reviewed today
  "sentencesToday": 2, "sentenceLimit": 30,
  "aiAvailable": true,    // students read it here to show "write your own sentence"
  "streak": { "current": 4, "longest": 9, "todayDone": true, "week": [...] }   // = /users/me/streak for the student
}
```
Teachers/admins must pass `studentId` (400 `VALIDATION_FAILED` otherwise).

#### Own sentences with AI feedback

```
POST /api/v1/vocabulary/{id}/sentences     Body: { "sentence": "Ich kümmere mich um die Blumen." }
-> 201 Sentence
GET  /api/v1/vocabulary/{id}/sentences                     -> [Sentence]   newest first
GET  /api/v1/students/{studentId}/sentences?page=&pageSize= -> { sentences: [Sentence], total, page, pageSize }
```
POST: student only, own word. GET: the student, their teachers, admins (others 403 / 404).

Sentence:
```json
{
  "id": "uuid", "studentVocabId": "uuid", "studentId": "uuid", "entryId": "uuid",
  "word": { "lemma": "kümmern", "article": null, "wordType": "VERB" },
  "sentence": "Ich kümmere mich um die Blumen.",
  "feedback": {
    "isCorrect": true,
    "corrected": "Ich kümmere mich um die Blumen.",
    "explanation": "Richtig! „sich kümmern um“ + Akkusativ.",
    "explanationTranslation": { "language": "ru", "text": "Верно! …" },
    "usesWord": true
  },
  "createdAt": "ISO8601"
}
```
- **Synchronous** call through the shared provider abstraction (no job), timeout
  `practice.sentence_timeout` = 30 s. Model output is validated JSON with one retry (as for jobs).
- `explanation`: one short line in simple German. `explanationTranslation` only when the student's
  level is A1/A2 (or unset) **and** the word's display setting contains a translation language
  (the first one in `fields`); otherwise null.
- `usesWord`: the sentence uses the target word (any inflected form). `isCorrect` concerns grammar
  and spelling; `corrected` equals the input when correct.
- Validation: trimmed, 1..300 chars → 400 `SENTENCE_EMPTY` / `SENTENCE_TOO_LONG`.
- Errors: 503 `AI_NOT_CONFIGURED`; 429 `PRACTICE_SENTENCE_LIMIT` after `practice.sentences_per_day`
  (30) stored sentences today (student's timezone), with `resetsAt` = the student's next local
  midnight; 429 `AI_RATE_LIMITED` only when the provider rate-limits; 503
  `AI_PROVIDER_ERROR` / `AI_OUTPUT_INVALID` / `AI_TIMEOUT`. Failed calls are not stored and do not
  count toward the limit.
- Every successful sentence + feedback is stored (`student_vocab_sentences`) and counts as a
  `VOCAB_SENTENCE` learning activity for the streak.
- **Privacy**: the model only receives the sentence, the target word (lemma, article, word type,
  government), the level and the translation language code. No names, emails, ids,
  translations or topics. The system prompt is
  `resources/ai/sentence-feedback-system.md` (editable).
- Fake provider (deterministic): `usesWord` = the lemma stem (lemma minus a trailing `-en`/`-n`,
  case-insensitive) occurs in the sentence; `isCorrect` = `usesWord` and no `[fake:wrong]` marker.
  Correct → `corrected` = the sentence, explanation `"Richtig, gut gemacht!"`. Wrong → `corrected` =
  the sentence without markers, first letter capitalised, `.` appended if missing; explanation
  `"Benutze das Wort „<lemma>“ im Satz."` (word missing) or `"Achte auf Großschreibung und
  Satzzeichen."`. `explanationTranslation` = `"(<lang>) <explanation>"`. The usual markers work
  in the sentence: `[fake:error]` → 503 `AI_PROVIDER_ERROR`, `[fake:rate-limit]` → 429
  `AI_RATE_LIMITED`, `[fake:invalid]` → 503 `AI_OUTPUT_INVALID`, `[fake:invalid-once]` → retry
  succeeds, `[fake:delay=<ms>]` (→ 503 `AI_TIMEOUT` beyond `practice.sentence_timeout`).

Config: `practice.new_cards_per_day = 15` (`PRACTICE_NEW_CARDS_PER_DAY`),
`practice.learned_stability_days = 21`, `practice.sentences_per_day = 30`
(`PRACTICE_SENTENCES_PER_DAY`), `practice.sentence_timeout = 30s`.

Migration `V13__practice.sql`: `student_vocab.step`; cards reviewed by the old simple scheduler
(no stability) are reset to NEW; `vocab_reviews`; `student_vocab_sentences`; `VOCAB_SENTENCE`
learning-activity kind.

---

## Homework (`/api/v1/assignments`, `/api/v1/homework`)

Brief §5.8: students answer **inside the app** (interactive document blocks, text, audio, video,
files); closed exercises are auto-checked server-side; Anna reviews, comments, returns for rework
or closes. Replaces the old text-submission homework (table `homework` and its enums are dropped in
V12; the prod DB is reset).

- **Assignment** = what the teacher assigns once: title, instructions, due date, optional lesson,
  ordered **items**. Teacher-owned; admins read/delete.
- **Homework** = one per-student instance of an assignment, with its own status, answers, uploads,
  review. Assigning to a **group** expands to its members **at assign time** (later members are not
  added; `groupId` is kept for display).

### Items

| kind | input | stored | answerable units |
|---|---|---|---|
| `DOCUMENT` | `{ kind, documentId }` | **snapshot** of the document's `title` + `blocks` (with solutions) + `revision` at assign time | every item/question of an **interactive** exercise block (see below) |
| `MATERIAL` | `{ kind, materialId, task?, responseType? }` | material ref + name snapshot | one, only if `responseType` set |
| `TASK` | `{ kind, title, task?, responseType }` | as given | one |

`responseType: TEXT | AUDIO | VIDEO | FILE`. `title` ≤ 255, `task` ≤ 5 000 chars (plain text).
Items get server uuids (`assignmentItemId`), keep their order, and are **immutable** after create
(change = delete + re-assign). The snapshot means later edits of the document never shift answer
keys or change the grading of assigned homework; students need no share on the document.

**Answerable units of a DOCUMENT item** (only blocks with `interactive: true`; everything else is
shown as context, incl. `vocab_table` with the viewer-resolved `vocab` side-list, `media` source):

| block | unit = | answer payload | check |
|---|---|---|---|
| `gap_fill` | `items[]` | `{ "gaps": ["musst", "kümmern"] }` (index = gap order; ≤ number of gaps) | **auto** |
| `multiple_choice` | `items[]` | `{ "optionIds": [uuid] }` (options of that item; ≤ 1 unless `multiple`) | **auto** |
| `reading` / `media` / `exam_part` | `questions[]` | `TRUE_FALSE`: `{ "isTrue": bool }` · `CHOICE`: `{ "optionIds": [uuid] }` · `OPEN`: `{ "text" }` | auto / auto / review |
| `error_correction` | `items[]` | `{ "text": "Darum musst du dich kümmern." }` | review |
| `free_sentences` (purpose ≠ `SPEAKING`) | `items[]` | `{ "text" }` | review |
| `writing_task`, `free_form` | `items[]` | `{ "text" }` | review |

MATERIAL/TASK units: `{ "text"?, "uploadIds"?: [uuid] }` — `text` is the answer for `TEXT` (an
optional note otherwise); `uploadIds` (AUDIO/VIDEO/FILE only, ≤ 5) must be uploads of this homework
and unit (see Uploads). `text` ≤ 20 000 chars, each gap ≤ 500.
Unit address: `{ assignmentItemId, blockId?, itemId? }` (`blockId` + `itemId` for DOCUMENT
units, both absent for MATERIAL/TASK). A DOCUMENT item with no answerable unit → 400
`HOMEWORK_ITEM_INVALID` (mark the exercises interactive, or use a MATERIAL/TASK item).

### Auto-check (server-side, on submit)

Normalisation of text: Unicode NFC, trim, collapse whitespace runs to one space. Then:
- **gap_fill**: a gap is `CORRECT` if equal (case-**sensitive**) to one accepted answer of its
  group; else `CASE_MISMATCH` if equal ignoring case (counts as correct, flagged for the teacher —
  German noun capitalisation); else `WRONG` (empty = `WRONG`). Unit `correct` = all gaps correct;
  `score` = correct gaps / gaps. If the answer key does not match the text (gap count ≠ answer
  groups, or a group is empty) the unit is `PENDING_REVIEW` instead.
- **multiple_choice / CHOICE**: set equality of `optionIds` with `solution.correctOptionIds`
  (no partial credit). No key → `PENDING_REVIEW`.
- **TRUE_FALSE**: `isTrue == solution.isTrue`. No key → `PENDING_REVIEW`.
- Everything else → `PENDING_REVIEW`. Unanswered closed units count as incorrect; unanswered open
  units are just `UNANSWERED` (nothing to review).

Per unit `autoResult: CORRECT | INCORRECT | PENDING_REVIEW | UNANSWERED`, `autoScore` 0..1,
`caseMismatch`. Final verdict `correct` = teacher override ?? auto (null for open units until the
teacher sets it). Summary: `{ closedCorrect, closedTotal, pendingReview, unanswered }`.

### Status flow

```
OPEN      --submit (student)--> SUBMITTED
SUBMITTED --review REVIEWED---> REVIEWED     SUBMITTED|REVIEWED --review DONE--> DONE (final)
SUBMITTED|REVIEWED --review RETURNED--> OPEN (rework, then submit again)
```
- `OPEN`: student edits answers (draft autosave), uploads/deletes files.
- `submit` (student, OPEN only): runs auto-check, locks answers, `attempt++`, `submittedAt`.
- teacher `review` action on SUBMITTED or REVIEWED: `outcome` = `REVIEWED` | `RETURNED` (→ `OPEN`,
  student reworks and resubmits) | `DONE`. DONE is final (no more changes; teacher may still
  delete the homework).
- On resubmit, teacher override + comment are cleared for units whose answer changed.
- `lastOutcome: REVIEWED | RETURNED | DONE | null` = the teacher's latest review outcome. After a
  return the status is `OPEN` again with `lastOutcome = RETURNED`, `returnedAt` and the return
  `feedback`, so the portal can show "zur Überarbeitung zurückgegeben". It stays until the next
  review outcome. `returnedAt` is never cleared (it survives the resubmit), so "returned, not yet
  resubmitted" = `status == OPEN && lastOutcome == RETURNED`.
- Any write in the wrong state → 409 `SUBMISSION_LOCKED` (student answer/upload/submit when not
  OPEN) or 409 `HOMEWORK_INVALID_STATE` (review actions).

### Visibility (students)

- `solution` keys are stripped from every snapshot and `autoResult` / `autoScore` /
  `caseMismatch` / `correct` / `summary` are **null** unless status is `REVIEWED` or `DONE`.
  Before that the student sees items, their own answers and uploads, and "submitted".
- `feedback`, per-unit `comment` and `teacherCorrect` are visible only in `REVIEWED`, `DONE`, or
  `OPEN` with `lastOutcome = RETURNED` (feedback + comments only, still no results/solutions). While
  `SUBMITTED` they are hidden, so review drafts (`PUT …/review`, allowed only in SUBMITTED) are never
  seen early. Changing a REVIEWED homework goes through `POST …/review` again.
- **Teachers never see draft content.** While the homework is `OPEN` (first attempt or after a
  return) teachers/admins get `answer: null` for every unit and no `uploads` (download 404), only
  progress: `answeredUnits`, `totalUnits`, `lastSavedAt`. From `SUBMITTED` on they see the full
  submitted content, auto results and everything else.

### Endpoints — teacher

```
POST   /api/v1/assignments                 Body: CreateAssignment -> 201 Assignment
GET    /api/v1/assignments?studentId=&groupId=&lessonId=&page=&pageSize=  -> { assignments: [AssignmentSummary], total, page, pageSize }  createdAt desc
GET    /api/v1/assignments/{id}            -> Assignment (items with full snapshots + solutions, homework summaries)
PATCH  /api/v1/assignments/{id}            Body: { title?, instructions?, dueDate?, clearDueDate? } -> Assignment
DELETE /api/v1/assignments/{id}            -> 204 (owner or admin; deletes all homework + stored uploads)
DELETE /api/v1/homework/{id}               -> 204 (owner or admin; removes one student's homework)
PUT    /api/v1/homework/{id}/review        Body: ReviewDraft -> Homework   (autosave, SUBMITTED only, no status change)
POST   /api/v1/homework/{id}/review        Body: ReviewDraft + { outcome: REVIEWED|RETURNED|DONE } -> Homework
```
`CreateAssignment = { title (1..255), instructions? (≤ 10 000), dueDate? (YYYY-MM-DD), lessonId?,
studentIds?: [], groupIds?: [], items: [ItemInput] (1..20) }`. Recipients = studentIds ∪ members of
groupIds, deduplicated, ≥ 1 (400 `ASSIGNMENT_NO_STUDENTS`). Students must be linked
(400 `STUDENT_NOT_LINKED`), groups / lessons / documents / materials must be the teacher's
(404 `GROUP_NOT_FOUND` / `LESSON_NOT_FOUND` / `DOCUMENT_NOT_FOUND` / `MATERIAL_NOT_FOUND`).
Bad item → 400 `HOMEWORK_ITEM_INVALID` + `pointer` (`/items/2/responseType`).

`ReviewDraft = { feedback: string|null (≤ 10 000), units?: [{ assignmentItemId, blockId?, itemId?,
correct: bool|null, comment: string|null (≤ 5 000) }] }` — `feedback` always replaces; for every
listed unit `correct` (teacher override, `null` = use the auto result) and `comment` replace; units
not listed are untouched. Only units that exist in the assignment (400
`HOMEWORK_ITEM_INVALID`).

### Endpoints — student (and teacher read)

```
GET    /api/v1/homework?studentId=&assignmentId=&documentId=&lessonId=&status=OPEN,SUBMITTED&dueBefore=&dueAfter=&sort=due|submitted|created&page=&pageSize=
       -> { homework: [HomeworkSummary], total, page, pageSize }
GET    /api/v1/homework/{id}               -> Homework
GET    /api/v1/homework/counts             -> teacher/admin: { toReview, overdue, openTotal } · student: { open, dueSoon, returned }
PUT    /api/v1/homework/{id}/answers       Body: { answers: [{ assignmentItemId, blockId?, itemId?, answer: {…} | null }] } -> AnswersSaved
POST   /api/v1/homework/{id}/submit        (no body) -> Homework
POST   /api/v1/homework/{id}/items/{assignmentItemId}/uploads   multipart `file` -> 201 HomeworkUpload
DELETE /api/v1/homework/{id}/items/{assignmentItemId}/uploads/{uploadId}  -> 204 (re-record: own upload, OPEN only)
GET    /api/v1/homework/{id}/uploads/{uploadId}/file            -> bytes (inline, stored Content-Type)
```
Roles: `GET` = the student, the assignment's teacher, admin (others: 404 `HOMEWORK_NOT_FOUND`).
List: students → own; teachers → homework of their assignments (`studentId` filter must be linked);
admins → all. `documentId` = homework with a DOCUMENT item made from that document (the
snapshot keeps the source id). `status` is a comma list. Sort: `due` = dueDate asc nulls last (default for
students), `submitted` = submittedAt asc (the teacher's "Hausaufgaben zu korrigieren" queue =
`status=SUBMITTED&sort=submitted`), `created` = desc (default for teachers).
Answers / submit / uploads: the assigned student only (teacher/admin 403, other students 404). `PUT …/answers` is a
**partial, idempotent merge**: only the listed units change (the 1.5 s autosave sends only changed
units; sending the same body twice is a no-op), `answer: null` removes one. It never submits.
`AnswersSaved = { updatedAt, lastSavedAt, answeredUnits, totalUnits }`.
Counts (one query, "today" in the caller's timezone): `toReview` = SUBMITTED; `overdue` = OPEN with
`dueDate < today`; `openTotal` = OPEN; student `open` = OPEN; `dueSoon` = OPEN with
`dueDate ≤ today + 2` (overdue included); `returned` = OPEN with `lastOutcome = RETURNED`.
Payload must match the unit type (400 `HOMEWORK_ANSWER_INVALID` + `pointer`); unknown unit →
400 `HOMEWORK_ITEM_INVALID`. Submitting with unanswered units is allowed (the portal confirms).

### Uploads

Only for MATERIAL/TASK units with `responseType` AUDIO / VIDEO / FILE, while `OPEN`, ≤ 5 per unit
(409 `UPLOAD_LIMIT_REACHED`). The response carries the upload `id`; the client then lists it in the
unit's answer (`uploadIds`). Deleting an upload also removes it from the answer. Size limit = `STORAGE_MAX_FILE_SIZE` (400 `FILE_TOO_LARGE`).
Upload is `multipart/form-data` with one `file` part (filename + `Content-Type` required).
Content type = the part's `Content-Type` without parameters (`audio/webm;codecs=opus` →
`audio/webm`), must be in the allowlist, else 400 `UPLOAD_TYPE_NOT_ALLOWED`:
- AUDIO: `audio/webm`, `audio/ogg`, `audio/mpeg`, `audio/mp4`, `audio/x-m4a`, `audio/aac`, `audio/wav`, `audio/x-wav`
- VIDEO: `video/webm`, `video/mp4`, `video/quicktime`
- FILE: `application/pdf`, DOCX, `application/msword`, `image/jpeg`, `image/png`, `image/webp`, `image/heic`, `text/plain`

Stored at `homework/{homeworkId}/{uploadId}/{safeName}`; streamed back through the API (authed
`GET …/file`) to the student and — once submitted — the teacher / admin. Deleted with the homework/assignment. The browser records
audio with `MediaRecorder` and uploads the blob like a file.
`HomeworkUpload = { id, assignmentItemId, fileName, contentType, size, downloadUrl, createdAt }`;
`downloadUrl` is an API path like the material one (`/api/v1/homework/{id}/uploads/{uploadId}/file`).

### Materials access

A student may `GET /api/v1/materials/{id}` and `/file` for every material referenced by a
MATERIAL item or by a `media` block of a DOCUMENT snapshot of their homework (checked at access
time, like documents).

### Response shapes

```
Assignment = { id, teacherId, title, instructions?, dueDate?, lessonId?, groupIds: [], createdAt, updatedAt,
  items: [AssignmentItem], homework: [HomeworkSummary] }
AssignmentSummary = Assignment without items/homework + { itemCount, studentCount,
  statusCounts: { OPEN, SUBMITTED, REVIEWED, DONE } }
AssignmentItem = { id, position, kind, title, task?, responseType?,
  documentId?, documentRevision?, blocks?: [Block], vocab?: [DocumentVocabEntry],   // DOCUMENT
  materialId?, material?: { id, name, type, contentType?, downloadUrl? } }          // MATERIAL (null if deleted)
HomeworkSummary = { id, assignmentId, title, dueDate?, lessonId?, status, lastOutcome?, attempt, itemCount,
  student: { id, firstName, lastName }, teacher: { id, firstName, lastName },
  answeredUnits, totalUnits, lastSavedAt?, summary?, submittedAt?, reviewedAt?, returnedAt?, doneAt?, createdAt, updatedAt }
Homework = HomeworkSummary + { instructions?, feedback?, items: [AssignmentItem],  // blocks solution-stripped for students before review
  units: [Unit], uploads: [HomeworkUpload] }
Unit = { assignmentItemId, blockId?, itemId?, blockType?, questionKind?, check: AUTO|REVIEW,
  answer?, answeredAt?, autoResult?, autoScore?, caseMismatch?, gapResults?: [CORRECT|CASE_MISMATCH|WRONG],
  teacherCorrect?, correct?, comment? }
```
`units` lists **every** answerable unit in item/document order (answered or not), so the portal
renders progress without re-deriving it. `summary = { closedCorrect, closedTotal, pendingReview, unanswered }`.

### Notifications

New `NotificationType`s (`referenceId` = homework id; wording lives in the portal):
`HOMEWORK_ASSIGNED` (→ each student, actor teacher), `HOMEWORK_SUBMITTED` (→ teacher, actor student),
`HOMEWORK_REVIEWED` (→ student, on outcome REVIEWED or DONE), `HOMEWORK_RETURNED` (→ student).
The learning streak still records `HOMEWORK_SUBMITTED` activity on submit.

### Tables (V12)

`assignments` (teacher_id, lesson_id? SET NULL, title, instructions, due_date DATE, item_count,
total_units), `assignment_groups` (assignment_id, group_id CASCADE), `assignment_items`
(assignment_id CASCADE, position, kind, title, task, response_type, document_id? SET NULL,
document_revision, blocks JSONB, material_id? SET NULL), `homework` (assignment_id CASCADE,
student_id, status, last_outcome, attempt, feedback, summary counts, last_saved_at,
submitted/reviewed/returned/done_at; unique (assignment, student)), `homework_answers`
(homework_id CASCADE, assignment_item_id CASCADE, block_id?, item_ref?, answer JSONB, answered_at
(set only for non-empty answers), submitted_answer JSONB, auto_result, auto_score, case_mismatch,
gap_results JSONB, teacher_correct, comment; partial unique indexes for document / item units),
`homework_uploads` (homework_id CASCADE, assignment_item_id, storage_key, file_name, content_type, size).

### Error codes

| Code | Status | Notes |
|---|---|---|
| `ASSIGNMENT_NOT_FOUND` | 404 | |
| `ASSIGNMENT_NO_STUDENTS` | 400 | no recipients after expanding groups |
| `HOMEWORK_NOT_FOUND` | 404 | also for students/teachers without access |
| `HOMEWORK_ITEM_INVALID` | 400 | bad item input or unknown unit; `pointer` |
| `HOMEWORK_ANSWER_INVALID` | 400 | answer payload does not fit the unit; `pointer` |
| `HOMEWORK_INVALID_STATE` | 409 | review action in the wrong status |
| `SUBMISSION_LOCKED` | 409 | student write while not OPEN |
| `UPLOAD_TYPE_NOT_ALLOWED` | 400 | content type not allowed for the unit's responseType (or TEXT unit) |
| `UPLOAD_LIMIT_REACHED` | 409 | > 5 uploads on one unit |
| `HOMEWORK_UPLOAD_NOT_FOUND` | 404 | |
| `FILE_TOO_LARGE` | 400 | shared with materials |

---

## Student progress (`/api/v1/students/{studentId}/progress`)

Brief §5.6: the **Progress** tab in student detail — grammar checklist for the student's level
(covered / practiced / needs work), topics covered vs. not, a few numbers. Everything is
**derived** from lessons, homework and vocabulary at request time (nothing is cached); the only
stored input is the teacher's **manual override** per student × grammar topic. The student gets the
same view read-only. A progress request runs a fixed number of grouped queries (independent of
the number of topics, lessons or homework).

Progress is always **per teacher library**: the checklist is the teacher's grammar topics, the
topics are the teacher's topic tree, and only that teacher's lessons / homework / words count.

```
GET    /api/v1/students/{studentId}/progress?teacherId=&level=&includeLower=true   -> StudentProgress
PUT    /api/v1/students/{studentId}/progress/grammar/{grammarTopicId}/override
       Body: { status: NOT_COVERED|COVERED|PRACTICED|NEEDS_WORK, note?: string (≤ 2 000) } -> GrammarProgressItem
DELETE /api/v1/students/{studentId}/progress/grammar/{grammarTopicId}/override     -> GrammarProgressItem (derived again)
```
Roles: `GET` = a linked teacher (`teacherId` ignored: always the caller), the student themself,
admin; others 403 `FORBIDDEN`. Students/admins pass `teacherId` when the student has several
teachers (default: the earliest, like vocab-settings); no teacher → 404 `TEACHER_STUDENT_NOT_FOUND`.
Override `PUT`/`DELETE` = a linked teacher only (students/admins 403); the grammar topic must be the
caller's (404 `GRAMMAR_TOPIC_NOT_FOUND`); a bad `status` / too long `note` → 400
`GRAMMAR_OVERRIDE_INVALID` (+ `pointer`). `DELETE` without an override is a no-op (200).

**Level**: `level` query param, else the student's `level`. `includeLower=true` adds every lower
level (A1 … level). Grammar topics **without** a level are never on the checklist. No level at all
→ `level: null`, empty `grammar` / `topics`, `checklistEmpty: true`.

### Grammar status (derived)

For each grammar topic G on the checklist (teacher T, student S):

| status | rule (first that matches wins, top to bottom) |
|---|---|
| `NEEDS_WORK` | over the latest `window` (10) scored units: `scored.total ≥ minScoredItems` (3) **and** `scored.correct / scored.total < needsWorkBelow` (0.60) |
| `PRACTICED` | S has **submitted** (`attempt ≥ 1`, any status) homework of T with an item whose source is tagged with G |
| `COVERED` | a lesson of T tagged with G **took place** for S (the shared rule in Library views → Covered by: CONFIRMED participant, not `CANCELLED` / `REQUEST`, and `COMPLETED` / `IN_PROGRESS` or `scheduledAt ≤ now`) |
| `NOT_COVERED` | none of the above |

- **Item source tagged with G**: a `DOCUMENT` item whose source document (`assignment_items.document_id`)
  has G in `document_grammar_topics`, or a `MATERIAL` item whose material has G in
  `material_grammar_topics`. Tags are read **live** (re-tagging a document changes past homework's
  attribution; a deleted source document/material drops that evidence — the snapshot keeps no tags).
  `TASK` items have no tags. Blocks copied into another document carry no provenance, so they count for
  the new document's tags only.
- **Scored units** (`scored`): answerable units (`homework_answers` rows) of those items in S's homework
  with status `REVIEWED` or `DONE` (i.e. results the student can see too) and a final verdict
  `correct = teacher_correct ?? auto_result` (`CORRECT` → 1, `INCORRECT` → 0; `PENDING_REVIEW` /
  `UNANSWERED` without teacher verdict are not scored). A unit of a document tagged with several
  grammar topics counts for each of them. Only the latest attempt counts (answers are overwritten on
  resubmit). Only the **latest `window` units per topic** count (newest homework first by
  `reviewed_at ?? submitted_at`, then `answered_at`), so early mistakes stop counting once the
  student improves: 3 wrong followed by 10 right is `PRACTICED`. `evidence.homeworkScored` is this window.
- `needsWorkBelow` / `minScoredItems` / `window` come from config (`progress.needs_work_below = 0.6`
  `PROGRESS_NEEDS_WORK_BELOW`, `progress.min_scored_items = 3` `PROGRESS_MIN_SCORED_ITEMS`,
  `progress.needs_work_window = 10` `PROGRESS_NEEDS_WORK_WINDOW`) and are echoed in the response.

**Override**: stored per (student, grammar topic) with the setting teacher; the **effective** `status`
= `override.status ?? derivedStatus`. Both are returned, so the UI can show "derived: practiced,
set by you: covered". Deleting the grammar topic (also `force=true`) deletes its overrides; a grammar
**merge** A → B moves A's overrides to B unless S already has one on B (B's wins).

### Topics

The teacher's topics **relevant to the level(s)**: topics whose `levels` contain one of the checklist
levels, plus topics with no levels. Covered = the P5 "covered by" rule for S (direct tags only):
`LESSON` (attended lesson tagged with it, same rule as above) and/or `VOCAB` (S has a word of T's
library tagged with it). Word counts: S's `student_vocab` rows for entries of T tagged with the topic
(`total`) and those with status `LEARNED` (`learned`).

### Shapes

```
StudentProgress {
  studentId, teacherId,
  level: A1..C2 | null,              // the level used (query > student level)
  levels: [Level],                   // checklist levels, ascending (one unless includeLower)
  checklistEmpty: bool,              // no grammar topic on the checklist -> no fake 0 %
  thresholds: { needsWorkBelow: 0.6, minScoredItems: 3, window: 10 },
  grammar: [GrammarProgressLevel],   // levels ascending; a level without grammar topics is still listed (empty items)
  topics: [TopicProgressItem],       // tree order: full path (ancestors + name), case-insensitive
  summary: ProgressSummary
}
GrammarProgressLevel {
  level, checklistEmpty: bool,       // this level has no grammar topic -> tell "empty" from "0 %"
  counts: GrammarCounts,
  items: [GrammarProgressItem]       // position, then name
}
GrammarProgressItem {
  id, name, level, category?, position,          // the grammar topic
  derived: NOT_COVERED | COVERED | PRACTICED | NEEDS_WORK,
  override: { status, note?, updatedAt, updatedBy } | null,   // note: teachers/admins only (null for the student)
  effective: …same enum,                          // override.status ?? derived
  evidence: {
    lessonCount, lastLessonAt?,                  // attended lessons tagged with it
    homeworkScored: { correct, total },          // scored units in the window (see above)
    lastPracticedAt?                             // latest submittedAt of a qualifying homework
  }
}
TopicProgressItem {
  id, name, parentId?, levels,
  path: [string],                                // ancestor names, root first (excludes the topic itself)
  covered: bool, via: [LESSON | VOCAB],
  lastLessonAt?,                                 // latest attended lesson tagged with it
  words: { learned, total }
}
GrammarCounts { total, notCovered, covered, practiced, needsWork,        // by effective status, exclusive
                percent: { notCovered, covered, practiced, needsWork, reached } | null }
                // integer % of total (reached = 100 − notCovered %); null when total = 0
ProgressSummary {
  grammar: GrammarCounts,                        // over all checklist levels
  topics: { total, covered, percent? },          // percent null when total = 0
  vocab: { total, learned, percent? },           // all S's words of T's library (not level-filtered)
  streak: { current, longest, todayDone }        // the student's learning streak (same as the student's /users/me/streak)
}
```

## Goals (`/api/v1/goals`)

Brief §5.9 / decision 10: **auto-tracked** goals replace the old manual-percentage goals (table
`learning_goals`, `PUT …/progress`, `/complete`, `/abandon` are removed; the prod DB is reset). The
teacher sets a goal for a student; progress is **computed** from the progress data above, never
typed in. The path stays `/api/v1/goals`; the request/response shapes are **replaced in place** (old
clients break — acceptable, there are none besides the portal, which ships with this change).

```
GET    /api/v1/goals?studentId=&status=ACTIVE,ACHIEVED   -> [Goal]   ACTIVE first, then targetDate asc nulls last, createdAt desc
POST   /api/v1/goals            Body: GoalInput -> 201 Goal                     teacher only
GET    /api/v1/goals/{id}       -> Goal
PATCH  /api/v1/goals/{id}       Body: GoalPatch -> Goal                          the goal's teacher only
DELETE /api/v1/goals/{id}       -> 204                                           the goal's teacher or admin
```
Roles: list/get — students see their own goals (from every teacher), teachers the goals they created
(`studentId` filter must be linked, else 403), admins all. Others get 404 `GOAL_NOT_FOUND`. Students
cannot create, edit or delete (403). The student must be linked to the teacher (403 `FORBIDDEN`).

```
GoalInput = { studentId, type: EXAM | LEVEL, examName?: string (1..100), targetLevel: A1..C2,
              targetDate?: YYYY-MM-DD, note?: string (≤ 2 000) }
GoalPatch = { examName?, targetLevel?, targetDate?, clearTargetDate?: bool, note?, clearNote?: bool,
              status?: ACTIVE | ACHIEVED | ARCHIVED }
```
Rules (400 `GOAL_INVALID` + `pointer`): `EXAM` needs `examName` and `targetDate`; `LEVEL` must not
have `examName`, `targetDate` optional; on create `targetDate` must not be before today (the
teacher's timezone). `type` cannot change. Status is set by the teacher (any transition, also back to
`ACTIVE`); `statusChangedAt` records the last change.

```
Goal {
  id, studentId, teacherId, type, examName?, targetLevel, targetDate?, note?,
  status: ACTIVE | ACHIEVED | ARCHIVED, statusChangedAt?, createdAt, updatedAt,
  baselinePercent: 0..100 | null,    // progress percent computed when the goal was created (null: checklist was empty)
  progress: GoalProgress
}
GoalProgress {
  percent: 0..100 | null,            // null when checklistEmpty
  checklistEmpty: bool,
  grammar: { total, practiced, covered, needsWork, notCovered },   // target level only, effective statuses
  topics: { total, covered },        // teacher topics whose levels contain the target level
  grammarDone, grammarTotal,         // breakdown under the bar: PRACTICED grammar topics / all (= grammar.practiced / grammar.total)
  topicsDone, topicsTotal,           // covered topics / all (= topics.covered / topics.total)
  daysLeft: int | null,              // targetDate − today (student's timezone); negative when past; null without targetDate
  expectedPercent: 0..100 | null,
  onTrack: bool | null               // null without targetDate, when not ACTIVE, or percent is null
}
```
**Progress formula** (computed on every read, against the goal teacher's library, target level only —
lower levels are not included):
```
credit(PRACTICED) = 1,  credit(COVERED) = 0.5,  credit(NEEDS_WORK) = credit(NOT_COVERED) = 0
grammarScore = Σ credit(effective status of G) / |G|        G = teacher's grammar topics with level = targetLevel
topicsScore  = covered topics / |T|                         T = teacher's topics whose levels contain targetLevel
percent      = round(100 × (0.8 × grammarScore + 0.2 × topicsScore))     (|T| = 0 → round(100 × grammarScore))
|G| = 0      → percent = null, checklistEmpty = true (no fake 0 %)
```
NEEDS_WORK earns no credit until the recent results improve (see the window above). Overrides count
(effective status). The percent is progress through the checklist, not exam readiness.

**On track** (only `ACTIVE` goals with `targetDate` and a `percent`), dates in the student's timezone.
The **baseline** is the goal's `percent` at creation (stored once; `null` → 0), so a student who
already stands at 60 % is measured against the remaining 40 %:
```
start = createdAt date;  span = max(1, targetDate − start) days;  elapsed = clamp(today − start, 0, span)
expectedPercent = round(baseline + (elapsed / span) × (100 − baseline))
onTrack = percent ≥ expectedPercent − 10          (10-point tolerance; past the date: expected = 100)
```
Listing goals runs a fixed number of grouped queries per distinct teacher in the result (no per-goal
queries).

### Tables (V14)

`learning_goals` + enums `GOAL_SET_BY` / `GOAL_STATUS` dropped. `goals` (student_id, teacher_id,
type `GOAL_TYPE`, exam_name?, target_level, target_date?, note?, baseline_percent?, status `GOAL_STATUS`
(ACTIVE/ACHIEVED/ARCHIVED), status_changed_at?, created/updated_at; CHECK exam ⇒ exam_name + target_date),
`grammar_progress_overrides` (PK student_id + grammar_topic_id, grammar topic `ON DELETE CASCADE`,
teacher_id, status `GRAMMAR_PROGRESS_STATUS`, note?, updated_at). Index
`assignment_items (document_id)` for the homework → document tag join.

### Error codes

| Code | Status | Notes |
|---|---|---|
| `GOAL_NOT_FOUND` | 404 | also for goals the caller cannot see |
| `GOAL_INVALID` | 400 | type-specific field rules, past `targetDate`; `pointer` |
| `GRAMMAR_OVERRIDE_INVALID` | 400 | unknown `status`, `note` too long; `pointer` |
| `GRAMMAR_TOPIC_NOT_FOUND` | 404 | override on another teacher's grammar topic |
| `TEACHER_STUDENT_NOT_FOUND` | 404 | progress of a student without (that) teacher |

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
Starting a lesson now sets status to `IN_PROGRESS` (was staying CONFIRMED before). Complete only works on IN_PROGRESS lessons.

### Start / complete
```
POST /api/v1/lessons/{id}/start      -> { videoRoom: VideoAccess }     teacher/admin, CONFIRMED only
POST /api/v1/lessons/{id}/complete   (no body) -> LessonResponse        teacher/admin, IN_PROGRESS only
```

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
  "rawNotes": "string | null (the lesson's one plain-text teacher note; teacher/admin only, null for students)",
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
Lesson teacher or admin, in any lesson status. `null` = unchanged; lists replace (`[]` clears). Ids must belong to the
lesson teacher's library (404 `TOPIC_NOT_FOUND` / `GRAMMAR_TOPIC_NOT_FOUND` / `VOCAB_ENTRY_NOT_FOUND`).

```
PUT    /api/v1/lessons/{id}/vocab-display   Body: { fields, allowTranslationToggle }
DELETE /api/v1/lessons/{id}/vocab-display
```
Per-lesson override of the vocab display setting for words students received in this lesson.

### Lesson notes (one field)
`lessons.raw_notes` is the only lesson note: plain text, written by the teacher. The live classroom
autosaves it with `PATCH /lessons/{id}/content { rawNotes }` (debounced; `""` clears), the recap shows
it, and the Nachbereitung panel prefills and edits the same value. Students never see it.
Removed with V15: the `lesson_documents` table (rich-text `teacherNotes`, `studentNotes`,
`teacherWentWell`, `teacherWorkingOn`, `studentReflection`, `studentHardToday`),
`PUT /lessons/{id}/sync`, `POST /lessons/{id}/reflect` and the `/complete` request body.
Existing `teacher_notes` HTML was converted to plain text into empty `raw_notes`. Corrected sentences
now reference the lesson directly (`lesson_corrections.lesson_id`). Also dropped: `lessons.has_ai_summary`,
`users.points` (never exposed).

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
- `gap_fill`: `solution.answers` may have fewer or more groups than gaps (e.g. a `___` was just deleted);
- `media` with neither `url` nor `materialId` (null / `""` / absent) — but never both;
- MC items with any number of correct options, also when `multiple` is false (e.g. just switched from multiple).
Only `url` + `materialId` both set on one media block is still rejected.
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
plus `blockCount` and `blockTypes: [string]` (distinct block types in document order, e.g.
`["heading", "vocab_table", "gap_fill"]`).

**Revision (optimistic concurrency)**: `revision` starts at 1 and is bumped by every successful
write to the document (`PUT`, share, unshare, restore). `PUT` must send the revision it was based
on; if it is not the current one the PUT is rejected with **409 `DOCUMENT_CONFLICT`** and the
ProblemDetail carries `currentRevision`. The response of every write returns the new `revision`
and `updatedAt`.

### Endpoints

```
GET    /api/v1/documents/schema            -> JSON Schema (any authenticated user)
GET    /api/v1/documents?level=&topicId=&grammarTopicId=&blockType=&audience=&lessonId=&studentId=&groupId=&q=&page=&pageSize=
       -> { documents: [DocumentSummary], total, page, pageSize }   updatedAt desc; q = title contains (ci); pageSize ≤ 100
       blockType = a block type from the table above (documents containing ≥ 1 such block; unknown → 400 VALIDATION_FAILED)
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
- `DUPLICATE`: on duplicate, a snapshot of the source;
- `AI_REFINE`: before an AI refine replaces a Nachbereitung draft (so Anna can restore her version).
Only the newest 30 versions per document are kept.

### Lessons

The lesson's old free-text shared document is gone (V9); lesson notes are `rawNotes` (see Lesson Changes).
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

## Nachbereitung — AI post-lesson flow (`/api/v1/lessons/{id}/nachbereitung`, `/api/v1/ai`)

Anna's raw notes + her free-text prompt → server adds context → AI (Koog, default model
`claude-sonnet-5`) → **validated JSON** → an unshared draft document + a vocab review list →
Anna reviews / refines → one transactional **publish**. Everything here is **teacher only**
(the lesson's teacher; admins may read jobs). Students never see jobs or drafts.

### Configuration

```
GET /api/v1/ai/status -> { available: bool }    teacher only (students / admins 403)
```
`available` is false when no provider is configured; the portal then hides AI buttons (generate,
fill-missing, material tag suggestions).

```
ai.provider  = anthropic | fake          AI_PROVIDER        (default anthropic)
ai.model     = claude-sonnet-5           AI_MODEL
ai.anthropic_api_key                     ANTHROPIC_API_KEY  (never in the repo)
ai.max_active_jobs_per_teacher = 2       AI_MAX_ACTIVE_JOBS_PER_TEACHER
ai.max_concurrent_jobs = 3               AI_MAX_CONCURRENT_JOBS   (global; extra jobs wait QUEUED)
ai.job_timeout = 600s                    AI_JOB_TIMEOUT          (whole job incl. the retry)
```
The app boots without a key: with `provider = anthropic` and an empty key every endpoint that
starts a job returns **503 `AI_NOT_CONFIGURED`** (reads still work). `fake` is a deterministic
offline provider used by all backend tests and the portal E2E (see below).

### Generation jobs

Jobs are persisted (`generation_jobs`) and run in-process on coroutines (single backend pod).
Clients **poll** `GET /api/v1/ai/jobs/{id}` (suggested every 2 s) until `SUCCEEDED` / `FAILED`.
On startup every `QUEUED` / `RUNNING` job left by a previous process is marked `FAILED` with
`AI_INTERRUPTED`. A teacher may have at most `max_active_jobs_per_teacher` jobs `QUEUED`+`RUNNING`
(more → **429 `AI_RATE_LIMITED`**); beyond the global cap jobs wait in `QUEUED`.

```
GenerationJob {
  id, kind: GENERATE | REFINE | FILL_TRANSLATIONS | SUGGEST_TAGS,
  status: QUEUED | RUNNING | SUCCEEDED | FAILED,
  lessonId: uuid | null,            // null for FILL_TRANSLATIONS / SUGGEST_TAGS
  materialId: uuid | null,          // SUGGEST_TAGS only (deleting the material deletes its jobs)
  parentJobId: uuid | null,         // REFINE: the job it refines
  documentId: uuid | null,          // the draft document (GENERATE/REFINE, set on success)
  input: {                          // what the teacher sent
    notes?, prompt?, promptTemplateId?,       // GENERATE
    instruction?,                             // REFINE
    entryIds?, fields?                        // FILL_TRANSLATIONS
  },                                          // SUGGEST_TAGS: {}
  result: NachbereitungResult | FillTranslationsResult | SuggestTagsResult | null,   // only when SUCCEEDED
  error: { code, message } | null,  // only when FAILED; message is short English debug text, never model output
  model: "claude-sonnet-5" | "fake" | null,
  attempts: 0..2,                   // model calls made (1 + at most one validation retry)
  usage: { inputTokens, outputTokens } | null,
  createdAt, startedAt?, finishedAt?, publishedAt?
}
```
Job error codes (in `error.code`, not HTTP statuses): `AI_OUTPUT_INVALID` (still invalid after
the retry), `AI_PROVIDER_ERROR` (provider/network error), `AI_RATE_LIMITED` (provider 429),
`AI_TIMEOUT`, `AI_INTERRUPTED` (server restarted), `DOCUMENT_NOT_FOUND` (REFINE: draft deleted meanwhile),
`MATERIAL_NOT_FOUND` (SUGGEST_TAGS: material gone before the job ran).

```
GET  /api/v1/ai/jobs/{id}                  -> GenerationJob          404 AI_JOB_NOT_FOUND (also for other teachers' jobs)
```

### Panel state

```
GET /api/v1/lessons/{id}/nachbereitung -> NachbereitungState
```
```
NachbereitungState {
  lessonId, mode: ONE_ON_ONE | CLUB,
  aiAvailable: bool,                   // false -> generate/refine return 503 AI_NOT_CONFIGURED
  notes: string | null,                // lesson.raw_notes (the notes the live classroom saved)
  prompt: string | null,               // lesson.prompt_used
  context: ContextSummary,             // what the server will add (shown read-only in the panel)
  latestJob: GenerationJob | null,     // newest GENERATE/REFINE of this lesson in any status (QUEUED/RUNNING
                                       // too, so the panel resumes polling after a reload), with result
  draftDocumentId: uuid | null,        // draft of the latest successful job
  publishedAt: ISO8601 | null          // last shared publish of this lesson (library-only saves don't count)
}
ContextSummary {
  level: A1..C2 | null,
  display: { fields, allowTranslationToggle }, displaySource: LESSON | STUDENT | LEVEL_DEFAULT,
  lessonOverrideActive: bool,          // = displaySource == LESSON
  knownWordCount,                      // words the context sends (1:1: the student's words from this teacher)
  coveredGrammar: [{ id, name, level }],
  grammarGaps: {                       // P8: what the model is told to target (same lists as the prompt)
    needsWork: [string],               // grammar topic names, checklist order, ≤ 30
    notCovered: [string],              // ≤ 30
    needsWorkCount, notCoveredCount    // uncapped totals
  },
  libraryTopicCount, libraryGrammarTopicCount,
  attendees: [{ id, firstName, lastName }],   // who publish targets (1:1: the student; club: confirmed attendees)
  attendeeCount
}
```
**Mode**: `CLUB` when the lesson has a `groupId` or its type is `SPEAKING_CLUB` / `READING_CLUB`,
else `ONE_ON_ONE`. A 1:1 lesson needs exactly one CONFIRMED student and a club lesson at least one
(else 400 `NACHBEREITUNG_NO_ATTENDEES` on generate).

### Generate

```
POST /api/v1/lessons/{id}/nachbereitung/generate
Body: { notes: string, prompt: string, promptTemplateId?: uuid }  -> 202 GenerationJob (QUEUED)
```
`notes` non-blank ≤ 20 000 chars, `prompt` ≤ 10 000 (may be blank: defaults apply) → 400
`VALIDATION_FAILED`. `promptTemplateId` is only recorded (the client inserts the template text
into `prompt` itself; 404 `PROMPT_TEMPLATE_NOT_FOUND` if not the teacher's). Nothing is written to
the lesson until publish. Any lesson status is allowed.

**Context the server appends** (Anna never types it). Built at request time, stored in nothing but
the prompt:
| | 1:1 | Club |
|---|---|---|
| level | student level, else lesson level | group level, else lesson level |
| display setting | lesson override > teacher–student setting > level default | lesson override > level default |
| known words | lemmas (+article) of the student's words from this teacher, newest 300 | lemmas linked to earlier lessons of the same group (or, without a group, the attendees' common words), newest 300 |
| covered grammar | grammar topics of the teacher's earlier lessons the student attended (CONFIRMED) | grammar topics of earlier lessons of the group |
| grammar gaps (P8) | the student's **effective** progress status (see Student progress) for the teacher's grammar topics **of the context level**: names with `NEEDS_WORK` and names with `NOT_COVERED` | per topic, the effective status of every confirmed attendee; a topic is listed when `NEEDS_WORK` for ≥ ⌈n/2⌉ attendees (resp. `NOT_COVERED` for ≥ ⌈n/2⌉) |
| library | the teacher's topic tree as paths (`Alltag > Haushalt`, ≤ 300) and grammar topic names with level (≤ 300) | same |

**Grammar gaps** (brief §2 "homework targets what the student is missing", §5.1): computed at request
time over the whole history (the current lesson counts if it is already tagged and held), in
checklist order (position, name), each list capped at 30 names; no level → no gaps. The prompt gets two
lines under "Grammar the student(s) still need to work on (weak homework results)" and "Grammar of
level X not covered yet"; the system prompt asks the model to prefer these when Anna's
instructions leave room (her instructions always win). Only names go to the model — no counts per
student, no names/ids.

**Privacy**: the model never receives student/teacher names, e-mails or any id (uuids are never
sent; topics/grammar are sent by name and mapped back server-side). Notes and prompt are sent as
Anna wrote them (HTML notes are converted to the same plain text as the prefill). Covered by a test that captures the fake provider's prompt.

**System prompt**: `resources/ai/nachbereitung-system.md` (+ `nachbereitung-club.md` for clubs,
`fill-translations-system.md`), editable without code changes. Rules: correct German (articles,
plural, capitalisation, verb forms, government), correct RU/EN translations, explanations in simple
German, Anna's instructions win over defaults, exercise types are not fixed (use `free_form` when
nothing fits), a club gets one overview (words by topic, short grammar tips as `grammar_box` TIP,
optional speaking questions as `free_sentences` SPEAKING).

### AI output (model → server, never sent to clients as is)

The model must return one JSON object (schema embedded in the system prompt; the server
validates, it does not trust the model):
```json
{
  "vocab": [{ "key": "v1", "lemma": "Gießkanne", "article": "DIE", "plural": "Gießkannen",
              "wordType": "NOUN", "forms": null, "government": null,
              "translations": { "ru": "лейка", "en": "watering can" },
              "explanationDe": "…", "exampleSentence": "…", "level": "A2", "synonyms": [],
              "topicName": "Haushalt" }],
  "document": { "title": "Haushalt – Wortschatz und Übungen", "blocks": [AiBlock] },
  "suggestedTopics": [{ "name": "Haushalt", "parentName": "Alltag" }],
  "suggestedGrammarTopics": [{ "name": "Reflexive Verben", "level": "A2" }],
  "correctedSentences": [{ "incorrect": "Darum muss du dicht kümmern", "correct": "Darum musst du dich kümmern" }]
}
```
`AiBlock` = a document block (schema above) with two differences, so the model never invents
uuids or library ids: every `id` (block/item/option/question) is any short unique string
(`"b1"`, `"o2"`; `correctOptionIds` reference them), and `vocab_table` has `vocabKeys: ["v1", …]`
instead of `rows`. The server then assigns fresh uuids (remapping `correctOptionIds`), turns each
`vocab_table` into `rows: []` and remembers `vocabTables: [{ blockId, vocabKeys }]`.

**Validation** (all must pass): JSON parses; shape above; `vocab` ≤ 150 items, each a valid
`VocabEntryInput` (article only for nouns, supported translation languages, unique keys);
`document.title` non-blank ≤ 255; blocks pass `DocumentBlockValidator.validate` (the published
schema) **and** `completenessIssues` is empty; every `vocabKeys` entry exists. On failure the
server retries **once**, sending the model its previous answer plus the list of problems
(JSON pointers + messages, ≤ 30). Still invalid → job `FAILED` / `AI_OUTPUT_INVALID`.

### Result (server → client)

On success (GENERATE) the server creates an **unshared draft document**: owner = teacher,
`audience` STUDENT (1:1) or GROUP (club), `level` = context level, `createdFromLessonId` = the
lesson but **not yet linked** to it (a linked document is readable by confirmed attendees, so the
link is only made at publish), tags = matched existing topics/grammar. The portal edits it with the normal document
endpoints (autosave `PUT`, versions).
```
NachbereitungResult {
  documentId: uuid,
  vocab: [ReviewVocabItem],
  vocabTables: [{ blockId: uuid, vocabKeys: ["v1", …] }],     // filled with entry ids at publish
  topics: [{ key: "t1", name, parentName?, existingId: uuid | null }],
  grammarTopics: [{ key: "g1", name, level?, existingId: uuid | null }],
  correctedSentences: [{ incorrect, correct }],
  publishedEntries: { "v1": "entry uuid" }                     // filled by publish
  savedToLibraryAt: ISO8601 | null                             // last library-only publish (share = false)
}
ReviewVocabItem {
  key: "v1",
  entry: VocabEntryInput,             // topicIds = matched existing topic ids
  topicKey: "t1" | null,              // suggested topic (from topics[]) — for new proposals
  matchedEntryId: uuid | null,        // same lemma+article+wordType already in the library
  matchedEntry: VocabEntry | null,    // the library version, so the UI can show a diff
  alreadyAssigned: bool,              // 1:1: the student has it; club: every attendee has it
  selected: bool                      // UI default: !alreadyAssigned
}
```
Matching: vocab by the library dedupe key (lower(lemma) + article + wordType); topics by name
(case-insensitive; with `parentName` the one under that parent wins); grammar by name
(case-insensitive, unique per teacher). Nothing is created in the library before publish.

```
PUT /api/v1/ai/jobs/{id}/review   Body: { vocab: [ReviewVocabItem] } -> GenerationJob
```
Optional: persists the review table edits (checkboxes, inline edits) into the job result so they
survive a refresh. Keys must exist in the result (400 `VALIDATION_FAILED`). Job must be SUCCEEDED
(409 `AI_JOB_NOT_READY`).

### Refine

```
POST /api/v1/ai/jobs/{id}/refine   Body: { instruction: string (1..4 000) } -> 202 GenerationJob (kind REFINE)
```
`{id}` = a SUCCEEDED GENERATE/REFINE job of the teacher (409 `AI_JOB_NOT_READY` otherwise).
Input to the model: the same context, the previous result's vocab/topics, the **current** draft
blocks (read at run time, converted back to AiBlock form incl. `vocabKeys`, so Anna's manual edits
are kept) and the instruction. Output schema as above. On success the draft is updated through the
normal document update path: snapshot (reason `AI_REFINE`), blocks + title replaced, `revision`
+1 (an open editor gets 409 `DOCUMENT_CONFLICT` on its next autosave and reloads). Words that already had
vocab_table rows (published, or added by hand) keep their rows; the refined result's
`publishedEntries` (key → entry id) carries them over. The new job's
`result` is the full new result (same `documentId`).

**Exercise numbers**: the app numbers exercise blocks "Übung 1, 2, …" when rendering (never stored):
only the types `gap_fill, multiple_choice, error_correction, free_sentences, writing_task, reading,
media, exam_part, free_form` count, in document order (`heading, rich_text, vocab_table,
grammar_box` do not). Generate and refine prompts tell the model this (`Prompts.EXERCISE_BLOCK_TYPES`,
kept identical to the portal's `isExercise`), so it writes no numbers into titles and maps "Mach
Übung 2 leichter" to the right block.

### Publish

```
POST /api/v1/lessons/{id}/nachbereitung/publish -> 200 PublishResult
Body: {
  jobId: uuid,                                // a SUCCEEDED GENERATE/REFINE job of this lesson
  vocab: [{ key, matchedEntryId?: uuid, entry?: VocabEntryInput, topicKeys?: ["t1"] }],   // only selected items
  topics: [{ key, name, parentId?: uuid, parentKey?: "t2" }],       // accepted NEW topic proposals
  grammarTopics: [{ key, name, level? }],                          // accepted NEW grammar proposals
  topicIds?: [uuid], grammarTopicIds?: [uuid],                      // existing ones to tag lesson + document with
  correctedSentences?: [{ incorrect, correct }],                    // replaces the lesson's list when present
  share: true                                                       // false = library-only save (see below)
}
```
One transaction:
1. **Topics / grammar**: find-or-create each accepted proposal (topic: same name under the same
   parent; grammar: same name). `topicKeys` / `parentKey` resolve to them.
2. **Vocab**: `matchedEntryId` → use that library entry as is (must be the teacher's, 404
   `VOCAB_ENTRY_NOT_FOUND`); else `entry` (required, validated like `POST /vocab-entries`) →
   find-or-create by the dedupe key, `sourceLessonId` = lesson, topics = `entry.topicIds` + resolved
   `topicKeys`. With `share: true` each entry is assigned to the student (1:1) or to every CONFIRMED
   attendee (club) with `lessonId` = lesson, and linked to the lesson's words. Existing assignments are
   skipped. With `share: false` entries are only found or created in the library: not assigned and not
   linked to the lesson.
3. **Lesson content**: `raw_notes` / `prompt_used` = the job's notes/prompt; topics / grammar =
   current ∪ `topicIds` ∪ created; words = current ∪ published (`share: true` only); corrected
   sentences replaced if sent.
4. **Document**: each `vocabTables[].blockId` still present in the draft gets `rows` for its
   published keys (unpublished keys dropped, existing rows kept, no duplicates); tags = the lesson's
   topics/grammar; saved via the update path (revision +1). With `share: true` it is linked to the lesson
   and shared with the student (1:1) or the group (club with a group) or the confirmed attendees
   (club without group); `share: false` leaves it unlinked, in the library only.
5. `share: true` sets `publishedAt` on the job (and so `NachbereitungState.publishedAt`); `share: false`
   leaves it untouched and sets `result.savedToLibraryAt` instead, so the panel still offers
   "Veröffentlichen" (not "Erneut veröffentlichen") after a library-only save.

**Library-only** (`share: false`, "Nur in Bibliothek speichern"): words, topics and the filled, tagged
document land in the teacher's library and the lesson content (notes, prompt, tags) is updated, but no
student gets words (no student-vocab rows, nothing in their practice queue) and the document is not
linked to the lesson or shared. Publishing the same job later with `share: true` assigns and shares
everything; find-or-create reuses what the library-only save created.

**Idempotent**: publishing the same (or a newer) job again creates nothing twice — find-or-create
everywhere, existing assignments/links/shares/rows are skipped; it simply re-applies edits.

```
PublishResult {
  documentId, revision,
  wordsCreated, wordsReused,            // library entries created / found (matchedEntryId or dedupe key)
  wordsAssigned,                        // new student-vocab rows (already assigned ones are not counted)
  recipients: int, recipientIds: [uuid],   // who received words / the document (0 / [] when share = false)
  topicsCreated, grammarTopicsCreated,
  vocab: [{ key, entryId, reused: bool }],
  topics: [{ key, id, reused: bool }], grammarTopics: [{ key, id, reused: bool }],
  publishedAt,                          // time of this publish
  shared: bool                          // = request.share; false: nothing assigned, linked or shared
}
```
Errors: 404 `AI_JOB_NOT_FOUND`, 409 `AI_JOB_NOT_READY`, 400 `AI_JOB_LESSON_MISMATCH` (job of
another lesson), 400 `VALIDATION_FAILED` (unknown `key`, `entry` missing without `matchedEntryId`),
404 `DOCUMENT_NOT_FOUND` (draft deleted), plus the vocab/topic codes above.

### Prompt templates (`/api/v1/prompt-templates`, teacher only)

```
GET    /api/v1/prompt-templates?lessonType=ONE_ON_ONE|CLUB&level=B1   -> [PromptTemplate]  (filters also match null) name asc
POST   /api/v1/prompt-templates        Body: PromptTemplateInput -> 201
GET    /api/v1/prompt-templates/{id}
PUT    /api/v1/prompt-templates/{id}   Body: PromptTemplateInput (full replace)
DELETE /api/v1/prompt-templates/{id}   -> 204
```
`PromptTemplateInput = { name (1..100), text (1..10 000), level?: A1..C2, lessonType?: ONE_ON_ONE | CLUB }`;
`PromptTemplate` = input + `id, teacherId, createdAt, updatedAt`. Name unique per teacher
(case-insensitive, 409 `PROMPT_TEMPLATE_DUPLICATE`); other teachers' → 404 `PROMPT_TEMPLATE_NOT_FOUND`.

### Library suggestions (no AI)

```
GET /api/v1/lessons/{id}/library-suggestions -> LibrarySuggestions
```
Tags considered: the lesson's topics/grammar ∪ `existingId`s of the latest successful job's
suggestions. Matches the teacher's documents (excluding ones created from this lesson) and
materials whose level equals the context level (or is null) and that share ≥ 1 tag, ranked by
overlap, ≤ 10 each.
```
LibrarySuggestions {
  level,
  summary: [{ kind: TOPIC | GRAMMAR, id, name, level?, documentCount, materialCount }],  // "3 B1 exercises on …"
  documents: [{ document: DocumentSummary, matchedTopicIds, matchedGrammarTopicIds }],
  materials: [{ material: MaterialResponse, matchedTopicIds, matchedGrammarTopicIds }]
}
```
Suggestions only — nothing is assigned.

### Fill missing translations (brief §5.3)

```
POST /api/v1/vocab-entries/fill-missing   Body: { entryIds: [uuid] (1..100), fields: [ru | en | de_explanation] (≥1) }
-> 202 GenerationJob (kind FILL_TRANSLATIONS)
```
Entries must be the teacher's (404 `VOCAB_ENTRY_NOT_FOUND`). The model gets only lemma, article,
plural, wordType, forms, government, example sentence and level. Only **empty** fields are filled
(never overwritten); entries with nothing missing are skipped without a model call.
`FillTranslationsResult = { updated: [{ entryId, filled: ["ru", …] }], skipped: [entryId] }`.

```
GET /api/v1/students/{studentId}/vocab/missing-fields?fields=ru,en,de_explanation -> { count, entryIds: [uuid] }
```
Teacher only (the student must be theirs, else 403). Entries of the calling teacher's library assigned
to that student that lack **any** of the requested fields (`fields` required, same codes as the display
setting, 400 `VOCAB_DISPLAY_FIELD_UNSUPPORTED`). Feed `entryIds` (in chunks of 100) to `fill-missing`.

### Fake provider (`ai.provider = fake`)

Deterministic, offline, no key. Derives output from the notes: each non-empty note line without
digits becomes a vocab item (`der/die/das X` → noun with that article; a capitalised single word →
noun, article by suffix; a lowercase word ending in `-en` → verb; else phrase), translations
`ru`/`en` = `"<lemma> (ru)"` / `"<lemma> (en)"`, explanation `"Erklärung: <lemma>"`. Document: a
heading, a `vocab_table` with all keys, a `gap_fill` and a `multiple_choice` on the first word,
and (club) a `grammar_box` TIP + `free_sentences` SPEAKING. Suggests topic `Alltag` and grammar
`Perfekt`. Output always passes the strict profile. Test markers in the prompt/instruction:
`[fake:invalid-once]` (first answer invalid → retry succeeds), `[fake:invalid]` (always invalid →
`AI_OUTPUT_INVALID`), `[fake:error]` (→ `AI_PROVIDER_ERROR`), `[fake:rate-limit]` (→ `AI_RATE_LIMITED`),
`[fake:delay=<ms>]`. Tests can read the prompts it received (in-process only, no endpoint).

### Error codes

| Code | Status | Notes |
|---|---|---|
| `AI_NOT_CONFIGURED` | 503 | no provider key; starting a job (generate, refine, fill-missing) |
| `AI_RATE_LIMITED` | 429 | too many active jobs for this teacher (also a job error code for provider 429) |
| `AI_JOB_NOT_FOUND` | 404 | |
| `AI_JOB_NOT_READY` | 409 | refine/review/publish on a job that is not SUCCEEDED (or wrong kind) |
| `AI_JOB_LESSON_MISMATCH` | 400 | publish with a job of another lesson |
| `AI_OUTPUT_INVALID` | – | job `error.code`: model output still invalid after one retry |
| `AI_PROVIDER_ERROR` | – | job `error.code`: provider/network error |
| `AI_TIMEOUT` | – | job `error.code`: exceeded `ai.job_timeout` |
| `AI_INTERRUPTED` | – | job `error.code`: server restarted while the job was queued/running |
| `INTERNAL_ERROR` | – | job `error.code`: unexpected server error while running the job |
| `NACHBEREITUNG_NO_ATTENDEES` | 400 | 1:1 without exactly one confirmed student / club without attendees |
| `PROMPT_TEMPLATE_NOT_FOUND` | 404 | |
| `PROMPT_TEMPLATE_DUPLICATE` | 409 | |

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

### AI tag suggestions (brief §3 "tagging easy, AI-suggested")

Started only on upload (below); poll `GET /api/v1/ai/jobs/{id}`.
**Suggestions only**: nothing is written to the material or the library. Anna applies them with the
existing `PUT /api/v1/materials/{id}` (`level`, `skill`, `topicIds`, `grammarTopicIds`), after
creating the accepted new topics / grammar topics with `POST /topics` / `POST /grammar-topics`.

**Auto-start on upload (opt-in)**: the `metadata` part of `POST /api/v1/materials` accepts
`suggestTags: true`. After the material is stored the server starts a SUGGEST_TAGS job and returns
its id in `MaterialResponse.suggestTagsJobId` (only in that 201 response). The upload never fails
because of AI: if AI is not configured or the teacher is at the job limit, the material is created
and `suggestTagsJobId` is `null`.

**Text sent to the model** (at most **6 000 chars** after whitespace normalisation, cut at a word
boundary):
- always the material `name` (as Anna typed it, not the uploaded file name);
- `PDF`-type materials with a stored file, chosen by content type, else file extension:
  - PDF (`application/pdf`, `.pdf`): text of pages 1–3 via **Apache PDFBox 3** (text only, no
    images / OCR); files > 20 MiB, encrypted or unreadable PDFs → name only;
  - DOCX (`application/vnd.openxmlformats-officedocument.wordprocessingml.document`, `.docx`):
    `word/document.xml` read straight from the zip (`java.util.zip` + StAX with DTDs / external
    entities disabled; `w:t` text, `w:p` → newline, `w:tab` → space; XML read capped at 5 MiB); no Apache POI;
  - plain text (`text/plain`, `text/markdown`, `text/csv`, `.txt`, `.md`, `.csv`): UTF-8, as is;
- `LINK`, `AUDIO`, `VIDEO` and any other file type: name only (links are never fetched).

Plus the teacher's library as names (topic paths `Alltag > Haushalt` and grammar names with level,
≤ 300 each, same as Nachbereitung) so the model reuses existing names. **Privacy**: nothing else
from the database is sent — no student or teacher names, e-mails, ids, lesson data or share
targets. The extracted text is sent as uploaded (it may contain whatever Anna put in the file).
Covered by a test that captures the fake provider's prompt.

Model output (validated, one retry with the problems, then `AI_OUTPUT_INVALID`):
```json
{ "level": "B1", "skill": "READING",
  "topics": [{ "name": "Haushalt", "parentName": "Alltag" }],
  "grammarTopics": [{ "name": "Perfekt", "level": "A2" }] }
```
`level` A1..C2 or null, `skill` a `MaterialSkill` or null, ≤ 5 topics and ≤ 5 grammar topics, names
non-blank ≤ 255 chars (case-insensitive duplicates dropped).
```
SuggestTagsResult {
  level: A1..C2 | null,
  skill: SPEAKING | LISTENING | READING | WRITING | GRAMMAR | VOCAB | null,
  topics: [{ name, parentName?, existingId: uuid | null }],        // existingId = matched library topic
  grammarTopics: [{ name, level?, existingId: uuid | null }],
  source: { kind: PDF | DOCX | TEXT | NAME_ONLY, chars: int, truncated: bool }   // what was sent
}
```
Matching = the Nachbereitung matching (topics by name case-insensitive, `parentName` breaks ties;
grammar by name). **Fake provider**: level = first `A1`..`C2` token in the text (else null); skill
`READING` for PDF/DOCX/TEXT, else null; topics = library topics whose name occurs in the text,
else `Alltag`; grammar = library grammar names occurring in the text, else `Perfekt`; the
`[fake:…]` markers work when they occur in the material name.

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
  "createdAt": "ISO8601",
  "suggestTagsJobId": "uuid | null   (only in the upload response with suggestTags: true)"
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
value in the request body, e.g. `/blocks/3/items/0/solution/answers`), `currentRevision`
(on `DOCUMENT_CONFLICT`), `usage: { words, documents, materials, lessons }` (on
`TOPIC_HAS_CONTENT` / `GRAMMAR_TOPIC_HAS_CONTENT`) and `resetsAt` (ISO8601, when a daily limit lifts,
on `PRACTICE_SENTENCE_LIMIT`).

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
| Homework | `ASSIGNMENT_NOT_FOUND`, `ASSIGNMENT_NO_STUDENTS`, `HOMEWORK_NOT_FOUND`, `HOMEWORK_ITEM_INVALID` (+ `pointer`), `HOMEWORK_ANSWER_INVALID` (+ `pointer`), `HOMEWORK_INVALID_STATE` (409), `SUBMISSION_LOCKED` (409), `UPLOAD_TYPE_NOT_ALLOWED`, `UPLOAD_LIMIT_REACHED` (409), `HOMEWORK_UPLOAD_NOT_FOUND` |
| Progress / goals | `GOAL_NOT_FOUND`, `GOAL_INVALID` (+ `pointer`), `GRAMMAR_OVERRIDE_INVALID` (+ `pointer`) |
| Vocabulary | `VOCABULARY_WORD_NOT_FOUND`, `VOCAB_ENTRY_NOT_FOUND`, `VOCAB_ENTRY_DUPLICATE`, `VOCAB_LANGUAGE_UNSUPPORTED`, `VOCAB_DISPLAY_FIELD_UNSUPPORTED`, `TEACHER_STUDENT_NOT_FOUND`; practice: `PRACTICE_STUDENT_ONLY` (403), `PRACTICE_MODE_INVALID`, `NOT_A_NOUN`, `SENTENCE_EMPTY`, `SENTENCE_TOO_LONG`, `PRACTICE_SENTENCE_LIMIT` (429, + `resetsAt`) |
| Documents | `DOCUMENT_NOT_FOUND`, `DOCUMENT_INVALID_BLOCK` (+ `pointer`), `DOCUMENT_DUPLICATE_ID` (+ `pointer`), `DOCUMENT_TOO_LARGE`, `DOCUMENT_CONFLICT` (409, + `currentRevision`), `DOCUMENT_VERSION_NOT_FOUND` |
| AI / Nachbereitung | `AI_NOT_CONFIGURED` (503), `AI_RATE_LIMITED` (429), `AI_JOB_NOT_FOUND`, `AI_JOB_NOT_READY`, `AI_JOB_LESSON_MISMATCH`, `NACHBEREITUNG_NO_ATTENDEES`, `PROMPT_TEMPLATE_NOT_FOUND`, `PROMPT_TEMPLATE_DUPLICATE`; job-only: `AI_OUTPUT_INVALID`, `AI_PROVIDER_ERROR`, `AI_TIMEOUT`, `AI_INTERRUPTED` |
| Library / groups | `TOPIC_NOT_FOUND`, `TOPIC_DUPLICATE`, `TOPIC_HAS_CHILDREN`, `TOPIC_HAS_CONTENT` (409, + `usage`), `TOPIC_CYCLE`, `TOPIC_MERGE_INVALID`, `GRAMMAR_TOPIC_NOT_FOUND`, `GRAMMAR_TOPIC_DUPLICATE`, `GRAMMAR_TOPIC_HAS_CONTENT` (409, + `usage`), `GRAMMAR_TOPIC_MERGE_INVALID`, `GRAMMAR_ORDER_INVALID`, `GROUP_NOT_FOUND`, `STUDENT_NOT_LINKED` |

---

## User locale

`UserResponse.locale` is the user's interface language (`en` | `de`, default `en`).
Set it with `PATCH /api/v1/users/me { "locale": "de" }` (admins: `PATCH /api/v1/admin/users/{id}`).
Unsupported values return 400 `UNSUPPORTED_LOCALE`.

`PATCH /api/v1/users/me` does **not** change `level`: sending it returns 400 `VALIDATION_FAILED`
("level can't be changed here; it is set by the teacher") and nothing is updated. Teachers set a
student's level with `PUT /api/v1/students/{studentId}/level`, admins with `PATCH /api/v1/admin/users/{id}`. The supported list lives in
`user/data/SupportedLocale.kt`; adding a language is a one-line change there.
