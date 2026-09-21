# Packaging and publishing to Clojars

Both publication workflows are independent of test CI.
All packages use the coordinate `com.github.loganlinn/clj-codex`.
The release workflow runs only on `release: published`, including GitHub prereleases.
A tag push, draft release, or release edit does not publish to Clojars.
Publishing a GitHub Release starts the upload. It does not mean that the package is already available.
The `published` event also covers prereleases published from drafts, which the `prereleased` event alone misses.
One trigger per publication path prevents duplicate uploads from tag and release events.
See [GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release).

The canonical library version is in [version.edn](version.edn):

```clojure
{:version "0.1.0-SNAPSHOT"}
```

During development, `main` contains the next version with the `-SNAPSHOT` suffix.
Builds, local installs, snapshots, and releases all read this file.
The build and publication tasks accept no version arguments or version overrides.
The upstream Codex generator has a separate pin in `resources/codex/generator-version.txt`.

`bb release:prepare` removes `-SNAPSHOT` for the release commit.
After that commit passes CI on `main`, `bb release:draft` derives the tag from its version.
For example, version `0.1.0` produces tag `v0.1.0`.
The publisher checks that the event tag matches the committed version and points to the release commit.
`RELEASE_TAG` supplies this check, not the package version.

Versions can include `-alpha.N`, `-beta.N`, or `-rc.N` before an optional `-SNAPSHOT` suffix.
For example, `0.1.0-rc.1-SNAPSHOT` becomes release version `0.1.0-rc.1` and tag `v0.1.0-rc.1`.
Snapshot publication uses the committed snapshot version through manual dispatch on `main`, without a version input.
Snapshots need no tag or GitHub Release. Their POM records the source commit in the SCM tag field.

| Task | Effect |
| --- | --- |
| `bb version:show` | Print the version from `version.edn` |
| `bb release:prepare` | Remove `-SNAPSHOT` from `version.edn` |
| `bb version:bump` | Increment the patch number and start a new snapshot |
| `bb version:bump minor` | Increment the minor number, reset the patch number, and start a new snapshot |
| `bb version:bump major` | Increment the major number, reset the minor and patch numbers, and start a new snapshot |

The preparation and bump tasks modify only `version.edn`.
They do not commit or push changes.
Bump tasks remove any prerelease qualifier.
For a specific prerelease sequence, edit `version.edn` directly.

## One-time setup

1. Use your Clojars account with permission to publish under `com.github.loganlinn`.
   The build fixes this group in code. There is no workflow input to override it.
2. Create a Clojars deploy token with permission to publish `com.github.loganlinn/clj-codex`.
   Clojars cannot scope a token to an artifact that does not yet exist.
   A token scoped to the existing `com.github.loganlinn` group can publish the first version.
   See [Clojars deploy tokens](https://github.com/clojars/clojars-web/wiki/Deploy-Tokens).
3. Add these repository Actions secrets:

   | Secret | Value |
   | --- | --- |
   | `CLOJARS_USERNAME` | The Clojars username that owns the deploy token |
   | `CLOJARS_PASSWORD` | The deploy token, not the account password |

4. After the first upload, replace any bootstrap token with an artifact-scoped token.
5. Optionally configure protection rules for the `clojars` deployment environment.
   Allow branch `main` for snapshots and tags matching `v*` for releases.
   If you want approval before each upload, add required reviewers.
6. Protect `main` with required CI checks and review rules.
7. Protect `v*` tags against updates and deletion, and restrict their creation to release maintainers.

If the named environment does not exist, GitHub creates it on its first use without protection rules.
Repository secrets work with these jobs. You do not need to copy them into environment secrets.
Same-name environment secrets take precedence, so avoid stale duplicates.
Repository secrets remain accessible to other eligible workflows.
For credentials restricted to the deployment environment, move the secrets into that environment.
See [GitHub secret scopes](https://docs.github.com/en/actions/reference/security/secrets).
Environment protections and tag rules require repository settings. The workflow files do not create those rules.
Neither publisher waits for CI or downloads CI artifacts.
Maintainers select a commit whose independent CI checks pass.

The workflow grants GitHub's token read access only.
Actions use full commit SHAs. Tool and build dependencies use explicit versions.
Only the publication step receives Clojars credentials.
Publication runs queue behind each other without interrupting an active upload.
Both workflows share `clojars-publish`, with `cancel-in-progress: false` and `queue: max`.
This serializes Maven metadata updates and retains pending runs instead of replacing the previous pending run.
See [GitHub concurrency](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/control-workflow-concurrency).

## Update the protocol schemas

The generated schemas and catalogs stay in Git.
OpenAI states that schema output depends on the Codex version used.
See the [app-server message schema documentation](https://learn.chatgpt.com/docs/app-server#message-schema).
The generator pin is [resources/codex/generator-version.txt](resources/codex/generator-version.txt).
Both generation tasks reject a different installed version before they write outputs.

| Workflow | Responsibility |
| --- | --- |
| PR and push CI | Install the pinned Codex CLI, regenerate in a temporary directory, and fail on drift |
| Manual schema update | Select a version, change the pin, regenerate, and open a PR |
| Snapshot and release publication | Check and package the committed bundle from the selected commit |

`bb codegen:check` compares the complete generated file sets and bytes.
It detects added, changed, deleted, and untracked files within the generated outputs.
It leaves the checkout unchanged.
The generated outputs are `apis/codex/app-server/` and these resources:

- `resources/codex/catalog.edn`
- `resources/codex/schema-index.edn`
- `resources/codex/definition-index.edn`
- `resources/codex/stable-client-request.json`

`bb codegen` replaces the generated schema directory, so removed schemas do not survive an update.
It preserves the generator pin and handwritten resources such as `operation-semantics.edn`.

To update locally:

1. Set the exact version in `resources/codex/generator-version.txt`.
2. Install that version:

   ```sh
   npm install --global "@openai/codex@$(cat resources/codex/generator-version.txt)"
   ```

3. Run `bb codegen`, then `bb codegen:check`.
4. Review the schema and catalog changes, including deletions.
5. Run `bb test:codegen`, `bb test`, `bb test:jvm`, and `bb test:build`.
6. Commit the pin and generated outputs with any required SDK changes.
7. Merge after review and CI, then publish the library version.

The manual [schema update workflow](.github/workflows/schema-update.yml) automates the pin change, generation, and PR creation.
Dispatch it from `main` with an exact `version` input.
Enable **Allow GitHub Actions to create and approve pull requests** in the repository Actions settings.
The workflow uses `GITHUB_TOKEN` by default.
GitHub requires approval before PR CI runs for pull requests created with that token.
See [GitHub token workflow triggers](https://docs.github.com/en/actions/concepts/security/github_token#when-github_token-triggers-workflow-runs).
For automatic CI, optionally configure a `SCHEMA_UPDATE_GH_TOKEN` repository secret with contents and pull request write access.
Use a fine-grained GitHub personal access token restricted to this repository.
The workflow does not merge the PR or publish the library.

Both publishers run `bb schemas:check` without Codex or regeneration.
The JAR build repeats the same integrity check, including local builds.
The check covers the pinned generator version, full-schema digest, stable-schema digest, schema indexes, and catalog references.
The catalog retains the existing `:sha256` for the full experimental export and adds `:stable-sha256` for the bundled stable schema.
The JAR retains the catalog, schemas, and generator pin byte-for-byte.

The initial baseline reproduced all 441 generated files with Codex 0.155.1 before the stable-schema provenance fields were added.

## Preview the package locally

The tasks in [bb.edn](bb.edn) call [build.clj](build.clj) through the isolated `:build` alias in [deps.edn](deps.edn).
`tools.build` creates a library JAR and dependency POM. `slipset/deps-deploy` uploads them to Clojars.
Build dependencies stay outside the published dependency list.
This follows the [Clojure tools.build guide](https://clojure.org/guides/tools_build).

Java 21 and Babashka 1.12.218 match CI. The workflow also installs Clojure CLI 1.12.0.1530.
The Babashka tasks can invoke Clojure through Babashka's bundled `deps.clj` runner locally.

```sh
bb version:show
bb jar
jar tf "target/clj-codex-$(bb version:show).jar"
bb install
bb test:build
bb test:release
```

These tasks do not require a Git tag or Clojars credentials.
The generated POM is `target/classes/META-INF/maven/com.github.loganlinn/clj-codex/pom.xml`.
The JAR includes `src`, `resources`, and `apis`, with their contents at the classpath root.
It also includes both licenses and the private WebSocket implementation's provenance.
Tests, examples, build dependencies, and dependency classes are excluded.
Each build clears `target` first.
The POM records the project URL, description, license, dependencies, and source tag or commit.
Clojars requires license metadata and rejects unstable dependency versions in releases.
See [Clojars deployment validation](https://github.com/clojars/clojars-web/wiki/Pushing#validations).

After a local install, use the installed Maven version for `com.github.loganlinn/clj-codex` in a separate consumer project.

## Publish a snapshot

1. Commit and push these build and workflow files to `main`.
   Manual dispatch requires the workflow file on the default branch.
2. Check that the committed `version.edn` contains a snapshot version.
3. Check that CI passes for the selected commit on `main`.
4. Dispatch the snapshot workflow:

   ```sh
   gh workflow run snapshot.yml --ref main
   gh run list --workflow snapshot.yml --limit 5
   ```

5. Check the workflow result and the version on [Clojars](https://clojars.org/com.github.loganlinn/clj-codex).

The workflow accepts only `main`, checks the exact event commit, and publishes with the existing repository secrets.
It reads `version.edn` from that commit and rejects a version without the `-SNAPSHOT` suffix.
The release and snapshot workflows share a publication queue.

Use the snapshot from a consumer project:

```clojure
{:deps {com.github.loganlinn/clj-codex {:mvn/version "0.1.0-SNAPSHOT"}}}
```

Clojars accepts repeated uploads of the same snapshot version.
Each upload receives a timestamp and build number. The base snapshot coordinate resolves to the newest upload.
Older uploads remain available through their timestamped identifiers.
See [Clojars snapshot identifiers](https://github.com/clojars/clojars-web/wiki/Stable-SNAPSHOT-Identifiers).
Consumers can use `clojure -Sforce` to refresh dependency resolution.
When you need a stable version that cannot change, use a tagged release.

For local publication, export the credentials and run `bb publish:snapshot` from a clean checkout.
GitHub repository secrets are available inside Actions, not automatically in your local shell.

## Publish a release

1. Prepare the version for release:

   ```sh
   bb release:prepare
   ```

2. Commit the change to `version.edn` as the release commit.
3. Merge the release commit into `main` through the normal review process.
4. Wait for that commit's CI run on `main` to pass.
5. Start from a clean checkout of that commit.
6. Create the draft release:

   ```sh
   bb release:draft
   ```

7. Review the generated notes at the draft URL.
8. Publish the draft release in GitHub.
9. Check the **Publish to Clojars** workflow result.
10. Check the version on [Clojars](https://clojars.org/com.github.loganlinn/clj-codex).
11. Start the next development version:

    ```sh
    bb version:bump
    ```

12. Commit the new snapshot version and merge it into `main`.

For example, this sequence changes `0.1.0-SNAPSHOT` to `0.1.0`, tags that commit, then starts `0.1.1-SNAPSHOT`.
Use `bb version:bump minor` or `bb version:bump major` for a different next development version.

The task requires Git, an authenticated GitHub CLI with repository write access, and the Java and Babashka tools described above.
The `origin` fetch and push URLs must both point to `github.com/loganlinn/clj-codex` through HTTPS or SSH.
The task accepts no arguments and runs from the repository root.

Before it creates a tag, the task checks:

- The checkout is clean, including untracked files.
- `version.edn` matches the committed file and contains a release version without `-SNAPSHOT`.
- The tag is absent locally and on `origin`.
- No GitHub release or draft uses the tag.
- The latest `ci.yml` push run on `main` for this exact commit completed successfully.
- Clojars contains neither the version's POM nor its JAR.

A missing, pending, or failed CI run stops the task.
A network error or an unexpected response also stops the task.
The task creates an annotated tag at the checked commit, runs `release:check`, and pushes only that tag.
It then creates a draft with generated notes and prints the draft URL.
It does not publish the draft or upload to Clojars.

Versions with `-alpha.N`, `-beta.N`, or `-rc.N` automatically create prerelease drafts.
The preparation and draft commands are the same for stable releases and prereleases.

The workflow passes the tag through `RELEASE_TAG` and runs `bb release:check`, then `bb publish`.
The publication task repeats the source checks, builds once, and uploads that JAR and POM.
It does not run tests or modify source files.
The publication task rejects any mismatch between `RELEASE_TAG` and `version.edn`.

## Recover from a failed release

If `release:draft` fails after tag creation, it keeps the tag and stops before subsequent steps.
The task refuses an existing tag, including one from an earlier attempt.
It never deletes or replaces tags.

Before you resume, inspect the local tag, remote tag, and GitHub release.
Check that the version is still absent from Clojars.
From the original clean checkout, run the remaining steps:

```sh
bb release:check
release_tag="v$(bb version:show)"
git -c push.followTags=false push origin "refs/tags/$release_tag:refs/tags/$release_tag"
```

If the GitHub draft is absent, create it:

```sh
gh release create "$release_tag" --repo loganlinn/clj-codex --verify-tag --draft --generate-notes --title "$release_tag"
```

For a prerelease tag, add `--prerelease` to this manual command.
If the draft already exists, review that draft.

Clojars does not allow replacement of a published release version.
The publisher reports a deployment error on a duplicate version. It does not silently skip the upload.

- If the run fails before upload, correct the external cause and rerun the failed workflow.
- If the upload result is uncertain, inspect Clojars before retrying.
- If Clojars contains the version, do not rerun publication or move the tag.
- If source or workflow changes are necessary, create a new version and release.
- If an incomplete upload blocks a retry, contact Clojars support or use a new version.

A rerun uses the original event commit.
It does not pick up later source or workflow fixes.
Deleting a GitHub Release does not remove its Clojars package.

Snapshot retries can create another timestamped upload under the same snapshot version.
To publish current `main`, start a new manual dispatch instead of rerunning an older snapshot run.
An older run uses its original commit and can make older code the newest snapshot.
See [GitHub workflow reruns](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs).
