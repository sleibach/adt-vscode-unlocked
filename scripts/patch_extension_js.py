#!/usr/bin/env python3
"""Register the missing `requestLogonInput` handler in the extension's bundle.

The official extension ships `promptLogonInput` (a masked password input box)
but never wires the language-server request `adtLs/destinations/requestLogonInput`
to it. We add that one `onRequest` registration next to the existing
`requestBrowserBasedLogon` one. Idempotent.
"""
import re
import sys


def patch(path: str) -> int:
    with open(path, "r", encoding="utf-8", errors="surrogatepass") as fh:
        src = fh.read()

    if "this.getUI().promptLogonInput(" in src:
        print("extension.js: already patched")
        return 0

    # Class whose .type is the requestLogonInput RequestType (minified name).
    m_type = re.search(
        r'(\w+)=class\{static type=new \w+\.RequestType\("adtLs/destinations/requestLogonInput"\)\}',
        src,
    )
    # The existing browser-based-logon registration we hook next to.
    m_reg = re.search(
        r'(?P<full>(?P<subs>\w+)\.subscriptions\.push\((?P<e>\w+)\.onRequest\((?P<lo>\w+)\.type,'
        r'async (?P<n>\w+)=>this\.requestBrowserBasedLogon\((?P=n)\)\)\))',
        src,
    )
    if not m_type or not m_reg:
        print("extension.js: ERROR could not locate anchors (unexpected build).", file=sys.stderr)
        return 1

    gg = m_type.group(1)
    subs, e, n = m_reg.group("subs"), m_reg.group("e"), m_reg.group("n")
    addition = (
        f",{subs}.subscriptions.push({e}.onRequest({gg}.type,"
        f"async {n}=>this.getUI().promptLogonInput({n})))"
    )
    insert_at = m_reg.end("full")
    out = src[:insert_at] + addition + src[insert_at:]

    with open(path, "w", encoding="utf-8", errors="surrogatepass") as fh:
        fh.write(out)
    print("extension.js: patched (registered requestLogonInput -> promptLogonInput)")
    return 0


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: patch_extension_js.py <path-to-extension.js>", file=sys.stderr)
        sys.exit(2)
    sys.exit(patch(sys.argv[1]))
