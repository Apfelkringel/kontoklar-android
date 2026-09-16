type Installation = {
  installation_hash: string;
  provider_user_id: string;
  created_at: string;
  provider_user_ready: number;
};

type ProviderToken = { access_token: string; expires_in: number };
type ProviderRow = Record<string, unknown>;

const encoder = new TextEncoder();
const TARGET_BANKS = [
  { key: "c24", search: "C24", label: "C24" },
  { key: "comdirect", search: "comdirect", label: "comdirect" },
  { key: "trade-republic", search: "Trade Republic", label: "Trade Republic" },
] as const;
const MAX_JSON_BYTES = 64 * 1024;
const TRANSACTIONS_PER_PAGE = 100;
const MAX_TRANSACTION_PAGES = 50;
const ACCESS_HOSTS = new Set(["sandbox.finapi.io", "live.finapi.io"]);
const WEB_FORM_HOSTS = new Set(["webform-sandbox.finapi.io", "webform-live.finapi.io"]);

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (request.method === "GET" && url.pathname === "/health") {
      return json({ status: "ok", provider: "finAPI", environment: new URL(env.FINAPI_ACCESS_BASE_URL).hostname }, 200);
    }

    if (!url.pathname.startsWith("/v1/")) return json({ error: "Route nicht gefunden." }, 404);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: securityHeaders() });

    try {
      const identity = await authenticate(request, env);
      await applyInstallationRateLimit(env, identity.hash);
      const accessToken = await userAccessToken(identity, env);
      const path = url.pathname;

      if (request.method === "GET" && path === "/v1/banks") {
        return json({ banks: await supportedBanks(accessToken, env) });
      }
      if (request.method === "GET" && path === "/v1/connections") {
        return json({ connections: await listConnections(accessToken, env) });
      }
      if (request.method === "POST" && path === "/v1/connections") {
        const body = await readJson(request);
        const bankId = typeof body.bankId === "string" ? body.bankId : "";
        if (!/^\d{1,12}$/.test(bankId)) throw new HttpError(400, "Ungültige Bankauswahl.");
        const banks = await supportedBanks(accessToken, env);
        const bank = banks.find((candidate) => candidate.id === bankId);
        if (!bank) throw new HttpError(400, "Diese Bank ist für den Anbieter nicht freigeschaltet.");
        const form = await providerJson(
          env,
          "webform",
          "/api/webForms/bankConnectionImport",
          accessToken,
          {
            bank: { id: Number(bankId) },
            bankConnectionName: `KontoKlar – ${bank.name}`,
            // KontoKlar currently imports payment-account transactions only. Requesting SECURITY
            // would add another provider workflow without any holdings UI or persistence.
            accountTypes: ["CHECKING"],
            maxDaysForDownload: 90,
            skipBalancesDownload: true,
            skipPositionsDownload: true,
            loadOwnerData: false,
          },
        );
        const authorizationUrl = requireWebFormUrl(form.url, env);
        const formId = nonEmptyString(form.id);
        if (!formId) throw new HttpError(502, "Der Bankanbieter hat keine gültige Freigabe-Sitzung erstellt.");
        return json({ id: formId, authorizationUrl }, 201);
      }

      const connectionRoute = path.match(/^\/v1\/connections\/([0-9]+)(?:\/(sync))?$/);
      if (connectionRoute) {
        const connectionId = connectionRoute[1];
        const isSync = connectionRoute[2] === "sync";
        const connection = (await listConnections(accessToken, env)).find((item) => item.id === connectionId);
        if (!connection) throw new HttpError(404, "Bankverbindung nicht gefunden.");

        if (request.method === "DELETE" && !isSync) {
          await providerFetch(env, "access", `/api/v2/bankConnections/${connectionId}`, accessToken, undefined, "DELETE");
          await env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?")
            .bind(identity.hash, connectionId).run();
          return new Response(null, { status: 204, headers: securityHeaders() });
        }
        if (request.method === "POST" && isSync) {
          const syncResult = await startOrContinueSync(request, identity, connectionId, accessToken, env);
          if (syncResult.authorizationUrl) return json(syncResult);
          if (syncResult.status !== "COMPLETED") return json(syncResult, 202);
          const snapshot = await connectionSnapshot(connection, accessToken, env);
          return json({ status: "COMPLETED", snapshot });
        }
        if (request.method === "GET" && !isSync) {
          return json(await connectionSnapshot(connection, accessToken, env));
        }
      }

      if (request.method === "DELETE" && path === "/v1/profile") {
        await providerFetch(env, "access", "/api/v2/bankConnections", accessToken, undefined, "DELETE");
        await providerFetch(env, "access", "/api/v2/users", accessToken, undefined, "DELETE");
        await env.DB.batch([
          env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ?").bind(identity.hash),
          env.DB.prepare("DELETE FROM installations WHERE installation_hash = ?").bind(identity.hash),
        ]);
        return new Response(null, { status: 204, headers: securityHeaders() });
      }

      return json({ error: "Route nicht gefunden." }, 404);
    } catch (error) {
      if (error instanceof HttpError) return json({ error: error.message }, error.status);
      console.error(JSON.stringify({ event: "open_banking_request_failed", path: url.pathname, method: request.method }));
      return json({ error: "Bankdienst momentan nicht verfügbar. Bitte später erneut versuchen." }, 502);
    }
  },
  async scheduled(_controller: ScheduledController, env: Env): Promise<void> {
    const currentWindow = Math.floor(Date.now() / 60_000);
    const staleSyncBefore = new Date(Date.now() - 30 * 60_000).toISOString();
    await env.DB.batch([
      env.DB.prepare("DELETE FROM request_limits WHERE window_id < ?").bind(currentWindow - 5),
      env.DB.prepare("DELETE FROM sync_tasks WHERE created_at < ?").bind(staleSyncBefore),
    ]);
  },
} satisfies ExportedHandler<Env>;

class HttpError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

async function authenticate(request: Request, env: Env): Promise<{ hash: string; userId: string; password: string; row: Installation }> {
  const authorization = request.headers.get("Authorization") ?? "";
  const token = authorization.startsWith("Bearer ") ? authorization.slice(7) : "";
  if (!/^[A-Za-z0-9_-]{43}$/.test(token)) throw new HttpError(401, "App-Schlüssel fehlt oder ist ungültig.");
  if (typeof env.INSTALLATION_PEPPER !== "string" || env.INSTALLATION_PEPPER.length < 32 || !env.FINAPI_CLIENT_ID || !env.FINAPI_CLIENT_SECRET) {
    throw new HttpError(503, "Open-Banking-Server ist noch nicht vollständig eingerichtet.");
  }

  const hash = await sha256(token);
  const userId = `kk_${hash.slice(0, 32)}`;
  const password = await hmac(env.INSTALLATION_PEPPER, `finapi-user:${token}`);
  const now = new Date().toISOString();
  const existing = await env.DB.prepare("SELECT installation_hash, provider_user_id, created_at, provider_user_ready FROM installations WHERE installation_hash = ?")
    .bind(hash).first<Installation>();
  // Admission control must happen before a new durable installation is written.
  if (!existing) await applyGlobalNewInstallationLimit(env);
  await applyIpRateLimit(request, env, !existing || existing.provider_user_ready === 0);
  if (!existing) {
    const installationLimit = configuredLimit(env.MAX_INSTALLATIONS, 1_000);
    await env.DB.prepare(
      "INSERT OR IGNORE INTO installations (installation_hash, provider_user_id, created_at, last_seen_at, provider_user_ready) SELECT ?, ?, ?, ?, 0 WHERE (SELECT COUNT(*) FROM installations) < ?",
    ).bind(hash, userId, now, now, installationLimit).run();
  }
  const row = await env.DB.prepare("SELECT installation_hash, provider_user_id, created_at, provider_user_ready FROM installations WHERE installation_hash = ?")
    .bind(hash).first<Installation>();
  if (!row) throw new HttpError(429, "Die maximale Anzahl an App-Installationen ist erreicht.");
  if (row.provider_user_id !== userId) throw new HttpError(503, "Bankdienst konnte das lokale Profil nicht laden.");

  await env.DB.prepare("UPDATE installations SET last_seen_at = ? WHERE installation_hash = ?").bind(now, hash).run();
  return { hash, userId, password, row };
}

async function applyIpRateLimit(request: Request, env: Env, newInstallation: boolean): Promise<void> {
  const window = Math.floor(Date.now() / 60_000);
  const ip = request.headers.get("CF-Connecting-IP") ?? "unknown";
  const ipKey = await hmac(env.INSTALLATION_PEPPER, `ip:${ip}`);
  const key = `ip:${ipKey}`;
  await env.DB.prepare(
    "INSERT INTO request_limits (bucket_key, window_id, hits) VALUES (?, ?, 1) ON CONFLICT(bucket_key) DO UPDATE SET window_id = excluded.window_id, hits = CASE WHEN request_limits.window_id = excluded.window_id THEN request_limits.hits + 1 ELSE 1 END",
  ).bind(key, window).run();
  const count = await env.DB.prepare("SELECT hits FROM request_limits WHERE bucket_key = ? AND window_id = ?")
    .bind(key, window).first<{ hits: number }>();
  const limit = newInstallation ? 5 : 60;
  if ((count?.hits ?? limit + 1) > limit) throw new HttpError(429, "Zu viele Anfragen. Bitte kurz warten.");
}

async function applyGlobalNewInstallationLimit(env: Env): Promise<void> {
  const window = Math.floor(Date.now() / 60_000);
  const key = "global:new-installations";
  await env.DB.prepare(
    "INSERT INTO request_limits (bucket_key, window_id, hits) VALUES (?, ?, 1) ON CONFLICT(bucket_key) DO UPDATE SET window_id = excluded.window_id, hits = CASE WHEN request_limits.window_id = excluded.window_id THEN request_limits.hits + 1 ELSE 1 END",
  ).bind(key, window).run();
  const count = await env.DB.prepare("SELECT hits FROM request_limits WHERE bucket_key = ? AND window_id = ?")
    .bind(key, window).first<{ hits: number }>();
  const limit = configuredLimit(env.MAX_NEW_INSTALLATIONS_PER_MINUTE, 20);
  if ((count?.hits ?? limit + 1) > limit) throw new HttpError(429, "Zu viele neue App-Installationen. Bitte später erneut versuchen.");
}

function configuredLimit(value: string | undefined, fallback: number): number {
  if (!value || !/^\d+$/.test(value)) return fallback;
  const parsed = Number(value);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback;
}

async function applyInstallationRateLimit(env: Env, installationHash: string): Promise<void> {
  const window = Math.floor(Date.now() / 60_000);
  const key = `installation:${installationHash}`;
  await env.DB.prepare(
    "INSERT INTO request_limits (bucket_key, window_id, hits) VALUES (?, ?, 1) ON CONFLICT(bucket_key) DO UPDATE SET window_id = excluded.window_id, hits = CASE WHEN request_limits.window_id = excluded.window_id THEN request_limits.hits + 1 ELSE 1 END",
  ).bind(key, window).run();
  const count = await env.DB.prepare("SELECT hits FROM request_limits WHERE bucket_key = ? AND window_id = ?")
    .bind(key, window).first<{ hits: number }>();
  if ((count?.hits ?? 31) > 30) throw new HttpError(429, "Zu viele Anfragen. Bitte kurz warten.");
}

async function clientAccessToken(env: Env): Promise<string> {
  const base = providerBaseUrl(env, "access");
  const form = new URLSearchParams({
    grant_type: "client_credentials",
    client_id: env.FINAPI_CLIENT_ID,
    client_secret: env.FINAPI_CLIENT_SECRET,
  });
  const response = await fetch(new URL("/api/v2/oauth/token", base), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: form,
    redirect: "error",
  });
  const token = await responseJson<ProviderToken>(response);
  return requiredString(token.access_token, "Provider-Client-Token");
}

async function userAccessToken(identity: { userId: string; password: string }, env: Env): Promise<string> {
  const ready = await env.DB.prepare("SELECT provider_user_ready FROM installations WHERE provider_user_id = ?")
    .bind(identity.userId).first<{ provider_user_ready: number }>();
  if (!ready?.provider_user_ready) {
    const clientToken = await clientAccessToken(env);
    const created = await providerFetch(env, "access", "/api/v2/users", clientToken, {
      id: identity.userId,
      password: identity.password,
      isAutoUpdateEnabled: false,
    }, "POST", {}, [409]);
    if (!created.ok && created.status !== 409) throw providerError(created.status);
    await env.DB.prepare("UPDATE installations SET provider_user_ready = 1 WHERE provider_user_id = ?").bind(identity.userId).run();
  }
  const base = providerBaseUrl(env, "access");
  const form = new URLSearchParams({
    grant_type: "password",
    client_id: env.FINAPI_CLIENT_ID,
    client_secret: env.FINAPI_CLIENT_SECRET,
    username: identity.userId,
    password: identity.password,
  });
  const response = await fetch(new URL("/api/v2/oauth/token", base), {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded", Accept: "application/json" },
    body: form,
    redirect: "error",
  });
  const token = await responseJson<ProviderToken>(response);
  return requiredString(token.access_token, "Provider-User-Token");
}

async function supportedBanks(token: string, env: Env): Promise<Array<{ id: string; name: string }>> {
  const output: Array<{ id: string; name: string }> = [];
  for (const target of TARGET_BANKS) {
    const query = new URLSearchParams({ search: target.search, page: "1", perPage: "100" });
    const data = await providerJson(env, "access", `/api/v2/banks?${query}`, token);
    const rows = collection(data, ["banks", "items"]);
    const bank = rows
      .filter((row) => bankHasAis(row))
      .map((row) => ({ row, name: nonEmptyString(row.name) || nonEmptyString(row.bankName) || "" }))
      .filter(({ name }) => name.toLowerCase().includes(target.search.toLowerCase()))
      .sort((a, b) => a.name.length - b.name.length)[0];
    if (!bank) continue;
    const id = nonEmptyString(bank.row.id);
    if (id && /^\d+$/.test(id)) output.push({ id, name: target.label });
  }
  return output;
}

async function listConnections(token: string, env: Env): Promise<Array<{ id: string; bankName: string; status: string }>> {
  const data = await providerJson(env, "access", "/api/v2/bankConnections", token);
  return collection(data, ["bankConnections", "connections", "items"]).flatMap((row) => {
    const id = nonEmptyString(row.id);
    if (!id || !/^\d+$/.test(id)) return [];
    const bankName = stringAt(row, ["bank", "name"]) || nonEmptyString(row.bankName) || nonEmptyString(row.name) || "Bankverbindung";
    const status = nonEmptyString(row.updateStatus) || nonEmptyString(row.status) || "UNBEKANNT";
    return [{ id, bankName, status }];
  });
}

async function connectionSnapshot(
  connection: { id: string; bankName: string; status: string },
  token: string,
  env: Env,
): Promise<ProviderRow> {
  // finAPI's Accounts API returns an AccountList (not a pageable list).
  const accountQuery = new URLSearchParams({ bankConnectionIds: connection.id });
  const accountData = await providerJson(env, "access", `/api/v2/accounts?${accountQuery}`, token);
  const accounts = collection(accountData, ["accounts", "items"]);
  const ibanByAccountId = new Map<string, string>();
  const currencyByAccountId = new Map<string, string>();
  const accountIds: string[] = [];
  for (const account of accounts) {
    const id = nonEmptyString(account.id);
    if (!id) continue;
    accountIds.push(id);
    const iban = nonEmptyString(account.iban) || nonEmptyString(account.accountIban) || "";
    ibanByAccountId.set(id, iban);
    currencyByAccountId.set(id, nonEmptyString(account.currency) || "");
  }

  const transactions: ProviderRow[] = [];
  let hasMoreTransactions = false;
  for (let page = 1; page <= MAX_TRANSACTION_PAGES && accountIds.length > 0; page++) {
    const query = new URLSearchParams({ view: "userView", accountIds: accountIds.join(","), page: String(page), perPage: String(TRANSACTIONS_PER_PAGE) });
    const data = await providerJson(env, "access", `/api/v2/transactions?${query}`, token);
    const rows = collection(data, ["transactions", "items"]);
    transactions.push(...rows);
    const paging = data.paging;
    if (paging && typeof paging === "object" && !Array.isArray(paging) && "pageCount" in paging) {
      const pageCount = (paging as ProviderRow).pageCount;
      if (typeof pageCount !== "number" || !Number.isSafeInteger(pageCount) || pageCount < page) {
        throw new HttpError(502, "Der Anbieter hat ungültige Seiteninformationen für die Umsätze geliefert.");
      }
      hasMoreTransactions = page < pageCount;
    } else {
      // Fallback for older provider responses without paging metadata.
      hasMoreTransactions = rows.length === TRANSACTIONS_PER_PAGE;
    }
    if (!hasMoreTransactions) break;
  }
  if (hasMoreTransactions) throw new HttpError(502, "Der Abruf der Umsätze hat das sichere Seitenlimit erreicht. Bitte erneut synchronisieren.");

  return {
    id: connection.id,
    bankName: connection.bankName,
    status: connection.status,
    transactions: transactions.flatMap((row) => normalizeTransaction(row, ibanByAccountId, currencyByAccountId)),
  };
}

function normalizeTransaction(row: ProviderRow, ibanByAccountId: Map<string, string>, currencyByAccountId: Map<string, string>): ProviderRow[] {
  const id = nonEmptyString(row.id) || nonEmptyString(row.transactionId);
  const accountId = nonEmptyString(row.accountId);
  const cents = typeof row.amount === "number" && Number.isFinite(row.amount) ? Math.round(row.amount * 100) : NaN;
  const dateValue = nonEmptyString(row.bankBookingDate) || nonEmptyString(row.finapiBookingDate) || nonEmptyString(row.bookingDate) || nonEmptyString(row.valueDate);
  const date = dateValue?.slice(0, 10) ?? "";
  if (!id || !accountId || !Number.isSafeInteger(cents) || cents === 0 || !/^\d{4}-\d{2}-\d{2}$/.test(date)) return [];
  const currency = nonEmptyString(row.currency) || currencyByAccountId.get(accountId) || "";
  if (currency !== "EUR") return [];
  return [{
    id: `${accountId}:${id}`,
    accountIban: ibanByAccountId.get(accountId) ?? "",
    date,
    counterparty: nonEmptyString(row.counterpartName) || "Unbekannter Zahlungspartner",
    description: nonEmptyString(row.purpose) || nonEmptyString(row.remittanceInformation) || "",
    amountCents: cents,
    reference: nonEmptyString(row.endToEndId) || nonEmptyString(row.reference) || "",
  }];
}

async function startOrContinueSync(
  request: Request,
  identity: { hash: string },
  connectionId: string,
  token: string,
  env: Env,
): Promise<{ status: string; authorizationUrl?: string }> {
  let pending = await env.DB.prepare("SELECT task_id, web_form_id, web_form_url, created_at FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?")
    .bind(identity.hash, connectionId).first<{ task_id: string; web_form_id: string | null; web_form_url: string | null; created_at: string }>();
  if (pending && Date.parse(pending.created_at) < Date.now() - 30 * 60_000) {
    await env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?")
      .bind(identity.hash, connectionId).run();
    pending = null;
  }
  if (pending && !pending.web_form_id) {
    const task = await providerJson(env, "webform", `/api/tasks/${encodeURIComponent(pending.task_id)}`, token);
    const status = nonEmptyString(task.status) || "IN_PROGRESS";
    if (status === "WEB_FORM_REQUIRED") {
      const rawUrl = nestedString(task, ["webFormUrl"]) || nestedString(task, ["url"]) || nestedString(task, ["webForm", "url"]);
      const formId = nestedString(task, ["webFormId"]) || nestedString(task, ["webForm", "id"]) || (rawUrl ? new URL(requireWebFormUrl(rawUrl, env)).pathname.split("/").filter(Boolean).at(-1) ?? null : null);
      const safeUrl = rawUrl ? requireWebFormUrl(rawUrl, env) : null;
      await env.DB.prepare("UPDATE sync_tasks SET web_form_id = ?, web_form_url = ? WHERE installation_hash = ? AND connection_id = ?")
        .bind(formId, safeUrl, identity.hash, connectionId).run();
      return { status, ...(safeUrl ? { authorizationUrl: safeUrl } : {}) };
    }
    if (["COMPLETED", "COMPLETED_WITH_ERROR"].includes(status)) {
      await env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?").bind(identity.hash, connectionId).run();
      if (status === "COMPLETED_WITH_ERROR") throw new HttpError(502, "Die Bank konnte die Umsätze nicht aktualisieren.");
      return { status: "COMPLETED" };
    }
    return { status };
  }
  if (pending?.web_form_id) {
    const form = await providerJson(env, "webform", `/api/webForms/${encodeURIComponent(pending.web_form_id)}`, token);
    const formStatus = nonEmptyString(form.status) ?? "IN_PROGRESS";
    if (formStatus === "COMPLETED") {
      await env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?").bind(identity.hash, connectionId).run();
      return { status: "COMPLETED" };
    }
    if (["COMPLETED_WITH_ERROR", "ABORTED", "EXPIRED"].includes(formStatus)) {
      await env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?").bind(identity.hash, connectionId).run();
      throw new HttpError(409, "Die Bankfreigabe wurde nicht abgeschlossen. Bitte starte die Synchronisierung erneut.");
    }
    if (formStatus === "NOT_YET_OPENED" && pending.web_form_url) {
      await env.DB.prepare("UPDATE sync_tasks SET web_form_url = NULL WHERE installation_hash = ? AND connection_id = ?")
        .bind(identity.hash, connectionId).run();
      return { status: "WEB_FORM_REQUIRED", authorizationUrl: requireWebFormUrl(pending.web_form_url, env) };
    }
    return { status: "WEB_FORM_REQUIRED" };
  }

  const metadata = await readJson(request).catch(() => ({} as Record<string, unknown>));
  const deviceOs = typeof metadata.deviceOs === "string" ? metadata.deviceOs.slice(0, 40) : "Android";
  const userAgent = request.headers.get("User-Agent")?.slice(0, 250) || "KontoKlar Android";
  const ipAddress = request.headers.get("CF-Connecting-IP");
  const task = await providerJson(env, "webform", "/api/tasks/backgroundUpdate", token, {
    bankConnectionIds: [Number(connectionId)],
    importNewAccountsMode: "CONDITIONAL",
  }, "POST", {
    ...(ipAddress ? { "PSU-IP-Address": ipAddress } : {}),
    "PSU-Device-OS": deviceOs,
    "PSU-User-Agent": userAgent,
  });
  const taskId = nonEmptyString(task.id) || nonEmptyString(task.taskId);
  if (!taskId) throw new HttpError(502, "Der Bankanbieter hat keinen Synchronisierungsauftrag zurückgegeben.");
  const directUrl = nestedString(task, ["webFormUrl"]) || nestedString(task, ["url"]) || nestedString(task, ["webForm", "url"]);
  const status = nonEmptyString(task.status) || "IN_PROGRESS";
  if (status === "WEB_FORM_REQUIRED") {
    const url = directUrl ? requireWebFormUrl(directUrl, env) : undefined;
    const formId = url ? new URL(url).pathname.split("/").filter(Boolean).at(-1) ?? null : null;
    await env.DB.prepare("INSERT OR REPLACE INTO sync_tasks (installation_hash, connection_id, task_id, web_form_id, web_form_url, created_at) VALUES (?, ?, ?, ?, ?, ?)")
      .bind(identity.hash, connectionId, taskId, formId, url ?? null, new Date().toISOString()).run();
    return { status, ...(url ? { authorizationUrl: url } : {}) };
  }
  await env.DB.prepare("INSERT OR REPLACE INTO sync_tasks (installation_hash, connection_id, task_id, web_form_id, web_form_url, created_at) VALUES (?, ?, ?, NULL, NULL, ?)")
    .bind(identity.hash, connectionId, taskId, new Date().toISOString()).run();
  if (["COMPLETED", "COMPLETED_WITH_ERROR"].includes(status)) {
    await env.DB.prepare("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?").bind(identity.hash, connectionId).run();
    if (status === "COMPLETED_WITH_ERROR") throw new HttpError(502, "Die Bank konnte die Umsätze nicht aktualisieren.");
    return { status: "COMPLETED" };
  }
  return { status };
}

async function providerJson(
  env: Env,
  api: "access" | "webform",
  path: string,
  token: string,
  body?: Record<string, unknown>,
  method = body ? "POST" : "GET",
  extraHeaders: Record<string, string> = {},
): Promise<ProviderRow> {
  const response = await providerFetch(env, api, path, token, body, method, extraHeaders);
  return responseJson<ProviderRow>(response);
}

async function providerFetch(
  env: Env,
  api: "access" | "webform",
  path: string,
  token: string,
  body?: Record<string, unknown>,
  method = body ? "POST" : "GET",
  extraHeaders: Record<string, string> = {},
  acceptedStatuses: number[] = [],
): Promise<Response> {
  const root = new URL(providerBaseUrl(env, api));
  if (!path.startsWith("/") || path.startsWith("//")) throw new HttpError(400, "Ungültiger Provider-Endpunkt.");
  const url = new URL(path, root);
  if (url.host !== root.host || url.protocol !== "https:") throw new HttpError(400, "Ungültiger Provider-Endpunkt.");
  const response = await fetch(url, {
    method,
    headers: {
      Accept: "application/json",
      Authorization: `Bearer ${token}`,
      ...extraHeaders,
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    ...(body ? { body: JSON.stringify(body) } : {}),
    redirect: "error",
  });
  if (!response.ok && !acceptedStatuses.includes(response.status)) throw providerError(response.status);
  return response;
}

function providerError(status: number): HttpError {
  if (status === 401 || status === 403) return new HttpError(502, "Der Anbieterzugang ist ungültig oder nicht freigeschaltet.");
  if (status === 404) return new HttpError(404, "Bankverbindung oder Konto wurde beim Anbieter nicht gefunden.");
  if (status === 409) return new HttpError(409, "Dieser Vorgang ist bereits vorhanden.");
  if (status === 429) return new HttpError(429, "Der Anbieter bittet um eine kurze Pause. Bitte später erneut versuchen.");
  if (status >= 500) return new HttpError(502, "Der Open-Banking-Anbieter ist vorübergehend nicht verfügbar.");
  return new HttpError(502, "Der Anbieter konnte die Anfrage nicht verarbeiten.");
}

async function responseJson<T>(response: Response): Promise<T> {
  const text = await readLimitedText(response.body, MAX_JSON_BYTES, "Die Antwort des Anbieters ist unerwartet groß.");
  let value: unknown;
  try {
    value = JSON.parse(text);
  } catch {
    throw new HttpError(502, "Der Anbieter hat keine gültige JSON-Antwort geliefert.");
  }
  if (!response.ok) throw providerError(response.status);
  if (!value || typeof value !== "object" || Array.isArray(value)) throw new HttpError(502, "Die Antwort des Anbieters hat ein ungültiges Format.");
  return value as T;
}

async function readJson(request: Request): Promise<Record<string, unknown>> {
  const length = Number(request.headers.get("Content-Length") ?? 0);
  if (length > MAX_JSON_BYTES) throw new HttpError(413, "Anfrage ist zu groß.");
  const raw = await readLimitedText(request.body, MAX_JSON_BYTES, "Anfrage ist zu groß.");
  if (!raw) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) throw new Error("invalid");
    return parsed as Record<string, unknown>;
  } catch {
    throw new HttpError(400, "Ungültige JSON-Anfrage.");
  }
}

async function readLimitedText(stream: ReadableStream<Uint8Array> | null, limit: number, message: string): Promise<string> {
  if (!stream) return "";
  const reader = stream.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > limit) {
        await reader.cancel();
        throw new HttpError(413, message);
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const bytes = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return new TextDecoder().decode(bytes);
}

function collection(data: ProviderRow, keys: string[]): ProviderRow[] {
  for (const key of keys) {
    const value = data[key];
    if (Array.isArray(value)) return value.filter(isRecord);
  }
  return [];
}

function isRecord(value: unknown): value is ProviderRow {
  return Boolean(value && typeof value === "object" && !Array.isArray(value));
}

function bankHasAis(bank: ProviderRow): boolean {
  const interfaces = Array.isArray(bank.bankInterfaces) ? bank.bankInterfaces.filter(isRecord) : [];
  if (interfaces.length === 0) return bank.isAisSupported !== false;
  return interfaces.some((item) => item.isAisSupported === true || (isRecord(item.aisCapabilities) && Object.values(item.aisCapabilities).some((capability) => capability === true)));
}

function stringAt(row: ProviderRow, path: string[]): string {
  let value: unknown = row;
  for (const segment of path) value = isRecord(value) ? value[segment] : undefined;
  return nonEmptyString(value) ?? "";
}

function nestedString(row: ProviderRow, path: string[]): string | null {
  const result = stringAt(row, path);
  return result || null;
}

function nonEmptyString(value: unknown): string | null {
  if (typeof value === "string" || typeof value === "number") {
    const text = String(value).trim();
    return text || null;
  }
  return null;
}

function requiredString(value: unknown, label: string): string {
  const result = nonEmptyString(value);
  if (!result) throw new HttpError(502, `${label} wurde nicht zurückgegeben.`);
  return result;
}

function requireWebFormUrl(value: unknown, env: Env): string {
  const raw = nonEmptyString(value);
  if (!raw) throw new HttpError(502, "Der Anbieter hat keine Freigabe-URL zurückgegeben.");
  try {
    const url = new URL(raw);
    const configuredHost = new URL(env.FINAPI_WEBFORM_BASE_URL).host;
    if (url.protocol !== "https:" || !WEB_FORM_HOSTS.has(url.host) || url.host !== configuredHost || !url.pathname.startsWith("/wf/")) throw new Error("bad-url");
    return url.toString();
  } catch {
    throw new HttpError(502, "Der Anbieter hat eine nicht erlaubte Freigabe-URL geliefert.");
  }
}

function providerBaseUrl(env: Env, api: "access" | "webform"): URL {
  const value = api === "access" ? env.FINAPI_ACCESS_BASE_URL : env.FINAPI_WEBFORM_BASE_URL;
  let base: URL;
  try {
    base = new URL(value);
  } catch {
    throw new HttpError(503, "Die finAPI-Serveradresse ist nicht freigegeben.");
  }
  const allowedHosts = api === "access" ? ACCESS_HOSTS : WEB_FORM_HOSTS;
  if (base.protocol !== "https:" || !allowedHosts.has(base.host) || base.pathname !== "/" || base.search || base.hash || base.username || base.password) {
    throw new HttpError(503, "Die finAPI-Serveradresse ist nicht freigegeben.");
  }
  return base;
}

async function sha256(value: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encoder.encode(value));
  return hex(new Uint8Array(digest));
}

async function hmac(secret: string, value: string): Promise<string> {
  const key = await crypto.subtle.importKey("raw", encoder.encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const signature = new Uint8Array(await crypto.subtle.sign("HMAC", key, encoder.encode(value)));
  return base64Url(signature);
}

function hex(bytes: Uint8Array): string {
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
}

function base64Url(bytes: Uint8Array): string {
  let binary = "";
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function json(body: unknown, status = 200): Response {
  return Response.json(body, { status, headers: securityHeaders() });
}

function securityHeaders(): HeadersInit {
  return {
    "Cache-Control": "no-store, private",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "Content-Security-Policy": "default-src 'none'; frame-ancestors 'none'",
  };
}
