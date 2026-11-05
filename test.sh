#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
sh build.sh
mkdir -p build/test-classes
find src/test/java -name '*.java' | sort > build/test-sources.txt
javac -encoding UTF-8 --release 17 -cp build/classes -d build/test-classes @build/test-sources.txt
java -Djava.awt.headless=true -cp build/classes:build/test-classes chatapp.FrontendChecks
