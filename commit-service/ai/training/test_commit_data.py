"""Run from commit-service/ai:  python -m unittest discover -s training -t . -v   (standard library only)."""
import unittest

from commit_prompt import SYSTEM_PROMPT, build_messages, clean_message, is_conventional, trim_diff
from training.commit_data import build_diff, classify, conventional_subject, keep_row, make_example


class ConventionalSubjectTest(unittest.TestCase):
    def test_verbs_map_to_types(self):
        self.assertEqual(conventional_subject("Add about page", ["about.html"]), "feat: add about page")
        self.assertEqual(conventional_subject("Fixed login bug.", ["app.py"]), "fix: fixed login bug")
        self.assertEqual(conventional_subject("Refactor parser", ["a.py"]), "refactor: refactor parser")
        self.assertEqual(conventional_subject("Bump version to 2.0", ["setup.py"]), "chore: bump version to 2.0")

    def test_files_decide_docs_test_and_style(self):
        self.assertEqual(conventional_subject("Update intro", ["README.md"]), "docs: update intro")
        self.assertEqual(conventional_subject("Update cases", ["tests/test_a.py"]), "test: update cases")
        self.assertEqual(conventional_subject("Change colors", ["css/site.css"]), "style: change colors")

    def test_fix_wins_over_docs(self):
        self.assertEqual(classify("Fix typo in intro", ["README.md"]), "fix")

    def test_existing_type_is_kept_and_aliased(self):
        self.assertEqual(conventional_subject("feat(ui): Add button", ["a.js"]), "feat: add button")
        self.assertEqual(conventional_subject("perf: speed up query", ["a.py"]), "refactor: speed up query")

    def test_cleanup_of_issue_refs_and_acronyms(self):
        self.assertEqual(conventional_subject("Add URL parser (#12)", ["a.py"]), "feat: add URL parser")

    def test_result_is_conventional(self):
        self.assertTrue(is_conventional(conventional_subject("Whatever happens here", ["x.rb"])))


class BuildDiffTest(unittest.TestCase):
    def test_added_file_matches_java_format(self):
        diff = build_diff("", "index.html", "", "<h1>Hi</h1>\n<p>x</p>\n")
        self.assertEqual(diff, "diff --git a/index.html b/index.html\n--- /dev/null\n+++ b/index.html\n"
                               "@@ -0,0 +1,2 @@\n+<h1>Hi</h1>\n+<p>x</p>")

    def test_modified_file_has_explicit_counts(self):
        diff = build_diff("a.txt", "a.txt", "one\ntwo\nthree\n", "one\n2\nthree\n")
        self.assertIn("--- a/a.txt\n+++ b/a.txt\n@@ -1,3 +1,3 @@\n one\n-two\n+2\n three", diff)

    def test_single_line_hunks_keep_counts(self):
        diff = build_diff("a", "a", "x\n", "y\n")
        self.assertIn("@@ -1,1 +1,1 @@", diff)

    def test_deleted_file(self):
        diff = build_diff("old.txt", "", "bye\n", "")
        self.assertIn("--- a/old.txt\n+++ /dev/null\n@@ -1,1 +0,0 @@\n-bye", diff)

    def test_no_change_is_empty(self):
        self.assertEqual(build_diff("a", "a", "same\n", "same\n"), "")


class RowFilterTest(unittest.TestCase):
    row = {"subject": "Add about page", "old_file": "", "new_file": "about.html", "old_contents": "",
           "new_contents": "<h1>About us</h1>\n<p>Some text here</p>\n", "lang": "HTML"}

    def test_good_row_becomes_example(self):
        ex = make_example(self.row)
        self.assertEqual(ex["target"], "feat: add about page")
        self.assertIn("+<h1>About us</h1>", ex["diff"])

    def test_rejects_merges_non_english_and_unchanged(self):
        self.assertFalse(keep_row({**self.row, "subject": "Merge branch 'main' into dev"}))
        self.assertFalse(keep_row({**self.row, "subject": "修复了一个很严重的错误在这里面"}))
        self.assertFalse(keep_row({**self.row, "old_contents": self.row["new_contents"]}))
        self.assertFalse(keep_row({**self.row, "subject": "short"}))


class PromptTest(unittest.TestCase):
    def test_trim_cuts_on_a_line_and_marks_it(self):
        text = "\n".join(f"+line {i}" for i in range(500))
        out = trim_diff(text, 200)
        self.assertLessEqual(len(out), 200)
        self.assertTrue(out.endswith("[diff truncated]"))
        self.assertEqual(trim_diff("short"), "short")

    def test_messages_use_the_system_prompt(self):
        msgs = build_messages("diff")
        self.assertEqual(msgs[0], {"role": "system", "content": SYSTEM_PROMPT})
        self.assertEqual(msgs[1]["role"], "user")

    def test_clean_message(self):
        self.assertEqual(clean_message('\n  "feat: add page."\n\nmore text'), "feat: add page")
        self.assertEqual(clean_message("`fix: a  b`"), "fix: a b")
        self.assertEqual(clean_message("   \n"), "")


if __name__ == "__main__":
    unittest.main()
