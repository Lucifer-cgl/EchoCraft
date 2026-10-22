import assert from 'node:assert/strict';
import {chooseRoute,deviceLabel} from '../docs/download/routing.mjs';
for(const mainlandHint of [true,false]) {
  assert.equal(chooseRoute({directReachable:true,mainlandHint}).route,'direct');
  assert.equal(chooseRoute({directReachable:false,mainlandHint}).route,'mirror');
}
assert.match(deviceLabel('Android 13'),/ARM64/);
assert.match(deviceLabel('iPhone'),/无法安装/);
assert.match(deviceLabel('Windows'),/电脑/);
console.log('Download routing and device tests passed.');
