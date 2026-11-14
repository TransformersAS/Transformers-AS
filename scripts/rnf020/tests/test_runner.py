import importlib.util
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location('rnf020_runner',Path(__file__).resolve().parents[1]/'run.py')
runner=importlib.util.module_from_spec(spec);spec.loader.exec_module(runner)

class IsolationTests(unittest.TestCase):
    def test_cleanup_targets_only_generated_project(self):
        with patch.object(runner,'cmd',return_value=SimpleNamespace(returncode=0)) as execute, patch.object(runner.infra,'ids',return_value=[]):
            result=runner.cleanup()
            self.assertTrue(result['passed'])
            args=execute.call_args.args[0]
            self.assertEqual(args[args.index('-p')+1],runner.PROJECT)
            self.assertTrue(runner.PROJECT.startswith('rnf020-'))
            self.assertIn('down',args);self.assertNotIn('prune',args)

    def test_leftovers_and_cleanup_failure_are_never_approved(self):
        with patch.object(runner,'cmd',return_value=SimpleNamespace(returncode=0)), patch.object(runner.infra,'ids',return_value=['owned-resource']):
            self.assertFalse(runner.cleanup()['passed'])
        with patch.object(runner,'cmd',return_value=SimpleNamespace(returncode=1)), patch.object(runner.infra,'ids',return_value=[]):
            self.assertFalse(runner.cleanup()['passed'])

    def test_fixture_uses_disjoint_seller_and_buyers_without_business_calls(self):
        sql=runner.fixture()
        self.assertEqual(sql.count('INSERT INTO user_accounts('),3)
        self.assertIn("VALUES(2,'VENDEDOR')",sql)
        self.assertIn('owner_account_id=2',sql)
        self.assertNotIn('INSERT INTO conversation_messages',sql)
        self.assertNotIn('INSERT INTO notifications',sql)
        self.assertNotIn('DELETE FROM',sql)

if __name__=='__main__':unittest.main()
