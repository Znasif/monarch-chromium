#!/bin/sh

##############################################################################
# Gradle start up script for POSIX generated for Monarch Tactile Browser
##############################################################################

APP_HOME=$( cd "${APP_DIR:-./}" > /dev/null && pwd -P ) || exit
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar

# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    if [ -x "$JAVA_HOME/jre/sh/java" ] ; then
        JAVACMD=$JAVA_HOME/jre/sh/java
    else
        JAVACMD=$JAVA_HOME/bin/java
    fi
    if [ ! -x "$JAVACMD" ] ; then
        echo "ERROR: JAVA_HOME is set to an invalid directory: $JAVA_HOME" >&2
        exit 1
    fi
else
    JAVACMD=java
    which java >/dev/null 2>&1 || {
        echo "ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH." >&2
        exit 1
    }
fi

PROXY_OPTS=""
PROXY_URL="${HTTPS_PROXY:-${https_proxy:-${HTTP_PROXY:-${http_proxy:-}}}}"
if [ -n "$PROXY_URL" ]; then
    PROXY_HOST_PORT=$(echo "$PROXY_URL" | sed -e 's|^[^/]*//||' -e 's|/.*$||')
    PROXY_HOST=$(echo "$PROXY_HOST_PORT" | cut -d: -f1)
    PROXY_PORT=$(echo "$PROXY_HOST_PORT" | cut -d: -f2)
    if [ -n "$PROXY_HOST" ] && [ -n "$PROXY_PORT" ] && [ "$PROXY_HOST" != "$PROXY_PORT" ]; then
        PROXY_OPTS="-Dhttp.proxyHost=$PROXY_HOST -Dhttp.proxyPort=$PROXY_PORT -Dhttps.proxyHost=$PROXY_HOST -Dhttps.proxyPort=$PROXY_PORT"
    fi
fi

exec "$JAVACMD" $PROXY_OPTS $JAVA_OPTS $GRADLE_OPTS -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
