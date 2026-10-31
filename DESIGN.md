# OfficeFloorSlim — design

> **Status (31 October 2026). DESIGN, NOT YET BUILT.** Nothing in this repository is code
> yet. This document sets out what OfficeFloorSlim is, why it is separate from SinewSlim, and
> how the two share their libraries. `PLAN.md` is the order of work. Both were written at the
> end of the SinewSlim session that settled the split, so this document carries everything
> that session decided; nothing here depends on that conversation.

## What it does, in one paragraph

SinewSlim (`~/SinewSlim`, `officefloor/SinewSlim`) measures conventional Java. It finds
methods of 26 code lines or more and the cheapest way to extract pieces until every piece and
the remainder are at 25 or fewer, where the cost of a piece is what crosses its boundary. A
method is under the cap, slimmable, or not slimmable with a reason. OfficeFloorSlim asks the
other question for the same long method: **what would it cost as functions that OfficeFloor
injects and sequences**, rather than methods the remainder calls? It classifies each method as
an OfficeFloor function, a framework entry point or conventional code. It prices the
OfficeFloor split beside the SinewSlim cost and proposes the split: the functions plus their
wiring. Given AI credentials and configuration, it can also convert a method and the code
around it to OfficeFloor, which is the route for migrating from Spring one method at a time.
It takes in the OfficeFloor IntelliJ plugin, so a long function and its split show on the
plugin's graphs.

## Why it is a separate product

The framework reading that SinewSlim has today is only a re-weighting of its own algorithm.
`Params.framework()` in SinewSlim's `core` sets:

```java
freeParams(true).maxOutputs(-1).multiOutputWeight(1).countUnpacking(false)
```

The search behind it is unchanged: a remainder method that calls extracted pieces. Jumps still
block a split, and the remainder must still fit under the cap. SinewSlim's `DESIGN.md` already
calls the framework edit "a different refactoring … applied by hand until a framework-aware one
exists". That refactoring is this product. The difference is not only the weights: it changes
what a split is, so the search changes too.

| | Conventional extract-method (SinewSlim) | OfficeFloor function split (OfficeFloorSlim) |
|---|---|---|
| **Shape of the result** | A tree. The remainder keeps the control flow and calls the pieces, so the remainder must also fit under the cap. | A graph. There is no remainder: each function is a node the framework sequences. |
| **Parameter cost** | Every input is coupling the caller supplies. This drives `max_inputs` and the soft premium. | Injected values (the method's own parameters, managed objects) cost nothing. Only what one function hands the next costs. |
| **Outputs** | One return. Two or more need a record (3 per value) plus unpacking lines. | Several outputs are fine, as `Out<T>` / `@Val`: about linear per value, no unpacking. |
| **`return` / `throw` inside a run** | Blocks the split. On OfficeFloor's own code (October 2026) jumps cause 79 of the 106 not-slimmable methods. | A `return` becomes a `@Next` or flow; a `throw` becomes an escalation. Both are normal edges. |
| **`if` / `switch`** | Extracted whole, or their inner blocks extracted. | Can become a decision function whose branches are flows: a cut point of its own. |
| **Cost of a piece** | A name and a call line in the remainder. | A function plus its wiring (a flow or `@Next` edge); no line in a caller. |
| **`break` / `continue`, loops** | Block the split. | Still mostly block it: a loop body is not a flow without spawning. The two models agree here. |

The first row and the jump and branch rows change the **search space**:

- **Conventional:** a bottom-up dynamic program over nested statement sequences, with the
  remainder's length as state.
- **OfficeFloor:** a partition of the method's control flow into connected regions. A branch
  may be split into flows, and a `return` ends a region. The cost terms are the data carried
  across edges and the number of edges.

The conventional dynamic program cannot express "this `if` becomes a decision node with two
flows", however it is weighted. Kept together, every tuning change would need checking against
both models, and the OfficeFloor model would stay a re-weighted tree search. So the two
algorithms live in separate products and evolve separately. They share only the facts.

## Components

```
SinewSlim tools ─────┐            OfficeFloorSlim ──────┐
(CLI, Docker, Action,│            (classification, cost,│
 GitLab, plugin)     │             split, wiring,       │
                     │             migration, plugin)   │
                     ▼                                  ▼
 ┌─ shared libraries, Maven Central, net.officefloor.sinewslim ─────────┐
 │  sinewslim-config ──▶ sinewslim-core ───────────┐                    │
 │  sinewslim-scan ────▶ sinewslim-javaparser ─────┤                    │
 │                                                 ▼                    │
 │                                          sinewslim-facts             │
 └──────────────────────────────────────────────────────────────────────┘
```

Each arrow points from a part to what it depends on. Two rules hold:

- **One way only.** OfficeFloorSlim depends on the SinewSlim libraries, because it shows the
  SinewSlim cost beside its own. SinewSlim never depends on, or mentions, OfficeFloorSlim.
- **SinewSlim stays neutral.** A health measure is trusted only if it is not built to flatter
  anyone. The comparison with OfficeFloor is computed here, never in SinewSlim.

| Component | Purpose | Ships as |
|---|---|---|
| SinewSlim libraries | Parsing, method facts, seam roles, scanning; the conventional cost and split | Maven Central, `net.officefloor.sinewslim:sinewslim-*` |
| SinewSlim tools | Health scan, review gate, conventional split in the IDE | CLI jar, Docker image, GitHub Action, GitLab component, JetBrains plugin |
| OfficeFloorSlim | Classification, OfficeFloor cost and split, wiring, assessments | Docker image and CLI; nothing on Central (self-contained: only SinewSlim's libraries are shared) |
| OfficeFloorSlim IntelliJ plugin | OfficeFloor development and visualisation (today's OfficeFloor REST View), long functions and their splits marked on the graphs, and the AI-assisted migration | JetBrains Marketplace as "OfficeFloor", keeping the plugin ID `net.officefloor.ide.rest.plugin` |

### The shared libraries

They are published from SinewSlim. Today SinewSlim's `core` holds both the facts and the
search, and the scanning (`RepoFiles`, `Scanner`, `Glob`) lives in `cli`, which is never
published. Before the first Central release SinewSlim is restructured:

| Module | Holds | Taken from |
|---|---|---|
| `sinewslim-facts` | `MethodFacts`, `Stmt`, `StmtKind`, `Var`, `Jumps`, `MethodIndex`, `Seam` | `core` |
| `sinewslim-javaparser` | Facts from Java source, by JavaParser, syntax only | `javaparser` |
| `sinewslim-scan` | Walking a repository, include and exclude | `cli` |
| `sinewslim-core` | `Params`, `Search`, `Run`, `Slimmer`, `Assessor`, `SlimResult`, `Pieces`, `Binding` | `core` |
| `sinewslim-config` | `.sinew-slim.yml` | `config` |

The Gradle projects are renamed to match the artifact names, so a composite build substitutes
them without a substitution block. The facts gain an extension point: a consumer adds its own
extractors (annotations, try/catch structure, declared exceptions, non-Java files) without
changing the shared schema, so OfficeFloorSlim can grow what it reads without breaking
SinewSlim's parity with the study's `split.py`. The schema itself is versioned, continuing the
study's numbering (the facts are its live-width facts, schema 3), so a consumer can tell which
facts it was given and SinewSlim can change them without silently breaking OfficeFloorSlim.

SinewSlim keeps `Params.framework()` and the `framework:` configuration until
OfficeFloorSlim's own algorithm exists, then removes them (or keeps them only as a rough
"would function injection help?" hint).

## Classification

Every method is put in one of three classes, from evidence first, with heuristics only as a
fallback:

| Class | Evidence | What OfficeFloorSlim does |
|---|---|---|
| OfficeFloor function | `@Next`, `@FlowInterface`, `@Parameter`, `@Val`, `Out<T>`; a node on the plugin's graphs; the class named in `application.officefloor`, a `.woof` or procedure YAML | Prices and proposes the OfficeFloor split; shows it on the graph |
| Framework entry point | Spring `@RestController`, `@Service`, or similar: called by a framework, not by the project's own code | Prices both ways; the natural place for a migration to start, since nothing in the project calls it directly |
| Conventional | Called by the project's own code | Shows the SinewSlim cost and split, and the projected OfficeFloor cost beside it |

The reading is chosen from how the framework marks its functions, never guessed from the
method body, and every report says which reading is in force for each method.

## The OfficeFloor split

This is the algorithm to design and test in Phase 4 of `PLAN.md`. What follows is its
starting shape, not a settled specification.

- **Input:** the same facts SinewSlim reads, plus the OfficeFloor-specific ones (annotations,
  try/catch, declared exceptions, the wiring the method already has).
- **Search:** partition the method's control flow into connected regions, each a function at
  or under the cap.
- **Cost:** for each edge, the values one function hands the next (a value both produced by
  one function and declared by a later one is a variable between them, named once), plus a
  cost per function and per edge. Injected values cost nothing.
- **Jumps:** a `return` ends a function as a `@Next` or flow; a `throw` becomes an
  escalation. `break` and `continue` crossing a cut still block it.
- **Branches:** an `if` or `switch` may become a decision function whose branches are flows.
- **Not slimmable:** its own reasons, chiefly loops and the values carried across edges,
  instead of SinewSlim's jumps.

**What the cost model still has to settle** (Phase 4, before any OfficeFloor figure is
compared with a SinewSlim one, so the weights are not tuned after seeing which way they go).
The suggested default follows each question:

1. **A value used by several later functions:** does it cost once (it is one variable, named
   once) or once per function that declares it (each declaration is something a reader has to
   follow)? *Default: once per declaring function.* That is the conservative choice, and it is
   what makes "values carried a long way" show up as a cost.
2. **The cost of a function and of an edge,** against one carried value. Conventionally a piece
   costs its seam plus 1 for the name and call. *Default: a function costs 1 (its name) and an
   edge costs 1 (its wiring), so a two-function split pays 1 more than a conventional
   extraction for the edge.* Otherwise the OfficeFloor side is cheaper just by leaving the
   wiring out.
3. **What counts as injected:** the method's own parameters and managed objects only, or also
   values from earlier functions that were *not* computed in this method? *Default: only the
   method's parameters and managed objects are free.* Everything computed inside the method
   costs when it crosses an edge.
4. **A decision function:** what does turning an `if` or `switch` into flows cost? *Default:
   one function for the decision plus one edge per branch*, so a five-way `switch` is visibly
   dearer than a two-way `if`.
5. **An escalation:** is a `throw` that becomes an escalation free, or does it cost an edge to
   its handler? *Default: one edge, unless an existing handler already covers that exception
   type.*
6. **The selection order:** the same as SinewSlim's (the smallest worst seam, then the fewest
   pieces, then the smallest total)? *Default: yes*, so the two columns pick their plans by the
   same rule.
7. **Where the wiring is counted:** in the seam, or only in the migration-cost column?
   *Default: in the seam (questions 2, 4 and 5).* The migration column holds the one-off cost
   of getting there (beans, transactions, handlers), not the ongoing cost of the shape.

The proposal reads like the study's (`SPLIT-TOOL.md`, *Under function injection*):

```
function 48-71   24 lines   declares: request, config   produces: session   static   saves 23
function 73-90   18 lines   declares: session           produces: -         static   saves 17
remainder        19 lines   declares: request, config, session               was 61
```

*declares* is what a function asks the framework for: the method's own parameters passed
through, and the values earlier functions produced. *produces* is what it hands on. The order
of the functions is part of the proposal.

The split produces the functions as code **and** their wiring (the YAML, woof or procedure
configuration), applied as a refactoring in the IDE and checked by reading the facts again and
comparing them with the plan.

## Comparing the two costs

The two costs count different things: SinewSlim counts parameters and returns; OfficeFloor
counts values carried between functions and edges. People will compare them once they are
side by side, so the comparison has to be fair.

- **Two columns, never a common unit (decided).** The SinewSlim cost and the OfficeFloor cost
  are reported side by side, each in its own terms. A common unit would need a weight that
  turns one into the other, and that weight is exactly what a sceptical reader would say was
  chosen to flatter OfficeFloor.
- **Migration cost, its own column.** A method that splits cleanly but drags six Spring beans
  and a `@Transactional` with it is not cheap. Count the beans to bridge or turn into managed
  objects, the transaction boundaries, the exceptions that become escalations, and the wiring
  to write.
- **Growth.** Line count is a snapshot. Add git history: lines added to the method and how
  often it was touched over a window: the last N commits or the last 12 months, whichever is
  shorter (decided). AI-written code can make N commits in days; a slower codebase falls back
  to a year. N is configurable, default 500. "Over
  26, not slimmable, and grew 40 lines this year" is the case for containment.
- **Categories, labelled honestly.** Not every unsplittable method needs OfficeFloor: many
  guard-clause methods can be fixed conventionally.
  1. Conventionally fixable once restructured (for example, guard clauses inverted)
  2. Needs records
  3. Only splits under function injection: the real target
  4. Needs a redesign

## Surfaces

| Surface | Scope | What it does |
|---|---|---|
| **Scan / assess** (CLI and Docker image) | A whole repository | Classifies every method; for those over the cap, the SinewSlim cost, the OfficeFloor cost, the signed difference, the migration cost, growth and the category. Versioned JSON, plus HTML or Markdown |
| **IntelliJ plugin** | The project's OfficeFloor graphs and the method under the cursor | Long functions highlighted on the graphs; the proposed split previewed as new functions and flows; the refactoring that applies it |
| **Migration** (opt-in, in the IntelliJ plugin only) | One method and the code around it | The plan from the algorithm, the code rewritten to it by an AI, checked |

A review gate in CI (like SinewSlim's `review`) may follow once the scan has shown its worth.
It is not in the plan yet.

### The IntelliJ plugin

Today's plugin is `~/OfficeFloor-IntelliJ-Plugin` (`rootProject.name = "officefloor-rest-view"`,
Marketplace name "OfficeFloor REST View", plugin ID `net.officefloor.ide.rest.plugin`, 0.1.1
live, signed by OfficeFloor, publishing secrets set by OfficeFloorMaintenance's
`setup-jetbrains-publishing.sh`). It moves into this repository as the `intellij` module,
the way SinewSlim holds its own:

- its files are copied in as one commit, without its git history (it was a couple of commits
  in an evening; the old repository stays as the record until it is archived);
- its plugin ID is kept, so everyone with it installed gets the update; its Marketplace name
  changes from "OfficeFloor REST View" to "OfficeFloor", since it now covers any OfficeFloor
  function, not only REST;
- its graph model is exposed to the classification, which is the strongest evidence that a
  method is an OfficeFloor function;
- the analysis marks long functions and their proposed splits on the graphs;
- the old repository is archived with a pointer here once the first release from this
  repository is out.

### AI-assisted migration

- **Plan first.** The algorithm produces a deterministic plan: the cut lines, the values on
  each edge, the flows and the wiring. The AI only rewrites the code to it. This keeps the AI
  replaceable and its output checkable.
- **Checked.** Every rewrite is compiled, its facts are read again and compared with the plan,
  and the tests run. If the rewrite fails, the developer keeps the plan.
- **Contained.** One method at a time. OfficeFloor can host Spring beans, so a long method
  moves without moving the application: a strangler pattern at method level.
- **Opt-in.** Only with AI credentials and configuration provided.
- **In the IntelliJ plugin only (decided).** The migration changes code, and for now a person
  looks at every change straight after it is made, so it lives where the code is viewed. The
  CLI, Docker image and review tools report and propose; they never rewrite.
- **Pluggable providers (decided).** One small interface (send the plan and the code, get the
  rewritten code back), with implementations for the main providers (Anthropic, OpenAI,
  Google) and a local or OpenAI-compatible endpoint. The provider, model and key are set in
  the IDE's settings, the key in IntelliJ's credential store. No provider is privileged.

## Empirical studies and public assessments

The empirical study is `~/ui-long-degradation-test`. It serves the Spring versus OfficeFloor
comparison and the academic question of mutative versus additive architecture, with the same
data. Public assessments of other repositories are a separate use of the same scan, not part
of the study. Every result has to hold up for a reader sceptical of OfficeFloor.

### The experiment (reviewed 31 October 2026)

- **One application grown from nothing.** Each stack starts from an empty base (`base-empty`:
  a front-end shell, Spring Boot with or without OfficeFloor, Flyway on an empty in-memory H2)
  and is grown by 250 plain-English change requests. The current stream is the *early-overload*
  one (`docs/EARLY_OVERLOAD_DESIGN.md`). It is domain-free: people with raw values, concepts
  computed from values and from each other, and relationships whose cardinality migrates. A
  hub value, `keel`, is read by about 40 concepts and redefined ten times, so each
  redefinition ripples through everything that reads it. Cross-cutting rules (trim, rounding,
  held people, sinks, missing values) arrive early, at checkpoints 4 to 20. The cadence is
  three additive requests to one mutative one.
- **Each request is full-stack:** a schema migration, the backend, the front-end and its own
  Playwright spec, which binds only to `data-testid`. The specs are the stable contract while
  everything beneath them churns. Every expected value is checked against an executable
  reference model.
- **The agent:** a fresh `claude-opus-5-5` per checkpoint, blind to earlier requests, able to
  run its own spec. The full accumulated suite is the regression gate afterwards. The current
  runs use the ungated `just-solve` condition, with 10 chains per stack.
- **The stacks** are base repositories `~/officehq-<frontend>-<backend>`: Angular, htmx and
  TanStack, each with a `-spring` and an `-officefloor` backend, plus `react-officefloor` and
  `tanstack-mixed`. A Spring and OfficeFloor pair shares the front-end, harness, agent and
  stream, so **the backend architecture is the variable**.
- **The record:** every checkpoint is a commit on `evolve/<run_id>/<condition>/chain<N>` in the
  stack's repository, pushed by `push.sh`. The harness already measures Lizard CC and NLOC,
  ImpactGate, CK, hotspots, a boundary-violation count (touches to shared or central files),
  the Zero-Regression Rate and a cold-reader comprehension probe.

### What SinewSlim adds

Because every checkpoint is a commit, SinewSlim runs **after the fact** on each one. Nothing in
the running experiment changes.

- **Primary erosion measure:** the number of backend methods at 26 code lines or more that
  **cannot be brought under 25** (SinewSlim's *not slimmable*), at each checkpoint, per chain
  and per stack, with the reason (jumps, outputs, inputs, length). A method that cannot be
  slimmed is the one that only gets harder to take apart as more is added. That makes it a
  direct measure of erosion, not a proxy.
- **Secondary:** the number of methods over 26; the slim cost of each slimmable one; *locked*
  methods (god methods); lines added per method per change; the worst seam's growth over the
  run.
- **Measured the same way on both arms.** The primary measure is SinewSlim's conventional
  reading on the Spring arm *and* the OfficeFloor arm. OfficeFloor functions are priced as if
  their pieces had to be extracted conventionally, which is conservative towards OfficeFloor.
  The OfficeFloor reading (the projected cost under function injection) is a second column,
  labelled as projected.

### What has to be got right

- **The wiring is code too.** The OfficeFloor arm keeps structure in
  `src/main/resources/officefloor/**` YAML, which SinewSlim does not read. Measuring Java alone
  would flatter OfficeFloor. Beside the Java measures, count the wiring at each checkpoint: its
  lines, functions and edges, and how often a change touches it. The harness's
  boundary-violation count already covers part of this.
- **Backend only.** SinewSlim reads Java. Front-end erosion stays with the harness's Lizard
  series and is not compared with these measures.
- **Normalise by behaviour.** An arm that fails more checkpoints carries less behaviour. Report
  each measure per passing test (the harness's denominator) as well as raw.
- **One definition of a line:** SinewSlim's code lines, as in the paper, not Lizard's NLOC. On
  one run, show that the two agree closely enough that the harness's existing series can be
  read beside them.
- **The analysis plan before the analysis.** The runs were under way before this plan existed,
  so the runs themselves cannot be pre-registered. But no SinewSlim measure has been computed on
  any of them yet. The hypotheses, the measures and the comparison are committed to the
  experiment's repository, dated, **before** SinewSlim first reads a run's branches. That is a
  registered analysis of existing data, and the write-up says so.
- **Pilot on an old run, not the new one.** Older runs (for example
  `evolve/202610112340/just-solve/chain1` in `officehq-angular-spring`, on an earlier stream)
  exercise the pipeline without looking at the data the hypotheses are about.

### Hypotheses (draft, fixed before the first scan)

1. **H1, the headline.** Not-slimmable backend methods accumulate faster on the Spring arm than
   on the OfficeFloor arm of the same front-end, per passing test, over the 250 checkpoints.
2. **H2, the mechanism.** On the Spring arm, the not-slimmable reasons are mostly jumps and
   multiple outputs, the signature of behaviour added *inside* existing methods.
3. **H3, the shocks.** The `keel` redefinitions raise the Spring arm's not-slimmable count by
   more than the OfficeFloor arm's.
4. **H4, the cost OfficeFloor could be hiding.** The OfficeFloor arm's wiring grows roughly in
   proportion to behaviour, and does not concentrate in a few files. If it does concentrate,
   the erosion has moved from Java to YAML, and the result says so.

Every method where OfficeFloor comes out worse is listed, not only the savings.

**Exploratory, labelled as such:** whether an agent's failures at a checkpoint go with the
length or the sinew of the methods it had to change. This is the AI reasoning-limit question:
whether an agent's limit is set by lines or by the values carried through a method.

**Two kinds of measure, always labelled apart:**

| Measure | What it is | Where it comes from |
|---|---|---|
| Measured | The SinewSlim cost of code as actually written, Spring or OfficeFloor | `sinewslim-core` on each codebase |
| Projected | What an OfficeFloor split would cost for a conventional method, without doing it | OfficeFloorSlim on the same method |

A projection is an estimate. It is checked on a sample by doing the splits and measuring the
result.

**Mutative versus additive.** The academic framing of the same data. In a mutative
architecture new behaviour is added inside existing methods, which grow branches and early
returns until they cannot be split. In an additive one (function injection) new behaviour
arrives as new functions and flows, so methods stay bounded. H1 to H3 are its predictions, and
H4 is its main threat.

**Keeping it credible:**

- the analysis plan committed before the first scan, as above;
- tool versions and `.sinew-slim.yml` pinned, raw JSON published with every result;
- every method where OfficeFloor costs more (wiring, carried values) reported, not only the
  savings;
- both arms grown by the same stream and judged by the same specs, so the difference is the
  architecture and not the scope;
- the comparison computed here, never in SinewSlim.

**Public assessment:** an `assess` command in the CLI and Docker image. Point it at a
repository and get both costs per method, the signed difference and totals by category, as
versioned JSON and a readable report. Worked examples on the corpora SinewSlim already uses
(OfficeFloor and the MLCQ repositories). These are demonstrations of the tool, not part of the
study.

## Language, build and coordinates

- Java 17 and Gradle, as SinewSlim (the IntelliJ Platform's floor for the 2024.2 line).
- Maven group `net.officefloor.sinewslim` for both projects (decided). The artifact names keep
  them apart: `sinewslim-*` for SinewSlim's libraries, `officefloorslim-*` for this project's
  modules. Only SinewSlim publishes to Central; this project's modules never leave its own
  build. Never the bare `net.officefloor` group, so nothing collides with OfficeFloor's own
  artifacts or its BOM. Versions are this project's own, starting at 0.x.
- Licence Apache-2.0, as SinewSlim and OfficeFloor.

### Local development

All development and integration happen on the local machine until they work. Only a merge to
`main` publishes anything.

- **SinewSlim:** a composite build against the checkout beside this one. OfficeFloorSlim's
  build depends on the real coordinates (`net.officefloor.sinewslim:sinewslim-core:<version>`),
  so CI resolves them from Central and nothing changes between the two:

  ```kotlin
  // settings.gradle.kts: build against the SinewSlim checkout beside this one when it is there
  val sinewSlim = file("../SinewSlim")
  if (sinewSlim.isDirectory) {
      includeBuild(sinewSlim)
  }
  ```

  Until SinewSlim's Gradle projects are renamed to their artifact names, `includeBuild` needs a
  `dependencySubstitution` block mapping each `net.officefloor.sinewslim:sinewslim-*` module to
  its project (`:core`, `:javaparser`, `:config`).
- **Checking as an outsider would:** `./gradlew publishToMavenLocal` in SinewSlim installs
  unsigned `0.0.0-SNAPSHOT` artifacts in `~/.m2`. Use it before a merge, not day to day.
- **OfficeFloor itself** builds with Maven, which a composite build cannot include. If an
  unreleased OfficeFloor is needed, `mvn install` in `~/OfficeFloor` and add `mavenLocal()`
  restricted to `net.officefloor` snapshots.

### Release

The pipelines on `main` publish. They exist for outside contributors later, and for others
reusing the libraries in their own frameworks.

- **SinewSlim's libraries to Central.** Set up in SinewSlim's Gradle build with the
  `com.vanniktech.maven.publish` plugin 0.37.0: sources and javadoc jars, the POM, signing when
  a key is given. A dry run (`publishAllPublicationsToStagingRepository`) writes the bundle to
  `build/staging-deploy`. The release step (`publishToMavenCentral`) takes the same Central
  account and GPG key as `~/OfficeFloor`'s `release.yml`; the `net.officefloor` namespace
  covers every group below it:

  | Gradle property | Secret |
  |---|---|
  | `ORG_GRADLE_PROJECT_mavenCentralUsername` | `SONATYPE_USERNAME` |
  | `ORG_GRADLE_PROJECT_mavenCentralPassword` | `SONATYPE_PASSWORD` |
  | `ORG_GRADLE_PROJECT_signingInMemoryKey` | `GPG_PRIVATE_KEY` |
  | `ORG_GRADLE_PROJECT_signingInMemoryKeyPassword` | `GPG_PASSPHRASE` |

  Set them at the OfficeFloor organisation level, by a `setup-central-publishing.sh` in
  OfficeFloorMaintenance beside `setup-jetbrains-publishing.sh`.
- **Central is permanent.** A release cannot be deleted, so the module names and the facts
  schema settle before the first one.
- **This repository** releases its Docker image and CLI, and the plugin to the Marketplace with
  the same signing and secrets as the plugin has today. The version comes from the tag, as in
  SinewSlim (`vMAJOR.MINOR.PATCH`, passed as `-Pversion`).
- **Order on merge:** merge and tag SinewSlim, wait for its libraries on Central, bump the
  SinewSlim version here, then merge and tag OfficeFloorSlim. The composite build hides this
  order locally.

## What it is not

- **Not a replacement for SinewSlim.** SinewSlim answers for conventional code on its own
  terms. This product adds the OfficeFloor answer beside it and never changes SinewSlim's.
- **Not an advocate.** Where OfficeFloor costs more, the report says so.
- **Not an unchecked rewriter.** The AI follows a plan the algorithm made, and every rewrite is
  compiled, re-measured and tested.
- **Not the OfficeFloor framework.** It reads and writes OfficeFloor applications; it does not
  change OfficeFloor.

## Open questions

- The OfficeFloor cost model's seven questions (*The OfficeFloor split*), each with a suggested
  default, to settle before any comparison.
- Where to publish the study's results.

Decided on 31 October 2026, and recorded in the sections above: the Maven group
(`net.officefloor.sinewslim` for both), no Central libraries from this project, the plugin
copied in without its history and renamed "OfficeFloor", the two costs side by side, the growth
window (N commits or 12 months, whichever is shorter), the migration in the plugin only with
pluggable providers, the study being `~/ui-long-degradation-test`, and this repository public.

## Sources

- `~/SinewSlim/DESIGN.md`: the conventional algorithm, the seam roles, the configuration, the
  surfaces and the release flow this product builds on.
- `~/SinewSlim/core/.../Params.java` (`framework()`), `Pieces.java` (`frameworkSummary`),
  `MethodIndex.java` (`freeParams`): the framework reading as it stands.
- `~/concentration-divergence-study/SPLIT.md` (*The framework reading*) and `SPLIT-TOOL.md`
  (*Under function injection*): the reading this product turns into an algorithm.
- `~/OfficeFloor-IntelliJ-Plugin`: the plugin moving into this repository.
- `~/OfficeFloor`: the framework, and its Maven Central release (`.github/workflows/release.yml`).
- `~/ui-long-degradation-test` (`README.md`, `DESIGN.md`, `docs/EARLY_OVERLOAD_DESIGN.md`,
  `config.yaml`): the empirical study; the stacks are `~/officehq-*`.
