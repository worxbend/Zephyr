#!/usr/bin/env python3
"""Dependency checks for Kotlin production packages; no compiler or third-party deps.

This is a lexical guard, not Kotlin name resolution. See ARCHITECTURE.md for
scope and limitations. Comments/literal text are ignored, template expressions
are checked, and diagnostic offsets refer to the original source.
"""

import argparse
from dataclasses import dataclass
from pathlib import Path
import re
import sys


PACKAGE = "com.worxbend.zephyr"
COMMON = Path("shared/src/commonMain/kotlin")
# Stable platform factories, supplemented by expect declarations found in source.
FACTORIES = {
    "create" + name
    for name in (
        "ActivityStore", "AppSettingsRepository", "BrowserLauncher",
        "ClipboardService", "DesktopNotificationService", "DiagnosticsExporter",
        "EnvironmentSnapshotService", "OperationJournalExporter", "OperationStore",
        "PortablePreferencesService", "ProjectToolchainService",
        "ProxyConfigurationService", "SdkmanHomeConfigurationService",
        "SdkmanRepository", "TerminalLauncher",
    )
}
PLATFORM_PREFIXES = (
    "java", "javax", "jdk", "sun", "android", "kotlin.jvm",
    "kotlin.concurrent", "kotlin.io.path", "kotlin.system",
    "kotlinx.coroutines.swing", "org.apache.commons.exec", "okio",
)
# Fully qualified code must use a real Java namespace, not a local candidate
# variable such as `java.identifier` or `jdk.installedVersions`.
PLATFORM_CODE_PREFIXES = tuple(
    "java." + namespace for namespace in (
        "awt", "beans", "io", "lang", "math", "net", "nio", "rmi",
        "security", "sql", "text", "time", "util",
    )
) + ("javax", "jdk.internal", "sun.misc", "android", "kotlin.jvm",
     "kotlin.concurrent", "kotlin.io.path", "kotlin.system",
     "kotlinx.coroutines.swing", "org.apache.commons.exec")
COMPOSE_PREFIXES = ("androidx.compose", "org.jetbrains.compose")
PROCESS_NAMES = {"Process", "ProcessBuilder", "ProcessHandle", "Runtime", "Thread", "GlobalScope"}
SYSTEM_MEMBERS = {
    "getenv", "getProperty", "getProperties", "setProperty", "setProperties",
    "clearProperty", "currentTimeMillis", "nanoTime", "exit", "gc", "load",
    "loadLibrary", "out", "err", "in", "setOut", "setErr", "setIn",
    "arraycopy", "identityHashCode", "lineSeparator",
}
ADAPTER_NAMES = {
    "ApacheCommonsSdkmanCommandRunner", "SdkmanProcessSession",
    "SdkmanFilesystemInspector", "SecretToolExecutor", "SecretToolProxyCredentialStore",
    "AtomicDocumentStore", "SettingsSnapshotPersistence",
}
MODIFIERS = {"public", "internal", "private", "protected", "suspend", "inline", "expect"}


@dataclass(frozen=True)
class Token:
    text: str
    offset: int


@dataclass(frozen=True, order=True)
class Violation:
    path: str
    line: int
    rule: str
    detail: str

    def __str__(self):
        return f"{self.path}:{self.line}: {self.rule}: {self.detail}"


def tokenize(source):
    """Lex Kotlin code, including code in ${...} and $name string templates.

    Support nested block comments, raw/escaped strings, nested interpolated
    strings, character literals and escaped identifiers. Preserve offsets.
    """
    tokens = []
    size = len(source)

    def code(i, template=False):
        braces = 0
        while i < size:
            char = source[i]
            if source.startswith("//", i):
                end = source.find("\n", i + 2)
                i = size if end < 0 else end
            elif source.startswith("/*", i):
                depth = 1
                i += 2
                while i < size and depth:
                    if source.startswith("/*", i):
                        depth += 1
                        i += 2
                    elif source.startswith("*/", i):
                        depth -= 1
                        i += 2
                    else:
                        i += 1
            elif char == '"':
                i = string(i)
            elif char == "'":
                i += 1
                while i < size:
                    if source[i] == "\\":
                        i += 2
                    elif source[i] == "'":
                        i += 1
                        break
                    else:
                        i += 1
            elif char == "`":
                end = source.find("`", i + 1)
                if end < 0:
                    end = size
                tokens.append(Token(source[i + 1:end], i))
                i = end + 1
            elif char.isalpha() or char == "_":
                start = i
                i += 1
                while i < size and (source[i].isalnum() or source[i] == "_"):
                    i += 1
                tokens.append(Token(source[start:i], start))
            elif template and char == "}" and braces == 0:
                return i + 1
            elif char.isspace():
                i += 1
            else:
                if char == "{":
                    braces += 1
                elif char == "}":
                    braces -= 1
                tokens.append(Token(char, i))
                i += 1
        return i

    def string(i):
        delimiter = '"""' if source.startswith('"""', i) else '"'
        raw = len(delimiter) == 3
        i += len(delimiter)
        while i < size:
            if source.startswith(delimiter, i):
                return i + len(delimiter)
            if not raw and source[i] == "\\":
                i += 2
            elif source.startswith("${", i):
                # Separators prevent literal-adjacent tokens forming a fake name.
                tokens.append(Token("{", i))
                i = code(i + 2, template=True)
                tokens.append(Token("}", i - 1))
            elif source[i] == "$" and i + 1 < size and (source[i + 1].isalpha() or source[i + 1] == "_"):
                start = i + 1
                i = start + 1
                while i < size and (source[i].isalnum() or source[i] == "_"):
                    i += 1
                tokens.append(Token(source[start:i], start))
            else:
                i += 1
        return i

    code(0)
    return tokens


def qualified(tokens, start):
    """Return dotted name and next index (comments/whitespace already removed)."""
    parts = [tokens[start].text]
    end = start + 1
    while end + 1 < len(tokens) and tokens[end].text == ".":
        parts.append(tokens[end + 1].text)
        end += 2
    return ".".join(parts), end


def inside(name, prefix):
    return name == prefix or name.startswith(prefix + ".")


def expect_factory(tokens, index):
    if index == 0 or tokens[index - 1].text != "fun":
        return False
    index -= 2
    modifiers = []
    while index >= 0 and tokens[index].text in MODIFIERS:
        modifiers.append(tokens[index].text)
        index -= 1
    return "expect" in modifiers


def check_source(path, source, factories=None):
    """Return violations for one commonMain file, using path AND package scopes."""
    path = Path(path)
    if COMMON not in path.parents:
        return []
    tokens = tokenize(source)
    package = ""
    for i, token in enumerate(tokens[:-1]):
        if token.text == "package":
            package, _ = qualified(tokens, i + 1)
            break
    relative = path.relative_to(COMMON)
    parts = relative.parts
    domain = "domain" in parts or inside(package, PACKAGE + ".domain")
    application = "application" in parts or inside(package, PACKAGE + ".application")
    presenter = ("features" in parts or inside(package, PACKAGE + ".features")) and path.name.endswith("Presenter.kt")
    isolated = domain or application or presenter
    allowed_core = (PACKAGE + ".domain",) if domain else tuple(
        PACKAGE + "." + layer for layer in ("domain", "application", "data", "settings", "logging")
    )
    contract = inside(package, PACKAGE + ".data") or inside(package, PACKAGE + ".settings")
    factories = set(FACTORIES if factories is None else factories)
    aliases = set()
    violations = set()

    def report(token, rule, detail):
        violations.add(Violation(path.as_posix(), source.count("\n", 0, token.offset) + 1, rule, detail))

    imports = set()
    for i, token in enumerate(tokens[:-1]):
        if token.text != "import":
            continue
        name, end = qualified(tokens, i + 1)
        imports.update(range(i, end))
        imported = name.rsplit(".", 1)[-1]
        alias = None
        if end + 1 < len(tokens) and tokens[end].text == "as":
            alias = tokens[end + 1].text
            imports.update((end, end + 1))
        if imported.startswith("Jvm") or imported in ADAPTER_NAMES or any(
            inside(name, prefix) for prefix in (PACKAGE + ".sdkman", PACKAGE + ".storage")
        ):
            report(token, "COMMON_ADAPTER", f"concrete adapter import {name}; inject its common contract")
        if imported in PROCESS_NAMES:
            report(token, "COMMON_LIFECYCLE", f"{name} bypasses platform/scoped ownership")
        if imported in factories:
            if alias:
                aliases.add(alias)
            report(token, "COMMON_FACTORY", f"platform factory import {name}; inject a port from desktopApp")
        if any(inside(name, prefix) for prefix in PLATFORM_PREFIXES if prefix != "okio"):
            report(token, "COMMON_PLATFORM", f"platform dependency {name} belongs in jvmMain")
        if isolated:
            if any(inside(name, prefix) for prefix in COMPOSE_PREFIXES + ("okio",)):
                report(token, "CORE_DEPENDENCY", f"non-core dependency {name}")
            if domain or application:
                external = inside(name, "kotlin") or inside(name, "kotlinx.coroutines")
                if not external and not any(inside(name, prefix) for prefix in allowed_core):
                    report(token, "CORE_DEPENDENCY", f"{name} is outside this package's dependency direction")
            if presenter and (
                inside(name, PACKAGE + ".sdkman") or inside(name, PACKAGE + ".storage")
            ):
                report(token, "PRESENTER_DEPENDENCY", f"adapter dependency {name}; depend on a common port")

    for i, token in enumerate(tokens):
        if i in imports:
            continue
        name = token.text
        full, _ = qualified(tokens, i)
        if name in factories or name in aliases:
            if not (contract and expect_factory(tokens, i)):
                report(token, "COMMON_FACTORY", f"platform factory reference {name}; construct it in desktopApp")
        if name.startswith("Jvm") or name in ADAPTER_NAMES or any(
            inside(full, prefix) for prefix in (PACKAGE + ".sdkman", PACKAGE + ".storage")
        ):
            report(token, "COMMON_ADAPTER", f"concrete adapter reference {name}; inject its common contract")
        if name in PROCESS_NAMES:
            report(token, "COMMON_LIFECYCLE", f"{name} bypasses platform/scoped ownership")
        member = full.split(".", 2)[1] if "." in full else ""
        if name == "System" and member in SYSTEM_MEMBERS:
            report(token, "COMMON_PLATFORM", f"JVM System reference {full}; use an injected platform port")
        if any(inside(full, prefix) for prefix in PLATFORM_CODE_PREFIXES):
            report(token, "COMMON_PLATFORM", f"platform reference {full} belongs in jvmMain")
        if isolated and any(inside(full, prefix) for prefix in COMPOSE_PREFIXES + ("okio",)):
            report(token, "CORE_DEPENDENCY", f"non-core reference {full}")
        if (domain or application) and inside(full, PACKAGE) and not any(
            inside(full, prefix) for prefix in allowed_core
        ):
            # The package statement names this file, not a dependency.
            if not (i > 0 and tokens[i - 1].text == "package"):
                report(token, "CORE_DEPENDENCY", f"{full} is outside this package's dependency direction")
        if presenter and any(inside(full, prefix) for prefix in (PACKAGE + ".sdkman", PACKAGE + ".storage")):
            report(token, "PRESENTER_DEPENDENCY", f"adapter reference {full}; depend on a common port")
    return sorted(violations)


def check_repository(root):
    root = Path(root)
    directory = root / COMMON
    if not directory.is_dir():
        raise ValueError(f"missing production source directory: {directory}")
    sources = {path.relative_to(root): path.read_text(encoding="utf-8") for path in sorted(directory.rglob("*.kt"))}
    if not sources:
        raise ValueError(f"no Kotlin production files in {directory}")
    factories = set(FACTORIES)
    for source in sources.values():
        tokens = tokenize(source)
        factories.update(token.text for i, token in enumerate(tokens)
                         if re.fullmatch(r"create[A-Z]\w*", token.text) and expect_factory(tokens, i))
    return sorted({violation for path, source in sources.items()
                   for violation in check_source(path, source, factories)}), len(sources)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args(argv)
    try:
        violations, count = check_repository(args.root)
    except (OSError, ValueError) as failure:
        print(f"architecture check failed: {failure}", file=sys.stderr)
        return 2
    for violation in violations:
        print(violation)
    print(f"Architecture check: {count} commonMain Kotlin files, {len(violations)} violations.")
    return 1 if violations else 0


if __name__ == "__main__":
    sys.exit(main())
