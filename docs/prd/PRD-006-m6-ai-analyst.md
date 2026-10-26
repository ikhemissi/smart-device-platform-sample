# PRD-006 — M6: AI analyst

| Field | Value |
|---|---|
| Status | Draft |
| Milestone | M6 |
| Depends on | M3 (M5 for alert-related questions) |
| Overview | [PRD-000](PRD-000-product-overview.md) |

## 1. Goal

Let users **ask questions in natural language** about devices and their measurements, and get answers grounded in the platform's actual data.

## 2. Example questions

- "Which devices of the Lyon site have been silent since yesterday?"
- "How did the temperature of *cold-room-3* evolve over the last 7 days? Anything unusual?"
- "Compare humidity between the Paris and Lyon sites this week."
- "Which devices had the most alerts this month, and for which reading?"
- "Is the battery of any device likely to run out soon?"

## 3. User stories

| ID | As a… | I want… | So that… |
|---|---|---|---|
| M6-US1 | Data Viewer | to open an assistant panel from anywhere in the portal and ask a question | I get insight without building charts myself |
| M6-US2 | Data Viewer | answers to be based on the platform's real data and to say which devices and periods were used | I can trust and verify them |
| M6-US3 | Data Viewer | to ask follow-up questions in the same conversation | I can refine my analysis |
| M6-US4 | Data Viewer | answers to appear progressively | I do not wait in front of a blank panel |
| M6-US5 | Data Viewer | the assistant to take the device I am looking at as context | I do not have to repeat which device I mean |
| M6-US6 | Platform operator | the assistant to only see data the user is allowed to see and never change anything | it cannot be misused |

## 4. Acceptance criteria

1. The assistant answers the example questions above using platform data, citing devices and periods used.
2. The assistant never modifies data, even when asked to, and says so politely.
3. A user without permission to query data cannot use the assistant.
4. The first words of an answer appear within **5 seconds** for typical questions.
5. A conversation keeps its context for follow-up questions for at least **1 hour**.
6. When the data needed is unavailable, the assistant says so instead of guessing.
7. Answers are in the language of the question (at least English and French).

## 5. Out of scope

- Taking actions (editing devices, thresholds, certificates) through the assistant.
- Predictive models trained on the data.
- Voice interaction.
