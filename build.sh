#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
find src/main/java -name '*.java' | sort > build/main-sources.txt
javac -encoding UTF-8 --release 17 -d build/classes @build/main-sources.txt
echo '빌드 완료: Java 17 이상에서 실행할 수 있습니다.'
