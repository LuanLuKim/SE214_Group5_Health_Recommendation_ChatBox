import test from 'node:test';
import assert from 'node:assert/strict';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { modules } from '@health/contracts';
import { modulePages } from '../src/modules/index';

test('all six module pages render their assigned use cases', () => {
  for (const module of modules) {
    const markup = renderToStaticMarkup(createElement(modulePages[module.id]));
    for (const uc of module.useCases) assert.ok(markup.includes(uc), `${module.id}: ${uc}`);
    assert.ok(markup.includes(`heading-${module.id}`));
  }
});
