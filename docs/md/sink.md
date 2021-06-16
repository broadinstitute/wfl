# Sink
The workload `Sink` models the terminal stage of a processing pipeline. In a 
typical workload configuration, a `Sink` can be used to write workflow outputs
to a desired location in the cloud.

## User Guide
You can configure the type of `Sink` used in your workload by changing the
`sink` attribute of your workload request.

### Terra Workspace Sink
You can write workflow outputs to a Terra Workspace using the `Terra Workspace`
sink. A typical `Terra Workspace` sink configuration in the workload request
looks like:
```json
{
  "name": "Terra Workspace",
  "workspace": "{workspace-namespace}/{workspace-name}",
  "entityType": "{entity-type-name}",
  "identifier": "{output-name}",
  "fromOutputs": {
    "attribute0": "output0",
    "attribute1": ["output1", "output2"],
    "attribute3": "$literal",
    ...
  }
}
``` 
The table below summarises the purpose of each attribute in the above request.

| Attribute     | Description                                                  |
|---------------|--------------------------------------------------------------|
| `name`        | Selects the `Terra Workspace` sink implementation.           |
| `workspace`   | The Terra Workspace to write pipeline outputs to.            |
| `entityType`  | The entity type in the `workspace` to write outputs to.      |
| `identifier`  | Selects the output that will be used as the entity name.     |
| `fromOutputs` | Mapping from outputs to attribute names in the `entityType`. |

#### `workspace`
The workspace is a `"{workspace-namespace}/{workspace-name}"` string as it
appears in the URL path in the Terra UI. The workspace must exist prior to
workload creation. You must ensure that `workflow-launcher@firecloud.org` is
a workspace "Writer" in order to write entities to the workspace.

#### `entityType`
The `entityType` is the name of the entity type in the workspace that entities
will be created as. The entity type must exist prior to workload creation and
must be a table in the workspace.

#### `identifier`
The `identifier` is the name of a pipeline output that should be used as the 
name of each newly created entity. 

Example - Let's say the pipeline you're running has an output called
"sample_name" that uniquely identifies the inputs and outputs to that pipeline.
By setting `"identifier": "sample_name"` in the sink configuration, entities
will be created using the "sample_name" as the entity name.

!!! note
    When two sets of pipeline outputs share the same "identifier" value,
    the first set of outputs will be overwritten by the second in the workspace.

#### `fromOutputs`
`fromOutputs` configures how to create new entities from pipeline outputs by 
mapping the output names to attributes in the `entityType`. Note that all 
attribute names must exist in the entityType before the workload  creation.

`fromOutputs` allows a small amount of flexibility in how to construct an entity
and supports the following relations:

- `"attribute": "output"`
  Direct mapping from an output to an attribute

- `"attribute": ["output0", "output2"]`
  Make an attribute from a list of pipeline outputs.

- `"attribute": "$value"`
  Use the literal "value" for an attribute.

## Developer Guide
A sink is one satisfying the `Sink` protocol as below:
```clojure
(defprotocol Sink
  (update-sink!
    ^Sink
    [^Queue upstream ;; The queue to sink outputs from
     ^Sink  sink     ;; This sink instance
    ]
    "Update the internal state of the `sink`, consuming objects from the
     upstream `Queue`, performing any external effects as required.
     Implementations should avoid maintaining in-memory state and making long-
     running external calls, favouring internal queues to manage such tasks
     asynchronously between invocations. Note that The `Sink` and `Queue` are
     parameterised types and the `Queue`'s parameterisation must be convertible
     to the `Sink`s."))
```

!!! note
    The `Sink` protocol is implemented by the `update-sink!` multimethod. It's 
    documented thus as a means of differentiating the in-memory data model from
    the metadata a user sees.

To be used in a workload, a `Sink` implementation should satisfy `Stage`, the 
`to-edn` multimethod and the following multimethods specific to `Sink`s:
```clojure
(defmulti create-sink
  "Create a `Sink` instance using the database `transaction` and configuration
   in the sink `request` and return a `[type items]` pair to be written to a
   workload record as `sink_type` and `sink_items`.
   Notes:
   - This is a factory method registered for workload creation.
   - The `Sink` type string must match a value of the `sink` enum in the
     database schema.   
   - This multimethod is type-dispatched on the `:name` association in the
     `request`."
  (fn ^[^String ^String] 
      [^Connection         transaction  ;; JDBC Connection
       ^long               workload-id  ;; ID of the workload being created
       ^IPersistentHashMap request      ;; Data forwarded to the handler
      ]
      (:name request)))

(defmulti load-sink!
  "Return the `Sink` implementation associated with the `sink_type` and
   `sink_items` fields of the `workload` row in the database. Note that this
   multimethod is type-dispatched on the `:sink_type` association in the
   `workload`."
  (fn ^Sink
      [^Connection         transaction  ;; JDBC Connection
       ^IPersistentHashMap workload     ;; Row from workload table
      ]
      (:sink_type workload)))
```

