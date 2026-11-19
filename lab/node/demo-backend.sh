#!/bin/sh
# Backend de exemplo do lab: responde JSON com o nome do servidor (busybox httpd).
# "/" é servido por CGI com Cache-Control: no-store, para o navegador não reaproveitar a resposta
# de outro servidor (os arquivos estáticos teriam o mesmo ETag em app1/app2) nos testes de drain/failover.
mkdir -p /srv/cgi-bin
cat > /srv/cgi-bin/index.cgi <<EOF
#!/bin/sh
printf 'Content-Type: application/json\r\nCache-Control: no-store\r\n\r\n'
printf '{"backend":"%s"}\n' "${NAME:-app}"
EOF
chmod 755 /srv/cgi-bin/index.cgi
echo ok > /srv/health
exec busybox httpd -f -p 8080 -h /srv
