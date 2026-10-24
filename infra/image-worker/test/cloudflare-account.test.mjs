import test from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { cloudflareAccountId } from "../cloudflare-account.mjs";

const account = "a".repeat(32), other = "b".repeat(32);
function fixture(run) {
  const dir = mkdtempSync(join(tmpdir(), "pebble-account-test-"));
  try { run(join(dir, ".env.deploy")); } finally { rmSync(dir, { recursive: true, force: true }); }
}
test("explicit environment overrides the local account and normalizes case", () => fixture(file => {
  writeFileSync(file, `CLOUDFLARE_ACCOUNT_ID=${other}\n`);
  assert.equal(cloudflareAccountId({ CLOUDFLARE_ACCOUNT_ID: account.toUpperCase() }, file, true), account);
}));
test("reads only the deployment account without modifying the process environment", () => fixture(file => {
  const env = {};
  writeFileSync(file, `# local only\nCLOUDFLARE_ACCOUNT_ID="${account}"\nUNRELATED_SECRET=fixture-only\n`);
  assert.equal(cloudflareAccountId(env, file, true), account);
  assert.deepEqual(env, {});
}));
test("offline builds may omit an account but deployments fail before choosing one", () => fixture(file => {
  assert.equal(cloudflareAccountId({}, file), undefined);
  assert.throws(() => cloudflareAccountId({}, file, true), /CLOUDFLARE_ACCOUNT_ID/);
}));
test("invalid explicit accounts never fall back and never appear in errors", () => fixture(file => {
  writeFileSync(file, `CLOUDFLARE_ACCOUNT_ID=${account}\n`);
  for (const value of ["", "not-an-account-id", "a".repeat(31), `${account}\n`, "g".repeat(32)]) {
    assert.throws(() => cloudflareAccountId({ CLOUDFLARE_ACCOUNT_ID: value }, file, true), error => {
      assert.match(error.message, /Set a valid CLOUDFLARE_ACCOUNT_ID/);
      if (value) assert.equal(error.message.includes(value), false);
      return true;
    });
  }
}));
