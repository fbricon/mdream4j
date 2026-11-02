# Releasing

Releases go to Maven Central through the Sonatype Central Portal. The `release` profile in the
root POM does the work: it signs the artifacts with GPG, attaches the javadoc jar and uploads the
reactor, then waits until the bundle is published.

Only `mdream4j-parent` and `mdream4j` are in the reactor. The reference bridge and the benchmark
modules are profile-gated and are never deployed.

## Prerequisites

1. The `io.github.fbricon` namespace is verified in the Central Portal.
2. A GPG key exists locally and its public key is on the keyservers:
   `gpg --list-secret-keys` and `gpg --keyserver keyserver.ubuntu.com --send-keys <id>`.
3. `~/.m2/settings.xml` has a `central` server entry with a Portal user token as the password:

   ```xml
   <settings>
     <servers>
       <server>
         <id>central</id>
         <username>TOKEN_USERNAME</username>
         <password>TOKEN_PASSWORD</password>
       </server>
     </servers>
   </settings>
   ```

## Release steps

```shell
# 1. Full parity check (needs the Rust reference bridge and the submodule).
git submodule update --init --depth 1 tools/upstream
cargo build --release --manifest-path tools/reference/Cargo.toml
./mvnw -Pwith-reference clean verify

# 2. Native-image smoke (needs GraalVM CE 21 or 25 on PATH).
./tools/native-smoke/run.sh

# 3. Move every module off the snapshot version. The extra profiles are only
#    there so the bench and reference POMs are updated too; they are not part
#    of the deploy below.
./mvnw -Pwith-bench,with-reference versions:set -DnewVersion=0.1.0
./mvnw -Pwith-bench,with-reference versions:commit
git commit -am "release 0.1.0"

# 4. Sign, build the javadoc jar and publish parent + library.
./mvnw -Prelease clean deploy

# 5. Tag the released commit and start the next snapshot.
git tag -s v0.1.0 -m "mdream4j 0.1.0"
./mvnw -Pwith-bench,with-reference versions:set -DnewVersion=0.1.1-SNAPSHOT
./mvnw -Pwith-bench,with-reference versions:commit
git commit -am "chore: start 0.1.1-SNAPSHOT"
git push --follow-tags
```

`autoPublish` is enabled in the profile, so `deploy` also releases the bundle. Set it to `false`
in the profile to stop after the upload and release manually in the Portal.

## Rehearsing without credentials

`verify` exercises everything except the upload, and `-Dgpg.skip=true` skips signing:

```shell
./mvnw -Prelease -Dgpg.skip=true verify
```

That produces `mdream4j-<version>.jar`, `-sources.jar` and `-javadoc.jar` in `mdream4j/target/`.
The `mdream4j-bench` and `mdream4j-reference` modules are not part of a release build.

## OSGi note

bnd turns `0.1.0-SNAPSHOT` into a timestamped `Bundle-Version` (`0.1.0.<build>`), which OSGi
accepts. A released build has an exact `Bundle-Version` (`0.1.0`) and an exported package version
that matches it.
