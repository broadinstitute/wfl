# Welcome to WorkFlow Launcher

[![Build Board](https://img.shields.io/badge/-The%20Build%20Board%20(VPN)-blue)](https://internal.broadinstitute.org/~chengche/green-hornet-status.html)
![Docsite Build](https://github.com/broadinstitute/wfl/workflows/Publish%20docs%20via%20GitHub%20Pages/badge.svg?branch=main)
![Build on Main](https://github.com/broadinstitute/wfl/workflows/Tests%20on%20Pull%20Requests%20and%20Main/badge.svg?branch=main)
[![GitHub tag (latest by date)](https://img.shields.io/github/v/tag/broadinstitute/wfl?label=Latest%20Release)](https://github.com/broadinstitute/wfl/blob/main/CHANGELOG.md)

## Overview

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
    check other sections such as the [development guide](/docs/md/dev-process.md)
    or [modules design principles](/docs/md/modules-general.md).

### Build

The easiest way to build WFL is via `make` (or `gmake` on macOS), in addition, the
following prerequisites are needed:

- [The Docker daemon](https://www.docker.com/products/docker-desktop)
- Clojure (`brew install clojure` on macOS)
- Python3 (`brew install python@3.8` on macOS)
- NodeJS (`brew install node` on macOS)

!!! tip "Arch Linux tips"
    - Install [clojure](https://www.archlinux.org/packages/?name=clojure)
      from the official repository.
    - Install
      [google-cloud-sdk](https://aur.archlinux.org/packages/google-cloud-sdk)
      from the AUR.

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

where currently available `MODULE`s are {api functions/aou docs helm ui}

For most of the time, you would want to run something like:

```bash
$ make clean
```

to clean up the built modules (`-j8` is also available for `make clean`).

and then run:

```bash
$ make ui api TARGET=images -j8
```

to **only build** the WFL and its docker images without running tests.

!!! info
    Note if you updated the second party repositories such as
    `pipeline-config` or `gotc-deploy`, you might have to run:
    ```bash
    $ make distclean
    ```
    to remove them. This is not always needed but can help completely
    purge the local derived files.


### Test

If you only want to run tests on specific modules, you could run:

```bash
$ make [MODULE] TARGET=check
```

such as `make api TARGET=check` or `make functions/aou TARGET=check`.
Note this automatically makes all of `check`'s prerequisites.

#### Clojure Test

When it comes to clojure tests, sometimes it's useful to only run a subset
of tests to save time and filter out noise. You can do this by directly
invoke `clojure` cli from within the `api` directory, for example, `cd api` and:

```bash
$ clojure -M:test integration --focus wfl.integration.modules.copyfile-test
```

In general, we implement Clojure tests under the `test/` root directory and use the
[kaocha](https://cljdoc.org/d/lambdaisland/kaocha/1.0.632/doc/readme) test
runner. Test suites use a `-test` namespace suffix. You can pass extra command
line arguments to `kaocha`, such as the above `--focus` flag.
You can see the full list of options with the following:

```shell
clojure -M:test --help
```

At present, `wfl` api has three kinds of test, `unit`, `integration`, and `system`.
These can be run via the `deps.edn`, optionally specifying the kind:

```shell
clojure -M:test [unit|integration|system]
```

Note that the integration tests currently require a little more configuration
before they can be run, namely, they require a `wfl` server running locally:

```shell
./ops/server.sh
```

Additionally, there is a custom parallel test runner that can be invoked
to help speed up the `system` tests. Rather than `clojure -M:test system` you'd
just specify the namespace(s) to try to parallelize.

```shell
clojure -M:parallel-test wfl.system.v1-endpoint-test
```

!!! info
    Note for `system` tests, no matter it's kicked off through `clojure -M:test system` or
    `clojure -M:parallel-test wfl.system.v1-endpoint-test`, you can use an environment
    variable `WFL_CROMWELL_URL` to override the default Cromwell instance that's used in the test. For
    example:

    ```bash
    WFL_CROMWELL_URL=https://cromwell-gotc-auth.gotc-prod.broadinstitute.org/ clojure -M:parallel-test wfl.system.v1-endpoint-test
    ```

    will tell the test to submit workflows to the "gotc-prod" Cromwell instance no matter what the
    default instance was defined in the test. However, you need to make sure the validity of the Cromwell
    URL you passed in; certain IAM permissions will also be required in order for Cromwell to execute the
    testing workflows smoothly.

### Deploy

Currently, we mainly deploy WFL to `broad-gotc-dev` and `broad-gotc-prod` projects.
When it's time to deploy WFL, for most of the time developers need to
release a new version following the steps in [Release Guide](/docs/md/dev-release.md)

After which, the developers who have broad VPN connected can go to the
[Jenkins Page](https://gotc-jenkins.dsp-techops.broadinstitute.org/job/deploy-wfl/build?delay=0sec)
to deploy applicable versions of WFL to various available cloud projects.

Learn more about the deployment details in
[Deployment of WorkFlow Launcher](/docs/md/dev-deployment.md).

## Implementation

For frontend details, check [Frontend Section](/docs/md/dev-frontend.md)

### Top-level files

After cloning a new WFL repo, the top-level files are:
```
.
├── api/            - `workflow-launcher` backend
├── functions/      - cloud functions deployed separately
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

The `build.clj` file includes build and deployment code.

The `debug.clj` file defines some macros useful when debugging
or logging.

The `util.clj` file contains a few functions and macros used in
WFL that are not specific to its function.

The `environments.clj` file defines configuration parameters for
different execution contexts. It's a placeholder in this repo
but will be loaded in build/deploy time from a private repo.

The `module/xx.clj` file implements a command-line starter for
reprocessing *eXternal eXomes*.

The `module/wgs.clj` file helps implements a command-line starter for
reprocessing *Whole GenomeS*.

The `module/sg.clj` file implements *Somatic Genomes* support.

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
