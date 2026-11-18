#!/usr/bin/env bash
# Sobe o lab do HAProxy Manager do zero: build Maven -> imagens -> manager -> cluster/objetos via API -> nós -> publicação.
set -euo pipefail
cd "$(dirname "$0")"
PASS="${HAPM_ADMIN_PASSWORD:-admin12345}"
API=http://127.0.0.1:8080/api
j() { python3 -c "import sys,json;print(json.load(sys.stdin)$1)"; }

if [ ! -f ../haproxy-manager-server/target/haproxy-manager-server.jar ] || [ ! -f ../haproxy-manager-agent/target/haproxy-manager-agent.jar ] || [ "${REBUILD:-0}" = 1 ]; then
  echo "==> build Maven (manager, agente e UI)"
  (cd .. && mvn -q -DskipTests package)
fi

echo "==> construindo imagens"
HAPM_JOIN_TOKEN=pendente docker compose build

echo "==> subindo o manager"
HAPM_JOIN_TOKEN=pendente docker compose up -d manager
until curl -fs http://127.0.0.1:8080/actuator/health >/dev/null; do sleep 1; done

TOKEN=$(curl -fs -XPOST $API/auth/login -H 'content-type: application/json' -d "{\"username\":\"admin\",\"password\":\"$PASS\"}" | j "['token']")
H=(-H "authorization: Bearer $TOKEN" -H 'content-type: application/json')

CID=$(curl -fs "${H[@]}" $API/clusters | python3 -c "import sys,json;print(next((c['id'] for c in json.load(sys.stdin) if c['name']=='lab'),''))")
if [ -z "$CID" ]; then
  echo "==> criando cluster 'lab' (on-prem keepalived, VIP 172.30.0.100)"
  CID=$(curl -fs -XPOST "${H[@]}" $API/clusters -d '{
    "name":"lab",
    "settings":{"mode":"onprem-keepalived",
      "haproxy":{"maxconn":4096,"log":"stdout format raw local0","user":"haproxy"},
      "keepalived":{"vips":["172.30.0.100/24"],"interface":"eth0","routerId":51},
      "acme":{"backend":"172.30.0.5:8080"},
      "logs":{"enabled":true}}}' | j "['id']")
  curl -fs -XPOST "${H[@]}" $API/clusters/$CID/backends -d '{"name":"app","servers":[{"name":"app1","address":"172.30.0.21","port":8080},{"name":"app2","address":"172.30.0.22","port":8080}],"healthCheck":{"type":"http","path":"/health"}}' >/dev/null
  curl -fs -XPOST "${H[@]}" $API/clusters/$CID/backends -d '{"name":"api","balance":"leastconn","servers":[{"name":"api1","address":"172.30.0.23","port":8080}],"healthCheck":{"type":"http","path":"/health"}}' >/dev/null
  curl -fs -XPOST "${H[@]}" $API/clusters/$CID/frontends -d '{"name":"web","binds":[{"address":"*","port":80}],"defaultBackend":"app",
    "rules":[{"description":"API","conditions":[{"type":"path_beg","value":["/api"]}],"action":"use_backend","backend":"api"},
             {"description":"admin só da rede interna","conditions":[{"type":"path_beg","value":["/admin"]},{"type":"src","value":["10.0.0.0/8"],"negate":true}],"action":"deny","status":403}]}' >/dev/null
fi
JOIN=$(curl -fs "${H[@]}" $API/clusters/$CID | j "['joinToken']")
echo "HAPM_JOIN_TOKEN=$JOIN" > .env

echo "==> subindo nós, backends e observabilidade"
docker compose up -d

echo "==> aguardando os nós registrarem"
until [ "$(curl -fs "${H[@]}" $API/clusters/$CID/nodes | python3 -c 'import sys,json;print(sum(n["online"] for n in json.load(sys.stdin)))')" = 2 ]; do sleep 2; done

echo "==> publicando"
curl -fs -XPOST "${H[@]}" $API/clusters/$CID/deployments -d '{"note":"lab inicial"}' | j "['version']"

cat <<MSG

Pronto!
  Manager:    http://localhost:8080   (admin / $PASS)
  VIP:        curl http://172.30.0.100/   e   curl http://172.30.0.100/api/
  Grafana:    http://localhost:3000   (admin / ${GRAFANA_PASSWORD:-admin}) -> pasta HAProxy Manager
  Prometheus: http://localhost:9090

Teste de failover:  docker compose stop lb1   (o VIP migra para lb2 em ~3s)
MSG
