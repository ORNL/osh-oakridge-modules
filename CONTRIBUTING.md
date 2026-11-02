# Contributing to ORNL/osh-oakridge-modules

`ORNL/osh-oakridge-modules` is maintained by Oak Ridge National Laboratory as part of the [ORNL/oscar](https://github.com/ORNL/oscar) platform.

## Fork & Pull Request Workflow

1. Fork [`ORNL/osh-oakridge-modules`](https://github.com/ORNL/osh-oakridge-modules) to your GitHub account or organization.
2. Create a focused feature or bugfix branch from `main`.
3. Build and run the self-contained Gradle test suite from a recursive checkout of [`ORNL/oscar`](https://github.com/ORNL/oscar) (see [`README.md`](README.md)).
4. Open a Pull Request targeting `ORNL/osh-oakridge-modules:main`. All changes require code-owner review and passing Continuous Integration checks before merge.
5. Update all four localized operator manuals (`README.md`, `README_es.md`, `README_fr.md`, `README_el.md`) together whenever operator-facing behavior or configuration defaults change.
