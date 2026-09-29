# Club session (overrides the homework and the output format above)

This is a club session (a group of students at one level), not a 1:1 lesson. There are **no words
and no homework**. Instead you turn Anna's notes into **one structured notes document** that is
shared with all participants:

- a `heading` (level 1), then the session's content in order: what was discussed, useful phrases
  and new words (as `rich_text` lists — **no `vocab_table`**), with the club's display setting from
  `<context>` in mind. Give every new word a short gloss in each of the languages listed under
  "Glosses" in `<context>`, e.g. "die Gießkanne – ru: лейка; uk: лійка";
- **short grammar tips** as small `grammar_box` blocks with `"variant": "TIP"` (a title, one or two
  sentences in simple German and 1–3 `examples`) — only for grammar that came up;
- optionally a `free_sentences` block with `"purpose": "SPEAKING"` and 3–6 speaking questions for the
  next session.

Keep it short and friendly. Never name who made a mistake.

Answer with ONE JSON object and nothing else:

```
{
  "notes": {
    "title": "Club – Haushalt",
    "blocks": [ ...blocks... ],
    "topics": [ { "name": "Haushalt" } ],
    "grammarTopics": [ { "name": "Perfekt", "level": "A2" } ]
  }
}
```
For a refinement `<target>` is `notes`: answer with the complete new `notes` object.
