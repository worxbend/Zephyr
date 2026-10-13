import unittest

from audit_dependencies import audit, read_inventory


class DependencyAuditTest(unittest.TestCase):
    def test_inventory_deduplicates_versions_but_retains_every_scope(self):
        packages = read_inventory("org.example\tlib\t1.0\t:shared:jvmRuntimeClasspath\n"
                                  "org.example\tlib\t1.0\t:shared:jvmTestRuntimeClasspath\n"
                                  "org.example\tlib\t2.0\t:desktopApp:runtimeClasspath\n")
        self.assertEqual([p["version"] for p in packages], ["1.0", "2.0"])
        self.assertEqual(len(packages[0]["scopes"]), 2)

    def test_invalid_or_empty_inventory_cannot_be_a_clean_audit(self):
        for content in ("", "group\tartifact\tversion", "group\t\tversion\tscope"):
            with self.subTest(content=content), self.assertRaises(ValueError):
                read_inventory(content)

    def test_advisories_keep_package_version_and_reachability_context(self):
        packages = read_inventory("org.example\tlib\t1.0\t:shared:jvmRuntimeClasspath")
        requests = []

        def query(payload):
            requests.append(payload)
            return {"results": [{"vulns": [{"id": "OSV-fixture"}, {"id": "OSV-fixture"}]}]}

        report = audit(packages, query)
        self.assertEqual(requests[0]["queries"][0], {"package": {"ecosystem": "Maven", "name": "org.example:lib"}, "version": "1.0"})
        self.assertEqual(report["findings"], [{**packages[0], "advisories": ["OSV-fixture"]}])
        self.assertEqual(report["packages_checked"], 1)

    def test_every_batch_is_checked(self):
        packages = read_inventory("\n".join(f"org.example\tlib{i}\t1.0\ttest" for i in range(205)))
        sizes = []

        def query(payload):
            sizes.append(len(payload["queries"]))
            return {"results": [{} for _ in payload["queries"]]}

        report = audit(packages, query)
        self.assertEqual(sizes, [100, 100, 5])
        self.assertEqual(report["packages_checked"], 205)
        self.assertEqual(report["findings"], [])

    def test_incomplete_or_malformed_responses_fail_closed(self):
        packages = read_inventory("org.example\tlib\t1.0\ttest")
        for response in ({}, {"results": []}, {"results": [None]}, {"results": [{"vulns": "invalid"}]},
                         {"results": [{"vulns": [{"id": ""}]}]},
                         {"results": [{"error": "timeout"}]},
                         {"results": [{"vulns": [{"id": "OSV-fixture"}], "next_page_token": "more"}]}):
            with self.subTest(response=response), self.assertRaises(ValueError):
                audit(packages, lambda _: response)


if __name__ == "__main__":
    unittest.main()
