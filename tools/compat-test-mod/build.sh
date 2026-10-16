#!/bin/bash
# Builds a tiny test mod (tools/compat-test-mod/build/compat-test.jar) whose mixin hooks a method Cadence overwrites
# (ModelPart.Cube.compile): the check that Cadence's compatibility layers step aside for it. Not shipped.
cd "$(dirname "$0")"; ROOT="$(cd ../.. && pwd)"
JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}"
rm -rf build && mkdir -p build/classes
"$JAVA_HOME/bin/javac" -proc:none -d build/classes -cp "$(cat "$ROOT/metal/build/cadence-run/classpath.txt")" $(find src -name '*.java') || exit 1
cp fabric.mod.json compat_test.mixins.json build/classes/
(cd build/classes && "$JAVA_HOME/bin/jar" cf ../compat-test.jar .)
echo "built $(pwd)/build/compat-test.jar"
