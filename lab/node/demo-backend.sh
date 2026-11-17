#!/bin/sh
# Backend de exemplo do lab: responde JSON com o nome do servidor (busybox httpd).
mkdir -p /srv
printf '{"backend":"%s"}\n' "${NAME:-app}" > /srv/index.html
echo ok > /srv/health
exec busybox httpd -f -p 8080 -h /srv
