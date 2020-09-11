# Welcome to WorkFlow Launcher


[To the Build Board](https://internal.broadinstitute.org/~jwarren/green-hornet-status.html)

## Overview

![Docsite Build](https://github.com/broadinstitute/wfl/workflows/Publish%20docs%20via%20GitHub%20Pages/badge.svg?branch=master)
![ Build on Master ](https://github.com/broadinstitute/wfl/workflows/Tests%20on%20Pull%20Requests%20and%20Master/badge.svg?branch=master)

[WorkFlow Launcher (WFL)](https://github.com/broadinstitute/wfl.git)
is a workload manager.

For example, a workload could be a set of Whole Genome samples to be reprocessed in a
given project/bucket, the workflow is the processing of an individual sample
in that workload running WGS reprocessing; a workload could also be a queue of
incoming notifications that describe all of the required inputs to launch Arrays
scientific pipelines in Cromwell.

WFL is designed to be deployed to run as a service in the cloud, primarily
on Kubernetes clusters.

!!! info ""
    For more on Workflow Launcher's role in the Terra infrastructure
    see [Workflow Launcher's role in Terra](/docs/md/terra.md).


## Quickstart

???+ tip
    This is the Quickstart section, which should cover the most frequent
    uses cases that interact with WFL. For more detailed information, please
    check other sections such as the [development guide](/docs/md/dev.md)
    or [modules design principles](/docs/md/module.md).

### Build

The easiest way to build WFL is via `make` (or `gmake` on macOS), in addition, the
following prerequisites are needed:

- [The Docker daemon](https://www.docker.com/products/docker-desktop)
- Clojure (`brew install clojure` on macOS)
- [Boot](https://github.com/boot-clj/boot) (`brew install boot-clj` on macOS)
- Python3 (`brew install python@3.8` on macOS)
- NodeJS (`brew install node` on macOS)

You could then invoke `make` at the project level to test and build all
`workflow-launcher` modules:

```bash
$ make -j8
```
where `8` can be replaced by any number that represents the concurrent
jobs you wish to run.

!!! info
    If the version of your `make` is above GNU Make 4.0 (you could check
    by running `make --version`), it's **highly recommended** to use
    `--output-sync` along with `-j` so the standard outputs are sorted, i.e.
    ```
    $ make -j8 --output-sync
    ```

`make` will **build** each module in `workflow-launcher`, **run tests** and **generate**
`Docker` images. All generated files go into a `derived` directory under the
project root.

You can also invoke `make` on a module from the top level directory by

```bash
$ make [MODULE] TARGET={prebuild|build|check|images|clean|distclean}
```

where currently available `MODULE`s are {api cloud_function docs helm ui}

For most of the time, you would want to run something like:

```bash
$ make clean && make distclean
```

to clean up the built modules such as the docker images or the derived folder first,
and then run:

```bash
$ make ui api TARGET=images -j8
```

to **only build** the WFL and its docker images without running tests.

### Test

If you only want to run tests on specific modules, you could run:

```bash
$ make [MODULE] TARGET=check
```

such as `make api TARGET=check` or `make cloud_function TARGET=check`.

#### Clojure Test

When it comes to clojure tests, sometimes it's useful to only run a subset
of tests to save time and filter out noise. You can do this by directly
invoke `clojure` cli from within the `api` directory, for example, `cd api` and:

```bash
$ clojure -A:test integration --focus wfl.integration.v1-endpoint-test
```

In general, the Clojure test code lives under the project `test/` root. At present,
`wfl` api has two kinds of test, `unit` and `integration`. These can be run
via the `deps.edn`, optionally specifying the kind:

```shell
clojure -A:test [unit|integration]
```

Note that the integration tests currently require a little more configuration
before they can be run, namely, they require a `wfl` server running locally:

```shell
./ops/server.sh
```

See the [development guide](/docs/md/dev-process.md#Test) for more
information.

### Deploy

Currently, we mainly deploy WFL to gotc-dev and gotc-prod projects.
When it's time to deploy WFL, developers need to bump the version string
in the `version` file at the root of repo, which could be done either in
a standalone PR or along with a feature PR. After having done that, the Github Action
[Release Latest Version](https://github.com/broadinstitute/wfl/actions?query=workflow%3A%22Release+Latest+Version%22)
will get triggered to build and push the tagged docker images of WFL to
[DockerHub](https://hub.docker.com/repository/docker/broadinstitute/workflow-launcher-api).

From here, the developers who have broad VPN connected can go to the
[Jenkins Page](https://gotc-jenkins.dsp-techops.broadinstitute.org/job/deploy-wfl/build?delay=0sec)
to deploy applicable versions of WFL to various available cloud projects.

!!! warning
    In addition to its own version, Workflow Launcher also needs to manage
    the verions of `dsde-pipelines.git` which contribute the WDL files.
    Currently, that version is controlled by the commit hash string in
    function `stage-some-files` in `api/src/boot.clj`, for instance:

    ```clojure
    (util/shell-io! "git" "-C" (.getParent environments)
                      "checkout" "ad2a1b6b0f16d0e732dd08abcb79eccf4913c8d8")
    ```

    In the long term, this is likely to change.

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

## Implementation

For frontend details, check [Frontend Section](/docs/md/frontend.md)

### Top-level files

After cloning a new WFL repo, the top-level files are:
```
.
├── api/            - `workflow-launcher` backend
├── cloud_function/ - functions deployed separately
├── database/       - database scheme migration changelog and changeset
├── derived/        - generated artifacts
├── docs/           - ancillary documentation
├── helm/           - helm-managed k8s configuration
├── LICENSE.txt
├── Makefile        - project level` Makefile`
├── makerules/      - common `Makefile` functionality
├── ops/            - scripts to support Operations
├── README.md       - symbolic link to docs/md/README.md
├── ui/             - `workflow-launcher` frontend
└── version         - holds the current semantic version
```
Tip: Run `make` at least once after cloning the repo to make sure all the
necessary files are in place.

### `api` Module
#### Source code

The Clojure source code is in the `api/src/` directory.

The entry point for the WFL executable is the `-main` function
in `main.clj`. It takes the command line arguments as strings,
validates the arguments, then launches the appropriate process.

The `server.clj` file implements the WFL server. The
`server_debug.clj` file adds some tools to aid in debugging the
server.

Some hacks specific to WFL are in `wfl.clj`.

The `boot.clj` offloads code from the `build.boot` file for
easier development and debugging.

The `debug.clj` file defines some macros useful when debugging
or logging.

The `util.clj` file contains a few functions and macros used in
WFL that are not specific to its function.

The `environments.clj` file defines configuration parameters for
different execution contexts. It's a placeholder in this repo
but will be loaded in build/deploy time from a private repo.

The `module/ukb.clj` file implements a command-line starter for the
**White Album**, **Pharma5**, or **UK Biobank** project.

The `module/xx.clj` file implements a command-line starter for
reprocessing *eXternal eXomes*.

The `module/wgs.clj` file helps implements a command-line starter for
reprocessing *Whole GenomeS*.

The `module/all.clj` file hosts some utilities shared across modules.

The `metadata.clj` file implements a tool to extract metadata
from Cromwell that can be archived with the outputs generated by
a workflow.

The `dx.clj` file implements miscellaneous pipeline debugging
tools.

The `once.clj` file defines some initialization functions mostly
supporting authentication.

The `api/handlers.clj` file defines the handler functions used by
server.

The `api/routes.clj` file defines the routing strategy for server.

Each of the other source files implement an interface to one of
the services WFL talks to, and are named accordingly.

| File         | Service                                   |
| ------------ | ----------------------------------------- |
| cromwell.clj | Cromwell workflow runner                  |
| datarepo.clj | DSP DataRepo                              |
| db.clj       | On-prem and Cloud SQL databases           |
| gcs.clj      | Google Cloud Storage                      |
| jms.clj      | Java Message Service queues               |
| postgres.clj | Cloud SQL postgres databases              |
| server.clj   | the WFL server itself                     |
| wdl.clj      | parse WDL and manage dependencies         |

####  Exomes in the Cloud Resources

From [Hybrid Selection in the Cloud V1](https://docs.google.com/a/broadinstitute.org/document/d/1g8EmPjOZl-DzHlypXeOjKHzI4ff1LvzBiigDbZTy1Cs/edit?usp=sharing)

1.  Clients
    - [Google Cloud Storage Client Library (Java)](https://cloud.google.com/storage/docs/reference/libraries#client-libraries-install-java)
    - [Google Cloud Client Library for Java](https://googlecloudplatform.github.io/google-cloud-java/0.30.0/index.html)

2.  Diagrams
    - [Zamboni Overview](https://confluence.broadinstitute.org/download/attachments/39552724/ZamboniOverview.pdf)

3.  Sources
    - /Users/tbl/Broad/zamboni/Client/src/scala/org/broadinstitute/zamboni/client/lightning/clp/Lightning.scala
    - /Users/tbl/Broad/picard-private/src/java/edu/mit/broad/picard/lightning
    - /Users/tbl/Broad/gppipeline-devtools/release<sub>client</sub>
    - /Users/tbl/Broad/gppipeline-devtools/starter<sub>control</sub>
    - /picard02:/seq/pipeline/gppipeline-devtools/current/defs/prod.defs
