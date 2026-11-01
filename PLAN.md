# OfficeFloorSlim — plan

The order of work for `DESIGN.md`. Tick each item as it lands. Phases 0 and 1 happen in
`~/SinewSlim`, the rest here. Everything is built and integrated locally; only a merge to `main`
publishes (Phase 7). The go/no-go bar below is a reading, not a gate (decided 1 November 2026):
OfficeFloorSlim is a model of code, and is built and released whatever the study's Spring arms
show.

A copy of an earlier version of this plan, with the architecture drawn, is the doc
<https://claude.ai/artifact/GrhB2UfvvfH2rQEpwMp23D>. This file is the one to keep current.

## Decided (31 October 2026)

- Maven group `net.officefloor.sinewslim` for both projects; artifacts `sinewslim-*` and
  `officefloorslim-*`. Only SinewSlim publishes to Central.
- The OfficeFloor IntelliJ plugin is copied in without its git history and renamed "OfficeFloor IoCC"
  on the Marketplace (the plugin ID stays; "OfficeFloor" is taken there).
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
- The categories, operationally (1 November 2026). The not-slimmable long methods (M1, the
  bar's denominator) are both SinewSlim statuses that one return per piece cannot fix:
  `NOT_SLIMMABLE` and `SLIMMABLE_WITH_RECORD`. They are put in a category conventional first:
  2 if records slim it; else 1 if the default limits slim it once jumps are ignored (guard
  clauses inverted); else 3 if the OfficeFloor column slims it; else 4.
- The OfficeFloor cost model, questions 4 to 7 (1 November 2026): a decision function costs 1
  and an edge per branch, the join's edges ordinary edges; an escalation costs only when the
  split creates a handler (a function and an edge per exception type); the plan is chosen by
  SinewSlim's order (worst seam, then fewest functions, then total); a function's seam is the
  values it declares, and the wiring is counted in the total, not the seam or the migration
  column.
- The OfficeFloor split's limits (1 November 2026, before the study's runs are read): at most 5
  carried values per function, as SinewSlim's parameter limit; a lambda's body and a loop are
  never cut, as planned.
- Studies are separate from the products (1 November 2026). Each experiment keeps its harness,
  analysis and paper in its own repository and calls SinewSlim's and OfficeFloorSlim's command
  lines for every cost; neither product holds study code. The SinewSlim analysis's tools moved
  to `~/ui-long-degradation-test/harness/sinewslim/` (f1c09f8 there).
- A second study, `~/long-method-slim-study` (public): a sample of open-source repositories,
  their long methods priced by SinewSlim and by the OfficeFloor split, and whether the split
  takes what extraction cannot. It runs on released versions of both products, so both are
  completed and released first: SinewSlim merged and on Central, OfficeFloorSlim's CLI and
  Docker image released with its JSON contract documented. The plugin work and the AI migration
  carry on independently.

## Phase 0: Central publishing setup (in SinewSlim; built, uncommitted)

SinewSlim's Gradle build already publishes `core`, `javaparser` and `config` as
`sinewslim-core`, `sinewslim-javaparser` and `sinewslim-config`. The changes are uncommitted on
SinewSlim's `main` (`build.gradle.kts`, `settings.gradle.kts`, the three modules' build files).

- [x] Add `com.vanniktech.maven.publish` 0.37.0, the POM metadata and a local `staging` repository
- [x] Dry run: `./gradlew -Pversion=0.2.0 publishAllPublicationsToStagingRepository` writes jar, sources, javadoc and POM per module to `build/staging-deploy`
- [x] `./gradlew publishToMavenLocal` installs `0.0.0-SNAPSHOT` into `~/.m2`
- [x] Group `net.officefloor.sinewslim` (already set in SinewSlim's build)
- [x] Commit to a branch in SinewSlim, `central-libraries` (94e1277, pushed)
- [ ] Optional: fill in the javadoc comments behind the ~98 warnings

## Phase 1: restructure the SinewSlim libraries (in SinewSlim; done, 555aafe on `central-libraries`, not pushed)

Before anything goes to Central, because a Central release cannot be deleted. Build on
SinewSlim's `central-libraries` branch, which holds the Phase 0 publishing setup; `main` does
not have it yet. The branch merges to `main` once Phases 1–3 work locally.

- [x] Rename the Gradle projects to their artifact names (`sinewslim-core`, …), so composite builds substitute them automatically (directories keep their short names)
- [x] Move `MethodFacts`, `Stmt`, `StmtKind`, `Var`, `Jumps`, `MethodIndex`, `Seam` into `sinewslim-facts`; `sinewslim-core` depends on it. Package `net.officefloor.sinewslim.facts`; `Seam` keeps its roles and `Params` prices it
- [x] Move `RepoFiles`, `Scanner`, `Glob` out of `cli` into `sinewslim-scan`. `Scanner` takes the caller's reader; the CLI's assessment stays in `cli` as `HealthScan`
- [x] Add the facts extension point: a consumer adds its own extractors (annotations, try/catch, declared exceptions, non-Java files) without changing the shared schema. `JavaFacts.Extractor` and `FactKey` into `MethodUnit.extras()`; non-Java files through `Scanner` with the consumer's reader
- [x] Version the facts schema, continuing the study's numbering (schema 3): `MethodFacts.SCHEMA`
- [x] Keep `split.py` parity and the full test suite green throughout (63 tests; parity on the committed subset)
- [ ] Full `split.py` parity on the whole export (`export_split_golden.py`, all three sets; about an hour) before `central-libraries` merges to `main`
- [x] Leave `Params.framework()` in place; Phase 3 uses it as the stand-in for the OfficeFloor reading, and it goes in Phase 4
- [x] Update SinewSlim's `DESIGN.md` for the new modules

## Phase 2: this project's skeleton and local wiring

- [x] Create `~/OfficeFloorSlim` and `officefloor/OfficeFloorSlim` with `DESIGN.md` and `PLAN.md`
- [x] Make the repository public
- [x] Gradle build: Java 17, the same Gradle version as SinewSlim (9.1.0), group `net.officefloor.sinewslim`, modules `officefloorslim-*`, version from the tag as `-Pversion`. One module so far, `officefloorslim-core` (in `core/`, package `net.officefloor.officefloorslim.core`); the CLI comes with Phase 3, the plugin with Phase 2b
- [x] Depend on `sinewslim-facts`, `sinewslim-javaparser`, `sinewslim-scan`, `sinewslim-core` by their coordinates (`sinewslimVersion` in `gradle.properties`, bumped on a release)
- [x] Composite build against `../SinewSlim` (see `DESIGN.md`, *Local development*); check that an edit in SinewSlim shows up in the next build here (all four resolve to its projects with no substitution block; a probe constant added there compiled and passed in a test here)
- [ ] Open both in one IntelliJ workspace (by hand: opening this build imports `../SinewSlim` as its included build)
- [ ] If an unreleased OfficeFloor is needed: `mvn install` in `~/OfficeFloor`, plus `mavenLocal()` restricted to `net.officefloor` snapshots (not needed yet: nothing here depends on OfficeFloor)
- [x] CI workflow that builds and tests (no publishing yet): `.github/workflows/ci.yml`, with SinewSlim's `central-libraries` checked out beside it until its libraries are on Central (first run 41217184669 passed)

## Phase 2b: bring the OfficeFloor plugin in

- [x] Copy `~/OfficeFloor-IntelliJ-Plugin` into `intellij/` as one commit (no history), from its 7bc06d4: sources, samples and README, its build folded into this one (`:intellij`, `-Pversion`), its 14 tests passing
- [x] Keep the plugin ID `net.officefloor.ide.rest.plugin`, so everyone with 0.1.1 installed gets the update
- [ ] Rename it on the Marketplace from "OfficeFloor REST View" to "OfficeFloor IoCC" (`plugin.xml` says so; the listing follows with 0.2.1. 0.2.0's upload as "OfficeFloor" was refused: the name is taken)
- [x] Keep its signing and Marketplace publishing; move its release workflow across (`release.yml`, `verify-plugin.yml`, on `:intellij`)
- [x] Before the first release from here: add `OfficeFloorSlim` to OfficeFloorMaintenance's `jetbrains-plugin-repositories.txt` and run `setup-jetbrains-publishing.sh`, so this repository can read the publishing secrets (added, 01574af there; the script is to run)
- [x] Expose the graph model (which classes and methods are wired as functions) to the classification: `officefloorslim-graph` (`graph/`, package `net.officefloor.officefloorslim.graph`) holds the model and `ComposeGraph`, the YAML rules; the plugin reads by PSI, the scan by SnakeYAML (`ComposeYamlReader`), and `ReaderParityTest` checks they give the same graph on every sample
- [x] After the first release from here: archive the old repository with a pointer to this one (archived 1 November 2026, after 0.2.1)

## Phase 3: scan and the study (the go/no-go)

A report-only scan that prices every long method both ways. Until Phase 4 exists, the
OfficeFloor column is SinewSlim's framework reading (`Params.framework()`), labelled as such.

- [x] Classification: OfficeFloor function, framework entry point, conventional (`DESIGN.md`, *Classification*): `Classification` in `officefloorslim-core`
- [x] Extend the facts with annotations, try/catch and declared exceptions through the Phase 1 extension point: `ShapeExtractor` (also the binary class name, the parameters and the code lines)
- [x] Read `application.officefloor`, `.woof`, procedure YAML and Spring configuration: `WiringFile`; Spring's configuration is Java annotations, read with the shape
- [x] Per method over 26 lines: SinewSlim status and cost, the OfficeFloor column, migration cost, growth, category (`Pricing`, `Growth`: blame at `HEAD` over the window, on SinewSlim's code lines)
- [x] Wiring measures per commit: YAML lines, functions, edges, and how often a change touches them (at the commit scanned, and the window's commits that touched it; a series is the scan run at each commit, as the study's driver does)
- [x] Versioned JSON output, plus HTML or Markdown: `officefloorslim scan --format json|markdown`, schema `officefloorslim-scan/1`
- [x] Run on `~/OfficeFloor` and read the category 3 list by hand (88e86fca97, 1 November 2026; 7 s): 11,462 methods, 446 long, 119 not slimmable: category 1 74, 2 6, **3 12**, 4 27. Category 3 is 10.1% of them, under the bar's quarter, but this is a reading, not the decision. All 12 are conventional code, mostly section and office sources threading a designer and a context through every step; read by hand, `loadKeysForCoverage` (seven parameters, used inside a lambda) earns its category, since only its computed values would cost once its parameters are injected. Most stuck methods (74) are conventionally fixable once restructured.
- [ ] Go/no-go for Phases 4–6, by the bar below: waits on the study's Spring arms (the experiment is still running); OfficeFloor's own scan decides only if they have too few long methods

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
- [ ] Amend it with the run IDs, the SinewSlim commit and `docs/sinewslim-analysis.yml`, **before** SinewSlim reads any branch of the current runs; and with the categories' operational definition (decided 1 November 2026), which section 7 leaves open
- [x] A scan driver: for each `evolve/<run>/<condition>/chain<N>` branch, run SinewSlim at every checkpoint commit, backend Java only, and write one JSON per commit: `harness/sinewslim/scan_driver.py` in the experiment. A checkpoint is measured at its `cpNN agent` commit, as the analysis plan registers, once its `cpNN reset` commit shows it finished (it first scanned the reset commit; on the pilot the two give the same figures); only the backend root `stack.yaml` declares is extracted; W1-W3 beside it; OfficeFloorSlim's scan too with `--officefloorslim`, for the categories. It refuses any run not named with `--allow-run`, so no current run is read by accident.
- [x] Pilot the driver on an old run (for example `officehq-angular-spring`, `evolve/202610112340/just-solve/chain1`), not on the current runs (1 November 2026: all 250 checkpoints in 5 minutes, every file parsed; at cp250 803 backend methods, 51 long, M1 9, the same in both tools; categories 1/2/3/4 = 1/2/3/3; the `/__test__` seed method is one of the category 4)
- [x] Check SinewSlim's code lines against Lizard's NLOC on the pilot, so the harness's series can be read beside the new one: `harness/sinewslim/lizard_compare.py` in the experiment, with Lizard 1.23.0 as the harness pins it (1 November 2026, the pilot chain, all 250 checkpoints). Where both report a method they agree: 99.1% of 108,057 lengths equal, Lizard never lower (by at most 5), 42 method-checkpoints long by Lizard only. But **Lizard 1.23.0 does not read Java records**: a record's own methods vanish, the methods after one go under its name, and in `InvoiceService.java` it lost the last ~500 lines. So 627 of SinewSlim's 6,698 long method-checkpoints (9%) are not in the harness's series at all, from cp57 on; at cp250, 5 of 51 long methods. The harness's class-shape measures take those wrong container names too
- [ ] Tell the experiment: its Lizard misses methods in and after records (above), and this machine's `~/ui-long-degradation-test/.venv` has lizard 1.24.1, not the pinned 1.23.0; check the runner's
- [ ] Wiring counts for the OfficeFloor arms, per commit
- [x] Normalise by passing tests, as the harness does, and report raw as well: `harness/sinewslim/normalise.py` in the experiment, the passing tests from the capture the harness commits on the chain branch (`evolve-results/capture/cpNN.json`), full-suite captures only (the harness's new design runs the full suite every tenth checkpoint and at the last; `tests.scope`); pooled sum over sum, raw beside; a checkpoint without a passing count is listed and left out. On the pilot chain: all 250 graded; at cp250 M1 9 over 391 passing (M1n 0.023), pooled M1n 0.032 and M2n 0.119. (The September runs could not be used: none of their branches is on this machine, and their records CSVs keep only pass flags, not the per-test results.)
- [ ] Pin tool versions and `.sinew-slim.yml`; publish the raw JSON with every result
- [ ] When the runs finish: scan, then test H1–H4 as written, with every method where OfficeFloor costs more listed
- [x] Exploratory, labelled: agent failures against the length and the sinew of the methods each checkpoint touched: `harness/sinewslim/agent_limit.py` in the experiment (the first gate is the capture's when it ran on the `cpNN agent` commit; a touched not-slimmable method is flagged and banded, having no slim cost). On the pilot chain (1 November 2026): the agent's own spec passed at the first gate at all 250 checkpoints (a `just-solve` agent runs the end-to-end tests itself before it stops), so the planned outcome has nothing to explain there. Beyond the plan, labelled so, the whole gate as the outcome: 91 of 250 failed (regressions of earlier specs), about 36% in every slim-cost band and with no trend by length; the fit's effects are small and opposite (+0.21 per sd of length, −0.14 per sd of cost). Whether the planned outcome varies on the gated runs is for their scan
- [ ] Decide where to publish the study
- [ ] `assess` command in the CLI and Docker image for any open-source repository; worked examples on OfficeFloor and the MLCQ repositories (demonstrations, not part of the study)

## Phase 4: OfficeFloor split proposals

Resumed (1 November 2026): the bar is a reading, not a gate.

- [x] Settle cost model questions 1–3 (carried values per declaring function; function and edge 1 each; everything from outside the method free)
- [x] Settle cost model questions 4–7 (decision functions, escalations, selection order, where the wiring counts) before any comparison, starting from the suggested defaults (1 November 2026; the defaults, with the join and the new-handler rule made explicit, and the wiring in the total as the worked example has it)
- [x] The graph-partition search (`DESIGN.md`, *The OfficeFloor split*): `OfficeFloorSplit`, `officefloorslim-split/1`, a dynamic program over each statement sequence (runs of whole statements, or one long compound statement expanded), the plan chosen by worst seam, then functions, then total
- [x] A `return` ends a function as a `@Next` or flow; a `throw` becomes an escalation (a return before a function's end is one flow, to the end; an escalation costs only where a try is cut, question 5)
- [x] An `if` or `switch` can become a decision function whose branches are flows (a switch whose cases fall through is not cut; its default and arrow form are read with the shape)
- [x] Loops, `break` and `continue` still block a split (so do `synchronized` and labeled statements, a try with resources or a finally, and a lambda's body, which runs inside its call)
- [x] Its own not-slimmable reasons (loops, carried values): loop, long statement, try, switch, carried, jumps, with the blocking statement. The carried-value limit is 5 per function, as SinewSlim's parameter limit (decided)
- [x] Tests from hand-written fact sets and fixture sources, as SinewSlim's: `OfficeFloorSplitTest`, 12 cases worked out by hand against the cost model
- [ ] Re-run the study's scan with the real OfficeFloor column in place of the framework reading: the scan's column is the split now (`officefloorslim-scan/2`); the study's runs are still to come. Read so far (1 November 2026): on OfficeFloor itself category 3 falls from 12 to 8 (6.7% of 119), and on the pilot chain at cp250 from 3 to 0. Loops are the commonest block (82 of the 164 long methods with no plan on OfficeFloor), then long single statements: on the pilot, `recordPayment` is one 130-line `found.ifPresent(invoice -> {...})`, a lambda the split does not cut (SinewSlim extracts inside lambdas)
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

- [x] SinewSlim `release.yml`: a `publishToMavenCentral` step, `-Pversion` from the tag, after build and test (20ba26d on `central-libraries`: a `maven-central` job; the deployment is published by hand in the Central Portal, RELEASING.md)
- [x] Map the existing OfficeFloor secrets (`SONATYPE_USERNAME`, `SONATYPE_PASSWORD`, `GPG_PRIVATE_KEY`, `GPG_PASSPHRASE`) to the four `ORG_GRADLE_PROJECT_*` properties (`DESIGN.md`, *Release*)
- [ ] Set them at the OfficeFloor organisation level by a `setup-central-publishing.sh` in OfficeFloorMaintenance (written, 800df70 there; to run by an owner: they are OfficeFloor's repository secrets today, which cannot be read or copied)
- [x] This repository's release: Docker image, CLI, and the plugin to the Marketplace with its existing signing and secrets (`release.yml`; it builds on SinewSlim from Central and refuses a snapshot `sinewslimVersion`; the first release is 0.2.0 or later, above the plugin's 0.1.1)
- [ ] Release order: SinewSlim tagged and on Central, version bumped here, then this repository tagged
