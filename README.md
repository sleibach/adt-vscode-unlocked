# adt-vscode-unlocked

Community patches that re-enable **RFC username/password (basic auth) logon** in SAP's official **ABAP Development Tools for VS Code** extension (`SAPSE.adt-vscode`).

> Unofficial. Not affiliated with or endorsed by SAP. These scripts patch *your* locally installed extension only; original files are backed up and fully restorable. Use at your own risk.

## Why

In v1.0.0 the extension only supports SSO/SNC for RFC destinations:

- non-SSO systems are **hidden** from the New Destination → RFC wizard, and
- the non-SSO logon path simply throws `UnsupportedOperationException`.

For the many on-prem ABAP systems that authenticate with **username/password over RFC**, that means you cannot connect at all. The basic-auth machinery already exists in the bundled communication layer (`JCoDestinationRegistry` feeds `token.getPassword()` to JCo) — it just isn't wired up. These patches connect it.

## What it does

Three small, idempotent changes to the installed extension:

1. **Show non-SSO RFC systems** in the destination wizard
   (`AdtLsDestinationService.listSystemConfigurations`).
2. **Prompt for a password and log on** for non-SSO RFC destinations instead of failing — via an injected `com.sap.adt.patch.BasicAuthRfcLogon` helper that reuses the existing `AuthenticationToken` + `ensureLoggedOn` flow (`AdtLsLogonService`).
3. **Register the password input handler** the UI already ships but never wires up
   (`extension.js`: `adtLs/destinations/requestLogonInput` → `promptLogonInput`).

## Requirements

- macOS, Apple Silicon (arm64)
- The official **ABAP Development Tools** extension installed in Cursor or VS Code (`SAPSE.adt-vscode`, v1.0.0)
- `python3` (preinstalled on macOS)
- No JDK required — the patcher runs on the JRE bundled inside the extension.

## Install

```bash
git clone https://github.com/sleibach/adt-vscode-unlocked.git
cd adt-vscode-unlocked
./install.sh
```

Then reload the editor window: Command Palette → **Developer: Reload Window**.

## Use

New Destination → **RFC** → pick your system → enter user / client / language.
On **Open Objects**, you'll be prompted for your password, and the connection is made via JCo.

## Uninstall

```bash
./uninstall.sh
```

Restores the original files from the `*.orig` backups created at install time, then reload the window.

## How it works

- `scripts/patch_extension_js.py` inserts one `onRequest(...)` registration next to the existing browser-logon one.
- `tools/adt-unlock.jar` (source in [`src/`](src)) opens the language-server jar, rewrites **only** the two target methods with ASM (every other method is copied verbatim so stack-map frames stay valid), injects the helper class, and repackages the jar.

See [`src/AdtUnlock.java`](src/AdtUnlock.java) and [`src/com/sap/adt/patch/BasicAuthRfcLogon.java`](src/com/sap/adt/patch/BasicAuthRfcLogon.java).

## Rebuild from source (optional)

The prebuilt `tools/adt-unlock.jar` is committed so install needs no toolchain. To rebuild it (requires JDK 21+ and the extension installed, for the compile classpath):

```bash
./build.sh
```

## Caveats

- Pinned to extension build **v1.0.0** (`...202605281240`). A new extension release overwrites these files — just re-run `./install.sh`, or wait for SAP to ship basic auth natively.
- The password is requested on each logon (not persisted).
- Editing/saving classic objects (e.g. programs) is gated separately by the extension and is **not** addressed here.

## License

[MIT](LICENSE) for the patch code in this repository. The SAP extension itself is **not** redistributed.
