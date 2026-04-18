#!/bin/sh
#
# Gradle wrapper script for Unix/Mac/Linux
#

APP_NAME="Gradle"
APP_BASE_NAME=$(basename "$0")
DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

APP_HOME=$(dirname "$0")
cd "$APP_HOME" || exit

exec "$JAVACMD" "$DEFAULT_JVM_OPTS" $JAVA_OPTS $GRADLE_OPTS \
    "-Dorg.gradle.appname=$APP_BASE_NAME" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain "$@"
