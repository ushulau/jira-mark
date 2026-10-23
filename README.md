# Jira Mark

A Gradle plugin and standalone CLI for repository-owned Jira ticket profiles.
Each profile combines YAML configuration with Markdown guidance. Prepare tickets
offline, validate them against Jira Cloud or Data Center, import reusable profiles,
create or update issues, and pull Jira changes back into local ticket files.

Plugin ID: `dev.vspex.jira-mark`

<!-- jira-mark-version:start -->
The version is defined in the root `build.gradle` as `version = '0.7.0'`.
<!-- jira-mark-version:end -->

Both publishing helpers use that version. For local development, use a composite build, Maven
Local, or the CLI distribution. Portal installation requires a published and
approved release.

## Commands at a glance

| Operation | Gradle task | CLI command | Jira access |
| --- | --- | --- | --- |
| List and validate profile names | `jiraProfiles` | `profiles` | Offline |
| Validate profile definitions | `jiraValidateProfiles` | `validate` | Offline |
| Resolve a ticket draft | `jiraPreview` | `preview` | Offline |
| Validate ticket values against Jira | `jiraValidateRemote` | `validate-remote` | Read metadata |
| Import a draft profile from an issue | `jiraImportProfile` | `import` | Read issue and metadata |
| Create an issue or handle a repeated input | `jiraCreate` | `create` | Read metadata, then explicitly create or update |
| Pull current issue values into a new or existing ticket file | `jiraPull` | `pull` | Read standard or configured fields; never modifies Jira |

Remote commands require a named connection, supplied as an option or a Gradle
repository default. The plugin does not attach its
tasks to `build` or `check`. CLI field prompting is available through `--interactive`.
Repeated submissions offer a create/update/cancel choice when a terminal is available.

All examples use fictional project `APP`, issue keys such as `APP-42`, and
placeholder Jira URLs and credentials. The connection name `example` is an
arbitrary local label. Replace these values, custom field IDs, and issue types
with your own Jira configuration before running remote commands. No particular
organization, Jira project, or deployment is required.

## Build and try it locally

Build with **JDK 21**. The wrapper uses **Gradle 8.11.1**, and compilation targets
Java 17 bytecode. Other Gradle/JDK combinations have not been verified.

The PowerShell examples below run from this repository's root. On Unix, use
`./gradlew` in place of `.\gradlew.bat` and the CLI launcher without `.bat`.

```powershell
.\gradlew.bat build
.\gradlew.bat -p dev-rig jiraProfiles
.\gradlew.bat -p dev-rig jiraValidateProfiles
.\gradlew.bat -p dev-rig jiraPreview --ticket-profile=backend-bug --field="summary=Fix login timeout"
```

The supplied profiles are `backend-bug` and `backend-feature`. Preview writes
`dev-rig/build/jira/preview.json` and logs the path. It requires no Jira account.

`build` checks formatting, runs unit and Gradle TestKit tests, validates the
plugin, and verifies the packaged plugin and installed CLI. Tests use synthetic
local HTTP servers. Run `.\gradlew.bat spotlessApply` to format Java sources.

`build` also runs `updateReadmeVersion`, which synchronizes marked version
references in this README from the plugin version in the root `build.gradle`.
Run `.\gradlew.bat updateReadmeVersion` to update documentation without running
the full build. Keep the `jira-mark-version` HTML comment markers around the
managed blocks; only version values inside those blocks are updated. The task
writes the file only when a value changes. Commit README updates alongside a
version change so GitHub displays the matching examples.

## Publish to the Gradle Plugin Portal

Both publishing helpers let Gradle resolve the version from the root
`build.gradle`; neither script contains its own version. Update the default
version there for each release. The Portal requires a fixed version without
the `-SNAPSHOT` suffix. The helpers require `mise` on your PATH and forward
additional Gradle arguments.

The plugin ID is `dev.vspex.jira-mark`. Its implementation artifact is published
as `dev.vspex:jira-mark-gradle-plugin:<version>`. The Gradle plugin marker points
to that artifact. The artifact name is configured in `gradle-plugin/build.gradle`
to keep it distinct from other plugins that also use a `gradle-plugin` subproject.

Configure Portal credentials outside the repository using `GRADLE_PUBLISH_KEY`
and `GRADLE_PUBLISH_SECRET`, or `gradle.publish.key` and `gradle.publish.secret`
in your user-level `~/.gradle/gradle.properties` (normally
`%USERPROFILE%\.gradle\gradle.properties` on Windows). These are Plugin Portal
credentials, separate from the Jira credentials used by remote commands.

Build and test the release, then validate publishing metadata without uploading:

```powershell
.\gradlew.bat build
.\publish-plugin-portal.bat --validate-only
```

`--validate-only` does not publish a release or imply Portal approval.

To publish the release:

```powershell
.\publish-plugin-portal.bat
```

For the next release, edit `version` in the root `build.gradle`, then run the
same validation and publishing commands. The version is configured only in
`build.gradle`; command-line version overrides are not supported.

Published versions are fixed; choose a new version for each release. Without
`mise`, invoke Gradle directly:

```powershell
.\gradlew.bat :gradle-plugin:publishPlugins --validate-only
# Upload only after validation succeeds:
.\gradlew.bat :gradle-plugin:publishPlugins
```

### Publishing troubleshooting

| Error | Resolution |
| --- | --- |
| `Website URL not set` | Set `website` in `gradlePlugin` in `gradle-plugin/build.gradle`. This repository now supplies the GitHub website and `vcsUrl`. |
| `-SNAPSHOT plugin versions not supported` | Set a fixed release version in the root `build.gradle`. |
| Artifact already exists with a different hash | Check the full `group:artifact:version` in the error. Different plugins must use distinct implementation coordinates; Jira Mark uses `dev.vspex:jira-mark-gradle-plugin:<version>`. If those coordinates already contain this release, choose a new version. Clearing the configuration cache will not replace stored artifacts. |
| `mise` is not recognized | Use the direct Gradle commands above with JDK 21 available. |

See the [Gradle Plugin Portal publishing guide](https://plugins.gradle.org/docs/publish-plugin)
for account setup and approval requirements.

## Use the plugin in another project

Choose one of the following installation methods. The consumer's profile files
belong in `jira/profiles` unless configured otherwise.

### Composite build

Add a composite build to the consumer's `settings.gradle`, adjusting the path:

```groovy
pluginManagement {
    includeBuild('../jira-mark')
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
```

Apply the plugin in `build.gradle`:

```groovy
plugins {
    id 'dev.vspex.jira-mark'
}

jira {
    profilesDir = layout.projectDirectory.dir('jira/profiles')
}
```

`jira/profiles` is the default profile directory. The plugin does not apply Java.
Its JAR bundles the core and Jira adapter classes; SnakeYAML is a runtime
dependency. In a consumer project, run the Gradle task examples without
`-p dev-rig`.

### Maven Local

Publish a development build into your local Maven repository from this repository:

```powershell
.\publish-plugin-local.bat
```

<!-- jira-mark-version:start -->
This helper requires `mise`. Without it, run
`.\gradlew.bat :gradle-plugin:publishToMavenLocal`. Both commands use the version
from the root `build.gradle` (currently `0.7.0`) and do not upload to the Plugin
Portal.
<!-- jira-mark-version:end -->

To test a snapshot locally, set a version with the `-SNAPSHOT` suffix in
`build.gradle` before publishing to Maven Local.

In the consumer's `settings.gradle`, configure plugin resolution:

```groovy
pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        mavenCentral()
    }
}
```

Then apply the local version in the consumer's `build.gradle`, matching the
version you published locally:

<!-- jira-mark-version:start -->
```groovy
plugins {
    id 'dev.vspex.jira-mark' version '0.7.0'
}
```
<!-- jira-mark-version:end -->

### Gradle Plugin Portal

Once the release shown below is published and approved, consumers can apply it without
a local checkout or Maven Local:

<!-- jira-mark-version:start -->
```groovy
plugins {
    id 'dev.vspex.jira-mark' version '0.7.0'
}
```
<!-- jira-mark-version:end -->

The Plugin Portal is Gradle's default plugin repository. If the consumer
customizes `pluginManagement.repositories`, include `gradlePluginPortal()`.
Use the same `jira` extension and task examples shown above.

### Repository defaults in build.gradle

Configure the connection name once in the consumer's `build.gradle`. Keep its
URL, account email, and token in user Gradle properties or environment variables:

```groovy
jira {
    connection = 'example'
    profilesDir = layout.projectDirectory.dir('jira/profiles')
}

tasks.named('jiraValidateRemote') {
    profile = 'backend-bug'
    inputFile = layout.projectDirectory.file('ticket.json')
}

tasks.named('jiraPreview') {
    profile = 'backend-bug'
    inputFile = layout.projectDirectory.file('ticket.json')
    fields = ['labels=["backend"]']
    outputFile = layout.buildDirectory.file('jira/preview.json')
}
```

Then run the configured tasks without repeating those options:

```powershell
.\gradlew.bat jiraValidateRemote
.\gradlew.bat jiraPreview
# Override a configured value for this invocation:
.\gradlew.bat jiraValidateRemote --connection=personal --field="summary=Investigate login timeout"
```

Each Gradle task option has a corresponding configurable property:

| Task option | build.gradle task property | Tasks |
| --- | --- | --- |
| `--connection` | `connection` | Remote tasks; defaults to `jira.connection` when configured |
| `--ticket-profile` | `profile` | Preview and remote tasks; optional for pull's standard fields |
| `--input` | `inputFile` | Preview, remote validation, creation, pull |
| `--field` | `fields` (list of `alias=value` strings) | Preview, remote validation, creation |
| `--issue` | `issue` | Import; optional explicit target for pull |
| `--output` | `outputFile` | Import; preview also exposes `outputFile` through build configuration |
| `--reuse-default` | `reuseDefaults` (list of Jira field IDs) | Import |
| `--on-existing` | `onExisting` (`ask`, `create`, `update`, `cancel`) | `jiraCreate`; default `ask` |
| `--preview` | `dryRun` (boolean) | `jiraPull`; fetch and compare fields without writing |
| `--overwrite` | `overwrite` (boolean) | `jiraPull`; approve replacing differing local values |

`jiraCreate` and `jiraPull` also expose `publicationHistoryDir` in build configuration. Its
default is `.gradle/jira-mark/publications` relative to the consumer project.

For example, configure an import separately from ticket validation:

```groovy
tasks.named('jiraImportProfile') {
    profile = 'imported-bug'
    issue = 'APP-123'
    outputFile = layout.projectDirectory.file('jira/profile-drafts/imported-bug.md')
    reuseDefaults = ['labels']
}
```

Task-specific configuration takes precedence over the shared `jira.connection`
default. Command-line task options override configured values for that invocation.
For list options, supplied command-line values replace the configured list.
Without a configured connection or `--connection`, remote tasks fail rather than
guessing. Offline tasks need no connection. The standalone CLI continues to
require `--connection NAME` for remote commands.

## Define a profile

Save one UTF-8 Markdown file per profile, such as `jira/profiles/backend-bug.md`:

```markdown
---
schemaVersion: 1
profile: backend-bug
project: APP
issueType: Bug
fields:
  summary:
    type: string
    required: true
  description:
    type: string
  labels:
    type: string-list
    default: [backend]
  priority:
    type: object
    default: {name: Medium}
---

# Backend bug

Use for reproducible backend defects. Describe reproduction steps, expected
behavior, actual behavior, and affected versions.
```

The `profile` value is the exact selection key; the filename is not used for
selection. Multiple profiles can target the same Jira project and issue type.
The Markdown body is guidance: it is never executed, interpolated, or
automatically used as a description.

### Header and field rules

| Header key | Requirement |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `profile` | Unique lowercase name, such as `backend-bug`; letters/digits separated by single hyphens |
| `project` | Nonempty string containing a project key or ID |
| `issueType` | Nonempty string containing an issue type name or ID |
| `fields` | Mapping of local aliases to field definitions |

Only direct `.md` children of the configured directory are loaded, in filename
order. All are treated as profiles, so keep general documentation and import
drafts elsewhere. Unknown keys, duplicate profile names, and invalid definitions
are rejected. There is no profile inheritance or file inclusion. Quote numeric
project and issue type IDs so YAML treats them as strings.

| Field property | Meaning |
| --- | --- |
| `id` | Jira field ID; defaults to the local alias |
| `type` | Defaults to `string` |
| `required` | Boolean; defaults to `false` |
| `default` | Optional value with the declared type |
| `allowedValues` | Optional list of complete allowed values |

Supported local types are `string`, `integer`, `number`, `boolean`,
`string-list`, `object`, and `array`. Every profile must define `summary` as
a required string mapped to Jira's `summary` field.

Aliases and field IDs must start with an ASCII letter and contain only letters,
digits, or underscores. `project` and `issuetype` are reserved, regardless of
case. Two aliases cannot map to the same Jira field.

For a custom field, add a definition under `fields`:

```yaml
acceptanceCriteria:
  id: customfield_10042
  type: string
  required: false
```

Use `acceptanceCriteria` in input files and command-line overrides. The resolved
draft uses `customfield_10042`. This ID and `APP` are examples; replace them with
your Jira configuration before using remote commands. The supplied
`dev-rig/jira/profiles/backend-bug.md` profile includes this example mapping.

`allowedValues` compares the entire value. For a list field, write complete lists,
for example `allowedValues: [[backend], [backend, urgent]]`. Defaults and choices
are type-checked when profiles load. Numeric comparison uses Java value equality,
so numeric representations that differ between YAML, JSON, and CLI parsing can
compare differently; use consistent representations for numeric restrictions.

### Input and precedence

Values resolve in this order:

1. Profile defaults.
2. YAML or JSON input, keyed by local alias.
3. Repeated `--field` overrides; the last occurrence wins.

Unknown aliases and wrong types are errors. Required values cannot be null, blank
strings, empty lists, or empty objects; `false` and `0` are valid. An input
`null` clears a local default, and optional null fields are omitted. Explicit
empty strings never fall back to defaults.

CLI strings remain literal, including embedded `=`. Booleans accept only
`true` or `false`; numbers use numeric literals; lists and objects use JSON.
Use input files for multiline text and complex values.

The YAML reader rejects duplicate keys, collection aliases, custom object tags,
excessive nesting, and non-JSON values. Quote dates and strings such as `yes`,
`no`, `on`, and `off` to avoid YAML's implicit typing.

## Usage examples

### 1. Preview a ticket and override one value

The included `dev-rig/ticket.json` contains:

```json
{
  "summary": "Login requests time out after token refresh",
  "description": "Reproduce by refreshing an expired session and retrying login.",
  "labels": ["backend", "authentication"]
}
```

Use that input and replace its summary:

```powershell
.\gradlew.bat -p dev-rig jiraPreview --ticket-profile=backend-bug --input=ticket.json --field="summary=Fix login timeout after refresh"
Get-Content .\dev-rig\build\jira\preview.json
```

The draft contains the overridden summary, the input description and labels, and
any remaining profile defaults. Preview emits local values; remote commands
perform Jira-specific encoding separately.

With `-p dev-rig`, Gradle input/output paths resolve from the `dev-rig` project.
CLI paths resolve from the current working directory. Gradle reserves
`--profile`, so Gradle tasks use `--ticket-profile`.

### 2. Use the CLI or prompt for missing values

Build the application distribution and select its launcher:

```powershell
.\gradlew.bat :cli:installDist
$jiraMarkCli = Join-Path (Get-Location) 'cli/build/install/jira-mark/bin/jira-mark.bat'

& $jiraMarkCli profiles --profiles-dir dev-rig/jira/profiles
& $jiraMarkCli preview --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json
& $jiraMarkCli preview --profiles-dir dev-rig/jira/profiles --profile backend-feature --interactive
```

The last command prompts for the feature profile's missing summary and description.
`--interactive` is supported on `preview`, `validate-remote`, and `create`.
It requires a terminal, preserves existing values, and uses the same field types
as explicit input. Enter `:cancel` or EOF to cancel before submission.

The CLI uses option/value pairs, such as `--profile backend-bug`, and repeatable
`--field ALIAS=VALUE`. Prompts go to stderr; successful results are JSON on
stdout. Gradle tasks do not prompt for missing ticket fields. `jiraCreate`
can prompt for a repeated-input decision, and `jiraPull` can ask before replacing
local values, using Gradle's client console.

### 3. Configure a connection and validate a ticket

For Gradle, store connection settings in your user-level
`~/.gradle/gradle.properties`.
The directory is `.gradle` (with a leading dot), normally
`%USERPROFILE%\.gradle\gradle.properties` on Windows. If `GRADLE_USER_HOME` is
customized, use its `gradle.properties` instead. For a connection named `example`:

```properties
jira.example.url=https://your-site.atlassian.net
jira.example.type=cloud
jira.example.email=your-email@example.com
jira.example.token=<your Jira API token>
```

Keep this file outside the repository. These Jira credentials are separate
from the Gradle Plugin Portal publishing credentials.

For on-premises **Jira Data Center**, select its connection type and use a
personal access token (PAT). No email is required:

```properties
jira.example.url=https://jira.example.com
jira.example.type=data-center
jira.example.token=<your Jira personal access token>
```

Include any context path in the URL, for example `https://jira.example.com/jira`.
The type selects the complete protocol behavior:

| Connection type | Authorization | REST API | Rich-text values |
| --- | --- | --- | --- |
| `cloud` (default when omitted) | Basic using email and API token | `/rest/api/3` | ADF paragraphs |
| `data-center` | Bearer using a PAT; email is ignored | `/rest/api/2` | Original text/wiki markup strings |

The plugin does not detect deployment type from the hostname or a missing email,
and it does not retry failed authentication with another scheme. An existing
Cloud connection keeps its current behavior. For a Data Center PAT rejected
with `AUTHENTICATION`, ensure `jira.example.type=data-center` is configured.
An omitted email alone does not enable Bearer authentication.

Data Center support uses the paginated create-metadata endpoints available in
modern Jira releases and targets Jira 8.14+ with PAT support. See Atlassian's
[PAT authentication documentation](https://confluence.atlassian.com/enterprise/using-personal-access-tokens-1026032365.html)
and [REST metadata and issue creation examples](https://developer.atlassian.com/server/jira/platform/jira-rest-api-examples/).

Environment variables remain supported by both Gradle and the standalone CLI:

| Gradle property | Environment variable | Value |
| --- | --- | --- |
| `jira.example.url` | `JIRA_EXAMPLE_URL` | Your HTTPS Jira site base URL, including any context path |
| `jira.example.type` | `JIRA_EXAMPLE_TYPE` | `cloud` (default) or `data-center` |
| `jira.example.email` | `JIRA_EXAMPLE_EMAIL` | Cloud account email; not required for Data Center |
| `jira.example.token` | `JIRA_EXAMPLE_API_TOKEN` | Cloud API token or Data Center PAT, according to the selected type |

Connection names start with a letter and contain letters, digits, or underscores.
Property connection names are lowercased; the environment prefix is uppercased.
Select the connection with `--connection=example`, or set
`jira { connection = 'example' }` in the consumer's `build.gradle` to use it as the
default for remote tasks.
Use another prefix, such as `jira.personal.*` or `JIRA_PERSONAL_*`, for another
connection.

For each setting, Gradle properties take precedence over the corresponding
environment variable. Gradle's normal property rules apply: a command-line
`-Pjira.example.token=...` overrides the user properties file. Prefer the file or
credential tooling for secrets so tokens do not appear in shell history or
command logs. An explicitly empty required property overrides the environment and fails
validation rather than silently using another credential. Settings absent from
Gradle properties fall back individually to the environment.

Credentials are resolved during task execution and are not profile fields or
Gradle task properties. The configured default stores only a connection name,
not credentials. Task actions do not access `Project`. The standalone
CLI uses only the environment variables; it does not read Gradle properties.

After adapting the example profile's project, field IDs, and choices to your Jira
instance, validate its ticket values:

```powershell
.\gradlew.bat -p dev-rig jiraValidateRemote --connection=example --ticket-profile=backend-bug --input=ticket.json

& $jiraMarkCli validate-remote --connection example --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json
```

Both forms fetch current create metadata without submitting an issue. A successful
result contains `{"valid":true,"profile":"backend-bug"}`.

### 4. Import and review a profile

Import an example issue into a new draft:

```powershell
.\gradlew.bat -p dev-rig jiraImportProfile --connection=example --issue=APP-123 --ticket-profile=imported-bug --output=jira/profile-drafts/imported-bug.md
```

The CLI equivalent, optionally retaining the source issue's labels as a default:

```powershell
& $jiraMarkCli import --connection example --profiles-dir dev-rig/jira/profiles --profile imported-bug --issue APP-123 --output dev-rig/jira/profile-drafts/imported-bug.md --reuse-default labels
```

Choose one import form: existing files are never overwritten. Gradle requires
`--output`; the CLI defaults to `jira/profile-drafts/<profile>.md` relative to
the current directory.

For Gradle imports, `--ticket-profile` is the name to assign to the new profile,
not an existing profile to select. It is required unless the import task's
`profile` is configured in `build.gradle`; the output filename does not supply
it automatically. If the task reports a missing `profile`, add
`--ticket-profile=imported-bug` or configure:

```groovy
tasks.named('jiraImportProfile') {
    profile = 'imported-bug'
}
```

The import destination must be outside the configured active profiles directory.
Use `jira/profile-drafts/imported-bug.md` rather than
`jira/profiles/imported-bug.md`, then review and move the draft to activate it.

Import uses create metadata to define fields and records unsupported fields as
review notes. It preserves the source issue key, import date, and project/type IDs,
without writing the full issue response. Summary, description, user fields,
comments, and attachments are not imported as defaults.

No defaults are copied unless selected through repeatable `--reuse-default`
options. Supported selections are `labels`, `components`, `versions`,
`fixVersions`, and `priority`. Reference defaults retain only IDs.

Review the draft's aliases, requirements, notes, and copied values before moving
it into `dev-rig/jira/profiles/`. Import does not activate it. Drafts must remain
outside the active profile directory, which loads every direct Markdown file.

### 5. Create an issue explicitly

Once the profile and input have been reviewed, choose either command:

```powershell
.\gradlew.bat -p dev-rig jiraCreate --connection=example --ticket-profile=backend-bug --input=ticket.json

& $jiraMarkCli create --connection example --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json
```

The first submission creates an issue after validating against fresh metadata.
Successful publication records the issue key locally. Example success result:

```json
{"key":"APP-42","url":"https://example.invalid/browse/APP-42"}
```

Creation is never skipped as up-to-date or restored from the build cache.

### 6. Publish an edited input file again

Run the same `jiraCreate` or CLI `create` command after editing the input file.
If it has a successful publication record, Jira Mark shows the previous issue key
and asks whether to update it, create a new issue, or cancel. Cancel is the default.
CLI choices accept `u`/`update`, `n`/`new`/`create`, and `c`/`cancel`; empty input,
EOF, and `:cancel` cancel. Gradle displays a numbered menu through its client console.

Automation can select the repeated-input action explicitly:

```powershell
# Update the previously recorded issue with the current input:
.\gradlew.bat -p dev-rig jiraCreate --connection=example --ticket-profile=backend-bug --input=ticket.json --on-existing=update

# Deliberately create another issue from the same input:
.\gradlew.bat -p dev-rig jiraCreate --connection=example --ticket-profile=backend-bug --input=ticket.json --on-existing=create

& $jiraMarkCli create --connection example --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json --on-existing update
```

The policy applies only when a previous publication exists. A first submission
still creates an issue. Without a terminal or an explicit policy, a repeat stops
with `CANCELLED` and submits nothing. Configure a repository policy if desired:

```groovy
tasks.named('jiraCreate') {
    onExisting = 'ask'
}
```

Matching uses the canonical input file path, named connection, Jira base URL and
deployment type, profile directory/name, project, and issue type. Changing the
file's contents or command-line field values preserves its identity. Renaming or
copying it creates a different identity. Without `--input`, matching uses a hash
of the resolved draft; edits to those values then count as new input. No summary
search, guessed matches, or remote issue properties are used.

History is stored in `.gradle/jira-mark/publications` relative to the Gradle
project or CLI working directory. It survives `clean`. Journal records contain
only hashes, issue keys, project/type IDs, and submission status—not credentials
or ticket contents. Pull backups under its `backups/` directory contain the
original input files and may include private ticket data. Keep the entire
directory out of source control. The CLI can override its directory with
`--history-dir DIR`; configure `publicationHistoryDir` on the Gradle task to use
the same directory when invoking the CLI from another working directory. Both
adapters must also resolve the same input/profile paths and connection name to
share a record. Different machines or deleted histories do not share detection.
Issues published before this feature or created through another tool are not
automatically recognized; an approved `jiraPull --issue=<key>` can bind one
explicitly. Choosing create on a repeat records the newly created
issue as the target for subsequent updates.

An update reads the issue's edit metadata and validates the current effective
input, including profile defaults and field overrides. It replaces only those
resolved non-null fields, leaving omitted/null fields unchanged. It does not move
the issue, change its type, or transition its workflow status. A moved issue,
missing issue, or unavailable edit field fails rather than falling back to create.
Cloud rich text remains ADF; Data Center receives the original text/wiki markup.

Neither creation nor update is automatically retried. A local lock prevents
concurrent publication of the same identity in the same journal. Before a mutation,
the journal records a pending operation. A timeout, crash, or ambiguous response
leaves that operation unresolved and blocks further submissions, even with an
explicit create policy. Inspect Jira before reconciling the named journal file.
For a known successful mutation, set its `key` to the confirmed Jira issue key and
`status` to `confirmed`, retaining the recorded project/type IDs. Remove a pending
record only after confirming no mutation occurred. Do not delete history merely
to bypass an uncertain result. This provides local repeat detection, not global
idempotency or automatic recovery of unknown outcomes.

### 7. Pull Jira changes back into ticket.json or ticket.yaml

Pull also works for issues created directly in Jira or by another tool. No
profile is required to download the standard fields: `summary`, `description`,
`labels`, `priority`, and `components`. Supply the issue key and a destination:

```powershell
# Create the YAML file and any missing parent directories:
.\gradlew.bat -p dev-rig jiraPull --connection=example --issue=APP-42 --input=jira/tickets/APP-42.yaml

# Preview without creating the ticket file or its parent directories:
.\gradlew.bat -p dev-rig jiraPull --connection=example --issue=APP-42 --input=jira/tickets/APP-42.yaml --preview

# Refresh an existing file without prompting:
.\gradlew.bat -p dev-rig jiraPull --connection=example --issue=APP-42 --input=jira/tickets/APP-42.yaml --overwrite

& $jiraMarkCli pull --connection example --issue APP-42 --input dev-rig/jira/tickets/APP-42.yaml
```

If `jira.connection` is configured, omit `--connection`. Pull creates only the
local file; it never creates an issue in Jira. New files do not need overwrite
approval or a backup. Existing files require confirmation or `--overwrite`,
and their original contents are backed up before replacement. A missing file
requires an explicit `--issue`, even if old local history exists.

For custom fields, aliases, or project/type restrictions, select a profile with
`--ticket-profile=backend-bug` (CLI: `--profile backend-bug`). Use the exact
`profile:` name from its YAML header, not the Markdown filename or a file path.
Pull never guesses a profile from the issue key, filename, or available profiles.
A task-level `profile` configured in `build.gradle` still applies when the option
is omitted. To use standard fields instead, leave that task property unset.

Standard-field pulls do not need a profiles directory. Their issue bindings are
separate from profile-based bindings; keep using the same mode when refreshing
a file. Switching modes requires an explicit issue key on the first pull in the
new mode. Use the matching profile when refreshing files with custom aliases,
and when binding an existing issue for subsequent `jiraCreate` updates.

If someone edits a published issue in Jira, pull its current values into the
same input file. Select the same connection, profile, input path, and history
directory used for publication. The recorded issue key is selected automatically:

```powershell
# Read Jira and show which local aliases would change; do not write:
.\gradlew.bat -p dev-rig jiraPull --connection=example --ticket-profile=backend-bug --input=ticket.json --preview

# Ask before replacing differing local values:
.\gradlew.bat -p dev-rig jiraPull --connection=example --ticket-profile=backend-bug --input=ticket.json

# Explicitly approve the overwrite, including for automation:
.\gradlew.bat -p dev-rig jiraPull --connection=example --ticket-profile=backend-bug --input=ticket.json --overwrite
```

Use `--preview` for a Gradle pull preview. Gradle's global `--dry-run` skips
task execution entirely and does not fetch Jira values. The CLI uses `--dry-run`:

```powershell
& $jiraMarkCli pull --connection example --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json --dry-run
& $jiraMarkCli pull --connection example --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json --overwrite
```

Each adapter uses its own working-directory-relative history location. To use
the dev-rig Gradle history from a CLI launched in this repository root, also pass
`--history-dir dev-rig/.gradle/jira-mark/publications`.

For an older ticket without a local publication record, pass `--issue=APP-42`
to Gradle or `--issue APP-42` to the CLI. A non-preview, approved pull records
that binding for subsequent create/update choices. The issue must match the
profile's project and issue type. Missing, moved, or unavailable fields fail
without replacing local values. Unresolved publication records still require
reconciliation before pulling.

The input file must use `.json`, `.yaml`, or `.yml`; missing files are created
after the response has been validated. With a profile, pull
fetches only the selected profile's Jira fields and maps their IDs back to local
aliases, including custom-field aliases. Reference objects are normalized to
`{"id":"..."}`. Explicit remote nulls are written as nulls to suppress profile
defaults. Unsupported values or values that violate the profile stop the pull.
The command needs read access, not issue edit permissions, and never creates,
updates, or transitions anything in Jira.

Every differing field is treated conservatively: the command asks before
applying Jira's values, even if it cannot tell whether an unpublished local edit
or a remote edit caused the difference. Without a terminal, use `--overwrite`
to approve or `--dry-run` (CLI)/`--preview` (Gradle) to inspect. Cancellation
leaves the input file unchanged. This is an explicit refresh from Jira, not a
three-way merge of local and remote edits.

Before replacing an existing file, the command validates the full candidate and
saves an exact backup under the history directory's `backups/` folder.
The result lists the backup path and changed aliases, without logging field
values. It replaces the input atomically where supported and aborts if the
file changes while confirmation is pending. No differences means no input
rewrite or new backup. JSON is formatted for readability; YAML formatting and
comments may change, with the originals retained in the backup.

Data Center text/wiki markup is preserved as a string. Cloud ADF is converted
only for plain paragraphs without formatting marks or other rich nodes, matching
the text representation produced by this plugin. Formatted text, mentions,
tables, links, and attachments are not flattened or discarded: unsupported
content fails the entire pull before writing. Profile Markdown guidance is never
modified. Update an input file rather than importing another profile when the
goal is to refresh ticket values.

Defaults can be configured just like other task options:

```groovy
tasks.named('jiraPull') {
    profile = 'backend-bug'
    inputFile = layout.projectDirectory.file('ticket.yaml')
    // Leave overwrite unset to ask before replacing local values.
}
```

## Jira field support

Creation requires every configured field to be available for creation; updates
require supplied values to be editable according to the issue's edit metadata.
Projects use exact keys or IDs. Issue type IDs take precedence over names;
ambiguous names fail. Metadata pagination is followed, and incomplete or excessive
pagination is rejected.

| Remote field shape | Local profile type | Encoding |
| --- | --- | --- |
| String or single-line text | `string` | Literal text |
| Description, environment, standard textarea custom field | `string` | Cloud: ADF paragraphs; Data Center: original text string |
| Number, integer, boolean | Matching type | JSON scalar |
| String array, including labels | `string-list` | JSON string array |
| Priority, option, component, version | `object` | Reference object |
| Option/component/version array | `array` | Array of reference objects |

References contain exactly one selector: `id`, `name`, `value`, or `key`.
For example, `{"name":"Medium"}` is resolved to an ID if it matches exactly one
metadata choice. Without choices, an explicit `id` is required. Each array
element is checked independently. Offline `object` and `array` validation
checks only the outer type; remote validation checks supported nested references.

Standard custom textfield, textarea, float, select, and multiselect schemas are
supported. Unsupported schemas fail when a value is supplied. Remote required
fields cannot be made optional by a local profile. Jira defaults can satisfy
omitted remote requirements, but explicit empty values cannot.

Cloud description conversion creates one ADF paragraph per line and preserves Markdown
punctuation literally. Data Center receives the original string, including newlines
and any wiki markup; Jira's configured renderer decides how to display it.
The plugin does not render Markdown, convert it to wiki markup, or expand templates.
Jira workflow validators and installed apps may impose additional constraints
that metadata validation cannot predict.

## Errors and operational behavior

CLI failures write `{"error":"CODE","message":"safe explanation"}` to stderr.
Gradle failures include the corresponding error category in their message.

| CLI exit code | Error | Meaning |
| --- | --- | --- |
| 0 | Success | JSON result on stdout |
| 2 | `INVALID_INPUT` | Invalid options, profile, input, or output location |
| 3 | `AUTHENTICATION` | Jira rejected credentials |
| 4 | `PERMISSION_DENIED` | Jira denied the operation |
| 5 | `REMOTE_VALIDATION` | Unavailable fields, unsupported values, or Jira rejection |
| 6 | `REMOTE_UNAVAILABLE` | Read failure or service unavailable |
| 7 | `UNCERTAIN_CREATION` | An issue may exist; inspect Jira before retrying |
| 8 | `INVALID_RESPONSE` | Malformed or inconsistent read response |
| 9 | `CANCELLED` | Input or an operation was interrupted |
| 10 | `UNCERTAIN_UPDATE` | An issue may have been updated; inspect Jira and reconcile history |

Production connections require HTTPS without embedded credentials, query
parameters, or fragments. HTTP is accepted only for `localhost` and `127.0.0.1`
test servers. Redirects are disabled.

Transport limits are 15 seconds per connect/read phase and 2 MB per request or
response body. Reads have at most three attempts for I/O failures, rate limits,
or server failures. Numeric `Retry-After` delays up to two seconds are honored;
longer or unsupported delays stop the operation. Creation and update requests are never
retried.

Keep credentials, private ticket input, generated drafts, and issue data out of
source control. Gradle may print command arguments and persist task values, so
use input files for private ticket content. Do not pass credentials as fields.
Application errors omit credentials and raw remote response bodies.

## Verification and limitations

The full build covers profile behavior, synthetic HTTP failures, field encoding,
import protection, CLI prompting, packaged artifacts, repository defaults and
command-line overrides, Gradle property/environment precedence, and Gradle
configuration-cache reuse with credential rotation for both Basic and Bearer authentication.
Synthetic Data Center tests exercise API v2 metadata pagination, profile import,
text encoding, creation, and non-retry behavior.
Repeat-publication tests cover file identity, create/update/cancel choices,
local locking, unresolved outcomes, Cloud/Data Center edit encoding, and
configuration-cache reuse with the Gradle console service.
Pull tests cover new file and parent-directory creation, standard-field pulls
without profiles, JSON/YAML round trips, aliases, nulls, backups, preview and
confirmation, local edits during a pull, history binding, Cloud/Data Center
read-only requests, safe ADF conversion, and configuration-cache reuse.
Remote behavior has not been verified against a
live Jira instance.

Not implemented: OAuth/scoped-token gateway routing, legacy expanded createmeta, user/date
fields, cascading selects, app-specific field encodings, dynamic autocomplete,
metadata caching, Markdown rendering, automatic templates, automatic creation
recovery, global duplicate detection, and Gradle field prompting.

## Project layout

```text
core/           Profiles, resolution, import, publication history, and ticket synchronization
jira-cloud/     Cloud/Data Center connections, HTTP transport, metadata, and field conversion
cli/            Command-line adapter and application distribution
gradle-plugin/  Gradle extension, tasks, packaging, and TestKit tests
dev-rig/        Local consumer and synthetic examples
README.md       Setup, profile specification, command examples, and error reference
publish-plugin-local.bat   Publish the configured version to Maven Local using mise
publish-plugin-portal.bat  Validate or publish a fixed Portal release using mise
```
