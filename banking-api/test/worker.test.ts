import assert from "node:assert/strict";
import { test } from "node:test";
import worker from "../src/index.ts";

class MemoryStatement {
  private values: unknown[] = [];
  private readonly db: MemoryD1;
  private readonly sql: string;
  constructor(db: MemoryD1, sql: string) { this.db = db; this.sql = sql; }
  bind(...values: unknown[]) { this.values = values; return this; }
  async run() {
    const [a, b, c, d] = this.values;
    if (this.sql.startsWith("INSERT OR IGNORE INTO installations")) {
      if (this.db.installations.size >= Number(this.values[4])) return { success: true };
      if (!this.db.installations.has(String(a))) this.db.installations.set(String(a), { installation_hash: String(a), provider_user_id: String(b), created_at: String(c), last_seen_at: String(d), provider_user_ready: 0 });
    } else if (this.sql.startsWith("UPDATE installations SET last_seen_at")) {
      const row = this.db.installations.get(String(b));
      if (row) row.last_seen_at = String(a);
    } else if (this.sql.startsWith("UPDATE installations SET provider_user_ready")) {
      const row = [...this.db.installations.values()].find((candidate) => candidate.provider_user_id === String(a));
      if (row) row.provider_user_ready = 1;
    } else if (this.sql.startsWith("INSERT INTO request_limits")) {
      const row = this.db.limits.get(String(a));
      this.db.limits.set(String(a), { window_id: Number(b), hits: row?.window_id === Number(b) ? row.hits + 1 : 1 });
    } else if (this.sql.startsWith("INSERT OR REPLACE INTO sync_tasks")) {
      this.db.syncTasks.set(`${String(a)}:${String(b)}`, {
        installation_hash: String(a), connection_id: String(b), task_id: String(c),
        web_form_id: this.values[3] == null ? null : String(this.values[3]),
        web_form_url: this.values[4] == null ? null : String(this.values[4]),
        created_at: String(this.values[5]),
      });
    } else if (this.sql.startsWith("UPDATE sync_tasks SET web_form_id")) {
      const key = `${String(c)}:${String(d)}`;
      const row = this.db.syncTasks.get(key);
      if (row) {
        row.web_form_id = a == null ? null : String(a);
        row.web_form_url = b == null ? null : String(b);
      }
    } else if (this.sql.startsWith("UPDATE sync_tasks SET web_form_url = NULL")) {
      const row = this.db.syncTasks.get(`${String(a)}:${String(b)}`);
      if (row) row.web_form_url = null;
    } else if (this.sql.startsWith("DELETE FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?")) {
      this.db.syncTasks.delete(`${String(a)}:${String(b)}`);
    } else if (this.sql.startsWith("DELETE FROM sync_tasks WHERE installation_hash = ?")) {
      for (const [key, row] of this.db.syncTasks) if (row.installation_hash === String(a)) this.db.syncTasks.delete(key);
    } else if (this.sql.startsWith("DELETE FROM installations WHERE installation_hash = ?")) {
      this.db.installations.delete(String(a));
    }
    return { success: true };
  }
  async first<T>() {
    const [a, b] = this.values;
    if (this.sql.includes("FROM installations WHERE installation_hash")) return (this.db.installations.get(String(a)) ?? null) as T | null;
    if (this.sql.includes("FROM installations WHERE provider_user_id")) {
      return ([...this.db.installations.values()].find((row) => row.provider_user_id === String(a)) ?? null) as T | null;
    }
    if (this.sql.includes("FROM request_limits")) {
      const row = this.db.limits.get(String(a));
      return (row?.window_id === Number(b) ? { hits: row.hits } : null) as T | null;
    }
    if (this.sql.includes("FROM sync_tasks WHERE installation_hash = ? AND connection_id = ?")) {
      return (this.db.syncTasks.get(`${String(a)}:${String(b)}`) ?? null) as T | null;
    }
    return null;
  }
}

class MemoryD1 {
  installations = new Map<string, { installation_hash: string; provider_user_id: string; created_at: string; last_seen_at: string; provider_user_ready: number }>();
  limits = new Map<string, { window_id: number; hits: number }>();
  syncTasks = new Map<string, { installation_hash: string; connection_id: string; task_id: string; web_form_id: string | null; web_form_url: string | null; created_at: string }>();
  prepare(sql: string) { return new MemoryStatement(this, sql); }
  async batch(statements: MemoryStatement[]) {
    for (const statement of statements) await statement.run();
    return [];
  }
}

const baseEnv = () => ({
  DB: new MemoryD1(),
  FINAPI_ACCESS_BASE_URL: "https://sandbox.finapi.io",
  FINAPI_WEBFORM_BASE_URL: "https://webform-sandbox.finapi.io",
  FINAPI_CLIENT_ID: "sandbox-client",
  FINAPI_CLIENT_SECRET: "sandbox-secret",
  INSTALLATION_PEPPER: "a-test-only-pepper-with-more-than-thirty-two-characters",
  MAX_INSTALLATIONS: "1000",
  MAX_NEW_INSTALLATIONS_PER_MINUTE: "20",
});

test("requires an installation bearer token and answers health without provider credentials", async () => {
  const env = baseEnv();
  const health = await worker.fetch(new Request("https://api.test/health"), env as never, {} as never);
  assert.equal(health.status, 200);
  assert.deepEqual(await health.json(), { status: "ok", provider: "finAPI", environment: "sandbox.finapi.io" });

  const unauthorized = await worker.fetch(new Request("https://api.test/v1/banks"), env as never, {} as never);
  assert.equal(unauthorized.status, 401);
  assert.deepEqual(await unauthorized.json(), { error: "App-Schlüssel fehlt oder ist ungültig." });
});

test("rejects an untrusted Access API base URL before sending OAuth credentials", async () => {
  for (const baseUrl of ["http://sandbox.finapi.io", "https://attacker.example", "https://webform-sandbox.finapi.io", "https://sandbox.finapi.io.evil.example"]) {
    const env = baseEnv();
    env.FINAPI_ACCESS_BASE_URL = baseUrl;
    const originalFetch = globalThis.fetch;
    let calls = 0;
    globalThis.fetch = async () => {
      calls++;
      return Response.json({ access_token: "must-not-be-requested" });
    };
    try {
      const response = await worker.fetch(new Request("https://api.test/v1/banks", {
        headers: { Authorization: `Bearer ${"U".repeat(43)}`, "CF-Connecting-IP": "192.0.2.20" },
      }), env as never, {} as never);
      assert.equal(response.status, 503, baseUrl);
      assert.equal(calls, 0, baseUrl);
    } finally {
      globalThis.fetch = originalFetch;
    }
  }
});

test("provisions an isolated provider user and returns only supported target banks", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  const providerCalls: Array<{ url: URL; method: string; authorization: string | null }> = [];
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    const method = init?.method ?? "GET";
    providerCalls.push({ url, method, authorization: new Headers(init?.headers).get("Authorization") });
    if (url.pathname === "/api/v2/oauth/token") {
      return Response.json({ access_token: new URLSearchParams(String(init?.body)).get("grant_type") === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/banks") {
      const search = url.searchParams.get("search");
      const item = search === "C24" ? { id: 24001, name: "C24 Bank GmbH" }
        : search === "comdirect" ? { id: 28001, name: "comdirect – eine Marke der Commerzbank" }
          : { id: 35001, name: "Trade Republic Bank GmbH" };
      return Response.json({ banks: [{ ...item, bankInterfaces: [{ isAisSupported: true }] }] });
    }
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };

  try {
    const request = new Request("https://api.test/v1/banks", {
      headers: { Authorization: `Bearer ${"A".repeat(43)}`, "CF-Connecting-IP": "192.0.2.10" },
    });
    const response = await worker.fetch(request, env as never, {} as never);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      banks: [
        { id: "24001", name: "C24" },
        { id: "28001", name: "comdirect" },
        { id: "35001", name: "Trade Republic" },
      ],
    }, JSON.stringify(providerCalls.map((call) => [call.url.toString(), call.method, call.authorization])));
    assert.equal(env.DB.installations.size, 1);
    assert.equal([...env.DB.installations.values()][0].provider_user_ready, 1);
    assert.ok(providerCalls.some((call) => call.url.pathname === "/api/v2/users" && call.authorization === "Bearer client-token"));
    assert.ok(providerCalls.filter((call) => call.url.pathname === "/api/v2/banks").every((call) => call.authorization === "Bearer user-token"));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("rejects excess new installations before persisting their D1 identity", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/banks") return Response.json({ banks: [] });
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };
  try {
    const responses: number[] = [];
    for (let index = 0; index < 6; index++) {
      const response = await worker.fetch(new Request("https://api.test/v1/banks", {
        headers: { Authorization: `Bearer ${String.fromCharCode(65 + index).repeat(43)}`, "CF-Connecting-IP": "192.0.2.30" },
      }), env as never, {} as never);
      responses.push(response.status);
    }
    assert.deepEqual(responses, [200, 200, 200, 200, 200, 429]);
    assert.equal(env.DB.installations.size, 5);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("enforces a global installation ceiling across distinct source IPs", async () => {
  const env = baseEnv();
  env.MAX_INSTALLATIONS = "2";
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/banks") return Response.json({ banks: [] });
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };
  try {
    const responses: number[] = [];
    for (let index = 0; index < 3; index++) {
      responses.push((await worker.fetch(new Request("https://api.test/v1/banks", {
        headers: { Authorization: `Bearer ${String.fromCharCode(75 + index).repeat(43)}`, "CF-Connecting-IP": `192.0.2.${40 + index}` },
      }), env as never, {} as never)).status);
    }
    assert.deepEqual(responses, [200, 200, 429]);
    assert.equal(env.DB.installations.size, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("enforces a shared new-installation rate limit across distinct source IPs", async () => {
  const env = baseEnv();
  env.MAX_NEW_INSTALLATIONS_PER_MINUTE = "2";
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/banks") return Response.json({ banks: [] });
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };
  try {
    const responses: number[] = [];
    for (let index = 0; index < 3; index++) {
      responses.push((await worker.fetch(new Request("https://api.test/v1/banks", {
        headers: { Authorization: `Bearer ${String.fromCharCode(81 + index).repeat(43)}`, "CF-Connecting-IP": `198.51.100.${40 + index}` },
      }), env as never, {} as never)).status);
    }
    assert.deepEqual(responses, [200, 200, 429]);
    assert.equal(env.DB.installations.size, 2);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("creates a provider-hosted bank consent and normalizes linked EUR transactions", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/banks") return Response.json({ banks: [{ id: 24001, name: "C24", bankInterfaces: [{ isAisSupported: true }] }] });
    if (url.pathname === "/api/webForms/bankConnectionImport") {
      const payload = JSON.parse(String(init?.body));
      assert.equal(payload.bank.id, 24001);
      assert.deepEqual(payload.accountTypes, ["CHECKING"]);
      assert.equal(payload.skipBalancesDownload, true);
      assert.equal(payload.skipPositionsDownload, true);
      return Response.json({ id: "session-1", url: "https://webform-sandbox.finapi.io/wf/session-1" }, { status: 201 });
    }
    if (url.pathname === "/api/v2/bankConnections") {
      return Response.json({ bankConnections: [{ id: 987, bank: { name: "C24" }, updateStatus: "READY" }] });
    }
    if (url.pathname === "/api/v2/accounts") {
      assert.equal(url.searchParams.get("bankConnectionIds"), "987");
      return Response.json({ accounts: [{ id: 77, iban: "DE02120300000000202051", currency: "EUR" }] });
    }
    if (url.pathname === "/api/v2/transactions") {
      return Response.json({ transactions: [{ id: 456, accountId: 77, amount: -12.34, currency: "EUR", bankBookingDate: "2026-09-15 12:30:00", counterpartName: "Stadtwerke", purpose: "Abschlag", endToEndId: "ref-1" }] });
    }
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };

  try {
    const headers = { Authorization: `Bearer ${"B".repeat(43)}`, "CF-Connecting-IP": "192.0.2.11" };
    const consent = await worker.fetch(new Request("https://api.test/v1/connections", {
      method: "POST", headers: { ...headers, "Content-Type": "application/json" }, body: JSON.stringify({ bankId: "24001" }),
    }), env as never, {} as never);
    assert.equal(consent.status, 201);
    assert.deepEqual(await consent.json(), { id: "session-1", authorizationUrl: "https://webform-sandbox.finapi.io/wf/session-1" });

    const snapshot = await worker.fetch(new Request("https://api.test/v1/connections/987", { headers }), env as never, {} as never);
    assert.equal(snapshot.status, 200);
    assert.deepEqual(await snapshot.json(), {
      id: "987", bankName: "C24", status: "READY",
      transactions: [{ id: "77:456", accountIban: "DE02120300000000202051", date: "2026-09-15", counterparty: "Stadtwerke", description: "Abschlag", amountCents: -1234, reference: "ref-1" }],
    });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("fetches transaction pages beyond the former five-page cutoff", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  const requestedPages: number[] = [];
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/bankConnections") return Response.json({ bankConnections: [{ id: 987, bank: { name: "C24" }, updateStatus: "READY" }] });
    if (url.pathname === "/api/v2/accounts") {
      assert.equal(url.searchParams.has("page"), false);
      assert.equal(url.searchParams.has("perPage"), false);
      return Response.json({ accounts: [{ id: 77, currency: "EUR" }] });
    }
    if (url.pathname === "/api/v2/transactions") {
      const page = Number(url.searchParams.get("page"));
      requestedPages.push(page);
      const transactions = page < 6
        ? Array.from({ length: 100 }, (_, index) => ({ id: `${page}-${index}`, accountId: 77, amount: 1, currency: "EUR", bankBookingDate: "2026-09-16" }))
        : [{ id: "last", accountId: 77, amount: 1, currency: "EUR", bankBookingDate: "2026-09-16" }];
      return Response.json({ transactions, paging: { page, perPage: 100, pageCount: 6, totalCount: 501 } });
    }
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };
  try {
    const response = await worker.fetch(new Request("https://api.test/v1/connections/987", {
      headers: { Authorization: `Bearer ${"V".repeat(43)}`, "CF-Connecting-IP": "192.0.2.21" },
    }), env as never, {} as never);
    assert.equal(response.status, 200);
    const snapshot = await response.json() as { transactions: unknown[] };
    assert.equal(snapshot.transactions.length, 501);
    assert.deepEqual(requestedPages, [1, 2, 3, 4, 5, 6]);
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("does not return a successful partial snapshot when transaction paging reaches its safety cap", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/bankConnections") return Response.json({ bankConnections: [{ id: 987, bank: { name: "C24" }, updateStatus: "READY" }] });
    if (url.pathname === "/api/v2/accounts") return Response.json({ accounts: [{ id: 77, currency: "EUR" }] });
    if (url.pathname === "/api/v2/transactions") return Response.json({ transactions: Array.from({ length: 100 }, (_, index) => ({ id: String(index), accountId: 77, amount: 1, currency: "EUR", bankBookingDate: "2026-09-16" })), paging: { page: Number(url.searchParams.get("page")), perPage: 100, pageCount: 51, totalCount: 5001 } });
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };
  try {
    const response = await worker.fetch(new Request("https://api.test/v1/connections/987", {
      headers: { Authorization: `Bearer ${"W".repeat(43)}`, "CF-Connecting-IP": "192.0.2.22" },
    }), env as never, {} as never);
    assert.equal(response.status, 502);
    assert.deepEqual(await response.json(), { error: "Der Abruf der Umsätze hat das sichere Seitenlimit erreicht. Bitte erneut synchronisieren." });
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("runs an explicit user-present sync with PSU metadata and returns the refreshed snapshot", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  const calls: Array<{ url: URL; headers: Headers }> = [];
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    const headers = new Headers(init?.headers);
    calls.push({ url, headers });
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/bankConnections") return Response.json({ bankConnections: [{ id: 987, bank: { name: "C24" }, updateStatus: "READY" }] });
    if (url.pathname === "/api/tasks/backgroundUpdate" && init?.method === "POST") {
      assert.deepEqual(JSON.parse(String(init.body)), { bankConnectionIds: [987], importNewAccountsMode: "CONDITIONAL" });
      assert.equal(headers.get("PSU-IP-Address"), "192.0.2.12");
      assert.equal(headers.get("PSU-Device-OS"), "Android");
      assert.equal(headers.get("PSU-User-Agent"), "KontoKlar test");
      return Response.json({ id: "task-1", status: "COMPLETED" }, { status: 201 });
    }
    if (url.pathname === "/api/v2/accounts") return Response.json({ accounts: [{ id: 77, iban: "DE02120300000000202051", currency: "EUR" }] });
    if (url.pathname === "/api/v2/transactions") return Response.json({ transactions: [{ id: 456, accountId: 77, amount: 3.21, currency: "EUR", bankBookingDate: "2026-09-16 08:00:00", counterpartName: "Arbeitgeber", purpose: "Gehalt" }] });
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };

  try {
    const response = await worker.fetch(new Request("https://api.test/v1/connections/987/sync", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${"C".repeat(43)}`,
        "CF-Connecting-IP": "192.0.2.12",
        "User-Agent": "KontoKlar test",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ deviceOs: "Android" }),
    }), env as never, {} as never);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), {
      status: "COMPLETED",
      snapshot: {
        id: "987", bankName: "C24", status: "READY",
        transactions: [{ id: "77:456", accountIban: "DE02120300000000202051", date: "2026-09-16", counterparty: "Arbeitgeber", description: "Gehalt", amountCents: 321, reference: "" }],
      },
    });
    assert.equal(env.DB.syncTasks.size, 0);
    assert.ok(calls.filter((call) => call.url.pathname !== "/api/v2/oauth/token")
      .every((call) => call.headers.get("Authorization")?.startsWith("Bearer ")));
  } finally {
    globalThis.fetch = originalFetch;
  }
});

test("deletes the finAPI bank profile and server-side installation data on request", async () => {
  const env = baseEnv();
  const originalFetch = globalThis.fetch;
  const deletedPaths: string[] = [];
  globalThis.fetch = async (input, init) => {
    const url = new URL(input instanceof Request ? input.url : String(input));
    if (url.pathname === "/api/v2/oauth/token") {
      const grant = new URLSearchParams(String(init?.body)).get("grant_type");
      return Response.json({ access_token: grant === "client_credentials" ? "client-token" : "user-token" });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "POST") return Response.json({ id: "user" }, { status: 201 });
    if (url.pathname === "/api/v2/bankConnections" && init?.method === "DELETE") {
      deletedPaths.push(url.pathname);
      return new Response(null, { status: 204 });
    }
    if (url.pathname === "/api/v2/users" && init?.method === "DELETE") {
      deletedPaths.push(url.pathname);
      return new Response(null, { status: 204 });
    }
    return Response.json({ error: "unexpected test route" }, { status: 500 });
  };

  try {
    const response = await worker.fetch(new Request("https://api.test/v1/profile", {
      method: "DELETE",
      headers: { Authorization: `Bearer ${"D".repeat(43)}`, "CF-Connecting-IP": "192.0.2.13" },
    }), env as never, {} as never);
    assert.equal(response.status, 204);
    assert.deepEqual(deletedPaths, ["/api/v2/bankConnections", "/api/v2/users"]);
    assert.equal(env.DB.installations.size, 0);
    assert.equal(env.DB.syncTasks.size, 0);
  } finally {
    globalThis.fetch = originalFetch;
  }
});
