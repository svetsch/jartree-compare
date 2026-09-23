#!/bin/sh
# Builds a native application with jpackage (JDK 17+).
#
#   packaging/package.sh                 -> target/dist/jartree-compare (app image with launchers)
#   packaging/package.sh dmg             -> installer of that type (msi/exe need WiX, deb/rpm need their tools)
#   packaging/package.sh app-image 12g   -> same, with a 12 GB maximum heap (default 4g)
#
# Run "mvn package" first: the shaded jar contains the JavaFX binaries of this platform.
set -e
cd "$(dirname "$0")/.."

TYPE="${1:-app-image}"
HEAP="${2:-${JARTREE_XMX:-4g}}"
JAR=target/jartree-compare.jar
[ -f "$JAR" ] || { echo "$JAR is missing; run 'mvn package' first" >&2; exit 1; }

# jpackage only accepts numeric versions, and copies the whole --input directory
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' pom.xml | head -1 | sed 's/-SNAPSHOT//')
INPUT=target/jpackage-input
rm -rf "$INPUT" target/dist
mkdir -p "$INPUT" target/dist
cp "$JAR" "$INPUT/"

case "$(uname -s)" in
    Darwin) ICON=packaging/jartree-compare.icns ;;
    *)      ICON=packaging/jartree-compare.png ;;
esac
[ -f "$ICON" ] || ICON=

jpackage \
    --type "$TYPE" \
    --name jartree-compare \
    --app-version "$VERSION" \
    --description "Compares two hierarchies of jar files and shows the decompiled code changes" \
    --vendor "jartree-compare" \
    --copyright "MIT License" \
    --input "$INPUT" \
    --main-jar "$(basename "$JAR")" \
    --main-class io.jartree.Main \
    --java-options "-Xmx$HEAP" \
    --dest target/dist \
    ${ICON:+--icon "$ICON"}

echo "created in target/dist with a maximum heap of $HEAP"
