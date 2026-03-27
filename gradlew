#!/bin/sh

##############################################################################
# Gradle start up script for POSIX
##############################################################################

APP_HOME="`dirname "$0"`"
APP_HOME="`cd "$APP_HOME" && pwd -P`"
CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

JAVACMD="java"
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
fi

exec "$JAVACMD" -Xmx64m -Xms64m $JAVA_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
