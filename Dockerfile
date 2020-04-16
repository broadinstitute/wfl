FROM openjdk:11
LABEL maintainer="hornet@broadinsitute.org"
WORKDIR /wfl_api
COPY target/wfl-*.jar ./wfl_api.jar
EXPOSE 3000
CMD [ "java", "-jar", "wfl_api.jar", "server", "3000" ]
