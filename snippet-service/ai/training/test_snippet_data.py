"""Run from snippet-service/ai:  python -m unittest discover -s training -t . -v   (standard library only)."""
import unittest

from snippet_prompt import (MAX_CODE_CHARS, build_messages, clean_docstring, format_docstring, is_valid_docstring,
                            trim_code)
from training.snippet_data import make_example, normalize_doc, strip_python_docstring

PY = '''def add(a, b):
    """Return the sum of a and b.

    Args:
        a: first number
        b: second number
    """
    total = a + b
    return total
'''


class StripPythonDocstringTest(unittest.TestCase):
    def test_removes_only_the_docstring(self):
        self.assertEqual(strip_python_docstring(PY), "def add(a, b):\n    total = a + b\n    return total")

    def test_indented_method_is_dedented(self):
        method = "    def get(self):\n        '''Fetch it.'''\n        return self.x\n"
        self.assertEqual(strip_python_docstring(method), "def get(self):\n    return self.x")

    def test_no_docstring_docstring_only_or_unparsable(self):
        self.assertIsNone(strip_python_docstring("def f():\n    return 1\n"))
        self.assertIsNone(strip_python_docstring('def f():\n    """Only a doc."""\n'))
        self.assertIsNone(strip_python_docstring("def f(:\n"))


class MakeExampleTest(unittest.TestCase):
    def row(self, **kw):
        base = {"language": "python", "func_name": "add", "func_code_string": PY,
                "func_documentation_string": "Return the sum of a and b.\n\n    Args:\n        a: first number"}
        return {**base, **kw}

    def test_python_row_has_no_leaked_docstring(self):
        example = make_example(self.row())
        self.assertIsNotNone(example)
        self.assertNotIn('"""', example["code"])
        self.assertEqual(example["target"], "Return the sum of a and b.\n\nArgs:\n    a: first number")

    def test_java_row_keeps_code_as_is(self):
        code = "public int size() {\n    return this.items.size();\n}"
        example = make_example({"language": "java", "func_name": "Box.size", "func_code_string": code,
                                "func_documentation_string": "Returns the number of items in this box."})
        self.assertEqual(example["code"], code)

    def test_rejections(self):
        self.assertIsNone(make_example(self.row(language="ruby")))
        self.assertIsNone(make_example(self.row(func_name="test_add")))
        self.assertIsNone(make_example(self.row(func_documentation_string="{@inheritDoc} something here ok")))
        self.assertIsNone(make_example(self.row(func_documentation_string="Too short")))
        self.assertIsNone(make_example(self.row(func_documentation_string="Résumé généré très long café " * 3)))
        self.assertIsNone(make_example(self.row(func_code_string="def f():\n    return 1\n")))


class DocHelpersTest(unittest.TestCase):
    def test_normalize_doc_dedents_continuation_lines(self):
        self.assertEqual(normalize_doc("Summary line.\n\n    Args:\n        x: y\n\n\n\n    Returns: z"),
                         "Summary line.\n\nArgs:\n    x: y\n\nReturns: z")

    def test_clean_docstring_strips_quotes_fences_and_markers(self):
        self.assertEqual(clean_docstring('"""Adds two numbers."""'), "Adds two numbers.")
        self.assertEqual(clean_docstring("```\nAdds two numbers.\n```"), "Adds two numbers.")
        self.assertEqual(clean_docstring("/**\n * Adds two numbers.\n * @param a first\n */"),
                         "Adds two numbers.\n@param a first")

    def test_format_docstring(self):
        self.assertEqual(format_docstring("Adds.", "python", "    "), '    """Adds."""')
        self.assertEqual(format_docstring("Adds.\n\nArgs:\n    a: x", "python"),
                         '"""\nAdds.\n\nArgs:\n    a: x\n"""')
        self.assertEqual(format_docstring("Adds.\n@param a x", "java", "  "), "  /**\n   * Adds.\n   * @param a x\n   */")
        with self.assertRaises(ValueError):
            format_docstring("x", "cobol")

    def test_is_valid_docstring(self):
        self.assertTrue(is_valid_docstring("Return the sum of two numbers."))
        self.assertFalse(is_valid_docstring("Short"))
        self.assertFalse(is_valid_docstring("Has ``` a fence inside it"))

    def test_trim_code_cuts_on_line_boundary(self):
        code = "\n".join(f"line {i}" for i in range(2000))
        trimmed = trim_code(code)
        self.assertLessEqual(len(trimmed), MAX_CODE_CHARS)
        self.assertTrue(trimmed.endswith("... [code truncated]"))
        self.assertEqual(trim_code("short"), "short")

    def test_build_messages_mentions_language(self):
        messages = build_messages("def f(): pass", "python")
        self.assertEqual([m["role"] for m in messages], ["system", "user"])
        self.assertTrue(messages[1]["content"].startswith("Language: python"))


if __name__ == "__main__":
    unittest.main()
