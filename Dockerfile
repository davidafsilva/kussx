FROM openjdk:8-jre-alpine
MAINTAINER David Silva <david@davidafsilva.pt>
ADD build/distributions/kussx.tar /opt/
EXPOSE 8080
WORKDIR /opt/kussx
ENTRYPOINT ["bin/kussx"]
