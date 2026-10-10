"""Synthetic dependency regressions; never construct real JVM/application adapters."""

from pathlib import Path
import os
import subprocess
import sys
import tempfile
import unittest

from architecture_check import COMMON, PACKAGE, check_repository, check_source, tokenize


class ArchitectureCheckTest(unittest.TestCase):
    def check(self, source, suffix="domain/Plan.kt"):
        path = COMMON / "com/worxbend/zephyr" / suffix
        return check_source(path, source)

    def rules(self, source, suffix="domain/Plan.kt"):
        return {item.rule for item in self.check(source, suffix)}

    def test_domain_accepts_stdlib_coroutines_and_domain(self):
        self.assertEqual([], self.check(f"""package {PACKAGE}.domain
import kotlin.math.max
import kotlinx.coroutines.flow.StateFlow
import {PACKAGE}.domain.Candidate
fun policy(value: Int) = max(value, 0)
"""))

    def test_domain_cannot_depend_on_application_or_common_ports(self):
        for layer in ("application.Execute", "data.SdkmanRepository", "features.catalog.State"):
            with self.subTest(layer=layer):
                self.assertIn("CORE_DEPENDENCY", self.rules(f"import {PACKAGE}.{layer}"))

    def test_application_accepts_ports_and_coroutines(self):
        self.assertEqual(set(), self.rules(f"""package {PACKAGE}.application
import {PACKAGE}.data.OperationStore
import {PACKAGE}.domain.CommandOutcome
import {PACKAGE}.settings.AppSettings
import kotlinx.coroutines.sync.Mutex
""", "application/OperationCoordinator.kt"))

    def test_application_cannot_import_viewmodel_or_root_ui(self):
        for target in ("viewmodel.ZephyrViewModel", "App", "LocalAppServices", "features.settings.SettingsPresenter"):
            with self.subTest(target=target):
                self.assertIn("CORE_DEPENDENCY", self.rules(f"import {PACKAGE}.{target}", "application/Execute.kt"))

    def test_compose_imports_are_forbidden_in_core_and_presenters(self):
        for suffix in ("domain/Plan.kt", "application/Execute.kt", "features/settings/SettingsPresenter.kt"):
            for target in ("androidx.compose.runtime.*", "org.jetbrains.compose.resources.DrawableResource"):
                with self.subTest(suffix=suffix, target=target):
                    self.assertIn("CORE_DEPENDENCY", self.rules(f"import {target}", suffix))

    def test_compose_screen_can_depend_on_ports_and_compose(self):
        self.assertEqual([], self.check(f"""package {PACKAGE}.features.settings
import androidx.compose.runtime.Composable
import {PACKAGE}.data.ProxyConfigurationService
@Composable fun SettingsScreen(service: ProxyConfigurationService) {{}}
""", "features/settings/SettingsScreen.kt"))

    def test_common_platform_imports_include_aliases_and_wildcards(self):
        for target in ("java.nio.file.*", "javax.swing.JFileChooser as Picker", "kotlin.jvm.JvmStatic", "kotlinx.coroutines.swing.Swing", "org.apache.commons.exec.DefaultExecutor"):
            with self.subTest(target=target):
                self.assertIn("COMMON_PLATFORM", self.rules(f"import {target}", "features/settings/SettingsPresenter.kt"))

    def test_fully_qualified_platform_and_compose_references(self):
        self.assertIn("COMMON_PLATFORM", self.rules("fun read() = java.nio.file.Files.readString(path)"))
        self.assertIn("CORE_DEPENDENCY", self.rules("@androidx.compose.runtime.Composable fun render() {}"))

    def test_implicit_jvm_system_and_platform_kotlin_apis(self):
        for source in ("val home = System.getenv(key)", "val now = System.currentTimeMillis()", "fun exit() = kotlin.system.exitProcess(0)", "import kotlin.concurrent.thread", "import kotlin.io.path.Path"):
            with self.subTest(source=source):
                self.assertIn("COMMON_PLATFORM", self.rules(source, "features/settings/SettingsPresenter.kt"))
        self.assertEqual([], self.check("val theme = ThemePreference.System", "features/settings/SettingsPresenter.kt"))

    def test_fully_qualified_project_references_obey_dependency_direction(self):
        self.assertIn("CORE_DEPENDENCY", self.rules(f"val vm = {PACKAGE}.viewmodel.ZephyrViewModel()", "application/Execute.kt"))
        self.assertIn("CORE_DEPENDENCY", self.rules(f"val port: {PACKAGE}.data.SdkmanRepository", "domain/Plan.kt"))
        self.assertIn("PRESENTER_DEPENDENCY", self.rules(f"val runner: {PACKAGE}.sdkman.Runner", "features/settings/SettingsPresenter.kt"))
        self.assertEqual([], self.check(f"val candidate: {PACKAGE}.domain.Candidate"))

    def test_process_and_unowned_scope_references(self):
        for name in ("ProcessBuilder", "ProcessHandle", "Runtime", "Thread", "GlobalScope"):
            with self.subTest(name=name):
                self.assertIn("COMMON_LIFECYCLE", self.rules(f"fun launch() = {name}()", "features/settings/SettingsPresenter.kt"))

    def test_factories_forbidden_even_without_compose_import(self):
        for suffix in ("App.kt", "viewmodel/ZephyrViewModel.kt", "features/settings/SettingsPresenter.kt", "FutureScreen.kt", "runtime/Policy.kt"):
            with self.subTest(suffix=suffix):
                self.assertIn("COMMON_FACTORY", self.rules("val service = createDiagnosticsExporter()", suffix))

    def test_factory_alias_import_and_call_are_detected(self):
        violations = self.check(f"import {PACKAGE}.data.createClipboardService as clipboard\nval service = clipboard()", "FutureScreen.kt")
        self.assertEqual([1, 2], [item.line for item in violations if item.rule == "COMMON_FACTORY"])

    def test_factory_callable_reference_and_backtick_call(self):
        for source in ("val provider = ::createClipboardService", "val service = `createClipboardService`()"):
            with self.subTest(source=source):
                self.assertIn("COMMON_FACTORY", self.rules(source, "FutureScreen.kt"))

    def test_factory_import_itself_is_forbidden(self):
        self.assertIn("COMMON_FACTORY", self.rules(f"import {PACKAGE}.data.createSdkmanRepository", "App.kt"))

    def test_only_contract_expect_declarations_are_exempt(self):
        self.assertEqual([], self.check(f"package {PACKAGE}.data\npublic expect fun createSdkmanRepository(): SdkmanRepository", "data/SdkmanRepository.kt"))
        self.assertIn("COMMON_FACTORY", self.rules(f"package {PACKAGE}.data\nfun createSdkmanRepository() = realAdapter()", "data/SdkmanRepository.kt"))
        self.assertIn("COMMON_FACTORY", self.rules(f"package {PACKAGE}\nexpect fun createSdkmanRepository(): SdkmanRepository", "App.kt"))
        self.assertIn("COMMON_FACTORY", self.rules(f"package {PACKAGE}.data\nexpect fun createSdkmanRepository(): SdkmanRepository\nval leaked = createSdkmanRepository()", "data/SdkmanRepository.kt"))

    def test_concrete_adapters_are_not_common_dependencies(self):
        for source in ("val service = JvmSdkmanRepository()", "val session = SdkmanProcessSession()", f"import {PACKAGE}.sdkman.JvmSdkmanRepository as Repo\nval service = Repo()", f"import {PACKAGE}.sdkman.*", f"val runner = {PACKAGE}.sdkman.CommandRunner()"):
            with self.subTest(source=source):
                self.assertIn("COMMON_ADAPTER", self.rules(source, "FutureScreen.kt"))

    def test_presenter_cannot_import_adapter_package(self):
        self.assertIn("PRESENTER_DEPENDENCY", self.rules(f"import {PACKAGE}.sdkman.CommandRunner", "features/settings/SettingsPresenter.kt"))

    def test_nested_comments_and_literals_are_ignored(self):
        source = '''// createSdkmanRepository()
/* outer /* java.nio.Files and createClipboardService() */ @Composable */
val text = "/* not a comment */ createSdkmanRepository() \\\" end"
val raw = """java.nio.Files // createClipboardService()"""
val quote = '\\''
'''
        self.assertEqual([], self.check(source))

    def test_comments_do_not_hide_real_tokens_or_break_dotted_names(self):
        source = "/* fake */\nval service = createClipboardService /* nested /* */ */ ()\nval file = java /* gap */ . nio . file . Files"
        violations = self.check(source, "FutureScreen.kt")
        self.assertEqual([2], [item.line for item in violations if item.rule == "COMMON_FACTORY"])
        self.assertEqual([3], [item.line for item in violations if item.rule == "COMMON_PLATFORM"])

    def test_comment_delimiters_inside_strings_do_not_swallow_following_code(self):
        violations = self.check('val text = "/*"\nval service = createClipboardService()', "FutureScreen.kt")
        self.assertEqual([2], [item.line for item in violations])

    def test_executable_templates_are_checked_in_normal_and_raw_strings(self):
        for source in ('val text = "${createClipboardService()}"', 'val text = """${java.nio.file.Files.readString(path)}"""', 'val text = "${run { "${createClipboardService()}" }}"', 'val text = "$GlobalScope"'):
            with self.subTest(source=source):
                self.assertTrue(self.check(source, "FutureScreen.kt"))

    def test_escaped_templates_are_not_code(self):
        self.assertEqual([], self.check(r'val text = "\${createClipboardService()}"'))

    def test_candidate_variables_named_java_and_jdk_are_not_platform_packages(self):
        self.assertEqual([], self.check("val java = candidate\nval jdk = candidate\nval installed = jdk.installedVersions\nval id = java.identifier", "features/catalog/Screen.kt"))

    def test_token_offsets_preserve_multiline_comment_locations(self):
        source = '/* one\n two */\ncreateClipboardService()'
        tokens = tokenize(source)
        self.assertEqual(source.index("createClipboardService"), tokens[0].offset)
        self.assertEqual(3, self.check(source, "App.kt")[0].line)

    def test_package_rename_does_not_bypass_directory_boundary(self):
        self.assertIn("CORE_DEPENDENCY", self.rules("package migrated\nimport androidx.compose.runtime.Composable"))

    def test_wrong_directory_does_not_bypass_declared_core_package(self):
        self.assertIn("CORE_DEPENDENCY", self.rules(f"package {PACKAGE}.application\nimport androidx.compose.runtime.Composable", "Elsewhere.kt"))

    def test_jvm_desktop_and_tests_are_outside_scope(self):
        for path in ("shared/src/jvmMain/kotlin/Adapter.kt", "desktopApp/src/main/kotlin/main.kt", "shared/src/commonTest/kotlin/PlanTest.kt"):
            with self.subTest(path=path):
                self.assertEqual([], check_source(Path(path), "import java.nio.file.Files\nval repository = createSdkmanRepository()"))

    def test_repository_discovers_new_factories_without_blanket_create_rule(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as temporary:
            root = Path(temporary)
            directory = root / COMMON / "com/worxbend/zephyr/data"
            directory.mkdir(parents=True)
            (directory / "NewService.kt").write_text(f"package {PACKAGE}.data\nexpect fun createFutureService(): FutureService", encoding="utf-8")
            (directory.parent / "Screen.kt").write_text("val service = createFutureService()\nfun createPureModel() = Model()", encoding="utf-8")
            violations, count = check_repository(root)
            self.assertEqual(2, count)
            self.assertEqual(1, len(violations))
            self.assertIn("createFutureService", violations[0].detail)

    def test_missing_and_empty_source_trees_fail_closed(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as temporary:
            root = Path(temporary)
            with self.assertRaises(ValueError):
                check_repository(root)
            (root / COMMON).mkdir(parents=True)
            with self.assertRaises(ValueError):
                check_repository(root)

    def test_cli_exit_status_and_relative_diagnostic(self):
        with tempfile.TemporaryDirectory(dir=os.environ.get("TMPDIR")) as temporary:
            root = Path(temporary)
            directory = root / COMMON
            directory.mkdir(parents=True)
            fixture = directory / "Screen.kt"
            fixture.write_text("val service = createClipboardService()", encoding="utf-8")
            command = [sys.executable, str(Path(__file__).with_name("architecture_check.py")), "--root", str(root)]
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(1, result.returncode)
            self.assertIn("shared/src/commonMain/kotlin/Screen.kt:1: COMMON_FACTORY", result.stdout)
            fixture.write_text("val text = \"createClipboardService()\"", encoding="utf-8")
            result = subprocess.run(command, capture_output=True, text=True, check=False)
            self.assertEqual(0, result.returncode)
            self.assertIn("1 commonMain Kotlin files, 0 violations", result.stdout)


if __name__ == "__main__":
    unittest.main()
