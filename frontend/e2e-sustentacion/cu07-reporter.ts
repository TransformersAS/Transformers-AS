import type { TestCase, TestResult, TestStep } from '@playwright/test/reporter';
import { appendFileSync } from 'node:fs';
import { join } from 'node:path';
import DemoReporter from './reporter';
export default class Cu07Reporter extends DemoReporter {
  onStepBegin(_test:TestCase,_result:TestResult,step:TestStep) {
    if(step.category==='test.step') console.log(`CU07 → ${step.title}`);
  }
  onStepEnd(test:TestCase,_result:TestResult,step:TestStep) {
    if(step.category!=='test.step') return;
    appendFileSync(join(process.env['SUST_REPORT_DIR']!,'cu07-steps.jsonl'),JSON.stringify({scenario:test.title,step:step.title,duration_ms:step.duration,passed:!step.error})+'\n');
  }
}
