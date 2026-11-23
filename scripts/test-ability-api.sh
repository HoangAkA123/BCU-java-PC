#!/usr/bin/env bash
set -euo pipefail
repo=$(cd -- "$(dirname -- "$0")/.." && pwd)
jar=${1:-"$repo/build/libs/BCU-Animated-Background.jar"}
test_dir=$(mktemp -d)
trap 'rm -rf -- "$test_dir"' EXIT
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -source 8 -target 8 -encoding UTF-8 -cp "$jar" -d "$test_dir" "$repo"/tests/ability-api/*.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp "$jar:$test_dir" CombatApiSmoke
mkdir "$test_dir/plugins"
cp "$repo/examples/anchor/assets/ability/Anchor.java" "$repo/examples/anchor/assets/ability/Anchor.png" "$test_dir/plugins/"
# Compile the actual documentation example. Reuse an icon only as a loader test fixture.
awk '/^```java$/ { block++; next } /^```$/ { next } block == 3 { print }' "$repo/docs/ability-api.md" > "$test_dir/example-block.txt"
awk '/^import common.battle.extension.BattleExtension;/ { capture=1 } capture { print } /^}$/ && capture { exit }' "$test_dir/example-block.txt" > "$test_dir/plugins/SecondWind.java"
cp "$test_dir/plugins/Anchor.png" "$test_dir/plugins/SecondWind.png"
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -Djava.awt.headless=true -cp "$jar:$test_dir" PluginCompatibilitySmoke "$test_dir/plugins"
