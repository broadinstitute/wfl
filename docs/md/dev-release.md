# Release Process

Currently developers follow a simple process
in order to release a new version of WFL:

1. Developers create a standalone "release PR",
which should be exclusively used by releasing WFL.
2. In the PR, developers need to semanticaly bump
the version string in the `/version` file at the root
of the repo.
3. In the PR, developers need to run `./ops/cli.py release`
to generate the changelog for this release. (`-d` flag can
be used to do a dry run without writing to the `CHANGELOG.md`
file)
4. Once the release PR is reviewed and merged to `master`
branch, developers can go to Github Action
[Release Latest Version](https://github.com/broadinstitute/wfl/actions?query=workflow%3A%22Release+Latest+Version%22)
and manually tirgger a run with the `Run workflow` button to build and push the tagged docker images of WFL to
[DockerHub](https://hub.docker.com/repository/docker/broadinstitute/workflow-launcher-api).

!!! tip
    Note it can take up to 30 minutes for the Github Action to finish the build and push.
