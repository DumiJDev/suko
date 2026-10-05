#!/usr/bin/env bash
# Corre o Gradle numa cópia do repositório no sistema de ficheiros do Linux.
# Porquê: um IDE do lado Windows compila para build/ dentro de /mnt/c e faz
# falhar builds aleatoriamente ("bad class file", "cannot find symbol").
#
# Uso:  scripts/verify-isolated.sh :suko-core:test --tests '*Foo*'
# Env:  SUKO_VERIFY_DIR  pasta da cópia (por omissão /tmp/suko-verify)
#       SUKO_PULL        caminhos (relativos à raiz) a copiar DE VOLTA depois do
#                        Gradle, separados por espaço — p.ex. os goldens
#                        regenerados com -Dsuko.updateGolden=true
set -euo pipefail
ROOT="$(git rev-parse --show-toplevel)"
V="${SUKO_VERIFY_DIR:-/tmp/suko-verify}"
mkdir -p "$V"
rsync -a --delete \
  --exclude='.git' --exclude='build' --exclude='/*/bin' --exclude='/bin' --exclude='.gradle' \
  --exclude='.superpowers' --exclude='.claude' --exclude='.kilo' \
  --exclude='node_modules' --exclude='/*/out' \
  "$ROOT/" "$V/"
status=0
( cd "$V" && ./gradlew --console=plain "$@" ) || status=$?
for p in ${SUKO_PULL:-}; do
  mkdir -p "$ROOT/$p"
  rsync -a --delete "$V/$p/" "$ROOT/$p/"
done
exit $status
