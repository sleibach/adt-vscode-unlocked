# adt-vscode-unlocked

Community patches that re-enable **RFC username/password (basic auth) logon** in SAP's official **ABAP Development Tools for VS Code** extension (`SAPSE.adt-vscode`).

> Unofficial. Not affiliated with or endorsed by SAP. These scripts patch *your* locally installed extension only; original files are backed up and fully restorable. Use at your own risk.

## Important: license warning — read before using

The official extension is **not** open source. It is licensed under the
**SAP Developer License Agreement (v3.2)** (`LICENSE.txt` shipped inside the
extension). Section 2 of that agreement prohibits, among other things:

- decompiling, disassembling or reverse engineering the tools (except where
  applicable law expressly allows it),
- creating derivative works of or based on the tools, and
- using the tools to modify existing SAP software or product functionality.

These patches **disassemble and modify the shipped language-server bytecode and
the extension bundle**, so applying them **very likely violates the SAP
Developer License Agreement.**

Treat this repository as a **technical proof-of-concept / research write-up** —
not as something to run against a licensed installation, and not for production
or commercial use. The proper fix is for SAP to add username/password RFC
support natively.

This repo does **not** contain or redistribute any SAP code: it ships only the
original patch code and scripts, which operate on *your own* local copy. You
are solely responsible for complying with the SAP Developer License Agreement
and any agreements with SAP and/or your employer.

**If you choose to use it, you do so entirely at your own risk.**

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

- macOS, Apple Silicon (arm64), or Windows 10/11
- The official **ABAP Development Tools** extension installed in Cursor or VS Code (`SAPSE.adt-vscode`, v1.0.0)
- Python 3
- No JDK required — the patcher runs on the JRE bundled inside the extension.

## Install

### macOS / Apple Silicon

```bash
git clone https://github.com/sleibach/adt-vscode-unlocked.git
cd adt-vscode-unlocked
./install.sh
```

### Windows

```powershell
git clone https://github.com/sleibach/adt-vscode-unlocked.git
cd adt-vscode-unlocked
.\install.ps1
```

Then reload the editor window: Command Palette → **Developer: Reload Window**.

## Use

New Destination → **RFC** → pick your system → enter user / client / language.
On **Open Objects**, you'll be prompted for your password, and the connection is made via JCo.

## Uninstall

### macOS / Apple Silicon

```bash
./uninstall.sh
```

Restores the original files from the `*.orig` backups created at install time, then reload the window.

### Windows

```powershell
.\uninstall.ps1
```

Restores the original files from the `*.orig` backups created at install time, then reload the window.

## How it works

- `scripts/patch_extension_js.py` inserts one `onRequest(...)` registration next to the existing browser-logon one.
- `tools/adt-unlock.jar` (source in [`src/`](src)) opens the language-server jar, rewrites **only** the two target methods with ASM (every other method is copied verbatim so stack-map frames stay valid), injects the helper class, and repackages the jar.

See [`src/AdtUnlock.java`](src/AdtUnlock.java) and [`src/com/sap/adt/patch/BasicAuthRfcLogon.java`](src/com/sap/adt/patch/BasicAuthRfcLogon.java).

## Rebuild from source (optional)

The prebuilt `tools/adt-unlock.jar` is committed so install needs no toolchain. To rebuild it (requires JDK 21+ and the extension installed, for the compile classpath):

### macOS / Apple Silicon

```bash
./build.sh
```

### Windows

```powershell
.\build.ps1
```

## Caveats

- Pinned to extension build **v1.0.0** (`...202605281240`). A new extension release overwrites these files — just re-run `./install.sh`, or wait for SAP to ship basic auth natively.
- The password is requested on each logon (not persisted).
- Editing/saving classic objects (e.g. programs) is gated separately by the extension and is **not** addressed here.

## License

[MIT](LICENSE) for the patch code in this repository. The SAP extension itself is **not** redistributed, and MIT grants no rights in it — see the [license warning](#important-license-warning--read-before-using) above regarding the SAP Developer License Agreement.
