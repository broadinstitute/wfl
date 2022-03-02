# WorkFlow Launcher

[![Build Board](https://img.shields.io/badge/-The%20Build%20Board%20(VPN)-blue)](https://internal.broadinstitute.org/~chengche/green-hornet-status.html)
![Docsite Build](https://github.com/broadinstitute/wfl/workflows/Publish%20docs%20via%20GitHub%20Pages/badge.svg?branch=main)
![Build on Main Branch](https://github.com/broadinstitute/wfl/workflows/Tests%20on%20Pull%20Requests%20and%20Main/badge.svg?branch=main)
[![GitHub tag (latest by date)](https://img.shields.io/github/v/tag/broadinstitute/wfl?label=Latest%20Release)](https://github.com/broadinstitute/wfl/blob/main/CHANGELOG.md)
[![WFL Nightly Test](https://github.com/broadinstitute/wfl/actions/workflows/nightly.yml/badge.svg?event=schedule)](https://github.com/broadinstitute/wfl/actions/workflows/nightly.yml)


**For a complete introduction to WorkFlow Launcher, please visit its [documentation website](https://broadinstitute.github.io/wfl)!**

## Overview

[WorkFlow Launcher (WFL)](https://github.com/broadinstitute/wfl.git)
is a workload manager.

For example, a workload could be a set of Whole Genome samples to be reprocessed in a
given project/bucket, the workflow is the processing of an individual sample
in that workload running WGS reprocessing; a workload could also be a queue of
incoming notifications that describe all of the required inputs to launch Arrays
scientific pipelines in Cromwell.

## GitHub Secrets from Vault

When we need to access a Vault secret within GitHub Actions
(ex. within integration test runs), we should propagate it to a
[Github Secret](https://github.com/broadinstitute/wfl/settings/secrets/actions)
managed by Atlantis -- DSP's Terraform deployment server.
The GitHub Secret should then be passed to the action
as an environment variable rather than Vault being accessed
directly, an operation which could risk leaking secrets publicly.

To view or maintain WFL's Atlantis-managed Github Secrets, see
[terraform-ap-deployments](https://github.com/broadinstitute/terraform-ap-deployments/blob/master/github/tfvars/broadinstitute-wfl.tfvars)
repository.

More Information: ["Moving Vault secrets to Github via Atlantis"](https://docs.google.com/document/d/1JbjV4xjAlSOuZY-2bInatl4av3M-y_LmHQkLYyISYns/edit?usp=sharing)

Questions: [#dsp-devops-champions](https://broadinstitute.slack.com/archives/CADM7MZ35)
