# The scan's JSON (`officefloorslim-scan/2`)

`officefloorslim scan --format json` writes one document per repository. This is its contract:
what later tools and studies read. `schema` names the version. A change to a field's meaning, or
a field taken away, raises the version; a field added does not.

Every number in it is computed by OfficeFloorSlim, or by the SinewSlim libraries it is built on.
A reader needs to compute nothing to compare the two columns, and should not put them in a
common unit (DESIGN.md, *Comparing the two costs*).

## The document

| Field | Type | Meaning |
|---|---|---|
| `schema` | string | `officefloorslim-scan/2` |
| `tool` | string | `officefloorslim <version>` |
| `facts_schema` | int | The SinewSlim facts' schema the scan read (3) |
| `officefloor_reading` | string | What the OfficeFloor column is: `officefloorslim-split/1` |
| `repository` | string | The directory scanned |
| `commit` | string or null | Its `HEAD`; null outside git |
| `seconds` | number | How long the scan took |
| `options` | object | `include`, `exclude` (globs), `growth_commits`, `growth_months` |
| `files` | int | Files read (Java and configuration) |
| `unparsed` | array | `{path, problem}` for each file that could not be read |
| `methods_by_reading` | object | Every method by reading: `officefloor_function`, `framework_entry_point`, `conventional` |
| `long_methods` | int | Methods of 26 code lines or more |
| `bar` | object | The go/no-go bar's numbers (below) |
| `wiring` | object | The OfficeFloor configuration (below) |
| `methods` | array | Each long method (below); methods under the cap are counted, not listed |

### `bar`

| Field | Meaning |
|---|---|
| `not_slimmable` | Long methods SinewSlim cannot slim with one return per piece (`not_slimmable` or `slimmable_with_record`) |
| `by_category` | Those by category, `"1"` to `"4"` (below) |
| `category3_share` | Category 3 over `not_slimmable`; 0 when it is 0 |
| `go` | Category 3 is at least a quarter of `not_slimmable`, and at least 30 |
| `rule` | That rule, in words |

### `wiring`

| Field | Meaning |
|---|---|
| `files`, `lines`, `functions`, `edges` | Configuration files (compose YAML, `.woof`, `application.officefloor`), their non-blank lines, the functions and flows they declare |
| `window_commits` | Commits in the growth window (the last `growth_commits`, at most `growth_months` back); null outside git, or with `--no-growth` (`growth_commits` 0) |
| `touching_commits` | Of those, the commits that changed a configuration file there now; null outside git |
| `by_file` | Per file: `path`, `kind` (`compose_yaml`, `woof`, `officefloor`), `lines`, `functions`, `edges`, `wires` (the classes, and `class#method`, it wires) |

### Each method in `methods`

| Field | Meaning |
|---|---|
| `path`, `type`, `signature`, `line` | Where it is: the file, the declaring type as SinewSlim names it (`Outer.Inner`), its name and parameter types, the line of its name |
| `length` | Its code lines, as SinewSlim counts them |
| `reading` | `officefloor_function`, `framework_entry_point` or `conventional` |
| `evidence` | Why, readable: `wired in rest/api/x.GET.yml`, `@GetMapping`, `public, in a @Service` |
| `sinewslim` | SinewSlim's assessment, under its defaults (below) |
| `officefloor` | The OfficeFloor split's plan (below) |
| `category` | For a method SinewSlim cannot slim with one return per piece: `{number, label}`; else null |
| `migration` | `fields` (touched: beans to bridge), `transactional`, `escalations` (exception types declared or caught), `functions` and `edges` of the split (null without a plan). Each in its own unit; never summed |
| `growth` | `{lines_added, commits}` over the window, on its code lines, from `git blame` at `HEAD`; null outside git, or with `--no-growth` |

### `sinewslim`

| Field | Meaning |
|---|---|
| `status` | `slimmable`, `slimmable_but_locked`, `slimmable_with_record` or `not_slimmable` |
| `obstacle` | Why no segmentation reached the cap: `outputs`, `inputs`, `inputs_and_outputs`, `jumps`, `long_statement`, `length`; else null |
| `cost` | The slim cost of its proposal; null when it has none, and then the fields below are absent |
| `width` | The proposal's worst seam: the parameters and returns crossing its widest cut |
| `pieces` | Methods extracted |
| `remainder` | Code lines left in the method |
| `largest_piece` | The code lines of its largest extracted method |

### `officefloor`

| Field | Meaning |
|---|---|
| `feasible` | Whether the split has a plan |
| `reason`, `detail` | Without one: `loop`, `long_statement`, `try`, `switch`, `carried`, `jumps`, and the statement that blocks, when one does |
| `total` | The plan's cost: the carried values each function declares, plus 1 per function, plus 1 per flow; null without a plan, and then the fields below are absent |
| `worst_seam` | The most carried values one function declares |
| `edges` | Its flows, escalations included |
| `largest_function` | The code lines of its largest function (at most 25) |
| `functions` | Each function: `kind` (`run`, `decision`, `handler`), `lo`, `hi`, `lines`, `declares` (the carried values) |

### Categories

Tested in this order, for a method SinewSlim cannot slim with one return per piece:

| Number | Label | When |
|---|---|---|
| 2 | needs records | SinewSlim slims it with a record |
| 1 | conventionally fixable once restructured | SinewSlim's defaults slim it with the jump rule lifted |
| 3 | only splits under function injection | the OfficeFloor split has a plan |
| 4 | needs a redesign | none of these |

## What compares, and what does not

`sinewslim.width` and `officefloor.worst_seam` both measure what crosses one boundary;
`sinewslim.pieces` and the length of `officefloor.functions` both count the parts;
`largest_piece` and `largest_function` both measure the largest part. `sinewslim.cost` and
`officefloor.total` count different things and are not to be compared or combined.
