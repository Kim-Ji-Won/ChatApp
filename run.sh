#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
sh build.sh
exec java -cp build/classes chatapp.client.ClientMain
