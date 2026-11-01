# OfficeFloorSlim

Prices a long Java method two ways: the conventional extract-method split
([SinewSlim](https://github.com/officefloor/SinewSlim)) and a split into functions that
[OfficeFloor](https://officefloor.net) injects and sequences. It classifies each method,
proposes the OfficeFloor split with its wiring, marks it on the OfficeFloor IntelliJ plugin's
graphs, and can migrate a method from Spring to OfficeFloor.

Early. Built so far: the wiring to SinewSlim's libraries, the OfficeFloor IntelliJ plugin
(`intellij/`), the graph model it shares with the classification (`graph/`), and a report-only
scan that classifies every method and prices each long one both ways (`core/`, `cli/`). The
scan's OfficeFloor column is OfficeFloorSlim's own split (`core/`, `OfficeFloorSplit`); the
plugin does not show it yet, and nothing applies it. See [DESIGN.md](DESIGN.md) and
[PLAN.md](PLAN.md).

Build with `./gradlew build`. With [SinewSlim](https://github.com/officefloor/SinewSlim) checked
out beside this repository (`../SinewSlim`), the build uses that checkout for SinewSlim's
libraries. Scan a repository with:

```bash
java -jar cli/build/libs/officefloorslim.jar scan --repo DIR --format markdown   # or json
```

The JSON is a versioned contract: [docs/SCAN_JSON.md](docs/SCAN_JSON.md).

## Licence

[Apache License 2.0](LICENSE).
