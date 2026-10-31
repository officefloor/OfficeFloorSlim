# OfficeFloor — IntelliJ plugin

Until 0.1.1 this was **OfficeFloor REST View**, released from
[OfficeFloor-IntelliJ-Plugin](https://github.com/officefloor/OfficeFloor-IntelliJ-Plugin). It
now lives here, in OfficeFloorSlim, with the same plugin ID, so installed copies update to it.

Visualises OfficeFloor **WoOF / Activity "compose" YAML** configuration as an
auto-laid-out flow diagram. Open a configuration file and a **Flow** tab appears
next to the text editor, showing the functions flowing down the page with their
`next`, `outputs` (flows) and `escalations` drawn as connections.

> OfficeFloor configures REST endpoints by *function injection*: each YAML file
> describes a pipeline of functions that service a request. This plugin turns
> that YAML into the flow diagram it really is.

## Install

From the [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/35087-officefloor-rest-view):
in IntelliJ IDEA (2024.2 or later), open **Settings → Plugins → Marketplace**,
search for **OfficeFloor** (listed as **OfficeFloor REST View** until the first release from
here), and click **Install**. The IDE then offers
each new version as it is released.

## What it understands

The parser targets the OfficeFloor "compose" / Activity YAML model (as parsed by
`net.officefloor.activity.compose.ComposeConfiguration` and used by the
`officefloor-rest-spring-boot-starter`):

| YAML                             | Diagram                                                        |
|----------------------------------|----------------------------------------------------------------|
| each top-level key (not `composition`) | a **function** node                                      |
| `class` / `method`               | a procedure node → its Java class / method                     |
| `source` / `location`            | a section node                                                 |
| `procedure` / `resource`         | a custom-procedure node                                        |
| `next: target`                   | a **next** connection (sequential continuation)                |
| `outputs: { name: target }`      | a **flow** connection per named output                         |
| `escalations: { Exception: target }` | an **escalation** connection (dashed)                      |
| `composition.escalations`        | section-wide escalations (shown off the entry function)        |
| `#name` values                   | an external link target node                                   |
| file name `path.METHOD.yml`      | an **HTTP endpoint** node routing into the entry function      |

HTTP endpoints are derived from the file location under a `rest/` directory
(`index` → `/`, directory nesting → path nesting, `{param}` path parameters).

## Control flow, data flow and HTTP inputs

A collapsible control panel floats over the top-right of the diagram. Three
independent check boxes control what is drawn; any combination can be on:

- **Control flow** (on by default): the `next` / `outputs` / `escalations`
  connections — where the pipeline directs next.
- **Data flow**: the information passed between functions, read from the **Java
  source**:
  - *Variables* — each function's method is inspected for `@Val T` / `In<T>`
    (reads) and `Out<T>` (writes), plus `Var<T>` (both). Reads appear as chips on
    the left of a function, writes on the right, labelled by type. An amber
    overlay line runs from each writer to every function that reads the same
    variable. Two functions share a variable when their `(qualifier, type)` match
    — OfficeFloor's rule `name = (qualifier == null ? "" : qualifier + "-") +
    type`; a qualifier (a `@Qualifier`-meta-annotated annotation on the parameter)
    distinguishes two variables of the same type.
  - *Parameters* — a function's non-void return becomes the argument passed to
    its `next` function, received there by the `@Parameter` method parameter.
    These are shown as purple chips (a `return` chip on the producer, a `(param)`
    chip on the consumer) joined by a purple overlay line along the flow.
- **HTTP inputs**: the request values each function reads, shown as teal chips
  *inside* the function body. These come from the request, not from another
  function, so they are drawn on the node itself rather than in the left/right
  columns that carry inter-function flow. Both OfficeFloor web annotations
  (`@HttpPathParameter`, `@HttpQueryParameter`, `@HttpHeaderParameter`,
  `@HttpCookieParameter`, `@HttpContentParameter`, `@HttpObject`) and the
  equivalent Spring MVC annotations (`@PathVariable`, `@RequestParam`,
  `@RequestHeader`, `@CookieValue`, `@RequestBody`, `@RequestPart`) are read from
  the Java method. A chip's glyph shows its source (`/` path, `?` query, `H`
  header, `C` cookie, `{}` body); the tooltip names the annotation. Like the
  variable direction, these are read from the Java method, so a non-Java
  (polyglot) procedure will not show them.

Turning both on overlays data flow on top of the control flow.

### How the data-flow metadata is resolved

Data-flow metadata is loaded **lazily** — the first time Data flow is switched on
— and **in the background**, so opening a file and viewing control flow stay
instant. For each function it is resolved:

1. **OfficeFloor's compiled `ProcedureType` first.** The plugin runs OfficeFloor's
   own compiler over a classloader built from the module's compiled output and
   dependencies (it does not bundle OfficeFloor, so the project's own version and
   procedure sources are used). `ProcedureType` authoritatively gives the
   `@Parameter` type, the return/next type, and the set of variables with their
   types — and it understands custom / polyglot (Kotlin, Scala, JavaScript, …)
   procedures that have no plain Java method to read.
2. **Java source for the In/Out direction.** `ProcedureType` flattens variables
   and does not record read vs. write, so the plugin recovers each variable's
   direction from the Java source by matching the variable name. A variable whose
   direction cannot be determined (e.g. a polyglot procedure with no Java source)
   is shown as a neutral grey chip with no arrow.
3. **Full fallback to Java source** when a `ProcedureType` cannot be loaded (not an
   OfficeFloor project, OfficeFloor not on the classpath, or a load issue).

Because `ProcedureType` reflects on real classes, the **project must be compiled**
for step 1 to contribute; otherwise the plugin falls back to source. Open an
OfficeFloor project such as `tutorials/native/core/VariableHttpServer` (whose
`outIn.GET.yml` is mirrored in [`samples/`](samples)) to see it populated.

## Navigating

- **Single-click a node** → jump to the Java method implementing the function
  (the `method`, or the class's sole public method when `method` is omitted).
  Falls back to the YAML when there is no resolvable method (HTTP endpoints,
  external `#` links, or class names using `${property}` substitution).
- **Double-click a node** → jump to its declaration in the YAML.
- **Click a connection** → jump to the `next` / `outputs` / `escalations` entry
  that declares it.
- **Ctrl/Cmd + mouse wheel** → zoom. The floating control panel (top-right, and
  collapsible) has zoom and *Fit* buttons.

## Building & running

Requires JDK 17. From the repository root:

```bash
./gradlew :intellij:test         # unit tests (layout engine)
./gradlew :intellij:buildPlugin  # produces intellij/build/distributions/officefloor-*.zip
                                 # (installable with Settings → Plugins → ⚙ → Install Plugin from Disk…)
./gradlew :intellij:runIde       # launches a sandbox IDE with the plugin for manual testing
```

In the sandbox IDE, open any file under [`samples/`](samples) — e.g.
`samples/officefloor/rest/order/{id}.POST.yml` — and switch to the **Flow** tab.

## Project layout

```
graph/src/main/java/net/officefloor/officefloorslim/graph/     (the officefloorslim-graph module)
            FlowGraph, FlowNode, FlowLink, NodeKind, LinkKind,
            VariableAccess, VariableDirection, DataFlowLink,
            HttpInput, RestFileName                              (the model: pure, unit-tested)
            ComposeGraph                                         (the YAML -> graph rules)
            ComposeYamlReader                                    (SnakeYAML, outside the IDE)
intellij/src/main/java/net/officefloor/intellij/flow/
  layout/   LayeredLayout                                        (Sugiyama-style top-down)
  parser/   ComposeYamlParser                                    (PSI -> ComposeGraph)
            ComposeContentHeuristic, FunctionMetaData,
            FunctionMetaDataResolver, ProcedureTypeMetaData,     (hybrid resolution)
            JavaFunctionVariables, MetaDataEnricher
  ui/       FlowDiagramPanel                                     (Swing renderer; both modes)
  editor/   FlowDiagramEditorProvider, FlowDiagramEditor, JavaTargetNavigator
```

The graph model lives in `officefloorslim-graph`, with no IntelliJ dependency, so
the classification reads the same graph the plugin draws. Both readers of the YAML
(the plugin's PSI and the scan's SnakeYAML) build it through `ComposeGraph`, and
`ReaderParityTest` checks they agree, to the offset, on every sample. The `layout`
package has no IntelliJ dependency either; both are covered by plain JUnit tests.

## Status / roadmap

First version: read-only diagram with jump-to-source. Possible next steps:

- aggregate a whole `rest/` directory into one application-wide diagram;
- show `govern` / `cors` / `authorize` metadata as node badges;
- live refresh on edit (currently refreshes when the Flow tab is selected);
- richer orthogonal edge routing and crossing reduction.

## Publishing to JetBrains Marketplace

Signing and publishing are wired into `build.gradle.kts` and the
[`Release`](../.github/workflows/release.yml) workflow, which publishes when a
version tag is pushed. The plugin is already on the Marketplace (0.1.0 was
uploaded by hand and passed moderation), so a release is an update.

**The secrets** (all required by the Release workflow) are shared by all
OfficeFloor plugins: the organisation secrets `PUBLISH_TOKEN`,
`CERTIFICATE_CHAIN`, `PRIVATE_KEY` and `PRIVATE_KEY_PASSWORD`, set by
`setup-jetbrains-publishing.sh` in
[OfficeFloorMaintenance](https://github.com/officefloor/OfficeFloorMaintenance#publishing-intellij-plugins-to-the-jetbrains-marketplace).
Only the repositories listed in its `jetbrains-plugin-repositories.txt` can read
them: add `OfficeFloorSlim` there and run the script once before the first
release from here. Don't set them as repository secrets: those would override
the organisation's.

After that, each release is a tag. **The tag is the version**: `v0.2.0` builds
and publishes the plugin as `0.2.0` (passed as `-Pversion`).
`gradle.properties` holds only the development version (`0.0.0-SNAPSHOT`) and is
never edited for a release.

```bash
# 1. Add a <li><b>0.2.0</b>: …</li> entry to the change-notes in
#    intellij/src/main/resources/META-INF/plugin.xml, and commit it.
# 2. Tag that commit and push the tag.
git tag v0.2.0
git push origin v0.2.0
```

The workflow checks everything first and publishes nothing unless all pass: the
four secrets, the tag being `vMAJOR.MINOR.PATCH`, the change-notes entry, the
tests, and the [Plugin Verifier](../.github/workflows/verify-plugin.yml) against
every IDE of the plugin's range (one job per IDE). Only then does it sign and
upload. `publishPlugin` also refuses to run without the four secrets, so the
plugin is never published unsigned.

## License

Licensed under the [Apache License, Version 2.0](../LICENSE), matching the
[OfficeFloor](https://github.com/officefloor/OfficeFloor) framework.
