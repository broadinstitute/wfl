FROM clojure:openjdk-11-tools-deps AS build
WORKDIR /tmp

# Note this step requires docker builds in the root repo context
# e.g. `docker build -t wfl-api:test -f ./api/Dockerfile .`
ADD . .
RUN make api TARGET=build

# Use a Broad AppSec blessed image for security compliance
# https://github.com/broadinstitute/dsp-appsec-blessed-images
FROM us.gcr.io/broad-dsp-gcr-public/base/jre:11-distroless
LABEL maintainer="hornet@broadinsitute.org"
WORKDIR /wfl-api
COPY --from=build /tmp/derived/api/target/wfl-*.jar ./wfl-api.jar
EXPOSE 3000
CMD [ "wfl-api.jar", "server", "3000" ]
