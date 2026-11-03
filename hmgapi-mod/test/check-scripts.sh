#!/bin/sh
# The mod's scripts call only commands the head unit has (mksh and Android 4.4's
# toolbox: no sed, awk, cut, stat, wc ...), with HMG-API's own checker
# (tools/check-scripts.sh, the check the engine also runs at install).
#   HMGAPI=<HMG-API checkout> sh hmgapi-mod/test/check-scripts.sh
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
HMGAPI=${HMGAPI:-$REPO/../HMG-API}
sh "$HMGAPI/tools/check-scripts.sh" "$REPO/hmgapi-mod/scripts/offload.sh"
