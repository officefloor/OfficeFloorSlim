# OfficeFloorSlim

Prices a long Java method two ways: the conventional extract-method split
([SinewSlim](https://github.com/officefloor/SinewSlim)) and a split into functions that
[OfficeFloor](https://officefloor.net) injects and sequences. It classifies each method,
proposes the OfficeFloor split with its wiring, marks it on the OfficeFloor IntelliJ plugin's
graphs, and can migrate a method from Spring to OfficeFloor.

Early: the build and its wiring to SinewSlim exist, nothing else yet. See [DESIGN.md](DESIGN.md)
and [PLAN.md](PLAN.md).

Build with `./gradlew build`. With [SinewSlim](https://github.com/officefloor/SinewSlim) checked
out beside this repository (`../SinewSlim`), the build uses that checkout for SinewSlim's libraries.

## Licence

[Apache License 2.0](LICENSE).
