#!/usr/bin/env bash
#
# Mirror all images required by the ECS Fargate task into ECR.
#
#   - app      : built from the repo's multi-stage Dockerfile
#   - percona  : custom build (bakes in mysql/conf.d/myrocks.cnf)
#   - postgres / cockroach / cassandra / scylla : mirrored from Docker Hub
#
# Run from the repository root:
#   bash ecs/mirror-images.sh
#
# Requires: aws CLI (with ECR permissions) and docker, logged in to the target account.
set -euo pipefail

ACCOUNT="${AWS_ACCOUNT_ID:-074213351472}"
REGION="${AWS_REGION:-us-east-1}"
REGISTRY="${ACCOUNT}.dkr.ecr.${REGION}.amazonaws.com"

# Build the app image with the legacy builder so old buildx versions are not required.
export DOCKER_BUILDKIT="${DOCKER_BUILDKIT:-0}"

# repo  -> source image (empty = built locally below)
declare -A SOURCE=(
  ["b-treevslsm-tree-app"]=""
  ["bench/percona"]=""
  ["bench/postgres"]="postgres:16-alpine"
  ["bench/cockroach"]="cockroachdb/cockroach:v23.2.4"
  ["bench/cassandra"]="cassandra:4.1"
  ["bench/scylla"]="scylladb/scylla:5.4"
)
# repo -> destination tag
declare -A DEST_TAG=(
  ["b-treevslsm-tree-app"]="latest"
  ["bench/percona"]="8.0"
  ["bench/postgres"]="16-alpine"
  ["bench/cockroach"]="v23.2.4"
  ["bench/cassandra"]="4.1"
  ["bench/scylla"]="5.4"
)

echo "==> ECR login (${REGISTRY})"
aws ecr get-login-password --region "${REGION}" \
  | docker login --username AWS --password-stdin "${REGISTRY}"

echo "==> Ensure ECR repositories exist"
for repo in "${!DEST_TAG[@]}"; do
  aws ecr describe-repositories --region "${REGION}" --repository-names "${repo}" >/dev/null 2>&1 \
    || aws ecr create-repository --region "${REGION}" --repository-name "${repo}" >/dev/null
  echo "    - ${repo}"
done

# 1. App image (multi-stage Dockerfile at repo root)
echo "==> Build & push app image"
docker build -t "${REGISTRY}/b-treevslsm-tree-app:latest" -f Dockerfile .
docker push "${REGISTRY}/b-treevslsm-tree-app:latest"

# 2. Custom Percona image (bakes in myrocks.cnf)
echo "==> Build & push custom Percona image"
docker build -t "${REGISTRY}/bench/percona:8.0" -f ecs/percona/Dockerfile .
docker push "${REGISTRY}/bench/percona:8.0"

# 3. Mirror DB images from Docker Hub
for repo in bench/postgres bench/cockroach bench/cassandra bench/scylla; do
  src="${SOURCE[$repo]}"
  dst="${REGISTRY}/${repo}:${DEST_TAG[$repo]}"
  echo "==> Mirror ${src}  ->  ${dst}"
  docker pull "${src}"
  docker tag  "${src}" "${dst}"
  docker push "${dst}"
done

echo "==> Done. Images in ECR:"
for repo in "${!DEST_TAG[@]}"; do
  echo "    ${REGISTRY}/${repo}:${DEST_TAG[$repo]}"
done
