# Publishing to Clojars

Both publication workflows are independent of test CI.
All packages use the coordinate `com.github.loganlinn/clj-codex`.
The release workflow runs only on `release: published`, including GitHub prereleases.
A tag push, draft release, or release edit does not publish to Clojars.
Publishing a GitHub Release starts the upload. It does not mean that the package is already available.

The workflow builds the exact release commit and checks that its tag still points to that commit.
Versions come from tags, without a separate version file or version commit.
Accepted tags are `vMAJOR.MINOR.PATCH`, optionally followed by `-alpha.N`, `-beta.N`, or `-rc.N`.
For example, `v0.1.0-rc.1` publishes Maven version `0.1.0-rc.1`.
The separate snapshot workflow accepts `MAJOR.MINOR.PATCH-SNAPSHOT` through manual dispatch on `main`.
Snapshots need no tag or GitHub Release. Their POM records the source commit in the SCM tag field.

## One-time setup

1. Use your Clojars account with permission to publish under `com.github.loganlinn`.
   The build fixes this group in code. There is no workflow input to override it.
2. Create a Clojars deploy token with permission to publish `com.github.loganlinn/clj-codex`.
   Clojars cannot scope a token to an artifact that does not yet exist.
   For the first upload, use a short-lived token with sufficient group scope, or a single-use unscoped token.
3. Add these repository Actions secrets, as you already did:

   | Secret | Value |
   | --- | --- |
   | `CLOJARS_USERNAME` | The Clojars username that owns the deploy token |
   | `CLOJARS_PASSWORD` | The deploy token, not the account password |

4. After the first upload, replace any bootstrap token with an artifact-scoped token.
5. Optionally configure protection rules for the `clojars` deployment environment.
   Allow branch `main` for snapshots and tags matching `v*` for releases.
   Add required reviewers if you want approval before each upload.
6. Protect `main` with required CI checks and review rules.
7. Protect `v*` tags against updates and deletion, and restrict their creation to release maintainers.

GitHub creates the named environment on its first use if it does not exist, without protection rules.
Repository secrets work with these jobs. You do not need to copy them into environment secrets.
Same-name environment secrets take precedence, so avoid stale duplicates.
Environment protections and tag rules require repository settings. The workflow files do not create those rules.
Neither publisher waits for CI or downloads CI artifacts.
Maintainers select a commit whose independent CI checks pass.

The workflow grants GitHub's token read access only.
Actions use full commit SHAs. Tool and build dependencies use explicit versions.
Only the publication step receives Clojars credentials.
Publication runs queue behind each other without interrupting an active upload.

## Preview the package locally

Java 21 and Babashka 1.12.218 match CI. The workflow also installs Clojure CLI 1.12.0.1530.
The Babashka tasks can invoke Clojure through Babashka's bundled `deps.clj` runner locally.

```sh
bb jar v0.1.0
jar tf target/clj-codex-0.1.0.jar
bb install v0.1.0
bb jar 0.1.0-SNAPSHOT
bb install 0.1.0-SNAPSHOT
bb test:build
```

These tasks do not require a Git tag or Clojars credentials.
The generated POM is `target/classes/META-INF/maven/com.github.loganlinn/clj-codex/pom.xml`.
The JAR includes `src`, `resources`, and `apis`, with their contents at the classpath root.
It also includes both licenses and the private WebSocket implementation's provenance.
Tests, examples, build dependencies, and dependency classes are excluded.
Each build clears `target` first.

After a local install, use the installed Maven version for `com.github.loganlinn/clj-codex` in a separate consumer project.

## Publish a snapshot

1. Commit and push these build and workflow files to `main`.
   Manual dispatch requires the workflow file on the default branch.
2. Check that CI passes for the selected commit on `main`.
3. Dispatch the snapshot workflow with an explicit version:

   ```sh
   gh workflow run snapshot.yml --ref main -f version=0.1.0-SNAPSHOT
   gh run list --workflow snapshot.yml --limit 5
   ```

4. Check the workflow result and the version on [Clojars](https://clojars.org/com.github.loganlinn/clj-codex).

The workflow accepts only `main`, checks the exact event commit, and publishes with the existing repository secrets.
It accepts only snapshot versions, so manual dispatch cannot publish an untagged stable release.
The release and snapshot workflows share a publication queue.

Use the snapshot from a consumer project:

```clojure
{:deps {com.github.loganlinn/clj-codex {:mvn/version "0.1.0-SNAPSHOT"}}}
```

Clojars accepts repeated uploads of the same snapshot version.
Each upload receives a timestamp and build number. The base snapshot coordinate resolves to the newest upload.
Consumers can use `clojure -Sforce` to refresh dependency resolution.
Use a tagged release when you need a stable version that cannot change.

For local publication, export the credentials and run `bb publish:snapshot 0.1.0-SNAPSHOT` from a clean checkout.
GitHub repository secrets are available inside Actions, not automatically in your local shell.

## Publish a release

1. Merge the release changes and check that CI passes for the selected commit.
2. Start from a clean checkout of that commit.
3. Create an annotated tag with an unused version:

   ```sh
   git tag -a v0.1.0 -m 'Release 0.1.0'
   bb release:check v0.1.0
   git push origin refs/tags/v0.1.0
   ```

4. Create a draft GitHub Release for that existing tag:

   ```sh
   gh release create v0.1.0 --verify-tag --draft --generate-notes --title v0.1.0
   ```

5. Review the generated notes in GitHub.
6. Publish the draft release in GitHub.
7. Check the **Publish to Clojars** workflow result.
8. Check the version on [Clojars](https://clojars.org/com.github.loganlinn/clj-codex).

For a prerelease, use a prerelease tag and add `--prerelease` when you create the draft.
Publishing the draft triggers the same workflow.

The workflow passes the tag through `RELEASE_TAG` and runs `bb release:check`, then `bb publish`.
The publication task repeats the source checks, builds once, and uploads that JAR and POM.
It does not run tests or modify source files.
The tasks also accept an explicit tag argument for local use.

## Recover from a failed release

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

See [the research notes](releasing-research.md) for the official documentation behind these choices.
