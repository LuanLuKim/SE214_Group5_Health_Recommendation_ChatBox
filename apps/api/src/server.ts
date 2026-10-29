import { createApp } from './app.js';
import { loadConfig } from './config.js';

const config = loadConfig();
const app = createApp(config);
const server = app.listen(config.PORT, config.HOST, () => {
  console.info(`API: http://${config.HOST}:${config.PORT} (scaffold; private features disabled)`);
});
server.on('error', () => {
  console.error('API could not start. Check the host and port.');
  process.exitCode = 1;
});
for (const signal of ['SIGINT', 'SIGTERM'] as const) {
  process.on(signal, () => {
    server.close(() => {
      void Promise.resolve(app.locals.pool?.end()).finally(() => process.exit(0));
    });
  });
}
