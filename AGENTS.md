# Project guidance

Read [docs/README.md](docs/README.md), [initial decisions](docs/initial-decisions.md),
and the [roadmap](docs/roadmap.md) before starting project work. Then read the
specification relevant to the current task.

## Local skills and conventions

- For Java/Spring implementation or review, read
  [spring-boot-engineer](docs/skills/spring-boot-engineer/SKILL.md) and the
  [implementation guide](docs/implementation-guide.md).
- For service unit tests, read
  [write-simple-unit-tests](docs/skills/write-simple-unit-tests/SKILL.md).
- For milestone reviews, follow [Milestone review](docs/README.md#milestone-review).
- These are project-local adaptations of the ticketing reference (2026-10-21). Use
  these paths directly; they are not globally installed skills.

## Working rules

### Implementation decisions

- Prefer explicit local variable types in production and tests, especially for
  method, repository, and mapper results. Use `var` only when the initializer makes
  the type directly visible, such as `new Courier(...)`.

- Use feature-based packages: `courier/{api,application,domain,persistence}`,
  `store/{domain,persistence}`, `common/mapping`, `common/persistence` (shared
  `BaseEntity` base class), and the accepted shared `common/geo` calculation
  package, under `com.mbi.couriertracking`.
  Create packages only when needed by actual code. Keep courier code and its tests
  in the courier feature; shared mapper configuration stays in `common.mapping`.
- Controllers call concrete service classes directly. Do not introduce a service
  interface solely for dependency injection or mocking. This does not remove the
  explicitly approved distance-calculation Strategy abstraction.
- Use Lombok `@RequiredArgsConstructor` with `private final` required dependencies
  for constructor injection in controllers/services. Keep immutable DTOs as Java
  records. Use other Lombok annotations where useful, without exposing unrestricted
  domain mutation through blanket setters or `@Data` on entities.
- Use MapStruct for entity/DTO conversion. Every mapper must use
  `@Mapper(config = BaseMapperConfig.class)` with the shared Spring component model,
  constructor injection, and `unmappedTargetPolicy = ERROR`.
- Inject mapper beans. Declare renamed and intentionally ignored fields explicitly;
  do not suppress unmapped-target errors or edit generated implementations. Mappers
  translate data; business rules and repository access belong outside them.
- Keep Lombok, MapStruct, and `lombok-mapstruct-binding` annotation processors
  configured consistently for main and test compilation. Verify real generated
  mappings when conversion behavior changes.
- Add entity mappings when the relevant entities are implemented; do not invent a
  domain model just to populate a mapper. Preserve the current API contract.

### Development workflow

- Prioritize human-readable implementation, business logic, and focused tests.
  Keep supporting annotations and documentation concise. Defer advanced tooling
  and speculative abstractions until needed; preserve required correctness checks.

- Write code, comments, documentation, and command descriptions in English.
  Preserve proper names from supplied data. The owner may provide Turkish input.
- The project owner makes architecture and product behavior decisions. Help
  compare alternatives and clarify consequences; do not silently decide open items.
- Work through one roadmap step at a time. Draft specifications before implementing
  domain behavior. A proposed roadmap item or draft is not implementation approval.
- Defer detailed rule clarifications until their implementation milestone. Ask only
  what is needed for current work; preserve previous answers without re-asking them.
- Record decisions explicitly made by the owner. Existing authorization remains
  valid; do not request the same approval again. Record architectural decisions
  as ADRs in `docs/architecture-decisions.md` (Context, Decision, Alternatives,
  Trade-offs); the root README repeats only the five most important ones. Keep
  detailed behavior and evidence in the specification. Do not paste conversation
  quotes or review verdicts into documentation.
- Keep proposals, accepted decisions, implemented behavior, and verified behavior
  distinct. Do not turn a domain term into a class, aggregate, or table by assumption.
- Implement authorized work, run relevant checks, and record evidence in its spec.
  Update the roadmap and affected documentation when completing a step.
- Use the Gradle Wrapper and Makefile commands documented in the root README.
- Preserve the owner's IntelliJ IDEA formatting and import conventions exactly.
  Use the IDE's native Reformat Code and Optimize Imports actions with its active
  project settings when an IDE integration is available. Do not substitute
  Spotless, Google Java Format, or guessed import rules. Reformatting alone does
  not prove imports were optimized. If native automation is unavailable, report
  that limitation; do not claim exact formatting verification.
- Do not add infrastructure or features outside the agreed scope without discussing
  the decision with the owner.
