import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { cloudflareAccountId } from "./cloudflare-account.mjs";

const root = fileURLToPath(new URL(".", import.meta.url));
const accountId = cloudflareAccountId(process.env, new URL(".env.deploy", import.meta.url), true);
const result = spawnSync(process.execPath, [fileURLToPath(new URL("node_modules/wrangler/bin/wrangler.js", import.meta.url)), "deploy", ...process.argv.slice(2)], {
  cwd: root, env: { ...process.env, CLOUDFLARE_ACCOUNT_ID: accountId }, stdio: "inherit",
});
if (result.error) throw result.error;
process.exitCode = result.status ?? 1;
