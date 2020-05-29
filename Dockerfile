FROM openjdk:11
LABEL maintainer="hornet@broadinsitute.org"
WORKDIR /wfl-api
COPY target/wfl-*.jar ./wfl-api.jar
EXPOSE 3000
CMD [ "java", "-jar", "wfl-api.jar", "server", "3000" ]
