# Docker, PostgreSQL, and GitHub releases

## Local Compose build

Docker Desktop / Docker Engine and Docker Compose are required. A local JDK or Gradle installation is not needed for a container build.

1. Copy `.env.example` to `.env` and set a nonempty `POSTGRES_PASSWORD`.
2. From the repository root, run:

```sh
docker compose -f compose.yaml -f compose.build.yaml up -d --build --wait
```

Open http://localhost:8080. The application is built from source in a Java 21 build stage and runs as a non-root user in a Java 21 JRE image. The runtime filesystem is read-only in Compose, with temporary storage at `/tmp`.

The local override uses `vocabler:local`. The base `compose.yaml` uses `VOCABLER_IMAGE`, which can be changed to a published GHCR image without rebuilding it.

The `db` service uses PostgreSQL 18. It has no published host port. The `postgres_data` named volume is mounted at `/var/lib/postgresql`, the correct parent location for the PostgreSQL 18 official image. See the [official image documentation](https://hub.docker.com/_/postgres).

`docker compose down` stops the stack while preserving the database volume. Subsequent starts reuse the data. PostgreSQL initialization credentials only take effect on the first initialization of an empty volume; changing `.env` does not rotate an existing database password.

## GitHub CI and GHCR publishing

The workflow is `.github/workflows/ci.yaml`. Push this project to a GitHub repository with Actions enabled. No repository name is hardcoded: the image name is the lowercase `ghcr.io/<owner>/<repository>`.

Every branch push and pull request runs:

1. The Gradle build and H2 tests on Java 21.
2. The application integration tests and upgrade migrations against a real PostgreSQL 18 service.
3. A Docker image build.
4. A Compose smoke test with a fresh PostgreSQL database and database-aware readiness checks.

Test reports are retained as workflow artifacts for seven days. Third-party Actions are pinned to full commit IDs. Dependabot is configured to propose updates for those Actions, Docker base images, and Gradle dependencies.

After the test job succeeds, the default branch and version tags publish `linux/amd64` and `linux/arm64` images to GHCR. Pull requests and other branch pushes do not publish. The publish job uses the repository-provided `GITHUB_TOKEN` with `packages: write`; no registry secret is needed. The workflow configures the same GHCR login mechanism described in the [GitHub publishing guide](https://docs.github.com/en/actions/tutorials/publish-packages/publish-docker-images).

Published tags:

| Trigger | Tags |
| --- | --- |
| Default branch | `edge`, `sha-<full commit>` |
| Version tag such as `v1.2.3` | `v1.2.3`, `1.2.3`, `1.2`, `sha-<full commit>`; `latest` for stable semantic releases |

To release a version, commit the changes and push a version tag, for example:

```sh
git tag v1.0.0
git push origin v1.0.0
```

A manual workflow run on the default branch can also publish an `edge` image. Creating the workflow file does not itself publish an image or create a GitHub repository.

The new GHCR package may initially be private. To deploy a private package, authenticate the deployment machine with an account/token that has package read access using `docker login ghcr.io`. Alternatively, explicitly make the package public in GitHub if that is your intended distribution model.

## Run a published image

Copy `compose.yaml` and `.env.example` to the deployment directory. Rename `.env.example` to `.env`, set the database password, and select the published image, for example:

```dotenv
VOCABLER_IMAGE=ghcr.io/your-user/vocabler:v1.0.0
```

Replace `your-user/vocabler` with the actual lowercase repository name. The image must have been published successfully first.

```sh
docker compose pull
docker compose up -d --no-build --wait
```

For a later upgrade, change the image tag (or pin an immutable `@sha256:...` digest), back up the database, then run the same commands. Flyway applies pending migrations when the application starts.

Compose binds the application to `127.0.0.1:8080` by default. For access from family devices, serve it through an HTTPS reverse proxy and set `VOCABLER_SECURE_COOKIE=true`. Forwarded headers are enabled in Compose for that proxy. Keep direct application access restricted to the proxy; only trust forwarded headers from that route. PWA installation outside localhost requires HTTPS.

The public `/actuator/health/liveness` and `/actuator/health/readiness` endpoints return status only. Readiness includes a database check and is used by the Docker health check. Other Actuator endpoints are not publicly exposed.

There is no automatic remote-server deployment job: CI publishes the image, and Compose pulls/runs it on the deployment machine. Sessions are currently held in memory, so replacing/restarting the application requires users to log in again. Run one app replica until shared sessions are introduced.

## PostgreSQL tests on a development machine

Use a dedicated disposable PostgreSQL database named `vocabler_test`. The integration suite resets its application tables; do not point it at the deployed database.

On PowerShell:

```powershell
$env:TEST_DB_URL = 'jdbc:postgresql://localhost:5432/vocabler_test'
$env:TEST_DB_USER = 'vocabler'
$env:TEST_DB_PASSWORD = 'your-test-database-password'
.\gradlew.bat postgresTest
```

On Linux/macOS:

```sh
TEST_DB_URL=jdbc:postgresql://localhost:5432/vocabler_test \
TEST_DB_USER=vocabler \
TEST_DB_PASSWORD=your-test-database-password \
sh gradlew postgresTest
```

The ordinary `build` task always uses an in-memory H2 database and does not need Docker or PostgreSQL. Reports are under `build/reports/tests/test/` and `build/reports/tests/postgresTest/`.

The JDBC driver, Flyway PostgreSQL module, SQL, identifier casing, timestamps, and both upgrade scenarios are exercised by the PostgreSQL suite. Existing H2 development databases remain supported. Switching to PostgreSQL does not copy an existing H2 database or transfer its progress automatically.

## Database backup

A named volume persists data but is not a backup. This example writes a custom-format dump inside the database container, then copies it out without piping binary data through the host shell:

```sh
docker compose exec -T db sh -c 'pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc -f /tmp/vocabler.dump'
docker compose cp db:/tmp/vocabler.dump ./vocabler.dump
```

Store backups outside the container/volume and use distinct backup filenames. Database backups and actual `.env` files are ignored by Git and the Docker build context. Restore with a matching PostgreSQL `pg_restore` into an appropriate empty database; test the restore procedure before relying on backups.
