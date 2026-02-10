#!/bin/sh

set -e

rm -rf ../../bin
mkdir -p ../../bin

# Ensure gson is available
GSON_JAR="../../lib/gson-2.10.1.jar"
if [ ! -f "$GSON_JAR" ]; then
	echo "Gson not found at $GSON_JAR. Attempting to download gson-2.10.1.jar..."
	mkdir -p ../../lib
	if command -v curl >/dev/null 2>&1; then
		curl -L -o "$GSON_JAR" "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" || {
			echo "Failed to download gson. Please download gson-2.10.1.jar into ../../lib/ and re-run.";
			exit 1;
		}
	else
		echo "curl is not available. Please install curl or download gson-2.10.1.jar into ../../lib/ and re-run.";
		exit 1
	fi
fi

CLASSPATH="../../lib/cloudsim-7.0.0-alpha.jar:../../lib/commons-math3-3.6.1.jar:../../lib/colt.jar:$GSON_JAR"

javac -classpath "$CLASSPATH" -sourcepath ../../src ../../src/edu/boun/edgecloudsim/applications/sample_app1/MainApp.java -d ../../bin
