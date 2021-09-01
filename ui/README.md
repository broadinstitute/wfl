# Workflow Launcher Auth Proxy

This directory only hosts the container manifest of 
Apache auth proxy of WFL, and we have removed the
frontend component of WFL. This is a interim solution
until we finish building the next gen of WFL UI client.

For context, please refer to
this [Pull Request](https://github.com/broadinstitute/wfl/pull/499).
But in general, we keep this proxy even if it's not serving any
frontend code, in order to make sure no unauthenticated
external requests can reach to WFL's API endpoints on the K8S cluster.
In the future, a new UI client will be built and served from this proxy
container.
