Document `blocks` follow the JSON Schema below with exactly two differences:

1. Every `id` (blocks, items, options, questions) is a short unique string such as "b1", "b1i1",
   "o3" — not a uuid. `solution.correctOptionIds` reference option ids in the same way.
2. A `vocab_table` block (only where allowed) has `"vocabKeys": ["w1", "w2"]` (keys given to you)
   INSTEAD of `rows`, and no `topicId`: `{ "id": "b2", "type": "vocab_table", "title": "Haushalt", "vocabKeys": ["w1"] }`.

Rules the schema cannot show, all mandatory:
- No empty texts: headings, questions, options, sentences, prompts, writing points must be filled.
- `gap_fill`: each item's `text` contains at least one gap written as `___` (three underscores) and
  `solution.answers` has exactly one list of accepted answers per gap, in order.
- `multiple_choice`: at least two options, `solution.correctOptionIds` with exactly one id (or
  several when `"multiple": true`). Same for `CHOICE` questions.
- `media` blocks need a real https url; only use them if Anna gives you one.
- Rich text (`content`, `text` of reading) is ProseMirror JSON:
  `{ "type": "doc", "content": [ { "type": "paragraph", "content": [ { "type": "text", "text": "…" } ] } ] }`
  with only the nodes and marks allowed by the schema. Text nodes must not be empty.
- Never add fields the schema does not allow.

```json
{{BLOCK_SCHEMA}}
```
