import { config } from './config.js';
import { buildApp } from './app.js';

const app = await buildApp(config);
try {
  await app.listen({ port: config.port, host: config.host });
  // primeira checagem de renovação logo após subir
  setTimeout(() => app.hapm.certs.renewDue(), 30_000).unref();
} catch (e) {
  app.log.error(e);
  process.exit(1);
}
for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, async () => {
    await app.close();
    process.exit(0);
  });
}
