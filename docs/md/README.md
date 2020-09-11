# Welcome to WorkFlow Launcher


[To the Build Board](https://internal.broadinstitute.org/~jwarren/green-hornet-status.html)

## Overview

![Docsite Build](https://github.com/broadinstitute/wfl/workflows/Publish%20docs%20via%20GitHub%20Pages/badge.svg?branch=master)
![ Build on Master ](https://github.com/broadinstitute/wfl/workflows/Tests%20on%20Pull%20Requests%20and%20Master/badge.svg?branch=master)

[WorkFlow Launcher (WFL)](https://github.com/broadinstitute/wfl.git)
is a workload manager.

For example, a workload could be a set of Whole Genome samples to be reprocessed in a
given project/bucket, the workflow is the processing of an individual sample
in that workload running WGS reprocessing; a workload could also be a queue of incoming notifications that describe all of the required inputs to launch Arrays scientific pipelines in Cromwell.

WFL is designed to be deployed to run as a service in the cloud, primarily on Kubernetes clusters.

!!! info ""
    For more on Workflow Launcher's role in the Terra infrastructure see [Workflow Launcher's role in Terra](terra.md).


## Quickstart

???+ tip ""
    This is the Quickstart section, which should cover the most frequent
    uses cases that interact with WFL. For more detailed information, please
    check other sections such as the [development hacks](dev-process.md)
    or [modules design principles](module-arrays.md).

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

!!! info "about the out-of-order outputs"
    If the version of your `make` is above GNU Make 4.0 (you could check
    by running `make --version`), it's **highly recommended** to use
    `--output-sync` along with `-j` so the standard outputs are sorted, i.e.
    ```
    $ make -j8 --output-sync
    ```

`make` will build each module in `workflow-launcher`, run tests and generate
`Docker` images. All generated files go into a `derived` directory under the
project root.

You can also invoke `make` on a module from the top level directory by

```bash
$ make [MODULE] TARGET={prebuild|build|check|images|clean|distclean}
```

For most of the time, you would want to run something like:

```bash
$ make ui api TARGET=images -j8
```
to **only build** the WFL and its docker images without running tests.

## Versioning

Workflow Launcher needs to manage its version and the versions of
`dsde-pipelines.git` which contribute the WDL files. There may be as
many `dsde-pipelines.git` versions as there are workflow wdls.

The `wfl` jar includes a manifest with at least that information in
it, and a version command that returns it.

## Capabilities

When Workflow Launcher is on-premises or in the cloud, it can
currently talk to the following services:

| service                     | on premises | in cloud |
| --------------------------- | ----------- | -------- |
| Cloud SQL                   | x           | x        |
| Cromwell                    | x           | x        |
| Google App Engine           | x           | x        |
| Google Cloud Platform Admin | x           | x        |
| Google Cloud Storage        | x           | x        |
| Oracle DB                   | x           |          |
| Vault                       | x           | x        |

Workflow Launcher has a diagnostic mode, `dx`,
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

#### Test code

Test code lives under the project `test/` root. At present, `wfl` has two kinds
of test, `unit` and `integration`. These can be run via the `deps.edn`,
optionally specifying the kind:

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

#### Development

WFL is implemented in [Clojure](https://clojure.org) and uses a
tool named `boot` or `boot-clj` to manage dependencies and so on.
The `boot` tool is a Clojure bootstrapper: it's job is to turn a
standard Linux, MacOS, or Windows process into something that can
host a Clojure program.

WFL uses a `gcloud auth` command line to authenticate the user. You
need to be authenticated to Google Cloud and have a recent version
of `google-cloud-sdk` in your path to run `wfl` or its jar
successfully. I verified that `Google Cloud SDK 304.0.0` works. That
or any later version should be OK.

#####  Installation

See [this link](https://github.com/boot-clj/boot#install) to
install `boot-clj`.

Running `boot` is enough to "install" Clojure.

The `build.boot` file is equivalent to the `build.sbt` file for
SBT in Scala projects. It specified project dependencies and the
build and release pipeline. It also functions as a script for
running and testing the project without a separate compilation
step.

There is another tool like `boot` named `lein`, which is short
for "[Leiningen](https://leiningen.org/)". You currently need
`lein` to develop with
[IntelliJ](https://www.jetbrains.com/idea/) using its Clojure
plugin [Cursive](https://cursive-ide.com/).

###### MacOS
On MacOS, I suggest installing [Homebrew](http://brew.sh/) and
then running this.

``` bash
# brew install boot-clj leiningen
```

You can `brew install maven`, and `java` too if necessary.

###### Arch Linux
Install [clojure](https://www.archlinux.org/packages/?name=clojure) and
[leiningen](https://www.archlinux.org/packages/?name=leiningen)
from the official repositories.
Install [boot](https://aur.archlinux.org/packages/boot/) and
[google-cloud-sdk](https://aur.archlinux.org/packages/google-cloud-sdk)
from the AUR.

#####  Hacking

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

####  Testing

If you've never run `boot` before, you may have to run it twice:
first to bootstrap Clojure and `boot` itself, and again to
download their and WFL's dependencies.

The first `boot build` run will create a `./wfl` link to the
`build.boot` file

```bash
./wfl starter dev $USER@broadinstitute.org
```

You should eventually receive an humongous email from
`wfl@broadinstitute.org` containing evidence of WFL's
adventures.

Of course, after `boot build`, you can also run WFL from its
JAR file.

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
