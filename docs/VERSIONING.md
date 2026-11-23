# Versioning

mdream4j versions describe two things at once: **which mdream release the port tracks** and
**which port revision that is**.

## Scheme

```
<mdream-version>.<port-revision>
```

The first three segments are the upstream mdream version. The fourth is the port revision.

| Version     | Meaning                                                     |
|-------------|-------------------------------------------------------------|
| `2.2.0.1`   | First mdream4j release tracking mdream 2.2.0               |
| `2.2.0.2`   | Java-side fix, still tracking mdream 2.2.0                 |
| `2.2.1.1`   | Synced to mdream 2.2.1; the port revision resets to 1      |
| `2.3.0.1`   | Synced to mdream 2.3.0                                     |

### Rules

1. The first three segments always equal [`mdream.upstream.version`](../pom.xml). Change them only
   when the port moves to a new upstream mdream release.
2. The port revision starts at `1`. Increment it for a release that does not move upstream. Reset
   it to `1` when the upstream version changes. `2.2.0.0` is intentionally unused.
3. Published versions are immutable. Never reuse a version or move a published GAV; ship a fix as
   the next port revision. This is also Maven Central's own rule.
4. Development builds use the next release version plus `-SNAPSHOT`, for example
   `2.2.0.2-SNAPSHOT`. The published version is the release tag without the `v` prefix
   (`v2.2.0.2` → `2.2.0.2`), so a SNAPSHOT is never deployed and a stale SNAPSHOT in the POM can
   never leak into a release.
5. Tags are the version with a `v` prefix: `v2.2.0.1`.

## Where the upstream reference is recorded

The version only carries the upstream release, not the exact commit. Both are recorded in the POM,
the JAR manifest and the documentation:

| Location                 | Value                                            |
|--------------------------|--------------------------------------------------|
| Root POM properties      | `mdream.upstream.version`, `mdream.upstream.commit` |
| JAR / OSGi manifest      | `Mdream4j-Upstream-Version`, `Mdream4j-Upstream-Commit` |
| `README.md` status line  | Current development version                      |
| [`docs/REFERENCE.md`](REFERENCE.md) | Parity pin and corpus provenance      |

When the parity pin moves, update `mdream.upstream.version` and `mdream.upstream.commit`, set the
project version to `<new-upstream>.1-SNAPSHOT`, and regenerate the goldens in the same change (see
the `mdream-backport` skill).

## Why this scheme

- `<mdream-version>` alone (`2.2.0`) leaves no room for a port-only release: a Java fix could not
  be published without inventing a different upstream version.
- `2.2.0-1` is converted by bnd to the OSGi version `2.2.0.1`, colliding with the dot form, and a
  hyphen qualifier is a pre-release to Gradle and SemVer tooling.
- `0.1.0-mdream.2.2.0` puts the upstream version in a qualifier. Maven sorts that after `0.1.0`
  while Gradle treats it as a pre-release of `0.1.0`, bnd strips the dots
  (`0.1.0.mdream220`), and the port revision has no defined slot.
- `2.2.0.1` is all numeric, so Maven, Gradle and OSGi agree on the order, and the port revision is
  the trailing digit where it belongs.

## Tool behaviour

Maven orders the lifecycle as expected, with numeric port revisions:

```
2.2.0.1-SNAPSHOT < 2.2.0.1 < 2.2.0.2-SNAPSHOT < 2.2.0.2 < ... < 2.2.0.10 < 2.2.1.1
```

In OSGi, `2.2.0.1` is `micro=0` with qualifier `1`, and bnd replaces `-SNAPSHOT` with a build
timestamp (`Bundle-Version: 2.2.0.1-202611230956`). OSGi qualifiers compare lexicographically, so
`2.2.0.10` sorts before `2.2.0.2` there. That only matters for a range that starts at a specific
qualifier; ordinary Maven ranges and OSGi ranges such as `[2.2,2.3)` are unaffected.

## Bumping versions

Move every POM at once, including the profile-gated reference and benchmark modules:

```shell
./mvnw versions:set -DnewVersion=2.2.0.2-SNAPSHOT -DprocessAllModules=true \
  -DgenerateBackupPoms=false
```

Do not activate `-Pwith-reference` or `-Pwith-bench` for the release build: those modules are not
published.
