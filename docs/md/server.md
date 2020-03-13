# WorkFlow Launcher Server

We now have the basics of WorkFlow Launcher
running as a server
in Google App Engine (GAE).

## Deploy to Google App Engine

To build and deploy WFL,
run `./ops/deploy.sh`.

It's Google Credentials page is here.

https://console.developers.google.com/apis/credentials?project=broad-gotc-dev

## WFL server features

The WFL server doesn't do much now.

- It can configure its secrets and deploy itself.
- It can authenticate to Google using OAuth2.
- It can serve authorized and unauthorized routes.

This is the application server's home URL.

https://zero-dot-broad-gotc-dev.appspot.com/

The following URIs work now.

 - Home ([/](https://zero-dot-broad-gotc-dev.appspot.com/)) :
   Home replies with `Authorized!` when authorized.
   Otherwise it redirects to the Status page.

 - Status ([/status](https://zero-dot-broad-gotc-dev.appspot.com/status)) :
   Status is an uauthorized endpoint that responds with "OK".

 - Version ([/version](https://zero-dot-broad-gotc-dev.appspot.com/version)) :
   Version is an uauthorized endpoint that responds
   with the version currently deployed.

 - OAuth Launch
   ([/auth/google](https://zero-dot-broad-gotc-dev.appspot.com/auth/google)) :
   Launch begins the OAuth2 call chain
   to authenticate using your Google credentials.

 - Environments
   ([/api/v1/environments](https://zero-dot-broad-gotc-dev.appspot.com/api/v1/environments)) :
   Environments returns WFL's environment tree as JSON when authorized.
   Environments redirects to Status when unauthorized.

## Starting WFL server for local development

Run `./ops/server.sh` from the command line.

There is a `wrap-reload-for-development-only` handler wrapper
commented out on the `app` defined in the `server.clj` file.
When it is compiled in,
source code changes that you make
will be reloaded into the running server.

As its name implies,
you should comment it out
before deploying WFL.
