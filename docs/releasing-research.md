# Clojars release research

Research updated: 2026-09-20. These recommendations separate publication from the existing test workflow.

## Release event and version

Use a separate workflow with `release: {types: [published]}`. Derive the Maven version from the release tag, such as `v0.1.0`.
GitHub supplies the tagged commit as `GITHUB_SHA` and the release tag as `GITHUB_REF`.
The `published` event includes stable releases and prereleases, including prereleases published from drafts.
The `prereleased` event alone misses that draft case.
([GitHub event documentation](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release))

Recommended contract:

- A maintainer creates a version tag and publishes a GitHub release for that tag.
- The workflow builds the event commit and publishes that version to Clojars.
- The release path rejects malformed versions and `SNAPSHOT` versions.
- A manual `workflow_dispatch` on `main` publishes an explicit `MAJOR.MINOR.PATCH-SNAPSHOT` version.
- The test workflow remains independent. Branch protection can require tests before merge.
- A tag push alone does not publish a package. Draft creation and release edits do not publish packages.

Each publication path has one trigger. This avoids duplicate deployments from separate tag and release events.
It also avoids a `workflow_run` dependency on CI results or artifacts.

## Snapshot publication

Use `workflow_dispatch` with a required version input, such as `0.2.0-SNAPSHOT`.
Restrict this path to `refs/heads/main` and build its event commit SHA.
The workflow file must exist on the default branch before GitHub accepts manual dispatch.
Dispatch can select a branch or tag, so the workflow must enforce the `main` restriction itself.
The event SHA identifies the selected commit even if `main` advances before the job starts.
([GitHub manual dispatch](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#workflow_dispatch))

Clojars permits repeated publication of a snapshot version.
Each upload receives a timestamp and build number, while the `-SNAPSHOT` coordinate points to the newest upload.
Older uploads remain available through their timestamped identifiers.
Thus `0.2.0-SNAPSHOT` is mutable, while its timestamped identifier selects one upload.
([Clojars snapshot identifiers](https://github.com/clojars/clojars-web/wiki/Stable-SNAPSHOT-Identifiers))

Keep snapshot publication separate from GitHub releases and version tags.
Record the exact source commit in the snapshot POM's SCM metadata.
Do not skip a snapshot upload merely because its base version already exists.
These recommendations preserve traceability while supporting repeated publication of development builds.

## Build tasks

Babashka's `http-client` project has a `bb publish` task that calls `build/deploy`.
Its `build.clj` uses `tools.build` to generate a POM, copy sources and resources, and create a library JAR.
The POM includes a description, project URL, MIT license, and SCM tag.
([Pinned `bb.edn`](https://github.com/babashka/http-client/blob/ca3665c622dd299a2feaa9c8e07d8d137a6037a6/bb.edn),
[pinned `build.clj`](https://github.com/babashka/http-client/blob/ca3665c622dd299a2feaa9c8e07d8d137a6037a6/build.clj))

For this project, use `bb` tasks as the public interface to `tools.build` functions.
Keep build dependencies in a separate `deps.edn` alias.
Generate a normal library JAR with its dependency POM, rather than an application uberjar.
The official guide demonstrates this build structure.
([Clojure tools.build guide](https://clojure.org/guides/tools_build))

Include the three runtime roots from this project's `deps.edn`: `src`, `resources`, and `apis`.
Include the root license and preserve the private WebSocket implementation's license and provenance.
Exclude tests, examples, build tooling, and dependency classes from the JAR.
Use the user-required project coordinate `com.github.loganlinn/clj-codex` under their existing Clojars group.

Both `slipset/deps-deploy` and `babashka/deps-deploy` support Clojars publication.
They accept a JAR and POM and read `CLOJARS_USERNAME` and `CLOJARS_PASSWORD` from the environment.
The latter variable holds a deploy token.
The newer Babashka implementation also runs directly in Babashka.
The JVM implementation fits the project's existing Java and Clojure toolchain without a Babashka runtime upgrade.
([slipset/deps-deploy](https://github.com/slipset/deps-deploy),
[babashka/deps-deploy](https://github.com/babashka/deps-deploy))

## Clojars account and credentials

Clojars authenticates deployments with a username and deploy token.
Tokens support artifact or group scope, expiration, and single-use operation.
A token cannot target an artifact or group that does not yet exist.
For a new artifact under the existing group, a group-scoped token can publish its first version.
An artifact-scoped token is available after that artifact exists.
The official deployment documentation describes token authentication, with no documented GitHub OIDC exchange.
Use that documented mechanism instead of granting `id-token: write` without an integration.
([Clojars deploy tokens](https://github.com/clojars/clojars-web/wiki/Deploy-Tokens))

The repository already contains the Actions secrets `CLOJARS_USERNAME` and `CLOJARS_PASSWORD`.
Their names were confirmed with `gh secret list --repo loganlinn/clj-codex` on 2026-09-20.
That check confirms their presence, not their values or deployment permissions.

Repository secrets remain available to a job that references the `clojars` environment.
An environment secret with the same name takes precedence over a repository secret.
The existing repository secrets do not need duplication in the environment.
([GitHub secrets reference](https://docs.github.com/en/actions/reference/security/secrets))

Optionally restrict the shared `clojars` environment to branch `main` and tags matching `v*`.
The environment can also require a reviewer before either publication path starts.
Environment rules protect jobs that reference the environment, but repository secrets remain accessible to other eligible workflows.
For credential access restricted to this environment, move the secrets into it.
The workflow YAML alone does not configure these protection rules.
([GitHub deployment environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments))

Pin actions to full commit SHAs and tool dependencies to explicit versions.
Grant only `contents: read` unless another job requires more access.
Expose the Clojars token only in the deployment step.
Pass the tag through an environment variable, then quote it in shell commands.
These recommendations apply GitHub's guidance on immutable action references, minimum permissions, and script injection.
([GitHub secure use reference](https://docs.github.com/en/actions/reference/security/secure-use))

## Failed runs and duplicate publication

Clojars requires a valid POM with a license and rejects replacement of a non-SNAPSHOT version.
It also rejects unstable dependency versions in releases.
A build must therefore generate complete metadata and retain the project's explicit dependency versions.
([Clojars deployment validation](https://github.com/clojars/clojars-web/wiki/Pushing#validations))

Use a concurrency group for publication and keep `cancel-in-progress: false`.
An in-progress upload must finish before another upload starts.
GitHub normally permits only one pending run per group.
For a shared publication group, `queue: max` can retain multiple pending releases.
([GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency))

GitHub reruns retain the original event's commit SHA and ref.
For a transient failure before publication, rerun the failed workflow.
([GitHub workflow reruns](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs))

For a tagged release, do not treat an existing Clojars version as unconditional success.
Either fail with a clear explanation or compare the published artifacts with the intended artifacts before skipping deployment.
After successful release publication, source changes require a new version and tag.
If the upload result is uncertain, inspect Clojars before retrying.
These recovery recommendations follow from Clojars' release immutability.

A snapshot rerun can publish another timestamped build under the same base version.
Reruns retain the original source commit, so an older rerun can replace the newest snapshot with older source.
For the latest `main` commit, start a new manual dispatch instead of rerunning an older snapshot workflow.
