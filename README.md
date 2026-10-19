# Jira Mark

A Gradle plugin and standalone CLI for repository-owned Jira ticket profiles.
Each profile combines YAML configuration with Markdown guidance. Prepare tickets
offline, validate them against Jira Cloud, import reusable profiles, and create
issues through an explicit command.

Plugin ID: `dev.vspex.jira-mark`

The version is defined in the root `build.gradle` as `version = '0.1.2'`.
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
| Create an issue | `jiraCreate` | `create` | Read metadata, then submit |

Remote commands require an explicit connection. The plugin does not attach its
tasks to `build` or `check`. CLI prompting is available through `--interactive`.

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

## Publish to the Gradle Plugin Portal

Both publishing helpers let Gradle resolve the version from the root
`build.gradle`; neither script contains its own version. Update the default
version there for each release. The Portal requires a fixed version without
the `-SNAPSHOT` suffix. The helpers require `mise` on your PATH and forward
additional Gradle arguments.

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
| Artifact already exists with a different hash | The Portal already has different content for that version. Choose a new version in the root `build.gradle`; clearing the configuration cache will not replace the stored artifact. |
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
Its JAR bundles the core and Cloud adapter classes; SnakeYAML is a runtime
dependency. In a consumer project, run the Gradle task examples without
`-p dev-rig`.

### Maven Local

Publish a development build into your local Maven repository from this repository:

```powershell
.\publish-plugin-local.bat
```

This helper requires `mise`. Without it, run
`.\gradlew.bat :gradle-plugin:publishToMavenLocal`. Both commands use the version
from the root `build.gradle` (currently `0.1.2`) and do not upload to the Plugin
Portal. To test a snapshot locally, set a version such as `0.1.3-SNAPSHOT` in
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

```groovy
plugins {
    id 'dev.vspex.jira-mark' version '0.1.2'
}
```

### Gradle Plugin Portal

Once release `0.1.2` is published and approved, consumers can apply it without
a local checkout or Maven Local:

```groovy
plugins {
    id 'dev.vspex.jira-mark' version '0.1.2'
}
```

The Plugin Portal is Gradle's default plugin repository. If the consumer
customizes `pluginManagement.repositories`, include `gradlePluginPortal()`.
Use the same `jira` extension and task examples shown above.

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
stdout. Gradle tasks do not support interactive prompting.

### 3. Validate a ticket against Jira Cloud

For a connection named `work`, configure these environment variables outside
tracked files using your terminal or credential tooling:

| Variable | Value |
| --- | --- |
| `JIRA_WORK_URL` | Your HTTPS Jira site base URL |
| `JIRA_WORK_EMAIL` | Your account email |
| `JIRA_WORK_API_TOKEN` | An API token compatible with the selected site endpoint |

Connection names start with a letter and contain letters, digits, or underscores.
The environment prefix is uppercased. There is no implicit default connection.
Credentials are resolved during execution and are not profile fields or Gradle
task properties.

After adapting the example profile's project, field IDs, and choices to your Jira
instance, validate its ticket values:

```powershell
.\gradlew.bat -p dev-rig jiraValidateRemote --connection=work --ticket-profile=backend-bug --input=ticket.json

& $jiraMarkCli validate-remote --connection work --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json
```

Both forms fetch current create metadata without submitting an issue. A successful
result contains `{"valid":true,"profile":"backend-bug"}`.

### 4. Import and review a profile

Import an example issue into a new draft:

```powershell
.\gradlew.bat -p dev-rig jiraImportProfile --connection=work --issue=APP-123 --ticket-profile=imported-bug --output=jira/profile-drafts/imported-bug.md
```

The CLI equivalent, optionally retaining the source issue's labels as a default:

```powershell
& $jiraMarkCli import --connection work --profiles-dir dev-rig/jira/profiles --profile imported-bug --issue APP-123 --output dev-rig/jira/profile-drafts/imported-bug.md --reuse-default labels
```

Choose one import form: existing files are never overwritten. Gradle requires
`--output`; the CLI defaults to `jira/profile-drafts/<profile>.md` relative to
the current directory.

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
.\gradlew.bat -p dev-rig jiraCreate --connection=work --ticket-profile=backend-bug --input=ticket.json

& $jiraMarkCli create --connection work --profiles-dir dev-rig/jira/profiles --profile backend-bug --input dev-rig/ticket.json
```

**Each invocation is a separate creation attempt.** Creation revalidates against
fresh metadata, then submits one request. Example success result:

```json
{"key":"APP-42","url":"https://example.invalid/browse/APP-42"}
```

Creation is never skipped as up-to-date or restored from the build cache. A
timeout, server error, or unusable success response can leave the result uncertain.
For `UNCERTAIN_CREATION`, inspect Jira before retrying manually; automatic
submission retries and duplicate reconciliation are not implemented.

## Jira field support

Remote workflows require every configured field to be available for creation.
Projects use exact keys or IDs. Issue type IDs take precedence over names;
ambiguous names fail. Metadata pagination is followed, and incomplete or excessive
pagination is rejected.

| Remote field shape | Local profile type | Encoding |
| --- | --- | --- |
| String or single-line text | `string` | Literal text |
| Description, environment, standard textarea custom field | `string` | Literal text to ADF paragraphs |
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

Description conversion creates one ADF paragraph per line and preserves Markdown
punctuation literally. There is no Markdown rendering or template expansion.
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

Production connections require HTTPS without embedded credentials, query
parameters, or fragments. HTTP is accepted only for `localhost` and `127.0.0.1`
test servers. Redirects are disabled.

Transport limits are 15 seconds per connect/read phase and 2 MB per request or
response body. Reads have at most three attempts for I/O failures, rate limits,
or server failures. Numeric `Retry-After` delays up to two seconds are honored;
longer or unsupported delays stop the operation. Creation requests are never
retried.

Keep credentials, private ticket input, generated drafts, and issue data out of
source control. Gradle may print command arguments and persist task values, so
use input files for private ticket content. Do not pass credentials as fields.
Application errors omit credentials and raw remote response bodies.

## Verification and limitations

The full build covers profile behavior, synthetic HTTP failures, field encoding,
import protection, CLI prompting, packaged artifacts, and Gradle configuration-cache
reuse with credential rotation. Remote behavior has not been verified against a
live Jira instance.

Not implemented: OAuth/scoped-token gateway routing, Jira Data Center, user/date
fields, cascading selects, app-specific field encodings, dynamic autocomplete,
metadata caching, Markdown rendering, automatic templates, automatic creation
recovery, and Gradle prompting.

## Project layout

```text
core/           Profiles, resolution, workflow contracts, import, and prompting
jira-cloud/     Connections, HTTP transport, metadata, and field encoding
cli/            Command-line adapter and application distribution
gradle-plugin/  Gradle extension, tasks, packaging, and TestKit tests
dev-rig/        Local consumer and synthetic examples
README.md       Setup, profile specification, command examples, and error reference
publish-plugin-local.bat   Publish the configured version to Maven Local using mise
publish-plugin-portal.bat  Validate or publish a fixed Portal release using mise
```
