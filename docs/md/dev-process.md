# Development Process

This is a development process
we are tying to standardize
within the team,
and encourage ourselves
to follow in most cases.

## The Swagger page

WFL ships with a swaggger UI that documents all of the
available endpoints. It's available at path `/swagger`, e.g.
https://dev-wfl.gotc-dev.broadinstitute.org/swagger

## Development Setup

Clojure development feels very different from Scala and Java
development. It even differs markedly from development in other
*dynamic languages* such as Python or Ruby.

Get a demonstration from someone familiar with Clojure
development before you spend too much time trying to figure
things out on your own.

Find a local Cursive user for guidance if you like IntelliJ.
[Rex Wang](mailto:chengche@broadinstitute.org) and
[Saman Ehsan](mailto:sehsan@broadinstitute.org) know how to use it.
Cursive licences are available
[here](https://broadinstitute.atlassian.net/wiki/spaces/DSDE/pages/48234557/Software%2BLicenses%2B-%2BCursive).
The steps for getting this project set up with very recent versions of IntelliJ
differ from Cursive's docs:

???+ tip
    It is recommended to run `prebuild` before launching IntelliJ
    as it sets up all libraries and derived resources and sources:
    ```bash
    make TARGET=prebuild - jN
    ```

1. *Outside of IntelliJ*, `clone` the repo and run `boot` at the top-level to
generate the `project.clj` (see below)
2. *Now inside of IntelliJ*, import the project by specifically targeting the
`project.clj` file (it should offer to import the entire project, and targeting
the `project.clj` will make use of Leiningen to work with Cursive)
3. Use the Project Structure window (Help -> Find Action -> Project Structure) to set a JDK as the Project SDK

There is also a
[Calva](https://marketplace.visualstudio.com/items?itemName=betterthantomorrow.calva)
plugin for [Visual Studio Code](https://code.visualstudio.com/).

I hack Clojure in Emacs using
[CIDER](https://cider.readthedocs.io/) and
[nREPL](https://github.com/clojure/tools.nrepl). CIDER is not
trivial to set up, but not *especially* difficult if you are
used to Emacs. (I can help if CIDER gives you trouble.)

Every time `boot` runs, it generates a `project.clj` file to
support `lein`, Cursive, and Calva users.

Running `boot build` will not only build a fat jar (*uberjar*)
for the WFL project, but will add an executable symbolic link
`wfl` to conveniently execute the Clojure code as a script.

## Process

We always make feature branches from `master`,
make pull requests,
ask for reviews
and merge back to `master` on Github.

For the release process, please refer to the [release guide](/docs/md/dev-release.md)

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

## Development Tips

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
in `wfl.util/do-or-nil` to ensure
that any exceptions show up
in the server logs.

### debugging API specs

If an API references an undefined spec,
HTTP requests and responses might silently fail
or the Swagger page will fail to render.
Check the `clojure.spec.alpha/def`s
in `wfl.api.routes` for typos
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

Modify the value of `WFL_POSTGRES_URL`
in `(postgres/wfl-db-config)` to redirect
the WFL server's database
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

### Useful hacks for debugging Postgres/Liquibase locally

Starting `postgres`:
```bash
pg_ctl -D /usr/local/var/postgresql@11 start
```

!!! tip
    It might be useful to set up some an alias for postgres if you are using zsh, for
    example:
    ```
    alias pq="pg_ctl -D /usr/local/var/postgresql@11"
    ```
    thus you could use `pq start` or `pq stop` to easily spin up and turn down the db.


Running `liquibase update`:
```bash
liquibase --classpath=$(clojure -Spath) --url=jdbc:postgresql:wfl --changeLogFile=database/changelog.xml --username=$USER update
```
For the above, the username and password need to be correct for the target environment.

If you're running a local server with the postgres command above, you don't need a
password and can omit it.

Otherwise, you may be able to find this data in the Vault entry for the
environment's server --
`resources/wfl/environments.clj` has some environments if you've built locally.
You can use `--password=$ENV_SOMETHING` to supply it.

!!! tip
    It is more convenient to use the following alias to migrate the database schema
    from within the `api` directory:
    ```
    clojure -A:liquibase
    ```
    if you are working aginst a local database. If you are working with a CloudSQL
    database, the liquibase migration is a encoded step of the `cli.py`'s `deploy`
    command.

### Diagnosis

Workflow Launcher has a diagnostic command, `dx`,
for debugging problems.

Run `wfl dx` to get a list of the diagnostics available.

```bash
$ java -jar derived/api/target/wfl.jar dx

wfl dx: tools to help debug workflow problems.

Usage: wfl dx <tool> [<arg> ...]
Where: <tool> is the name of some diagnostic tool.
       <arg> ... are optional arguments to <tool>.

The <tool>s and their <arg>s are named here.
  all-metadata environment & ids
    All workflow metadata for IDS from Cromwell in ENVIRONMENT.
  event-timing environment id
    Time per event type for workflow with ID in ENVIRONMENT.
...
Error: Must specify a dx <tool> to run.
BTW: You ran: wfl dx
wm28d-f87:wfl yanc$
```
