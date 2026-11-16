const { readFileSync, writeFileSync } = require('node:fs');
const { setting } = require('./cu03-real-support.cjs');
const keys = ['CU03_BUYER_EMAIL', 'CU03_BUYER_PASSWORD', 'CU03_MP_TEST_USER', 'CU03_MP_TEST_PASSWORD', 'CU03_MP_TEST_CARD_CVV'];
function scrub(text) {
  for (const key of keys) {
    const secret = setting(key);
    if (secret) text = text.split(secret).join('[REDACTADO]');
  }
  return text;
}
module.exports = class PrivateReporter {
  onTestEnd(test, result) {
    for (const error of result.errors) {
      for (const field of ['message', 'stack', 'snippet']) if (typeof error[field] === 'string') error[field] = scrub(error[field]);
    }
    for (const attachment of result.attachments) {
      if (attachment.path && attachment.name === 'error-context') {
        writeFileSync(attachment.path, scrub(readFileSync(attachment.path, 'utf8')));
      }
    }
  }
};
