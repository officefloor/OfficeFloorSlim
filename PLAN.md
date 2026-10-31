# OfficeFloorSlim — plan

The order of work for `DESIGN.md`. Tick each item as it lands. Phases 0 and 1 happen in
`~/SinewSlim`, the rest here. Everything is built and integrated locally; only a merge to `main`
publishes (Phase 7). Phases 4–6 go ahead only if the Phase 3 study shows enough methods in
category 3 ("only splits under function injection").

A copy of this plan, with the architecture drawn, is the doc
<https://claude.ai/artifact/GrhB2UfvvfH2rQEpwMp23D>. This file is the one to keep current.

## Phase 0: Central publishing setup (in SinewSlim; built, uncommitted)

SinewSlim's Gradle build already publishes `core`, `javaparser` and `config` as
`sinewslim-core`, `sinewslim-javaparser` and `sinewslim-config`. The changes are uncommitted on
SinewSlim's `main` (`build.gradle.kts`, `settings.gradle.kts`, the three modules' build files).

- [x] Add `com.vanniktech.maven.publish` 0.37.0, the POM metadata and a local `staging` repository
- [x] Dry run: `./gradlew -Pversion=0.2.0 publishAllPublicationsToStagingRepository` writes jar, sources, javadoc and POM per module to `build/staging-deploy`
- [x] `./gradlew publishToMavenLocal` installs `0.0.0-SNAPSHOT` into `~/.m2`
- [ ] Commit to a branch in SinewSlim, `central-libraries`
- [ ] Optional: fill in the javadoc comments behind the ~98 warnings

## Phase 1: restructure the SinewSlim libraries (in SinewSlim)

Before anything goes to Central, because a Central release cannot be deleted.

- [ ] Rename the Gradle projects to their artifact names (`sinewslim-core`, …), so composite builds substitute them automatically
- [ ] Move `MethodFacts`, `Stmt`, `StmtKind`, `Var`, `Jumps`, `MethodIndex`, `Seam` into `sinewslim-facts`; `sinewslim-core` depends on it
- [ ] Move `RepoFiles`, `Scanner`, `Glob` out of `cli` into `sinewslim-scan`
- [ ] Add the facts extension point: a consumer adds its own extractors (annotations, try/catch, declared exceptions, non-Java files) without changing the shared schema
- [ ] Version the facts schema, continuing the study's numbering (schema 3)
- [ ] Keep `split.py` parity and the full test suite green throughout
- [ ] Leave `Params.framework()` in place; it goes in Phase 4
- [ ] Update SinewSlim's `DESIGN.md` for the new modules

## Phase 2: this project's skeleton and local wiring

- [x] Create `~/OfficeFloorSlim` and `officefloor/OfficeFloorSlim` with `DESIGN.md` and `PLAN.md`
- [ ] Decide the Maven group (open: `net.officefloor.slim` or `net.officefloor.sinewslim`)
- [ ] Gradle build: Java 17, the same Gradle version as SinewSlim (9.1.0), version from the tag as `-Pversion`
- [ ] Depend on `sinewslim-facts`, `sinewslim-javaparser`, `sinewslim-scan`, `sinewslim-core` by their coordinates
- [ ] Composite build against `../SinewSlim` (see `DESIGN.md`, *Local development*); check that an edit in SinewSlim shows up in the next build here
- [ ] Open both in one IntelliJ workspace
- [ ] If an unreleased OfficeFloor is needed: `mvn install` in `~/OfficeFloor`, plus `mavenLocal()` restricted to `net.officefloor` snapshots
- [ ] CI workflow that builds and tests (no publishing yet)

## Phase 2b: bring the OfficeFloor plugin in

- [ ] Move `~/OfficeFloor-IntelliJ-Plugin` into `intellij/`, keeping its git history (`git subtree` or `git filter-repo`)
- [ ] Keep the plugin ID `net.officefloor.ide.rest.plugin`, so everyone with 0.1.1 installed gets the update
- [ ] Keep its signing and Marketplace publishing; move its release workflow across
- [ ] Expose the graph model (which classes and methods are wired as functions) to the classification
- [ ] Decide the Marketplace name: keep "OfficeFloor REST View", or rename it
- [ ] After the first release from here: archive the old repository with a pointer to this one

## Phase 3: scan and study (the go/no-go)

A report-only scan that prices every long method both ways.

- [ ] Classification: OfficeFloor function, framework entry point, conventional (`DESIGN.md`, *Classification*)
- [ ] Extend the facts with annotations, try/catch and declared exceptions through the Phase 1 extension point
- [ ] Read `application.officefloor`, `.woof`, procedure YAML and Spring configuration
- [ ] Per method over 26 lines: SinewSlim status and cost, OfficeFloor cost, migration cost, growth from git, category
- [ ] Decide how the two costs are compared: a common unit, or side by side
- [ ] Decide the growth window: the last N commits, or the last 12 months
- [ ] Versioned JSON output, plus HTML or Markdown
- [ ] Run on `~/OfficeFloor` and on at least two large Spring codebases; read the category 3 list by hand
- [ ] Go/no-go for Phases 4–6

## Empirical studies and public assessments

Runs alongside Phase 3 and grows with each later phase.

- [ ] Confirm how `~/ui-long-degradation-test` produces its Spring and OfficeFloor variants, and that both implement the same features
- [ ] Write the hypotheses and metrics down before the runs (mutative versus additive: `DESIGN.md`)
- [ ] Measure at each step of the change sequence, not only at the end
- [ ] Label every figure as measured or projected; check projections on a sample by doing the splits
- [ ] Pin tool versions and `.sinew-slim.yml`; publish the raw JSON with every result
- [ ] Report every method where OfficeFloor costs more, not only the savings
- [ ] `assess` command in the CLI and Docker image for any open-source repository
- [ ] Worked examples on OfficeFloor and the MLCQ repositories
- [ ] Decide where to publish the study, and whether to register the hypotheses first

## Phase 4: OfficeFloor split proposals

- [ ] The graph-partition search (`DESIGN.md`, *The OfficeFloor split*)
- [ ] Cost model: values carried across an edge, plus each function and edge; injected values free
- [ ] A `return` ends a function as a `@Next` or flow; a `throw` becomes an escalation
- [ ] An `if` or `switch` can become a decision function whose branches are flows
- [ ] Loops, `break` and `continue` still block a split
- [ ] Its own not-slimmable reasons (loops, carried values)
- [ ] Tests from hand-written fact sets and fixture sources, as SinewSlim's
- [ ] In the plugin: long functions highlighted on the graphs, the split previewed as new functions and flows, first on code that already uses OfficeFloor
- [ ] In SinewSlim: remove `Params.framework()` and the `framework:` configuration

## Phase 5: wiring

- [ ] Generate the functions as code, plus the YAML or woof wiring, from the plan
- [ ] An IDE refactoring that applies the split, checked by reading the facts again against the plan

## Phase 6: AI-assisted migration (opt-in)

- [ ] Decide the default AI provider and model, and where the credentials live
- [ ] Convert a conventional or Spring method, and the code around it, following the Phase 4 plan
- [ ] Check every rewrite: compile, read the facts again against the plan, run the tests; keep the plan if it fails
- [ ] Contain one method at a time, with OfficeFloor hosting the Spring beans

## Phase 7: release pipelines

Wired only once Phases 1–3 work locally. The first Central release of the libraries is 0.x.

- [ ] SinewSlim `release.yml`: a `publishToMavenCentral` step, `-Pversion` from the tag, after build and test
- [ ] Map the existing OfficeFloor secrets (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`) to the four `ORG_GRADLE_PROJECT_*` properties (`DESIGN.md`, *Release*)
- [ ] Set them at the OfficeFloor organisation level by a `setup-central-publishing.sh` in OfficeFloorMaintenance
- [ ] This repository's release: Docker image, CLI, and the plugin to the Marketplace with its existing signing and secrets
- [ ] Release order: SinewSlim tagged and on Central, version bumped here, then this repository tagged
