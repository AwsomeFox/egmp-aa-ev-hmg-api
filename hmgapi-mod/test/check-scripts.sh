#!/bin/sh
# The mod's scripts call only commands the head unit has (mksh and Android 4.4's
# toolbox: no sed, awk, cut, stat, wc ...), with HMG-API's own checker
# (tools/check-scripts.sh, the check the engine also runs at install).
#   sh hmgapi-mod/test/check-scripts.sh   (the SDK's checker; HMGAPI_SDK, or hmgapi-mod/sdk.sh)
set -e
cd "$(dirname "$0")/../.."
REPO=$PWD
[ -n "$HMGAPI_SDK" ] || eval "$(sh hmgapi-mod/sdk.sh)"
sh "$HMGAPI_SDK/bin/check-scripts.sh" "$REPO/hmgapi-mod/scripts/offload.sh"
