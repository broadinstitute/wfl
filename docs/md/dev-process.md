# Development Process

This is a development process
we are tying to standardize
within the team,
and encourage ourselves
to follow in most cases.

## Summary

We always make feature branches from `master`,
make pull requests,
ask for reviews
and merge back to `master` on Github.

Currently we always deploy the latest master
to the development environment after merge,
but in the future we might need
to cut off releases on master
and deploy the released versions
to the server only.
It's not decided yet.

## Steps

1. Clone the repo
    ```
    git@github.com:broadinstitute/wfl.git
    ```

2. Start from the latest copy of the remote master
    ```
    git checkout master
    git pull origin master
    ```

3. Create a feature branch

    _It is highly recommend that you follow the naming convention
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

5. [Optional] Rebase onto lastet master: only if you want to get updates from the master
    ```
    git checkout master
    git pull origin master
    git checkout tbl/GH-666-feature-branch-something
    git rebase master
    ```

    alternatively, you could use the following commands without switching branches:
    ```
    git checkout tbl/GH-666-feature-branch-something
    git fetch origin master
    git merge master
    ```

6. Push branch to Github in the early stage of your development (recommended):
    ```
    git push --set-upstream origin tbl/GH-666-feature-branch-something
    ```

7. Create the pull request on Github UI. Be sure to fill out the PR description following the PR template instructions.

    - If the PR is still in development, make sure use the dropdown menu and choose `Create draft pull request`

    - If the PR is ready for review, click `Create pull request`.

8. Look for a reviewer in the team.

9. Address reviewer comments with more commits.

10. Receive approval from reviewers.

11. Make sure build the backend code at least once with:
    ```
    make api
    ```

12. Merge the PR. The feature branch will be automatically cleaned up.

13. [Temporary] Fetch the lastest master branch again and deploy it to dev server.
    ```
    git checkout master
    git pull origin master
    boot deploy
    ```

    you might need to login to vault and google by the following commands before you want to deploy:
    ```
    vault auth -method=github token=$(cat ~/.github-token)
    gcloud auth login
    ```

    **Note: this action might interfere other people's work that is under QA, please always coordinate before you do this!**

## Tips

Here are some tips for WFL development.

Some of this advice might help
when testing Liquibase migration
or other changes
that affect WFL's Postgres database.

### migrating a database

To change WFL's Postgres database schema,
add a changeset XML file
in the `database/changesets` directory.
Name the file for a recent or the current date
followed by something describing the change.
That will ensure that the changesets
list in the order in which they apply.
Note that the `id` and `logicalFilePath` attributes
are derived from the changeset's file name.
Then add the changeset file
to the `database/changlog.xml` file.

Test the changes against a local _scratch database_.
See the next section for suggestions.

### debugging JDBC SQL

Something seems to swallow SQL exceptions
raised by Postgres and the JDBC library.
Wrap suspect `clojure.java.jdbc` calls
in `zero.util/do-or-nil` to ensure
that any exceptions show up
in the server logs.

### debugging API specs

If an API references an undefined spec,
HTTP requests and responses might silently fail
or the Swagger page will fail to render.
Check the `clojure.spec.alpha/def`s
in `zero.api.routes` for typos
before tearing your hair out.

### hacking a scratch database

You can test against a local Postgres
before running Liquibase or SQL
against a shared database
in `gotc-dev` or *gasp* production.

First install Postgres locally.

``` shell
brew install postgresql@11
```

You need version 11 because that
is what Google's hosted service supports,
and there are differences in the SQL syntax.

Set `"ZERO_POSTGRES_URL"`
to `(postgres/zero-db-url :debug)`
in `zero.server/env_variables`
to redirect the WFL server's database
to a local Postgres server.
With that hack in place,
running `./ops/server.sh`
(or however you launch a local WFL server)
will connect the server to a local Postgres.

Now any changes to WFL state
will affect only your local database.
That includes running Liquibase,
so don't forget to reset `:debug` to `env`
before deploying your changes
after merging a PR.

### Useful hacks for debugging Postgres/Liquibase

Starting `postgres`:
```bash
pg_ctl -D /usr/local/var/postgresql@11 start
```

Running `liquibase update`:
```bash
liquibase --classpath=$(clojure -Spath) --url=jdbc:postgresql:wfl --changeLogFile=database/changelog.xml --username=$USER update
```
For the above, the username and password need to be correct for the target environment.

If you're running a local server with the postgres command above, you don't need a password and can omit it.

Otherwise, you may be able to find this data in the Vault entry for the environment's server --
`resources/zero/environments.clj` has some environments if you've built locally. You can use `--password=$ENV_SOMETHING`
to supply it.

### Test

We implement tests under the `test/` root directory and use the
[kaocha](https://cljdoc.org/d/lambdaisland/kaocha/1.0.632/doc/readme) test
runner. Test suites use a `-test` namespace suffix. You can pass extra command
line arguments to `kaocha`. For example, to run a specific test point:

```shell
clojure -A:test --focus my.integration-test/test-foo-works
```

You can see the full list of options with the following:

```shell
clojure -A:integration --help
```
