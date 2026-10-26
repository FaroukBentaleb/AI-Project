import json
import unittest
from pathlib import Path

from training import triage_data as d

PROMPTS = Path(__file__).resolve().parents[2] / "src" / "main" / "resources" / "prompts" / "v1"


def bug(**fields):
    base = {"id": 1, "summary": "Bookmarks toolbar overlaps the tab strip", "severity": "S3",
            "component": "Toolbars and Customization", "keywords": []}
    base.update(fields)
    return base


DESCRIPTION = "Steps to reproduce:\n1. Open Firefox\n2. Show the bookmarks toolbar\n\nActual results:\nIt overlaps."


class CategoryTest(unittest.TestCase):
    def test_keyword_rules_win_over_component(self):
        self.assertEqual(d.category(bug(keywords=["sec-high"])), "SECURITY")
        self.assertEqual(d.category(bug(keywords=["crash"])), "CRASH")
        self.assertEqual(d.category(bug(keywords=["dataloss"])), "DATA")
        self.assertEqual(d.category(bug(keywords=["perf:pageload"])), "PERFORMANCE")

    def test_summary_and_component_rules(self):
        self.assertEqual(d.category(bug(summary="Firefox crashes when opening a PDF")), "CRASH")
        self.assertEqual(d.category(bug(component="Networking: HTTP")), "INTEGRATION")
        self.assertEqual(d.category(bug()), "UI")
        self.assertEqual(d.category(bug(component="DOM: Core & HTML")), "FUNCTIONAL")
        # "ui" must be a word: "Build Config" is not a UI component.
        self.assertEqual(d.category(bug(component="Build Config")), "FUNCTIONAL")


class ExampleTest(unittest.TestCase):
    def test_makes_a_labeled_example(self):
        e = d.make_example(bug(severity="S2"), DESCRIPTION)
        self.assertEqual((e["severity"], e["category"]), ("HIGH", "UI"))
        self.assertEqual(json.loads(e["target"]), {"severity": "HIGH", "category": "UI",
                                                   "title": "Bookmarks toolbar overlaps the tab strip"})

    def test_skips_noise(self):
        self.assertIsNone(d.make_example(bug(severity="--"), DESCRIPTION))
        self.assertIsNone(d.make_example(bug(keywords=["intermittent-failure"]), DESCRIPTION))
        self.assertIsNone(d.make_example(bug(summary="Intermittent test_foo.js | timed out"), DESCRIPTION))
        self.assertIsNone(d.make_example(bug(), "too short"))

    def test_cleans_boilerplate_and_personal_data(self):
        text = d.clean_description("User Agent: Mozilla/5.0 (Windows NT 10.0)\n\n\n\nMail jane@corp.com from "
                                   "C:\\Users\\jane\\file and 83.12.45.7 with token=abc123 " + "x" * 3000)
        self.assertNotIn("User Agent", text)
        self.assertNotIn("jane", text)
        self.assertIn("[EMAIL]", text)
        self.assertIn("[IP]", text)
        self.assertIn("token=[SECRET]", text)
        self.assertTrue(text.endswith(" ... (truncated)"))


class PromptTest(unittest.TestCase):
    def test_messages_use_bug_service_prompt_files(self):
        system = (PROMPTS / "triage-system.txt").read_text(encoding="utf-8")
        user = (PROMPTS / "triage-user.txt").read_text(encoding="utf-8")
        messages = d.build_messages(system, user, "The page is blank.")
        self.assertEqual([m["role"] for m in messages], ["system", "user"])
        self.assertIn("The page is blank.", messages[1]["content"])
        self.assertIn("Title given by the reporter: (not provided)", messages[1]["content"])
        self.assertNotIn("{{", messages[1]["content"])


class ParseTest(unittest.TestCase):
    def test_reads_lenient_answers(self):
        self.assertEqual(d.parse_output('Sure: ```json\n{"severity":"major","category":"ui/ux","title":"Menu cut off"}```'),
                         {"severity": "HIGH", "category": "UI", "title": "Menu cut off"})

    def test_rejects_unusable_answers(self):
        self.assertIsNone(d.parse_output("It is a crash."))
        self.assertIsNone(d.parse_output('{"severity":"HUGE","category":"UI","title":"Menu cut off"}'))
        self.assertIsNone(d.parse_output('{"severity":"LOW","category":"UI","title":"Bug"}'))


if __name__ == "__main__":
    unittest.main()
