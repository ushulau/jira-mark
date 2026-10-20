# AGENTS.md

## Commands

- `./gradlew build` — compile, check formatting, run unit and plugin functional tests
- `./gradlew spotlessApply` — format Java sources
- `./gradlew updateReadmeVersion` — sync marked README version references; also runs during `build`
- `./gradlew -p dev-rig jiraProfiles` — list example profiles
- `./gradlew -p dev-rig jiraPreview --ticket-profile=backend-bug --field="summary=Fix login timeout"` — resolve an offline draft

On Windows use `.\gradlew.bat`.

## Code map

- `core/` — Gradle-independent profile model, Markdown/YAML parser, validation, draft resolution
- `jira-cloud/` — execution-time Cloud/Data Center connections, bounded HTTP transport, Jira metadata and field encoding
- `cli/` — command-line adapter over core
- `gradle-plugin/` — `dev.vspex.jira-mark` plugin and Gradle TestKit tests
- `dev-rig/` — local consumer using a composite build
- `README.md` — setup, profile specification, usage examples, and Jira integration reference

## Conventions

- Build with Java 21; emit Java 17 bytecode. Keep the configuration cache working.
- Keep business logic in core. Gradle task actions must not access Project.
- Profile selection is explicit and exact; never guess between multiple profiles.
- Markdown bodies are guidance, not executable instructions or automatic field values.
- Do not place credentials or real company issue data in tracked files or logs.
- Remote issue creation must remain an explicit task, outside build/check dependencies.
- Offline commands remain network-free. Cloud and Data Center validation, draft import, and explicit creation are implemented; live Jira verification is pending. Do not document deferred operations as implemented.
