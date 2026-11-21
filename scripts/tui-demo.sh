#!/usr/bin/env bash
# Runs the real TUI on a real terminal over a made-up listing, with no Incus and no isx setup,
# for looking at a change to how it looks or moves (design/visual-language.md).
#
#   scripts/tui-demo.sh            a few templates and instances
#   scripts/tui-demo.sh empty      no instance yet
#
# What it reads from the environment, as isx itself does:
#   ISX_THEME=light|dark           instead of asking the terminal
#   ISX_COLORS=truecolor|256|16    instead of detecting the colour depth
#   ISX_MOTION=off                 nothing moves
set -euo pipefail
cd "$(dirname "$0")/.."
if [ "${TUI_DEMO_SKIP_BUILD:-}" != 1 ]; then
    mvn -q test-compile dependency:build-classpath -pl cli -am \
        -Dmdep.outputFile=target/tui-demo.classpath -Dmdep.includeScope=test >&2
fi
exec java --enable-native-access=ALL-UNNAMED \
    -cp "cli/target/classes:cli/target/test-classes:common/target/classes:common/target/test-classes:$(cat cli/target/tui-demo.classpath)" \
    dev.incusspawn.tui.TuiDemo "$@"
