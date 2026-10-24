import { readFileSync } from "node:fs";
import { parseEnv } from "node:util";

export function cloudflareAccountId(env, file, required = false) {
  let value = env.CLOUDFLARE_ACCOUNT_ID;
  if (value === undefined) {
    try { value = parseEnv(readFileSync(file, "utf8")).CLOUDFLARE_ACCOUNT_ID; }
    catch (error) { if (error.code !== "ENOENT") throw new Error("Unable to read local deployment configuration"); }
  }
  if (value === undefined && !required) return undefined;
  if (typeof value !== "string" || !/^[a-f0-9]{32}$/i.test(value)) {
    throw new Error("Set a valid CLOUDFLARE_ACCOUNT_ID in the environment or Git-ignored .env.deploy");
  }
  return value.toLowerCase();
}
