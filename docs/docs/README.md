# Welcome to WorkFlow Launcher

## Overview

[WorkFlow Launcher (WFL)](https://github.com/broadinstitute/wfl.git) 
is a workload manager.

It runs as you, with your credentails, from your laptop, and
communicates with other services as necessary to manage a workload.

It can also be deployed to run as a service in the cloud.

For more on Workflow Launcher's role in the Terra infrastructure see 
[Workflow Launcher's role in Terra (./docs/docs/terra.md)](./terra.md).

## Set up

Run `boot build` at the top of a `wfl.git` repo to build an
uberjar. The resulting jar is in `./target/zero-*.jar` relative to
the `wfl.git` clone.

With some start-up and performance penalty, you can also run 
Workflow Launcher as a script. See below for details.

## Versioning

Workflow Launcher needs to manage its version and the versions of
`dsde-pipelines.git` which contribute the WDL files. There may be as
many `dsde-pipelines.git` versions as there are workflow wdls.

The `wfl` jar includes a manifest with at least that information in
it, and a version command that returns it.

## Role in Terra

The Data Sciences Platform (DSP) is building a new system (around
Terra) for storing and processing biological data. The system design
includes a Data Repository where data is stored, and a Methods
Repository that executably describes transformations on that data.

In the new system, the DSP needs something to fulfill the role that
Zamboni currently plays in our current infrastructure to support the
Genomics Platform (GP).

Zamboni watches various queues for messages describing new data and
how to process it. Zamboni interprets those messages to dispatch
work to workflow engines (running on the premises or in the loud)
and monitors the progress of those workflows. The Zamboni web UI
allows users to track the progress of workflows, and enables Ops
engineers to debug problems and resume or restart failed workflows.
Zamboni can run workflows on both a local Sun Grid Engine (SGE), and
on Cromwell on premises and in the cloud.

## Capabilities

When Workflow Launcher is on-premises or in the cloud, it can 
currently talk to the following services:

| service                     | on premises | in cloud |
| --------------------------- | ----------- | -------- |
| Clio                        | x           | x        |
| Cloud SQL                   | x           | x        |
| Cromwell                    | x           | x        |
| Google App Engine           | x           | x        |
| Google Cloud Platform Admin | x           | x        |
| Google Cloud Pub/Sub        | x           | x        |
| Google Cloud Storage        | x           | x        |
| JMS                         | x           |          |
| Mercury                     | x           |          |
| Oracle DB                   | x           |          |
| SMTP (mail)                 | x           | x        |
| Vault                       | x           | x        |
| Zero                        |             | x        |


Workflow Launcher has a diagnostic mode, `dx`, which leverages
Cromwell metadata to expose useful workflow information.

Run `zero dx` to get a list of the diagnostics available.

```bash
wm28d-f87:zero yanc$ java -jar ./target/zero-20190409-5.jar dx

zero dx: tools to help debug workflow problems.

Usage: zero dx <tool> [<arg> ...]
Where: <tool> is the name of some diagnostic tool.
       <arg> ... are optional arguments to <tool>.

The <tool>s and their <arg>s are named here.
  all-metadata environment & ids
    All workflow metadata for IDS from Cromwell in ENVIRONMENT.
  event-timing environment id
    Time per event type for workflow with ID in ENVIRONMENT.
...
Error: Must specify a dx <tool> to run.
BTW: You ran: zero dx
wm28d-f87:zero yanc$
```

## Implementation

### Frontend

For frontend details, check [Frontend Section](./frontend.md)

### Backend

The initial file structure looks like this.

```bash
$ tree .
.
├── LICENSE.txt
├── README.md -> ./docs/docs/README.md
├── boot.properties
├── build.boot
├── build.txt
├── database
│   └── migration
│       ├── changelog.xml
│       └── changesets
│           └── 01_db_schema.xml
├── deps.edn
├── docs
│   ├── docs
│   │   ├── README.md
│   │   ├── frontend.md
│   │   ├── server.md
│   │   ├── terra.md
│   │   └── terra.org
│   ├── mkdocs.yml
│   └── requirements.txt
├── ops
│   ├── README.org
│   ├── deploy.sh
│   ├── index.md
│   ├── server.sh
│   └── terra.md -> ./docs/docs/terra.md
├── resources
│   └── simplelogger.properties
├── src
│   └── zero
│       ├── api
│       │   ├── handlers.clj
│       │   └── routes.clj
│       ├── boot.clj
│       ├── debug.clj
│       ├── dx.clj
│       ├── environments.clj
│       ├── main.clj
│       ├── metadata.clj
│       ├── module
│       │   ├── all.clj
│       │   ├── ukb.clj
│       │   ├── wgs.clj
│       │   └── xx.clj
│       ├── once.clj
│       ├── references.clj
│       ├── server.clj
│       ├── server_debug.clj
│       ├── service
│       │   ├── cromwell.clj
│       │   ├── datarepo.clj
│       │   ├── gcs.clj
│       │   ├── postgres.clj
│       │   └── pubsub.clj
│       ├── util.clj
│       ├── wdl.clj
│       └── zero.clj
├── test
│   └── zero
│       ├── datarepo_test.clj
│       ├── gcs_test.clj
│       └── pubsub_test.clj
├── ui/
└── wfl.iml
```

#### Top-level files
    
After cloning a new WFL repo, the top-level files are.

  - `./README.md` is this file, which is just a symlink to the actual doc
    file under `docs/docs/`.

  - `./boot.properties` overrides some defaults in `boot-clj`.
    (`boot.properties` is something like `build.properties` for
    `sbt`.)

  - `./build.boot` is a Clojure script to bootstrap WFL with
    `boot-clj`.

  - `./build.txt` holds a monotonically increasing integer for
    build versioning.

  - `./.github` holds Github related files, such as PR templates and
    Github Actions files.

  - `./docs` holds database scheme migration changelog and changeset
    files for liquibase.

  - `./docs` has ancillary documentation. It's compiled as a static doc
    website.

  - `./ops` is a directory of standard scripts to support
    operations. It includes scripts to deploy the server in
    Google App Engine, and to run it locally for easier
    debugging. (See [./docs/docs/server.md](./server.md) for
    more information.)

  - `./resources` contains the `simplelogger` properties and
    files staged from other repositories that need to be on the
    Java classpath when running from the `.jar` file.

  - `./src/zero` contains the Workflow Launcher source code.

  - `./test/zero` contains some unit tests.

After building and working with WFL a while, you may notice a
couple of other top-level files and directories.

  - `./project.clj` is a `lein` project file to support
    IntelliJ.

  - `./zero` is a link to `build.boot` that runs WFL as a
    script.

Run `boot build` at least once after cloning the repo to make
sure all the necessary files are in place.

#### Source code
    
The Clojure source code is in the `./src/zero` directory.

The entry point for the WFL executable is the `-main` function
in `main.clj`. It takes the command line arguments as strings,
validates the arguments, then launches the appropriate process.

The `server.clj` file implements the WFL server. The
`server_debug.clj` file adds some tools to aid in debugging the
server.

Some hacks specific to WFL are in `zero.clj`.

The `boot.clj` offloads code from the `build.boot` file for
easier development and debugging.

The `debug.clj` file defines some macros useful when debugging
or logging.

The `util.clj` file contains a few function and macros used in
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
| cromwell.clj | Cromwell workflow runner                   |
| datarepo.clj | DSP DataRepo                              |
| db.clj       | On-prem and Cloud SQL databases           |
| gcs.clj      | Google Cloud Storage                      |
| jms.clj      | Java Message Service queues               |
| postgres.clj | Cloud SQL postgres databases              |
| pubsub.clj   | Google Cloud Pub/Sub                      |
| server.cl    | the WFL server itself                    |
| wdl.clj      | parse WDL and manage dependencies         |

#### Test code
    
There are some unit tests under `./test/zero/`.

| File                      | Test the namespace        |
| ------------------------- | ------------------------- |
| gcs<sub>test</sub>.clj    | zero.gcs in gcs.clj       |
| pubsub<sub>test</sub>.clj | zero.pubsub in pubsub.clj |
    

#### Development
    
WFL is implemented in [Clojure](https://clojure.org) and uses a
tool named `boot` or `boot-clj` to manage dependencies and so on.
The `boot` tool is a Clojure bootstrapper: it's job is to turn a
standard Linux, MacOS, or Windows process into something that can
host a Clojure program.

WFL uses a `gcloud auth` command line to authenticate the user. You
need to be authenticated to Google Cloud and have a recent version
of `google-cloud-sdk` in your path to run `zero` or its jar
successfully. I verified that `Google Cloud SDK 161.0.0` works. That
or any later version should be OK.

1.  Cheatsheets
    
    I find a cheatsheet handy when programming in Clojure. There are
    a bunch. Bookmark or print one.
    
      - <https://clojure.org/api/cheatsheet>
      - <https://www.conj.io/> (… used to be called Grimoire …)
      - <https://github.com/jafingerhut/clojure-cheatsheets/tree/master/pdf>
      - <http://cljs.info/cheatsheet/> (ClojureScript)
      - <https://github.com/jafingerhut/clojure-cheatsheets>
        (sources)
    
    These may also be handy.
    
      - [Clojure Error
        Messages](https://github.com/yogthos/clojure-error-message-catalog)
      - ["Weird" Characters in
        Clojure](https://clojure.org/guides/weird_characters)

2.  Installation
    
    See [this link](https://github.com/boot-clj/boot#install) to
    install `boot-clj`.
    
    Running `boot` is enough to "install" Clojure.
    
    There is another tool like `boot` named `lein`, which is short
    for "[Leiningen](https://leiningen.org/)". You currently need
    `lein` to develop with
    [IntelliJ](https://www.jetbrains.com/idea/) using its Clojure
    plugin [Cursive](https://cursive-ide.com/).
    
    On MacOS, I suggest installing [Homebrew](http://brew.sh/) and
    then running this.
    
    ``` bash
    zero # brew install boot-clj leiningen
    ==> Using the sandbox
    ==> Downloading https://github.com/boot-clj/boot-bin/releases/download/2.5.2/boot
    🍺  /usr/local/Cellar/boot-clj/2.5.2: 3 files, 7.7K, built in 2 seconds
    ...
    zero #
    ```
    
    You can `brew install maven`, and `java` too if necessary.
    
    There are `boot-clj` and `lein` distributions for all the common
    OS platforms. Each tool is just a file. Copy them into your
    `PATH`, run them once to bootstrap them, and you're done. (The
    first run of each tool downloads dependencies and so on.)
    
    The `build.boot` file is equivalent to the `build.sbt` file for
    SBT in Scala projects. It specified project dependencies and the
    build and release pipeline. It also functions as a script for
    running and testing the project without a separate compilation
    step.

3.  Hacking
    
    Clojure development feels very different from Scala and Java
    development. It even differs markedly from development in other
    *dynamic languages* such as Python or Ruby.
    
    Get a demonstration from someone familiar with Clojure
    development before you spend too much time trying to figure
    things out on your own.
    
    Find a local Cursive user for guidance if you like IntelliJ.
    [Jay Carey](mailto:jcarey@broadinstitute.org) and [Charley
    Yan](mailto:yanc@broadinstitute.org) know how to use it. There
    are [Cursive licenses
    here](https://broadinstitute.atlassian.net/wiki/spaces/DSDE/pages/48234557/Software%2BLicenses%2B-%2BCursive).
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
    for the Zero project, but will add an executable symbolic link
    `zero` to conveniently execute the Clojure code as a script.

4.  Testing
    
    If you've never run `boot` before, you may have to run it twice:
    first to bootstrap Clojure and `boot` itself, and again to
    download their and WFL's dependencies.
    
    The first `boot build` run will create a `./zero` link to the
    `build.boot` file
    
    ```bash
    ./zero starter dev $USER@broadinstitute.org
    ```
    
    You should eventually receive an humongous email from
    `zero@broadinstitute.org` containing evidence of Zero's
    adventures.
    
    The result should look something like this.
    
    ```bash
    tbl@wm97a-c2b ~/Tmp # brew install boot-clj
    Warning: boot-clj 2.7.2 is already installed
    tbl@wm97a-c2b ~/Tmp # which boot
    /usr/local/bin/boot
    tbl@wm97a-c2b ~/Tmp # ls
    tbl@wm97a-c2b ~/Tmp # git clone https://github.com/broadinstitute/zero.git
    Cloning into 'zero'...
    remote: Counting objects: 456, done.
    remote: Compressing objects: 100% (59/59), done.
    remote: Total 456 (delta 62), reused 98 (delta 44), pack-reused 337
    Receiving objects: 100% (456/456), 71.27 KiB | 663.00 KiB/s, done.
    Resolving deltas: 100% (214/214), done.
    tbl@wm97a-c2b ~/Tmp # ls
    zero
    tbl@wm97a-c2b ~/Tmp # cd ./zero
    tbl@wm97a-c2b ~/Tmp/zero # ls
    README.org      build.boot      src
    tbl@wm97a-c2b ~/Tmp/zero # boot build
    Compiling 1/1 zero.main...
    Adding uberjar entries...
    Writing pom.xml and pom.properties...
    Writing zero-20190409-5.jar...
    Writing target dir(s)...
    tbl@wm97a-c2b ~/Tmp/zero # ls
    README.org      build.boot         project.clj
    src             target             zero
    tbl@wm97a-c2b ~/Tmp/zero # ./zero starter
    zero: Error: Must specify an environment.
    zero: The valid environments are:
      cromwellv36
        Test Cromwell v36 for PAPIv2 requester pays
      cromwellv38
        Test Cromwell v38 for PAPIv2 requester pays
      dev
        Development
      hca
        HCA/DCP Lira and Falcon for the Mint team
      pharma5
        Pharma5 WhiteAlbum UK Biobank, UKB, whatever for ukb.clj
      prod
        Production (gotc-prod)
      staging
        Staging
    Zero: zero Email a report from all systems.
    Usage: zero starter <env> [<to> ...]
    Where: <env> is the runtime environment.
            <to> ... are email addresses of recipients.
    zero: Error: Must specify an environment.
    BTW: You ran: zero starter
    tbl@wm97a-c2b ~/Tmp/zero # ./zero starter dev $USER@broadinstitute.org
    SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
    SLF4J: Defaulting to no-operation (NOP) logger implementation
    SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
    ... just inSANE spilling of debug logs ...
    tbl@wm97a-c2b ~/Tmp/zero #
    ```
    
    Of course, after `boot build`, you can also run WFL from its
    JAR file.
    
    ``` example
    tbl@wm97a-c2b ~/Broad/zero # boot build
    Compiling 1/1 zero.main...
    Adding uberjar entries...
    Writing pom.xml and pom.properties...
    Writing zero-20190409-5.jar...
    Writing target dir(s)...
    tbl@wm97a-c2b ~/Broad/zero # java -jar ./target/zero-20190409-5.jar
    ...
    tbl@wm97a-c2b ~/Broad/zero 1#
    ```

5.  Rich Comments
    
    Some Clojure source files have `(comment ...)` forms at the
    bottom.
    
    ``` example
    tbl@wm97a-c2b ~/Broad/zero # tail ./src/zero/db.clj ./src/zero/main.clj
    ==> ./src/zero/db.clj <==
                  {:connection-uri (metrics-sql-url environment)}
                  db-spec)
                :user username :password password) sql)))
    
    (comment
      (query [:on-prem-picard :dev]
              "select count (*) from picard.res_proj_agg_override")
      (query [:cloud-metrics :dev]
              "SELECT COUNT(*) FROM EXOME_METRICS")
      )
    
    ==> ./src/zero/main.clj <==
      (-main "write-inputs")
      (-main "write-inputs" "WF=ExomeGermlineSingleSample" "FGBN=FGBN" "S=S"
              "REF=./reference.json" "CON_REF=./reference_contamination.json"
              (str "UBAMS=" ubam))
      (-main "write-inputs" "WF=ExomeGermlineSingleSample" "FGBN=FGBN" "S=S"
              "REF=./reference.json" "CON_REF=./reference_contamination.json"
              (str "UBAMS=" ubam) "O=./o.json")
      (-main "run-starter" "ENV=dev" "VERBOSITY=fnord" "fnord")
      (-main "run-starter" "EMAIL=tbl@broadinstitute.org")
      )
    tbl@wm97a-c2b ~/Broad/zero #
    ```
    
    They permit fast testing of code changes by storing expressions
    that you can evaluate in your editor buffer.
    
    Feel free to add, edit, or augment them as you see fit.

6.  Documentation
    
    This file is written in markdown.

8.  Exomes in the Cloud Resources
    
    From [Hybrid Selection in the Cloud
    V1](https://docs.google.com/a/broadinstitute.org/document/d/1g8EmPjOZl-DzHlypXeOjKHzI4ff1LvzBiigDbZTy1Cs/edit?usp=sharing)
    
    1.  Clients
        
          - [Google Cloud Storage Client Library
            (Java)](https://cloud.google.com/storage/docs/reference/libraries#client-libraries-install-java)
        
          - [Google Cloud Client Library for
            Java](https://googlecloudplatform.github.io/google-cloud-java/0.30.0/index.html)
    
    2.  Diagrams
        
          - [Zamboni
            Overview](https://confluence.broadinstitute.org/download/attachments/39552724/ZamboniOverview.pdf)
    
    3.  Sources
        
          - /Users/tbl/Broad/zamboni/Client/src/scala/org/broadinstitute/zamboni/client/lightning/clp/Lightning.scala
          - /Users/tbl/Broad/picard-private/src/java/edu/mit/broad/picard/lightning
          - /Users/tbl/Broad/gppipeline-devtools/release<sub>client</sub>
          - /Users/tbl/Broad/gppipeline-devtools/starter<sub>control</sub>
          - /picard02:/seq/pipeline/gppipeline-devtools/current/defs/prod.defs

#### Cruft
    
There are some versioning notes in
`gs://broad-pharma5-ukbb-outputs/README.txt` from early in the UK
Biobank project.

The **Structural Variation** project used *unreleased* WDL files
from the `/tbl/mw_sv/` branch of
[dsde-pipelines](https://github.com/broadinstitute/dsde-pipelines/tree/tbl/mw_sv).
