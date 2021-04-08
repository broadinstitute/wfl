# Release Process

The `main` branch contains tagged release commits. We follow a simple process
in order to release a new version of WFL:

1. Create a release branch based off `develop`
   - release branch names follow the convention `release/X.Y.Z-rc`
   - the version string should match that specified in `version`
2. Identify and cherry-pick additional commits from `develop` that you want
   to release (e.g. late features and bug fixes).
3. Create a pull request into `main`. You will need to run
   `./ops/cli.py release` to generate the changelog for this release (the `-d`
   flag can be used to do a dry run without writing to the `CHANGELOG.md`file).
5. When the PR is approved, merge it into `main`. The release action will run
   automatically to build, test and build and push the tagged docker images of
   WFL to [DockerHub](https://hub.docker.com/repository/docker/broadinstitute/workflow-launcher-api).
   Please merge PRs that have passed all automated tests only.

!!! tip
    It can take up to 30 minutes for the Github Action to finish! Please be 
    patient!

!!! tip
    Remember to create a PR to bump the version string in `version` in
    `develop` for the next release.
