#!/usr/bin/env bash
# Monta o bundle de instalação (on-premise e AWS) a partir do build Maven:
#   deploy/bash/make-bundle.sh            -> target/hapm-bundle/ e target/hapm-bundle-<versão>.tar.gz
# Rode antes: mvn -q -DskipTests package   (na raiz do repositório)
set -o errexit -o nounset -o pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
OUT=$ROOT/target/hapm-bundle
VERSION=$(sed -n 's:.*<version>\(.*\)</version>.*:\1:p' "$ROOT/pom.xml" | sed -n 2p)

for f in hapm-agent/target/hapm-agent.jar hapm-manager/target/hapm-manager.jar; do
  [[ -f $ROOT/$f ]] || { echo "falta $f: rode 'mvn -q -DskipTests package' na raiz" >&2; exit 1; }
done
rm -rf "$OUT"
mkdir -p "$OUT"/{scripts,systemd,otel}
install -m 0755 "$ROOT"/deploy/bash/{lib.sh,install-node.sh,install-manager.sh} "$OUT"/
install -m 0644 "$ROOT"/hapm-agent/target/hapm-agent.jar "$ROOT"/hapm-manager/target/hapm-manager.jar "$OUT"/
install -m 0755 "$ROOT"/hapm-agent/src/main/dist/scripts/*.sh "$OUT"/scripts/
install -m 0644 "$ROOT"/hapm-agent/src/main/dist/systemd/hapm-agent.service "$OUT"/systemd/
install -m 0644 "$ROOT"/observability/otel-collector/node.yaml "$OUT"/otel/
echo "$VERSION" > "$OUT"/VERSION
(cd "$OUT" && sha256sum -- *.jar *.sh scripts/* systemd/* otel/* > SHA256SUMS)
tar -C "$ROOT/target" -czf "$ROOT/target/hapm-bundle-$VERSION.tar.gz" hapm-bundle
echo "bundle: $OUT"
echo "pacote: $ROOT/target/hapm-bundle-$VERSION.tar.gz"
