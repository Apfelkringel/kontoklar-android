import { randomBytes } from "node:crypto";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import process from "node:process";

const workerName = "kontoklar-open-banking-api";
const checkOnly = process.argv.includes("--check-only");

function runWrangler(args, { quiet = false } = {}) {
  const result = spawnSync("npx", ["wrangler", ...args], {
    cwd: process.cwd(),
    encoding: "utf8",
    stdio: quiet ? "pipe" : "inherit",
  });
  if (result.error) throw result.error;
  return result;
}

function requireFirstDeployment() {
  const result = runWrangler(["deployments", "list", "--name", workerName], { quiet: true });
  if (result.status === 0) {
    throw new Error("Der Worker existiert bereits. Dieser Erst-Deploy ist gesperrt, damit der INSTALLATION_PEPPER nicht versehentlich rotiert wird.");
  }
  const output = `${result.stdout ?? ""}\n${result.stderr ?? ""}`;
  if (!output.includes("This Worker does not exist on your account")) {
    process.stderr.write(output);
    throw new Error("Worker-Status konnte nicht sicher festgestellt werden; Abbruch ohne Änderungen.");
  }
}

function readHidden(label) {
  if (!process.stdin.isTTY || typeof process.stdin.setRawMode !== "function") {
    throw new Error("Bitte in einem interaktiven Terminal starten; Zugangsdaten werden nicht aus Pipe oder Umgebungsvariablen gelesen.");
  }
  return new Promise((resolve, reject) => {
    const stdin = process.stdin;
    let value = "";
    const wasRaw = stdin.isRaw;
    process.stdout.write(label);
    stdin.setRawMode(true);
    stdin.resume();
    const finish = (error) => {
      stdin.off("data", onData);
      stdin.setRawMode(wasRaw ?? false);
      stdin.pause();
      process.stdout.write("\n");
      if (error) reject(error);
      else resolve(value);
    };
    const onData = (buffer) => {
      for (const char of buffer.toString("utf8")) {
        if (char === "\u0003") return finish(new Error("Abgebrochen."));
        if (char === "\r" || char === "\n") return finish();
        if (char === "\u007f" || char === "\b") value = value.slice(0, -1);
        else if (char >= " " && char !== "\u007f") value += char;
      }
    };
    stdin.on("data", onData);
  });
}

async function main() {
  requireFirstDeployment();
  const db = runWrangler(["d1", "info", "kontoklar-banking-identities", "--json"], { quiet: true });
  if (db.status !== 0) throw new Error("Die konfigurierte D1-Datenbank ist nicht erreichbar; zuerst `npm run check` ausführen.");
  const dryRun = runWrangler(["deploy", "--dry-run"], { quiet: true });
  if (dryRun.status !== 0) {
    process.stderr.write(`${dryRun.stdout ?? ""}${dryRun.stderr ?? ""}`);
    throw new Error("Wrangler-Dry-Run fehlgeschlagen; Abbruch ohne Deployment.");
  }
  if (checkOnly) {
    process.stdout.write("Erst-Deploy-Prüfungen erfolgreich. Worker nicht verändert.\n");
    return;
  }

  const clientId = await readHidden("finAPI Sandbox Client ID (Eingabe verborgen): ");
  const clientSecret = await readHidden("finAPI Sandbox Client Secret (Eingabe verborgen): ");
  if (!clientId.trim() || !clientSecret.trim() || /[\r\n]/.test(clientId + clientSecret)) {
    throw new Error("Client-ID und Secret müssen vorhanden sein und dürfen keine Zeilenumbrüche enthalten.");
  }
  const pepper = randomBytes(48).toString("base64url");
  process.stdout.write(`\nNeuen INSTALLATION_PEPPER sicher im Passwortmanager speichern:\n${pepper}\n`);
  const confirmation = await readHidden("Pepper gespeichert? Zum Deploy exakt JA eingeben (Eingabe verborgen): ");
  if (confirmation !== "JA") throw new Error("Nicht bestätigt; Deployment abgebrochen.");

  const secretDir = await mkdtemp(path.join(tmpdir(), "kontoklar-first-deploy-"));
  const secretFile = path.join(secretDir, "secrets.txt");
  try {
    const contents = [
      `FINAPI_CLIENT_ID=${clientId}`,
      `FINAPI_CLIENT_SECRET=${clientSecret}`,
      `INSTALLATION_PEPPER=${pepper}`,
      "",
    ].join("\n");
    await writeFile(secretFile, contents, { encoding: "utf8", mode: 0o600, flag: "wx" });
    process.stdout.write("Starte den ersten Sandbox-Deploy. Die Secrets werden nicht ausgegeben.\n");
    const deploy = runWrangler(["deploy", "--secrets-file", secretFile]);
    if (deploy.status !== 0) throw new Error("Wrangler-Deployment fehlgeschlagen.");
  } finally {
    await rm(secretDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  process.stderr.write(`Erst-Deploy abgebrochen: ${error.message}\n`);
  process.exitCode = 1;
});
