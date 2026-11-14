import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec=importlib.util.spec_from_file_location('remediation_report',Path(__file__).resolve().parents[1]/'remediation-report.py')
report=importlib.util.module_from_spec(spec);spec.loader.exec_module(report)

class RemediationReportTests(unittest.TestCase):
    def generate(self,dev=0,ui=True,tests=True):
        with tempfile.TemporaryDirectory() as directory:
            p=Path(directory)
            def save(name,value):(p/name).write_text(json.dumps(value))
            save('before.json',{'alerts':[{'pluginId':'10003'}]})
            save('after.json',{'alerts':[],'result':{'exit_code':0,'blocking_alerts':0}})
            for name,total in [('audit-production.json',0),('audit-all.json',dev)]:save(name,{'metadata':{'vulnerabilities':{'total':total,'critical':dev}}})
            save('advisories.json',[]);save('ui.json',{'complete':ui})
            if tests:
                save('functional-tests.json',{'stats':{'expected':3,'unexpected':0}})
                save('security-test-result.json',{'passed':2,'exit_code':0})
            data=report.generate(p/'before.json',p/'after.json',p,p/'ui.json')
            return data,(p/'remediation-executive.html').read_text()

    def test_development_findings_prevent_complete_remediation_claim(self):
        data,_=self.generate(dev=1);self.assertEqual(data['state'],'Parcial')
    def test_missing_or_failed_functional_evidence_never_approves(self):
        for args in [{'ui':False},{'tests':False}]:self.assertEqual(self.generate(**args)[0]['state'],'Parcial')
    def test_test_counts_come_from_evidence_not_previous_run(self):
        data,page=self.generate();self.assertEqual(data['functional_test_stats']['expected'],3);self.assertIn('3 E2E aprobados',page);self.assertNotIn('159 E2E',page)
    def test_before_alerts_are_preserved_as_comparison_data(self):
        data,_=self.generate();self.assertEqual(data['removed_alert_instances'],1);self.assertEqual(data['before_rules'],{'10003':1});self.assertEqual(data['after_rules'],{})
