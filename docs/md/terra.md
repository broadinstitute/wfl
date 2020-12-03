# WorkFlow Launcher's Role in Terra

## Summary

The Data Sciences Platform (DSP) is building a new system (around
[Terra](https://terra.bio/)) for storing and processing biological
data. The system design includes a Data Repository where data is
stored, and a Methods Repository that executably describes
transformations on that data.

In the new system, the DSP needs something to fulfill the role that
Zamboni currently plays in DSP's current infrastructure to support
the Genomics Platform (GP).

Zamboni watches various queues for messages describing new data and
how to process it. Zamboni interprets those messages to dispatch
work to workflow engines (running on the premises or in the cloud)
and monitors the progress of those workflows. The Zamboni web UI
allows users to track the progress of workflows, and enables Ops
engineers to debug problems and resume or restart failed workflows.
Zamboni can run workflows on both a local Sun Grid Engine (SGE), and
on Cromwell on premises and in the cloud.

We think that WFL can fill the role of Zamboni in the new data
storage and processing system that DSP is developing now.

## History

WFL began as a project to replace a Zamboni *starter*, with the old
name "Zero". A *starter* is a Zamboni component that brokers messages
among the queues that Zamboni watches. It interprets messages
queued from a Laboratory Information Management System (LIMS),
such as the Mercury web service, and demultiplexes them to other
Zamboni queues.

Zero was later adapted to manage the reprocessing of the first batch
of UK Biobank exomes. It has since been adapted to drive workflows
for other projects at the Broad.

Zero is unusual in that it usually runs as a stateless command line
program without special system privilege, and interfaces with
services running both on premises and in Google Cloud. It also
manages a processing workload as a set of inputs mapped to outputs
instead of tracking the progress of individual sample workflows.

A Zero user need only specify a source of inputs, a workflow to run,
an execution environment, and an output location. Then each time it
is invoked, Zero ensures that workflows are started and retried as
needed until an output exists for every input.

Zero has recently been adapted again to deploy as a web service
under Google App Engine (GAE) though most of the value of Zero is
still not available to the server. And now it has the new name WFL.

## The role of WFL in Terra

Diagrams of the new DSP processing system show a WFL service
subscribed to event streams from the Data Repository (DR), with
interfaces to both the Data and the Method Repositories.

The implication is that something notifies WFL of new data in the
Data Repository and WFL determines how to process it somehow. WFL
then looks up whatever is required from the Method Repository, calls
on other services as necessary to process the data and writes the
results back to the DR. There is also, presumably, a web UI to track
and debug the workflows managed by WFL.

Many details are yet to be worked out.

## WFL Concepts

WFL is designed around several novel concepts.

1.  Manage workloads instead of workflows.

    This is the biggest difference between Zamboni and Zero (WFL).
    Zamboni manages workflows whereas WFL manages **workloads**.

    Zamboni's unit of work is the *workflow*. Zamboni manages each
    workflow separately.

    A *workflow* is a transformation specified in WDL or Scala code
    that succeeds or fails to produce a result. The input to a
    workflow and its result may consist of multiple files, but they
    represent a single unit of work managed by a workflow engine
    such as Cromwell. Zamboni prepares a new workflow for each
    message it receives by packaging up the input and submitting it
    to a workflow engine. It then monitors that workflow and reports
    on its success or failure.

    WFL manages a *workload*, which indirectly comprises multiple
    workflows. Each workflow maps an input to some output, but WFL
    generally tracks only the inputs and outputs instead of the
    workflows themselves.

    Think of a workload as a set of inputs transformed via a
    workflow engine into a set of outputs. Call that set of outputs
    the *result set*. WFL generally does not care whether any
    individual workflow succeeds or fails. It merely considers all
    possible inputs specified by the workload, and looks for inputs
    whose outputs are missing from the result set. If some input
    lacks an output in the result set, WFL starts a new workflow to
    process that input.

    Note: This characterization is unfair to Zamboni. Zamboni also
    had to manage multiple workflows before the advent of Cromwell
    and still does when running workflows on SGE. But WFL can take
    advantage of Cromwell's job management to simplify its
    implementation.

2.  Specify inputs and outputs by general predicates.

    Each Zamboni message explicitly specifies an input to be
    processed. Zamboni then starts a workflow for that input and
    reports its status. Zamboni reports failure so a user can debug
    and manually *succeed*, reconsider, or restart the workflow. The
    output of a successful workflow is not Zamboni's concern.

    WFL finds inputs by applying a predicate specified by the user
    subject to some run-time constraint. Then WFL applies a
    function to each input to find how that input maps to the result
    set. Another predicate applied to the input, and its output in
    the result set, determines whether WFL will launch a workflow
    on that input.

    Those predicates and function can be anything expressed in a
    programming language. The run-time constraint is some strings
    passed on the command line.

3.  Minimize user input and decisions at run time.

    WFL gathers the predicates and mapping functions described
    above into a *module* that also knows how to generate everything
    a workflow engine needs to launch the workflow to process an
    input into a result output.

    That module name is one of a few run-time constraints specified
    by strings in a web form or on a command line. Further
    constraints are usually one or two of the following:

        - a processing environment (`dev` `prod` `pharma5`),
        - a file system directory (`/seq/tng/tbl/`
        `file://home/tbl/`),
        - a cloud object prefix (`gs://bucket/folder/`
        `s3://bucket/`),
        - a pathname suffix (`.cram` `.vcf`),
        - a spreadsheet (or JSON, TSV, CSV, XML) filename
        - or a count to limit the scope of a predicate.

    The module interprets the other constraints, determines which
    processing environments are allowed, and parses any files named
    accordingly.

4.  Maintain provenance.

    WFL runs out of a single JARfile built entirely from sources
    pulled from Git repositories. WFL records the Git commit hashes
    in the JARfile and adds them to every Cromwell workflow it
    starts. WFL can also preserve the Cromwell metadata alongside
    any result files generated by the workflow.

5.  Run with minimal privilege.

    Zamboni runs as a service with system account credentials such
    as `picard`.

    WFL is designed to run as whoever invokes it, such as
    `tbl@broadinstitute.org`. WFL fetches the users credentials
    from the environment when invoked from the command line. WFL
    requires authentication when running as a server, and constructs
    a JSON Web Token (JWT) to authorize other services as needed.

6.  Limit dependencies.

    WFL depends on a Java runtime,
    and Gnu make
    and the Clojure CLI
    to manage dependencies.
    Of course, it also pulls in numerous Clojure and Java
    libraries at build time, and sources WDL files from the
    `warp` repository.
    A programmer need only install Clojure,
    clone the `wfl` Git repository,
    and run `make`
    to bootstrap WFL from source.

## WFL server

The WFL client is a command-line batch program that a person runs
intermittently on a laptop or virtual machine (VM).

We are working to port the client functions of WFL to a ubiquitous
web service (WFL server) running in Google Cloud. That port
requires we solve several problems.

1.  State

    The WFL client is a stateless program that relies on consistent
    command line arguments to provide the constraints needed to
    drive the input discovery predicates and so on. Each user runs a
    separate process that lasts only as long as necessary to
    complete some stage of a workload.

    The WFL server is shared among all its users and runs
    continually. Therefore it requires some kind of data store (a
    database) to maintain the state of each workload across
    successive connections from web browsers.

    We intend to use the hosted Postgres service available to GAE
    applications for this. This work is already underway (GH-573).

2.  Authorization

    The WFL client assumes it runs in an authenticated context. It
    can pull credentials from the environment on every invocation
    that requires authorization to a service.

    The WFL server will also need to authorize services to run as
    some authenticated user, but cannot assume the credentials are
    always available, nor that there is a user present to provide
    them. WFL can already use OAuth2.0 to authenticate users
    against an identity provider and use the resulting credentials
    to build a JWT. It can also derive the bearer token required by
    most of our authorized services from a JWT.

    But WFL also needs some secure JWT store, so tokens are
    available to authorize services even when there is no active
    user connection. It also needs some mechanism to refresh tokens
    as they expire to support long-running workloads.

3.  Workload specification

    The user of a WFL service needs some way to specify a workload.
    A workload may be some set of inputs and the kind of workflow to
    run on them.

    A WFL client user now specifies a workload with a *module* name
    and a *constraint*. For example, `ukb pharma5 110000
    gs://broad-ukb/in/ gs://broad-ukb/out/` means find up to
    `110000` cloud objects with names prefixed with
    `gs://broad-ukb/in/`, process them in the Cromwell set up for
    `pharma5`, and store their outputs under `gs://broad-ukb/out/`
    somewhere.

    The `ukb` module knows how to find `.aligned.cram` files under
    the `gs://broad-ukb/in/` cloud path and set up the WDL and
    Cromwell dependencies and options necessary to reprocess them
    into `.cram` output files. The `ukb` module also knows how to
    find the Cromwell deployed to support `pharma5` workloads, how
    to authorize the user to that Cromwell, and how to read any
    supporting data from other services. And finally, the `ukb`
    module knows how to determine which inputs do not yet have
    outputs under the `gs://broad-ukb/out/` cloud path, and do not
    have workflows running in the `pharma5` Cromwell.

    In an ideal design, this workload specification would integrate
    conveniently with the Data Repository's subscription or
    *eventing* service. In any case though, WFL needs some
    interface through which a user can specify what needs to be
    done.

4.  Workload management

    Workloads need to be started, stopped, and monitored somehow.
    This implies that there is some way to find active or suspended
    workloads, and affordances for acting on them.

    Users need some way to monitor the progress of a workload, and
    to find and debug workloads encountering unacceptable workflow
    failures.

    Monitoring and diagnostic code already exists in various WFL
    modules, but there is no easy way to use them from a web
    browser.

5.  Service interface

    WFL should be useful to programs other than web browsers. It is
    easy to imagine Terra users wanting to query WFL for the status
    of workloads directly without buggy and tedious screen scraping.

    WFL should at least export a query endpoint for use by other
    reporting services as well as its own browser interface. It
    would be nice to provide a familiar JSON or GraphQL query syntax
    to other services.

6.  Browser interface

    A browser interface should require little in addition to WFL's
    service interface. Ideally, one should be able to adapt WFL to
    new workloads via a browser interface without requiring a
    redeployment.
