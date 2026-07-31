#!/bin/sh

#
# Standard Gradle wrapper launcher script.
# The one piece this sandbox couldn't generate is gradle/wrapper/gradle-wrapper.jar
# (a compiled binary fetched from Gradle's servers, which needs network access).
# Android Studio will offer to download it automatically on first project sync,
# or run: gradle wrapper --gradle-version 8.7   (with any system Gradle install)
#

DEFAULT_JVM_OPTS='"-Xmx64m" "-Xms64m"'

APP_HOME=$(cd "$(dirname "$0")" && pwd -P) || exit
APP_BASE_NAME=$(basename "$0")

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD="$JAVA_HOME/jre/sh/java"
    else
        JAVACMD="$JAVA_HOME/bin/java"
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD="java"
    if ! command -v java >/dev/null 2>&1 ; then
        echo "ERROR: JAVA_HOME is not set and 'java' is not on PATH." >&2
        exit 1
    fi
fi

exec "$JAVACMD" $DEFAULT_JVM_OPTS $JAVA_OPTS $GRADLE_OPTS \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
