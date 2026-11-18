import unittest

from check_boot2_advisory import ACCEPTED, ADVISORY, check


class Boot2AdvisoryTest(unittest.TestCase):
    def setUp(self):
        self.added = dict(
            ACCEPTED, change_type="added", vulnerabilities=[{"advisory_ghsa_id": ADVISORY}]
        )

    def test_approved_coordinate_and_removals(self):
        check([self.added, dict(self.added, change_type="removed", version="5.3.31")])
        check([])

    def test_second_occurrence_outside_any_accepted_field_blocks_entire_check(self):
        for key in ACCEPTED:
            with self.subTest(key=key), self.assertRaises(ValueError):
                check([self.added, dict(self.added, **{key: "other"})])

    def test_missing_identity_and_malformed_data_fail_closed(self):
        for changes in (
            None, {}, [{}], [dict(self.added, scope=None)], [dict(self.added, change_type="other")]
        ):
            with self.subTest(changes=changes), self.assertRaises((KeyError, ValueError)):
                check(changes)


if __name__ == "__main__":
    unittest.main()
