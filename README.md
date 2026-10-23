# Vocabler

A personal/family vocabulary app: Spring Boot 4.1.1, Java 21+, and a plain JavaScript PWA served from the same application. No Node build or separate frontend server is required.

## Run

Install a JDK (21 or newer), then from this directory:

```powershell
.\gradlew.bat bootRun
```

On Linux/macOS: `sh gradlew bootRun`.

Open http://localhost:8080 and create an account. There are no default credentials or seeded accounts. The Gradle wrapper supplies Gradle 9.1.0; no separate Gradle installation is required. Internet access is needed for the first Gradle and dependency download.

Vocabulary and packages are private by default. A language pair belongs to its creator, who can share it with selected existing usernames. Recipients can read and study the words and packages; only the owner can change the collection or manage sharing. Learning progress and settings remain personal. Account registration is open; invitations are not implemented.

## Docker and GitHub releases

Container images are built with the [Jib Gradle plugin](https://github.com/GoogleContainerTools/jib/tree/master/jib-gradle-plugin). Install a JDK (21 or newer) and Docker with Compose. Copy `.env.example` to `.env`, set `POSTGRES_PASSWORD`, then build the local image and start PostgreSQL and the app:

```sh
sh gradlew jibDockerBuild
docker compose up -d --no-build --wait
```

On Windows, use `.\gradlew.bat jibDockerBuild`. Jib defaults to `vocabler:local`; image building needs no Dockerfile. To build an archive without a Docker daemon, run `sh gradlew jibBuildTar` (output: `build/jib-image.tar`).

The GitHub Actions workflow tests H2 and PostgreSQL, builds and smoke-tests the container, and publishes images to `ghcr.io/<owner>/<repository>` from the default branch and `v*` release tags. Released images support AMD64 and ARM64. For deployment, set `VOCABLER_IMAGE` in `.env`, then run `docker compose pull` and `docker compose up -d --no-build --wait`.

See [Docker deployment and CI instructions](docs/deployment.md) for release tags, HTTPS, database persistence/backups, and PostgreSQL tests.

## First session

1. Create a language pair, e.g. German / Romanian.
2. Open Vocabulary to add entries or import `examples/german-romanian.csv`.
3. Create packages, select words in the table, and add them to a package. An entry can belong to several packages. Removing package membership preserves the entry and its progress.
4. To delete vocabulary, select its checkbox (or select a page of words), choose **Delete selected words**, and confirm. Deletion removes the words from every package and permanently removes all users' progress, review history, and pending cards for those words. Removing words from a package remains a separate action that preserves vocabulary and progress.
5. Open Learn. A due card appears automatically after login or when you change the pair, direction, or package. Use Check for cards to refresh the queue.
6. Tap/click the card to reveal the answer. Swipe left for Again or right for Knew it, or use the buttons. Keyboard users can focus the card and press Enter/Space, then tab to the answer buttons.

## Private vocabulary and selected-user sharing

Each language pair is a separate owned collection, including all its words and packages. Different users can own the same languages without sharing any vocabulary. The selector labels collections as yours or shared by their owner.

As the owner, open **Share vocabulary** below the language selectors, enter an existing username, and select **Share with user**. Sharing covers the whole language pair; package-only sharing and collaborative editing are not implemented. Recipients can view and practice the collection in either direction. They cannot add words, import CSV, change packages, view the recipient list, or share it onward.

Use **Stop sharing** beside a recipient to revoke access. Future catalog and learning requests are denied and outstanding review tickets are invalidated. Personal scores are retained if access is granted again. Sharing never exposes another user's learning scores or settings. There is no public catalog or automatic sharing with newly registered accounts.

Authorization is checked on the server for catalog reads, every mutation, import preview and commit, queue selection, and review submission. Hidden UI controls are not the access boundary.

## Correcting words imported in the wrong order

In **Vocabulary**, select the affected words and choose **Swap translations**, then confirm. This exchanges the two translations for those entries in place; the language pair, word IDs, notes, and package memberships stay intact. Select a package first to limit the list to an imported batch, then use the page checkbox to select its words.

Learning scores, correct-answer streaks, review dates, active status, mastery recall, and review history move to the opposite direction for every user so they still describe the same question and answer. Pending cards for the affected words expire and must be loaded again. Only the collection owner can swap words, including in shared collections.

The operation is atomic. If a reversed word would duplicate an unselected entry, no selected words are changed; select a smaller batch or resolve the duplicate first. Swapping both existing reverse counterparts together is supported. To undo a swap, select the same words and swap them again.

## Learning model

The unique progress key is **user + vocabulary entry + translation direction**. German → Romanian and Romanian → German never update one another. Each has independent scores, review dates, active membership, and mastery recall eligibility.

- Scores range from 0 to 10 (fractional values, stored to three decimal places after a review).
- Defaults: base correct gain +1; incorrect −2; graduation at 8.
- Each word has its own consecutive-correct streak per user and translation direction. Correct reviews earn 1×, 2×, 3×, then at most 4× the configured base gain. With defaults, a new word advances 0 → 1 → 3 → 6 → 10 in four correct scheduled reviews. A miss resets that word’s directional streak to 0 and applies the normal penalty. Streaks persist across sessions; existing history is used to recover them on upgrade, preserving scores and review dates.
- Each user has up to 75 active entries **per language pair and direction**. This is configurable from 10 to 200.
- Graduating entries free a place for new words. Practice in a selected package parks active entries outside that package to make room, preserving all scores and review dates.
- Incorrect maintenance reviews rejoin the active list when there is room. If full, they remain eligible for maintenance and later enrollment.
- A review schedules the next appearance: score below 3: 5 minutes; below 5: 8 hours; below 8: 1 day; below 10: 3 days. Score 10: check again after 7 days, provided decay has made it eligible.
- Every fifth completed review gives maintenance priority when maintenance words are due. Otherwise active words get priority. Either group fills in when the other has no due words. Thus maintenance may exceed 20% when only maintenance is due.
- At exactly 10 a word is excluded. Scores above 8 decay by 0.2/day by default, using fractional elapsed days, with a floor of 8. Effective scores are calculated on demand from the last score timestamp; no daily job is needed.
- Reaching 10 enables mastery recall. A correct maintenance review restores 10 immediately and restarts its clock. A miss applies the ordinary penalty and clears the shortcut until the user earns 10 again.
- Settings are personal and configurable in the UI. Changing decay interprets elapsed time since the last review with the new rate. A decay of 0 keeps words at 10 excluded indefinitely.
- A server-issued ticket expires after one hour and can be answered once by its owner. Review updates are transactional and serialized per account.

There is no forced repeat within a session: when nothing is due, add words, change the selection, or return later.

## CSV import

UTF-8 (optional BOM), comma-separated. Use the exact header `source,target` for two-column files, or `source,target,notes` for three-column files:

```csv
source,target,notes
Apfel,măr,der Apfel
Guten Morgen,Bună dimineața,"A greeting, used in the morning"
```

Choose **CSV language order** before previewing. The choices use the selected pair's actual languages. For a German / French pair, select **French → German** to import a file whose `source` column contains French and `target` contains German:

```csv
source,target,notes
bonjour,Guten Tag,greeting
```

The preview shows the stored pair order: German `Guten Tag`, French `bonjour`. Notes stay attached to the word, and duplicates are checked after mapping the columns, so reverse imports do not create flipped copies or reset existing progress. This setting does not change the practice direction or its independent scores. Changing the file, CSV order, pair, or selected package clears the preview; preview again before committing.

The third column is named `notes`, not `optional notes`. Each data row must have the same number of columns as the header. With a `source,target,notes` header, end a row with a comma when its notes are empty (e.g. `Apfel,măr,`).

Quoted commas and multiline fields are supported. Maximum upload: 2 MB / 5,000 rows; translations: 200 characters; notes: 1,000 characters. Preview validates every row without saving. Commit revalidates and writes atomically; one invalid row prevents the entire import. Exact source/target duplicates within the pair or file are skipped. Existing notes are preserved. When a package is selected, valid imported words (including existing ones) are added to it. The preview table shows the first 20 rows.

## Storage and deployment

The default database is a persistent H2 file in `data/vocabler.mv.db`, relative to the working directory. Stop the application before copying the database for a consistent backup. Start it from this directory to use the same database. Database migrations are managed by Flyway.

Ownership migration: an existing V1 catalog with exactly one account is assigned to that account. With multiple accounts, existing language pairs remain preserved with an unassigned owner and are hidden from everyone until an administrator explicitly assigns each pair to its rightful owner using `language_pair.owner_user_id`. No ownership is guessed and no sharing grants are created automatically. Back up a real database before upgrading or assigning legacy ownership. New collections always receive the signed-in creator as owner.

Optional environment variables:

| Variable | Default |
| --- | --- |
| `VOCABLER_DB_URL` | `jdbc:h2:file:./data/vocabler` |
| `VOCABLER_DB_USER` | `sa` |
| `VOCABLER_DB_PASSWORD` | empty |
| `VOCABLER_SECURE_COOKIE` | `false` for localhost HTTP |
| `SERVER_PORT` | `8080` |

H2 is the default for direct development runs. PostgreSQL 18 is supported for container deployment; the driver, Flyway module, and SQL are included. The Compose example configures PostgreSQL automatically. Switching database engines does not copy existing data.

For family access outside localhost, use HTTPS and set `VOCABLER_SECURE_COOKIE=true`. PWA installation and service workers require HTTPS, except on localhost. The app has a manifest, 192/512px icons, and a service worker that caches the public application shell. **Learning, login, and editing require a connection**; offline reviews and synchronization are not implemented. Private API responses are never cached by the service worker.

Passwords are BCrypt-hashed. Authentication uses server sessions, HTTP-only SameSite cookies, and CSRF protection. Browser code obtains a CSRF token from `/api/auth/csrf` and refreshes it after login/logout. This initial version does not include password recovery, invitations, or login rate limiting.

## Verify and package

```powershell
.\gradlew.bat clean build
java -jar build/libs/vocabler-0.0.1-SNAPSHOT.jar
```

On Linux/macOS, use `sh gradlew clean build`. To run only tests, use `.\gradlew.bat test` (or `sh gradlew test`). Test reports are written to `build/reports/tests/test/index.html`.

Dependencies are declared in `build.gradle.kts`. Spring Boot dependency versions are aligned using its BOM through native Gradle platform support. Java sources are compiled for Java 21 with parameter metadata retained for Spring MVC.

Tests cover scoring/decay, mastery recall, user and direction isolation, bounded enrollment, graduation/refill, package selection, single-use review tickets, scheduling, CSV direction mapping/validation/duplicates/atomicity, word deletion/cascades/atomicity, translation swaps/progress preservation/collision handling, registration/login/logout, CSRF enforcement, private ownership, selected-user sharing, revoked-ticket rejection, recipient write denial, and both single- and multiple-account upgrades. JavaScript syntax can be checked with `node --check src/main/resources/static/app.js` and the same command for `sw.js`.

## Main endpoints

- `GET /api/auth/csrf`, `POST /api/auth/register`, `POST /api/auth/login` (form fields username/password), `GET /api/auth/me`, `POST /api/auth/logout`
- `GET/POST /api/pairs` (lists only owned or explicitly shared pairs)
- `GET/PUT /api/pairs/{pairId}/shares` (owner-only recipient list and share-by-username)
- `DELETE /api/pairs/{pairId}/shares/{recipientId}` (owner-only revocation)
- `GET/POST /api/pairs/{pairId}/entries`
- `DELETE /api/pairs/{pairId}/entries/{entryId}` (owner-only word deletion)
- `DELETE /api/pairs/{pairId}/entries` (owner-only atomic bulk deletion; JSON `{"entryIds":[1,2]}`, at most 5,000 IDs)
- `POST /api/pairs/{pairId}/entries/swap` (owner-only atomic swap; JSON `{"entryIds":[1,2]}`, at most 5,000 IDs)
- `GET/POST /api/pairs/{pairId}/packages`
- `PUT/DELETE /api/pairs/{pairId}/packages/{packageId}/entries/{entryId}`
- `POST /api/pairs/{pairId}/import` (multipart file, commit=false/true, optional packageId, direction=FORWARD/REVERSE; default FORWARD)
- `GET/PUT /api/learning/settings`
- `POST /api/learning/next` (`pairId`, `direction`: FORWARD/REVERSE, optional `packageId`)
- `POST /api/learning/review` (`ticket`, `correct`)

All vocabulary and learning APIs require login. All writes, including login and registration, require the CSRF header returned by the token endpoint.

## Current limits

This is a working first version. Editing vocabulary, deleting language pairs, account administration, audio/pronunciation, progress charts, offline synchronization, and configurable review intervals are not yet implemented. Sharing currently applies to whole language pairs with read-only study access. Selected-package sharing, collaborative editing permissions, and ownership transfer are not yet implemented.
