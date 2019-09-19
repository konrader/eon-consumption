FROM openjdk:12-alpine
MAINTAINER Konrad Eriksson <konrad@konraderiksson.com>

ADD target/eon-consumption-*.jar /eon-consumption.jar

ENTRYPOINT ["java", "-jar", "/eon-consumption.jar"]
