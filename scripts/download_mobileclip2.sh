#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ASSETS="$ROOT/app/src/main/assets"
mkdir -p "$ASSETS"
MODEL="$ASSETS/mobileclip2_s2_vision.onnx"
URL="https://huggingface.co/plhery/mobileclip2-onnx/resolve/main/onnx/s2/vision_model.onnx?download=true"
if [[ ! -s "$MODEL" ]]; then
  echo "Downloading MobileCLIP2-S2 vision model (~136 MB)..."
  curl -fL --retry 3 "$URL" -o "$MODEL.tmp"
  mv "$MODEL.tmp" "$MODEL"
fi
if [[ $(stat -c%s "$MODEL") -lt 100000000 ]]; then
  echo "Model file is unexpectedly small; download may be incomplete." >&2
  exit 1
fi
echo "MobileCLIP2-S2 ready: $MODEL"
