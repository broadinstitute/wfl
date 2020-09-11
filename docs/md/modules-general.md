# Modules Design Principles and Assumptions

WorkFlow Launcher is responsible for preparing the required
workflow WDLs, inputs and options for Cromwell in a large scale.
This work involves in inputs validation, pipeline WDL orchestration
and Cromwell workflow management. Similar to other WFL modules, the
`aou-arrays` module takes advantage of the `workload` concept in order
to manage workflows efficiently.

![](./assets/workload.png)

In general, WFL classify all workloads into 2 categories: continuous and fixed.
For instance, `aou-arrays` module implements arrays workload as a continuous
workload, which means all samples are coming in like a continuous stream,
and WFL does not make any assumption of how many samples will be in the workload
or how to group the samples together: it hands off the workload creation and
starting process to its caller. `wgs` module implements External Whole Genome
workloads as a discrete workload that WFL has full knowledge about the number
and properties of the samples it's going to process, and the samples can be grouped
into batches (workloads) by a set of properties.

To learn more about the details of each module, please check their own sections in
this documentation.
