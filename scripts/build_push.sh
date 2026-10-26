#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || "$1" == -* ]]; then
    echo "Usage: bash scripts/build_push.sh REGISTRY/OWNER/IMAGE:TAG" >&2
    exit 1
fi

image="$1"
if [[ "$image" != */* || "${image##*/}" != *:* || "${image##*:}" == latest || "$image" == *'@'* ]]; then
    echo "Specify a registry image with an explicit tag other than latest." >&2
    exit 1
fi

project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
docker build --tag "$image" "$project_dir"
docker push "$image"
echo "Published: $image"
