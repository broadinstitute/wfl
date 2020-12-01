# WorkFlow Launcher

[![Build Board](https://img.shields.io/badge/-The%20Build%20Boad%20(VPN)-blue)](https://internal.broadinstitute.org/~chengche/green-hornet-status.html)
![Docsite Build](https://github.com/broadinstitute/wfl/workflows/Publish%20docs%20via%20GitHub%20Pages/badge.svg?branch=main)
![Build on Main Branch](https://github.com/broadinstitute/wfl/workflows/Tests%20on%20Pull%20Requests%20and%20Main/badge.svg?branch=main)
[![GitHub tag (latest by date)](https://img.shields.io/github/v/tag/broadinstitute/wfl?label=Latest%20Release)](https://github.com/broadinstitute/wfl/blob/main/CHANGELOG.md)


**For a complete introduction to WorkFlow Launcher, please visit its [documentation website](https://broadinstitute.github.io/wfl)!**

## Overview

[WorkFlow Launcher (WFL)](https://github.com/broadinstitute/wfl.git)
is a workload manager.

For example, a workload could be a set of Whole Genome samples to be reprocessed in a
given project/bucket, the workflow is the processing of an individual sample
in that workload running WGS reprocessing; a workload could also be a queue of
incoming notifications that describe all of the required inputs to launch Arrays
scientific pipelines in Cromwell.

