#!/bin/bash
# Compile-checks a native program (common.metal + FILE) for each variant given (space-separated defines per argument).
# usage: tools/native-check.sh core/entity "" "ALPHA_CUTOUT=0.1" "PER_FACE_LIGHTING" ...
set -u
cd "$(dirname "$0")/.."
[ -x build/msl-check ] || clang -fobjc-arc -framework Metal -framework Foundation tools/msl-check.m -o build/msl-check
f=$(mktemp -t native).metal
cat metal/src/main/resources/cadence/native/common.metal "metal/src/main/resources/cadence/native/$1.metal" > "$f"
shift
SLOTS="NATIVE_SLOT_Projection=0 NATIVE_SLOT_DynamicTransforms=1 NATIVE_SLOT_Lighting=2 NATIVE_SLOT_Fog=3 NATIVE_SLOT_Globals=4 NATIVE_SLOT_Sampler0=5 NATIVE_SLOT_Sampler1=6 NATIVE_SLOT_Sampler2=7 NATIVE_SLOT_GlintSampler=8 NATIVE_SLOT_DissolveMaskSampler=9 NATIVE_SLOT_InSampler=10 NATIVE_SLOT_SamplerInfo=11 NATIVE_SLOT_CloudInfo=12 NATIVE_SLOT_CloudFaces=13 NATIVE_SLOT_LightmapInfo=14 NATIVE_SLOT_BlitConfig=15"
fail=0
for v in "$@"; do
	out=$(build/msl-check "$f" $SLOTS $v 2>&1)
	if [ $? -eq 0 ]; then printf "ok    [%s]\n" "$v"; else printf "FAIL  [%s]\n%s\n" "$v" "$out"; fail=1; fi
done
rm -f "$f"
exit $fail
