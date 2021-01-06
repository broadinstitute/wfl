# WFL Configuration

There are a set of environments variables WFL reads from
the system as configurations. Those variables are used
across the WFL code base, this page keeps track of them.

## Variables

### `COOKIE_SECRET`

Set by user, required by WFL deployment, the value is store in Vault. It's used to create a cookie store engine
by WFL's default middleware.

### `CROMWELL`

Can be set by user, a string of Cromwell URL such as `https://cromwell-gotc-auth.gotc-prod.broadinstitute.org/`.
If defined, WFL system tests will use this Cromwell URL to override the default ones from the code.

**Note:** this is currently turned off for system tests for `Arrays` module as its `cromwell` field
actually expects a Terra url which is inconsistent with other module tests.

### `GOOGLE_APPLICATION_CREDENTIALS`

Path to a Service Account file, can be set by user. If provided, will dominate other variables and WFL
will use the Service Account file defined by this variable to mint tokens and authentication headers in
all HTTP requests send to:

- Cromwell
- Data Repo
- Google Cloud Storage API
- A deployed WFL instance (in testing code)

### `USER`

Set by the OS, used by WFL:

- To compose and write the `user` value of version information. Fallback to WFL's name if not defined.
- As the username when running **local** liquibase migrations.

### `WFL_DEPLOY_ENVIRONMENT`

Can be set by the user. It's supposed to be a string representing a valid environment entry of WFL's
`environments.clj`. WFL uses this variable to:

- Determine which server to use for testing. If present, the test suite's client will send testing requests to
`"https://dev-wfl.gotc-dev.broadinstitute.org"`, otherwise it will use `"http://localhost:3000"` instead.
- Try to mint the auth token (and then auth headers) in all HTTP requests send to:
    - Cromwell
    - Data Repo
    - Google Cloud Storage API
    - A deployed WFL instance (in testing code)

only if `GOOGLE_APPLICATION_CREDENTIALS` is not present. If both of them are not defined, it further falls back
to `"debug"`, if a wrong value is provided, WFL errors out.
- To authenticate with Google whenever OAuth2 is needed and `WFL_OAUTH2_CLIENT_ID` is not defined.

### `WFL_OAUTH2_CLIENT_ID`

Can be set by user, used by WFL (frontend and Swagger) to authenticate with Google whenever OAuth2 is needed.

### `WFL_VERSION`

Set by the `version` file in the project root, can be overwritten by user as environment variable. Used by WFL:

- To compose and write the `version` value of version information. Fallback to `"devel"` if not defined.
- To name the target JAR of WFL.
- To tag the docker images of WFL.
