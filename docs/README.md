# Project documentation

Project context, decisions, and specifications. Start here when resuming work.
The document map is the [Documentation table in the root README](../README.md#documentation).

## Current implementation

All case-study behavior is implemented and verified against PostgreSQL: courier
registration and listing, location persistence with duplicate suppression, atomic
travel-distance accumulation, the distance query, and store-entry detection with
the one-minute rule, persisted as store visits with entry and exit times and a
store summary on the courier row (S011). The same reports can be consumed from
Kafka when the optional `kafka` profile is active (S010). All architectural
decisions are recorded in [Architectural decisions](architecture-decisions.md);
the [root README](../README.md#architectural-decisions) repeats the five that
shape the system most.

For manual testing, start with [Swagger and curl examples](api-examples.md).

## Working cycle

1. Select the next milestone in the [roadmap](roadmap.md) and read its prerequisites.
2. Discuss only the open decisions that milestone needs. The owner chooses
   architecture and product behavior; alternatives and consequences are laid out first.
3. Record the agreed choice as concrete examples and acceptance criteria in a
   specification. Keep unanswered items explicitly open.
4. After the owner approves the specification, implement its scope with tests,
   following the [implementation guide](implementation-guide.md) and the local
   skills under `skills/`.
5. Review against the exit criteria (below), record verification evidence in the
   specification, and update the roadmap.

Creating a document or roadmap item is not implementation authorization.
Approvals already given are not requested again.

## Milestone review

Apply to the current milestone or requested diff; do not re-audit the whole project
for a small change. Review in this order:

1. Accepted case requirements and milestone exit criteria.
2. Observable correctness, domain invariants, and API compatibility.
3. Relevant transaction, retry, concurrent update, and restart behavior.
4. Failure handling and evidence from tests at the appropriate layer.
5. Simplicity, operational instructions, and documentation accuracy.

Review actual source and test evidence rather than treating a roadmap checkbox as
proof. Identify uncertain design choices as questions, not verified defects. Honor
the owner's focus on understandable implementation, business logic, and tests:
check whether names and control flow explain the behavior and whether tests
demonstrate it; flag unnecessary abstraction, repeated validation, or annotation
noise. Do not make advanced tooling or speculative improvements release
requirements without an agreed need.

For each finding give severity (`BLOCKER`, `HIGH`, `MEDIUM`, `LOW`, `QUESTION`),
file and line, observed behavior, impact, the smallest useful correction, and how
to verify it. Finish with `PASS`, `PASS WITH NOTES`, `CHANGES REQUIRED`, or
`NOT REVIEWABLE` and a short reason; state test gaps even when no defects are
found. A milestone whose required behavior is still a placeholder is not complete.
A review-only request stays read-only; an explicit fix request permits the edits.

## Specification conventions

- Name specifications `specs/NNN-short-topic.md`. Use stable IDs such as `S001-R01`
  (requirement), `S001-D01` (decision), and `S001-AC01` (acceptance criterion) so
  tests and follow-up work can reference behavior precisely. Specification numbers
  are independent of milestone numbers.
- Statuses: `Draft`, `Accepted`, `In progress`, `Complete`. A draft may contain
  previously agreed rules; its unresolved proposals remain unapproved. Mark work
  complete only after its acceptance checks pass.
- Keep proposals, accepted decisions, implemented behavior, and verified behavior
  distinct. Do not claim checks that have not run.
- The initial decisions remain the baseline. A later specification may refine or
  replace a rule only through an explicit owner decision, recorded in that spec and
  in [Architectural decisions](architecture-decisions.md).
- Keep one canonical copy of each document.

### Specification structure

Header lines: `Status`, `Milestone`, `Dependencies`, `Related` (ADRs). Then:

1. **Purpose and scope**: the observable outcome, included work, explicit exclusions.
2. **Decisions**: table `ID | Question | Options and consequences | Decision`;
   open items are marked `Open`.
3. **Requirements**: `SNNN-R01: ...` statements.
4. **Acceptance criteria**: table `ID | Given | When | Then`, covering boundary,
   invalid input, failure, retry, concurrency, and restart cases; mark irrelevant
   categories as not applicable with a brief reason.
5. **Implementation**: affected components, written after agreement. New
   architectural choices are recorded as ADRs, not introduced here.
6. **Verification**: acceptance criterion IDs mapped to tests or manual checks, plus
   remaining limitations.
