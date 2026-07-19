export interface XrpcResult<T = unknown> {
  ok: boolean;
  status: number;
  nsid: string;
  request: Record<string, unknown>;
  response: T | { error?: string };
}

export async function callXrpc<T = unknown>(
  base: string,
  nsid: string,
  payload: Record<string, unknown> = {}
): Promise<XrpcResult<T>> {
  const url = `${base}/xrpc/${nsid}`;
  let status = 0;
  try {
    const res = await fetch(url, {
      method: 'POST',
      mode: 'cors',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload)
    });
    status = res.status;
    const text = await res.text();
    let response: unknown = text;
    try {
      response = text ? JSON.parse(text) : {};
    } catch {
      response = { raw: text };
    }
    return { ok: res.ok, status, nsid, request: payload, response: response as T };
  } catch (err) {
    return { ok: false, status, nsid, request: payload, response: { error: String(err) } };
  }
}
