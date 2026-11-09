"""Validated bank snapshots are reused while sources are unchanged and never served stale."""
import json
import os
import threading
import unittest
from unittest.mock import patch

import assessment_service
from assessment_service import (
    SNAPSHOT_BUILD_ATTEMPTS, SNAPSHOT_CACHE_LIMIT, AssessmentConfigurationError, _load_question_bank,
    get_public_questions, parse_assessment_results, partition_assessment,
)
from test_support.question_banks import (
    CERT_ID, level_2_bank, temporary_assessment_configuration, temporary_question_bank,
)


def submitted(questions):
    return parse_assessment_results(CERT_ID, {"certification_id": CERT_ID, "results": [
        {"problem_id": question["id"], "question": question["prompt"], "choices": question["choices"],
         "correct_answer": question["correct_answer"], "user_answer": question["correct_answer"],
         "is_correct": True, "possible_score": question["possible_score"]} for question in questions]})


def write_atomically(path, document, *, keep_mtime_of=None):
    replacement = path.with_name(path.name + ".tmp")
    replacement.write_text(json.dumps(document, ensure_ascii=False), encoding="utf-8")
    if keep_mtime_of is not None:
        os.utime(replacement, ns=(keep_mtime_of.st_atime_ns, keep_mtime_of.st_mtime_ns))
    os.replace(replacement, path)


class SnapshotCacheTests(unittest.TestCase):
    def counting(self):
        """Count source reads, selector-catalog loads and bank validations."""
        counts = {"reads": 0, "selector": 0, "validations": 0}

        def wrap(name, target, attribute):
            original = getattr(target, attribute)

            def counted(*args, **kwargs):
                counts[name] += 1
                return original(*args, **kwargs)
            patcher = patch.object(target, attribute, counted)
            patcher.start()
            self.addCleanup(patcher.stop)

        wrap("reads", assessment_service, "_read_json_source")
        wrap("selector", assessment_service.selector_catalog, "load_certifications")
        wrap("validations", assessment_service, "retained_records")
        return counts

    def test_unchanged_sources_are_read_and_validated_once(self):
        with temporary_question_bank(level_2_bank()):
            counts = self.counting()
            first = get_public_questions(CERT_ID)
            after_first = dict(counts)
            second = get_public_questions(CERT_ID)
            fixed, unresolved = partition_assessment(CERT_ID, submitted(first["questions"]))

        self.assertEqual(after_first, {"reads": 2, "selector": 1, "validations": 1})
        self.assertEqual(counts, after_first)
        self.assertEqual(second, first)
        self.assertEqual((len(fixed), unresolved), (18, ()))

    def test_same_size_replacement_with_preserved_mtime_is_detected(self):
        with temporary_question_bank(level_2_bank()) as bank_path:
            self.assertEqual(get_public_questions(CERT_ID)["questions"][0]["choices"]["A"], "A1")
            document = level_2_bank()
            document["questions"][0]["choices"]["A"] = "B1"
            write_atomically(bank_path, document, keep_mtime_of=os.stat(bank_path))

            served = get_public_questions(CERT_ID)

        self.assertEqual(served["questions"][0]["choices"]["A"], "B1")

    def test_invalid_or_deleted_source_fails_until_repaired(self):
        with temporary_question_bank(level_2_bank()) as bank_path:
            baseline = get_public_questions(CERT_ID)
            invalid = level_2_bank()
            invalid["onboarding_question_ids"] = [1, 99]
            write_atomically(bank_path, invalid)
            with self.assertRaisesRegex(AssessmentConfigurationError, "unknown question ids"):
                get_public_questions(CERT_ID)
            with self.assertRaises(AssessmentConfigurationError):
                partition_assessment(CERT_ID, submitted(baseline["questions"][:1]))
            write_atomically(bank_path, level_2_bank())
            self.assertEqual(get_public_questions(CERT_ID), baseline)
            bank_path.unlink()
            with self.assertRaisesRegex(AssessmentConfigurationError, "question bank is unavailable"):
                get_public_questions(CERT_ID)
            write_atomically(bank_path, level_2_bank())
            self.assertEqual(get_public_questions(CERT_ID), baseline)

    def test_mapping_change_and_disabled_certification_take_effect_immediately(self):
        other = level_2_bank()
        other["onboarding_question_ids"] = [5, 4]
        with temporary_assessment_configuration(banks={"questions.json": level_2_bank(), "other.json": other},
                                                bank_map={CERT_ID: "questions.json"}) as root:
            self.assertEqual(len(get_public_questions(CERT_ID)["questions"]), 18)
            (root / "question_banks.json").write_text(json.dumps({CERT_ID: "other.json"}), encoding="utf-8")
            self.assertEqual([q["id"] for q in get_public_questions(CERT_ID)["questions"]], [5, 4])

            selector = assessment_service.selector_catalog.CATALOG_PATH
            catalog = json.loads(selector.read_text(encoding="utf-8"))
            catalog["certifications"][0]["enabled"] = False
            selector.write_text(json.dumps(catalog), encoding="utf-8")
            with self.assertRaises(KeyError):
                get_public_questions(CERT_ID)

    def test_symlinked_bank_retargeted_outside_the_questionnaire_is_rejected(self):
        with temporary_assessment_configuration(banks={"real.json": level_2_bank()},
                                                bank_map={CERT_ID: "link.json"}) as root:
            outside = root.parent / "outside.json"
            outside.write_text(json.dumps(level_2_bank(), ensure_ascii=False), encoding="utf-8")
            (root / "link.json").symlink_to(root / "real.json")
            self.assertEqual(len(get_public_questions(CERT_ID)["questions"]), 18)
            (root / "link.json").unlink()
            (root / "link.json").symlink_to(outside)

            with self.assertRaisesRegex(AssessmentConfigurationError, "leaves assessment_questionnaire"):
                get_public_questions(CERT_ID)

    def test_returned_data_cannot_modify_cached_records(self):
        with temporary_question_bank(level_2_bank()):
            served = get_public_questions(CERT_ID)
            served["questions"][0]["choices"]["A"] = "변경"
            served["questions"].pop()
            _load_question_bank(CERT_ID)["questions"][0]["prompt"] = "변경"

            again = get_public_questions(CERT_ID)
            fixed, _ = partition_assessment(CERT_ID, submitted(again["questions"]))

        self.assertEqual(len(again["questions"]), 18)
        self.assertEqual(again["questions"][0]["choices"]["A"], "A1")
        self.assertEqual(len(fixed), 18)

    def test_sources_that_keep_changing_fail_after_bounded_attempts(self):
        builds = []
        original = assessment_service._build_snapshot

        def counted_build(*args):
            builds.append(args)
            return original(*args)

        with temporary_question_bank(level_2_bank()), \
                patch.object(assessment_service, "_build_snapshot", counted_build), \
                patch.object(assessment_service, "_current_revision", side_effect=lambda *a: object()):
            with self.assertRaisesRegex(AssessmentConfigurationError, "kept changing"):
                get_public_questions(CERT_ID)
        self.assertEqual(len(builds), SNAPSHOT_BUILD_ATTEMPTS)

    def test_concurrent_reads_during_replacements_see_whole_snapshots(self):
        alternate = level_2_bank()
        alternate["onboarding_question_ids"] = [2, 1]
        with temporary_question_bank(level_2_bank()) as bank_path:
            expected = {json.dumps(get_public_questions(CERT_ID), sort_keys=True)}
            write_atomically(bank_path, alternate)
            expected.add(json.dumps(get_public_questions(CERT_ID), sort_keys=True))
            seen, failures = set(), []
            stop = threading.Event()

            def read():
                while not stop.is_set():
                    try:
                        seen.add(json.dumps(get_public_questions(CERT_ID), sort_keys=True))
                    except Exception as error:
                        failures.append(error)

            readers = [threading.Thread(target=read) for _ in range(4)]
            for reader in readers:
                reader.start()
            for index in range(40):
                write_atomically(bank_path, level_2_bank() if index % 2 else alternate)
            stop.set()
            for reader in readers:
                reader.join(timeout=10)

        self.assertTrue(seen)
        self.assertLessEqual(seen, expected)
        self.assertTrue(all(isinstance(error, AssessmentConfigurationError) for error in failures), failures)

    def test_retained_snapshots_stay_bounded_across_temporary_sources(self):
        for _ in range(SNAPSHOT_CACHE_LIMIT + 4):
            with temporary_question_bank(level_2_bank()):
                get_public_questions(CERT_ID)

        self.assertLessEqual(len(assessment_service._SNAPSHOTS), SNAPSHOT_CACHE_LIMIT)

    def test_separate_temporary_roots_stay_isolated(self):
        shortened = level_2_bank()
        shortened["onboarding_question_ids"] = [7]
        with temporary_question_bank(shortened):
            short = get_public_questions(CERT_ID)
        with temporary_question_bank(level_2_bank()):
            full = get_public_questions(CERT_ID)

        self.assertEqual(([q["id"] for q in short["questions"]], len(full["questions"])), ([7], 18))


if __name__ == "__main__":
    unittest.main()
