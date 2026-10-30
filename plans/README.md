# Change & Refactoring Plans

Working documents for planned framework changes: upgrade plans, refactorings, review findings and their
adjudication. Unlike [`docs/`](../docs/00-index.md), which is the user guide for framework consumers, these plans
are internal and track decisions and progress.

## Documents

| Plan                                            | Target | State  |
|-------------------------------------------------|--------|--------|
| [1.5.0 Upgrade & Refactoring](1.5.0-upgrade.md) | 1.5.0  | Active |

## Conventions

- One document per plan, named `<target-version>-<topic>.md` (or `<topic>.md` if not tied to a release).
- Every item has a stable ID, which is never reused or renumbered, so it can be referenced from commits and
  discussions (e.g. `Refs: 1.5.0/A-03`):

  | Prefix | Kind                                                        |
  |--------|-------------------------------------------------------------|
  | `U-`   | Upgrade work (version bumps, migrations)                    |
  | `Q-`   | Open question, needs a decision before or during the work   |
  | `A-`   | Architecture / code finding                                 |
  | `B-`   | Build / publishing finding                                  |
  | `I-`   | Further change idea, added as it comes up                   |

- Item status:

  | Status        | Meaning                                                  |
  |---------------|----------------------------------------------------------|
  | `proposed`    | Recorded, awaiting adjudication                          |
  | `accepted`    | Decided to do, not started                               |
  | `rejected`    | Decided not to do (keep the item, note the reason)       |
  | `deferred`    | Not for this target, revisit later                       |
  | `in-progress` | Being worked on                                          |
  | `implemented` | Done on the branch, awaiting review/merge                |
  | `done`        | Reviewed and merged                                      |

- Adjudicate an item by setting its status in the plan's overview table and filling in its **Decision** line.
- Each item records priority (`high`/`medium`/`low`) and whether it is **breaking** for framework consumers.
- Each plan ends with a dated progress log; add an entry when items change state.
