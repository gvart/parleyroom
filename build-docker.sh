#!/usr/bin/env bash
set -euo pipefail

IMAGE_NAME="${IMAGE_NAME:-parley-room}"
IMAGE_TAG="${IMAGE_TAG:-dev}"

cd "$(dirname "$0")"

echo "==> Building executable jar with Amper"
./amper task :parley-room:executableJarJvm

echo "==> Building Docker image ${IMAGE_NAME}:${IMAGE_TAG}"
docker build -t "${IMAGE_NAME}:${IMAGE_TAG}" .

echo "==> Done: ${IMAGE_NAME}:${IMAGE_TAG}"