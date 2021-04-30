# Release Process

The `main` branch contains tagged release commits. We follow a simple process
in order to release a new version of WFL:

1. Create a release branch based off `develop`
   - release branch names follow the convention `release/X.Y.Z-rc`
   - the version string should match that specified in `version`
2. Identify and cherry-pick additional commits from `develop` that you want
   to release (e.g. late features and bug fixes).
3. Create a release candidate and deploy to a testing environment. See
   instructions bellow.
4. [Bash](https://broadinstitute.atlassian.net/wiki/spaces/GHConfluence/pages/1731658083/Feature+Bashes)
   the release candidate. Add/cherry-pick any bug fixes that result.
5. Create a pull request into `main`. You will need to run
   `./ops/cli.py release` to generate the changelog for this release (the `-d`
   flag can be used to do a dry run without writing to the `CHANGELOG.md`file).
6. When the PR is approved, merge it into `main`. The release action will run
   automatically to build, test and build and push the tagged docker images of
   WFL to [DockerHub](https://hub.docker.com/repository/docker/broadinstitute/workflow-launcher-api).
   Please merge PRs that have passed all automated tests only.

!!! tip
    It can take up to 30 minutes for the Github Action to finish! Please be
    patient!

!!! tip
    Remember to create a PR to bump the version string in `version` in
    `develop` for the next release, including changes to `CHANGELOG.md`
    from the release.

## Creating a Release Candidate

In this example, we will create a release candidate for vX.Y.Z. We will assume
the existence of a release branch `release/X.Y.Z-rc`. From `wfl`'s root
directory:

1. Ensure your local repository clone is clean
    ```
    $ make distclean
    ```

2. Prepare sources
    ```bash
    $ git checkout release/X.Y.Z-rc
    $ git pull origin release/X.Y.Z-rc --ff
    ```

4. Build the docker images locally
    ```bash
    $ make TARGET=images
    ```

5. Tag the commit and release the images to dockerhub with the release
   candidate tag. Let us assume that this is the Mth release candidate.
    ```bash
    $ ./ops/cli.py tag-and-push-images --version=X.Y.Z-rcN
    ```

!!! tip
    You can run `make` in parallel by adding `-jN` to the end of your `make`
    command, where `N` is the number of concurrent jobs to run.
