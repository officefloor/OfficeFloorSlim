# OfficeFloorSlim — plan

The order of work for `DESIGN.md`. Tick each item as it lands. Phases 0 and 1 happen in
`~/SinewSlim`, the rest here. Everything is built and integrated locally; only a merge to `main`
publishes (Phase 7). Phases 4–6 go ahead only if Phase 3 passes the go/no-go bar below.

A copy of an earlier version of this plan, with the architecture drawn, is the doc
<https://claude.ai/artifact/GrhB2UfvvfH2rQEpwMp23D>. This file is the one to keep current.

## Decided (31 October 2026)

- Maven group `net.officefloor.sinewslim` for both projects; artifacts `sinewslim-*` and
  `officefloorslim-*`. Only SinewSlim publishes to Central.
- The OfficeFloor IntelliJ plugin is copied in without its git history and renamed "OfficeFloor"
  on the Marketplace (the plugin ID stays).
- The two costs are reported side by side, never in a common unit.
- Growth window: the last N commits or the last 12 months, whichever is shorter (N = 500 by
  default, configurable).
- AI migration lives only in the IntelliJ plugin, with pluggable providers.
- The empirical study is `~/ui-long-degradation-test`.
- This repository is public.
- The go/no-go bar: category 3 at least a quarter of the not-slimmable long methods, and at
  least 30 of them (Phase 3).
- The OfficeFloor cost model, questions 1 to 3: a carried value costs 1 per function that
  declares it; a function and an edge cost 1 each; everything from outside the method is
  injected and free, so only values computed inside it cost.

## Phase 0: Central publishing setup (in SinewSlim; built, uncommitted)

SinewSlim's Gradle build already publishes `core`, `javaparser` and `config` as
`sinewslim-core`, `sinewslim-javaparser` and `sinewslim-config`. The changes are uncommitted on
SinewSlim's `main` (`build.gradle.kts`, `settings.gradle.kts`, the three modules' build files).

- [x] Add `com.vanniktech.maven.publish` 0.37.0, the POM metadata and a local `staging` repository
- [x] Dry run: `./gradlew -Pversion=0.2.0 publishAllPublicationsToStagingRepository` writes jar, sources, javadoc and POM per module to `build/staging-deploy`
- [x] `./gradlew publishToMavenLocal` installs `0.0.0-SNAPSHOT` into `~/.m2`
- [x] Group `net.officefloor.sinewslim` (already set in SinewSlim's build)
- [x] Commit to a branch in SinewSlim, `central-libraries` (94e1277, local only, not pushed)
- [ ] Optional: fill in the javadoc comments behind the ~98 warnings

## Phase 1: restructure the SinewSlim libraries (in SinewSlim)

Before anything goes to Central, because a Central release cannot be deleted. Build on
SinewSlim's `central-libraries` branch, which holds the Phase 0 publishing setup; `main` does
not have it yet. The branch merges to `main` once Phases 1–3 work locally.

- [ ] Rename the Gradle projects to their artifact names (`sinewslim-core`, …), so composite builds substitute them automatically
- [ ] Move `MethodFacts`, `Stmt`, `StmtKind`, `Var`, `Jumps`, `MethodIndex`, `Seam` into `sinewslim-facts`; `sinewslim-core` depends on it
- [ ] Move `RepoFiles`, `Scanner`, `Glob` out of `cli` into `sinewslim-scan`
- [ ] Add the facts extension point: a consumer adds its own extractors (annotations, try/catch, declared exceptions, non-Java files) without changing the shared schema
- [ ] Version the facts schema, continuing the study's numbering (schema 3)
- [ ] Keep `split.py` parity and the full test suite green throughout
- [ ] Leave `Params.framework()` in place; Phase 3 uses it as the stand-in for the OfficeFloor reading, and it goes in Phase 4
- [ ] Update SinewSlim's `DESIGN.md` for the new modules

## Phase 2: this project's skeleton and local wiring

- [x] Create `~/OfficeFloorSlim` and `officefloor/OfficeFloorSlim` with `DESIGN.md` and `PLAN.md`
- [x] Make the repository public
- [ ] Gradle build: Java 17, the same Gradle version as SinewSlim (9.1.0), group `net.officefloor.sinewslim`, modules `officefloorslim-*`, version from the tag as `-Pversion`
- [ ] Depend on `sinewslim-facts`, `sinewslim-javaparser`, `sinewslim-scan`, `sinewslim-core` by their coordinates
- [ ] Composite build against `../SinewSlim` (see `DESIGN.md`, *Local development*); check that an edit in SinewSlim shows up in the next build here
- [ ] Open both in one IntelliJ workspace
- [ ] If an unreleased OfficeFloor is needed: `mvn install` in `~/OfficeFloor`, plus `mavenLocal()` restricted to `net.officefloor` snapshots
- [ ] CI workflow that builds and tests (no publishing yet)

## Phase 2b: bring the OfficeFloor plugin in

- [ ] Copy `~/OfficeFloor-IntelliJ-Plugin` into `intellij/` as one commit (no history)
- [ ] Keep the plugin ID `net.officefloor.ide.rest.plugin`, so everyone with 0.1.1 installed gets the update
- [ ] Rename it on the Marketplace from "OfficeFloor REST View" to "OfficeFloor"
- [ ] Keep its signing and Marketplace publishing; move its release workflow across
- [ ] Expose the graph model (which classes and methods are wired as functions) to the classification
- [ ] After the first release from here: archive the old repository with a pointer to this one

## Phase 3: scan and the study (the go/no-go)

A report-only scan that prices every long method both ways. Until Phase 4 exists, the
OfficeFloor column is SinewSlim's framework reading (`Params.framework()`), labelled as such.

- [ ] Classification: OfficeFloor function, framework entry point, conventional (`DESIGN.md`, *Classification*)
- [ ] Extend the facts with annotations, try/catch and declared exceptions through the Phase 1 extension point
- [ ] Read `application.officefloor`, `.woof`, procedure YAML and Spring configuration
- [ ] Per method over 26 lines: SinewSlim status and cost, the OfficeFloor column, migration cost, growth, category
- [ ] Wiring measures per commit: YAML lines, functions, edges, and how often a change touches them
- [ ] Versioned JSON output, plus HTML or Markdown
- [ ] Run on `~/OfficeFloor` and read the category 3 list by hand
- [ ] Go/no-go for Phases 4–6, by the bar below

**The go/no-go bar (fixed now, before any scan).** Category 3 means not slimmable
conventionally but slimmable under the OfficeFloor reading. Go if both hold, pooled over every
chain of the Spring arms at their last checkpoint:

1. category 3 is **at least a quarter** of the not-slimmable long methods; and
2. there are **at least 30** category 3 methods.

Below a quarter, conventional fixes (categories 1 and 2) solve most of the stuck methods, and
an OfficeFloor split tool would be marginal. Below 30, there are too few to read by hand with
any confidence, or they could come from one unlucky chain. If the Spring arms have too few long
methods for the bar to be read at all, that is a finding in itself, and `~/OfficeFloor`'s own
scan decides instead, on the same two numbers.

## Empirical study: `~/ui-long-degradation-test`

Runs alongside Phase 3. The experiment is running on another machine (about two weeks); its
commits arrive on the stacks' `evolve/…` branches. See `DESIGN.md`, *Empirical studies*.

- [x] Confirm how the experiment produces its Spring and OfficeFloor variants: the same 250-checkpoint stream, agent and specs on paired base repositories `~/officehq-<frontend>-spring` and `-officefloor`
- [x] Draft the analysis plan (H1–H4, measures, normalisation, the go/no-go bar): `~/ui-long-degradation-test/docs/SINEWSLIM_ANALYSIS_PLAN.md`
- [x] Commit it (61fe7f1, branch `sinewslim-analysis-plan` in the experiment repository)
- [ ] Amend it with the run IDs, the SinewSlim commit and `docs/sinewslim-analysis.yml`, **before** SinewSlim reads any branch of the current runs
- [ ] A scan driver: for each `evolve/<run>/<condition>/chain<N>` branch, run SinewSlim at every checkpoint commit, backend Java only, and write one JSON per commit
- [ ] Pilot the driver on an old run (for example `officehq-angular-spring`, `evolve/202610112340/just-solve/chain1`), not on the current runs
- [ ] Check SinewSlim's code lines against Lizard's NLOC on the pilot, so the harness's series can be read beside the new one
- [ ] Wiring counts for the OfficeFloor arms, per commit
- [ ] Normalise by passing tests, as the harness does, and report raw as well
- [ ] Pin tool versions and `.sinew-slim.yml`; publish the raw JSON with every result
- [ ] When the runs finish: scan, then test H1–H4 as written, with every method where OfficeFloor costs more listed
- [ ] Exploratory, labelled: agent failures against the length and the sinew of the methods each checkpoint touched
- [ ] Decide where to publish the study
- [ ] `assess` command in the CLI and Docker image for any open-source repository; worked examples on OfficeFloor and the MLCQ repositories (demonstrations, not part of the study)

## Phase 4: OfficeFloor split proposals

- [x] Settle cost model questions 1–3 (carried values per declaring function; function and edge 1 each; everything from outside the method free)
- [ ] Settle cost model questions 4–7 (decision functions, escalations, selection order, where the wiring counts) before any comparison, starting from the suggested defaults
- [ ] The graph-partition search (`DESIGN.md`, *The OfficeFloor split*)
- [ ] A `return` ends a function as a `@Next` or flow; a `throw` becomes an escalation
- [ ] An `if` or `switch` can become a decision function whose branches are flows
- [ ] Loops, `break` and `continue` still block a split
- [ ] Its own not-slimmable reasons (loops, carried values)
- [ ] Tests from hand-written fact sets and fixture sources, as SinewSlim's
- [ ] Re-run the study's scan with the real OfficeFloor column in place of the framework reading
- [ ] In the plugin: long functions highlighted on the graphs, the split previewed as new functions and flows, first on code that already uses OfficeFloor
- [ ] In SinewSlim: remove `Params.framework()` and the `framework:` configuration

## Phase 5: wiring

- [ ] Generate the functions as code, plus the YAML or woof wiring, from the plan
- [ ] An IDE refactoring that applies the split, checked by reading the facts again against the plan

## Phase 6: AI-assisted migration (opt-in, in the plugin only)

- [ ] A provider interface (the plan and the code in, the rewritten code out) with implementations for Anthropic, OpenAI, Google and an OpenAI-compatible or local endpoint
- [ ] Provider, model and key in the IDE settings; the key in IntelliJ's credential store
- [ ] Convert a conventional or Spring method, and the code around it, following the Phase 4 plan
- [ ] Check every rewrite: compile, read the facts again against the plan, run the tests; keep the plan if it fails
- [ ] Show the change for review straight after it is made
- [ ] Contain one method at a time, with OfficeFloor hosting the Spring beans

## Phase 7: release pipelines

Wired only once Phases 1–3 work locally. The first Central release of the libraries is 0.x.

- [ ] SinewSlim `release.yml`: a `publishToMavenCentral` step, `-Pversion` from the tag, after build and test
- [ ] Map the existing OfficeFloor secrets (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`) to the four `ORG_GRADLE_PROJECT_*` properties (`DESIGN.md`, *Release*)
- [ ] Set them at the OfficeFloor organisation level by a `setup-central-publishing.sh` in OfficeFloorMaintenance
- [ ] This repository's release: Docker image, CLI, and the plugin to the Marketplace with its existing signing and secrets
- [ ] Release order: SinewSlim tagged and on Central, version bumped here, then this repository tagged
