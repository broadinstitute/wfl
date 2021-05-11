# Development Process

This is a development process we are tying to standardize within the team and
encourage ourselves to follow in most cases.

## The Swagger page

WFL ships with a Swagger UI that documents all available endpoints. It's
available at path `/swagger`.  At present, we cannot hit it directly
without logging in first because it is bundled with the UI and not the API.

- Log into WFL UI, e.g. https://dev-wfl.gotc-dev.broadinstitute.org/login
- Navigate to `/swagger` via Swagger API button in top right

!!! tip
    To access the swagger page locally, you'll need to start a development
    server and access via the UI. See the development tips below for more
    information.

!!! tip
To access the swagger page locally, you'll need to start a development server
and access via the UI. See the development tips below for more information.

## Development Setup

Clojure development feels very different from Scala and Java development. It
even differs markedly from development in other *dynamic languages* such as
Python or Ruby.

Get a demonstration from someone familiar with Clojure development before you
spend too much time trying to figure things out on your own.

Find a local Cursive user for guidance if you like IntelliJ.
[Rex Wang](mailto:chengche@broadinstitute.org) knows how to use it.

Cursive licences are available
[here](https://broadinstitute.atlassian.net/wiki/spaces/DSDE/pages/48234557/Software%2BLicenses%2B-%2BCursive).
If none are available, free
[non-commercial licenses](https://cursive-ide.com/buy.html) are suitable for
open-source development.

The steps for getting this project set up with very recent versions of IntelliJ
differ from Cursive's docs:

???+ tip
    Run `make prebuild` before launching IntelliJ as it sets up all libraries
    and derived resources and sources:
    ```bash
    make TARGET=prebuild -jN
    ```

1. *Outside of IntelliJ*, `clone` the repo.
2. *Now inside of IntelliJ*, import the project.
3. Use the Project Structure window (Help -> Find Action -> Project Structure)
   to set a JDK as the Project SDK

There is also a
[Calva](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva)
plugin for [Visual Studio Code](https://code.visualstudio.com/).

I hack Clojure in Emacs using
[CIDER](https://cider.readthedocs.io/) and
[nREPL](https://github.com/clojure/tools.nrepl). CIDER is not
trivial to set up, but not *especially* difficult if you are
used to Emacs. (I can help if CIDER gives you trouble.)

## Process

We base feature branches off `develop`, make pull requests, ask for reviews
and merge back to `develop` on Github.

For the release process, please refer to the [release guide](./dev-release.md).

1. Clone the repo
    ```
    git@github.com:broadinstitute/wfl.git
    ```

2. Start from the latest copy of the remote develop
    ```
    git checkout develop
    git pull origin develop
    ```

3. Create a feature branch

    _It is highly recommended that you follow the naming convention
    shown below so JIRA could pick up the branch and link it
    to our JIRA board._
    ```
    git checkout -b tbl/GH-666-feature-branch-something
    ```

4. Start your work, add and commit your changes
    ```
    git add "README.md"
    git commit -m "Update the readme file."
    ```

5. [Optional] Rebase onto latest develop if you want to get updates
    ```
    git checkout develop
    git pull origin develop --ff
    git checkout tbl/GH-666-feature-branch-something
    git rebase develop
    ```

    alternatively, you could use the following commands without switching
    branches:
    ```
    git checkout tbl/GH-666-feature-branch-something
    git fetch origin develop
    git merge develop
    ```

6. Push branch to Github in the early stage of your development (recommended):
    ```
    git push --set-upstream origin tbl/GH-666-feature-branch-something
    ```

7. Create the pull request on Github UI. Be sure to fill out the PR description
   following the PR template instructions.

    - If the PR is still in development, make sure use the dropdown menu and
      choose `Create draft pull request`

    - If the PR is ready for review, click `Create pull request`.

8. Look for reviewer(s) in the team.

9. Address reviewer comments with more commits.

10. Receive approval from reviewers.

11. Merge the PR.



## Development Tips

Here are some tips for WFL development.

Some of this advice might help when testing Liquibase migration or other
changes that affect WFL's Postgres database.

### setting up a local Postgres

You can test against a local Postgres before running Liquibase or SQL against a
shared database in `gotc-dev` or *gasp* production.

1. Install Postgres locally.
    You need version 11 because that is what Google's hosted service supports,
    and there are differences in the SQL syntax.

    ```
    brew install postgresql@11
    ```

2. Start Postgres.

    ```
    pg_ctl -D /usr/local/var/postgresql@11 start
    ```
    !!! tip
        It might be useful to set up some an alias for postgres if you are using
        zsh, for example:
        ```
        alias pq="pg_ctl -D /usr/local/var/postgresql@11"
        ```
        thus you could use `pq start` or `pq stop` to easily spin up and turn down
        the db.

3. [Optional] Create wfl DB.

    If you see errors like this when launching a local WFL server
    or applying liquibase updates:

    ```
    FATAL:  database "wfl" does not exist
    ```

    You should do as instructed within your terminal:

    ```
    createdb wfl
    ```

    Or to recreate an existing wfl DB:

    ```
    dropdb wfl
    createdb wfl
    ```

You are now free to launch a local WFL server pointing to your local DB.

Assuming that `WFL_POSTGRES_URL` in `(wfl.environment/defaults)` is set to
point at a running local Postgres (e.g. `jdbc:postgresql:wfl`), running
`./ops/server.sh` (or however you launch a local WFL server) will
connect the server to that running local Postgres.

Now any changes to WFL state will affect only your local database.
That includes running Liquibase, so don't forget to reset `:debug` to `env`
before deploying your changes after merging a PR.


### migrating a database

To change WFL's Postgres database schema, add a changeset XML file in the
`database/changesets` directory. Name the file for a recent or the current date
followed by something describing the change. That will ensure that the
changesets list in the order in which they apply. Note that the `id` and
`logicalFilePath` attributes are derived from the changeset's file name.
Then add the changeset file to the `database/changlog.xml` file.

Test the changes against a local _scratch database_. See the next section for
suggestions.

### debugging JDBC SQL

Something seems to swallow SQL exceptions raised by Postgres and the JDBC
library. Wrap suspect `clojure.java.jdbc` calls in `wfl.util/do-or-nil` to
ensure that any exceptions show up in the server logs.

### debugging API specs

If an API references an undefined spec, HTTP requests and responses might
silently fail or the Swagger page will fail to render. Check the
`clojure.spec.alpha/def`s in `wfl.api.routes` for typos before tearing your
hair out.

### debugging Liquibase locally

Running `liquibase update`:
```bash
liquibase --classpath=$(clojure -Spath)          \
          --url=jdbc:postgresql:wfl               \
          --changeLogFile=database/changelog.xml \
          --username=$USER update
```
For the above, the username and password need to be correct for the target
environment.

If you're running a local server with the postgres command above, you don't
need a password and can omit it.

Otherwise, you may be able to find this data in the Vault entry for the
environment's server --
`resources/wfl/environments.clj` has some environments if you've built locally.
You can use `--password=$ENV_SOMETHING` to supply it.

!!! tip
    It is more convenient to use the following alias to migrate the database
    schema from within the `api` directory:
    ```
    clojure -M:liquibase
    ```
    if you are working with a local database.

### Override ENVIRONMENT variables for local development

WFL uses `src/wfl/api/environment.clj` to read and process environment variables.
Most of the variables have their default values, which can be overwritten for development
purposes. For example, if we want to run system tests in parallel against a local
WFL instance, use below under `api/` directory:

```shell
WFL_WFL_URL=http://localhost:3000 clojure -M:parallel-test wfl.system.v1-endpoint-test
### REPL testing with fixtures.

Now that we're using fixtures,
and so on,
in our tests,
it is no longer good enough
to run `deftest` vars as functions.
Running a test like this `(test-something)`
does not set up the necessary fixtures.

However,
`clojure.test/test-vars` can run a test
with all the surrounding `clojure.test`
mechanism in place.
It takes a vector of `var`s like this.

``` clojure
(comment (test-vars [#'test-something]))
```
