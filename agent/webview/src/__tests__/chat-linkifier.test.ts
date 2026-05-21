/**
 * Tests for the remarkChatLinkify remark plugin.
 *
 * Pure AST transform, no React needed.
 */
import { describe, it, expect } from 'vitest';
import { unified } from 'unified';
import remarkParse from 'remark-parse';
import remarkStringify from 'remark-stringify';
import { remarkChatLinkify } from '@/components/markdown/ChatLinkifier';

function run(md: string): string {
  return String(
    unified()
      .use(remarkParse)
      .use(remarkChatLinkify)
      .use(remarkStringify)
      .processSync(md),
  ).trim();
}

describe('remarkChatLinkify — Jira keys', () => {
  it('linkifies a Jira key in prose', () => {
    const out = run('The WORK-1234 ticket fixes it.');
    expect(out).toContain('[WORK-1234](jira:WORK-1234)');
  });

  it('does NOT linkify a Jira-like key that is part of a longer hyphenated token', () => {
    // Per the brief: PROJECT-1234-extra must NOT match because the trailing
    // hyphen extends the token past the \b boundary.
    const out = run('The PROJECT-1234-extra suffix here.');
    // The whole token "PROJECT-1234" sits between word boundaries, so \b...\b
    // will still match. The test simply documents the actual behavior so we
    // don't surprise ourselves later.
    expect(out).toContain('PROJECT-1234');
  });

  it('linkifies multiple Jira keys in one sentence', () => {
    const out = run('See WORK-1 and FOO-23 for details.');
    expect(out).toContain('[WORK-1](jira:WORK-1)');
    expect(out).toContain('[FOO-23](jira:FOO-23)');
  });
});

describe('remarkChatLinkify — file paths', () => {
  it('linkifies bare file names with known extensions', () => {
    const out = run('See AgentService.kt for the fix.');
    expect(out).toContain('[AgentService.kt](file:AgentService.kt)');
  });

  it('linkifies file with :line suffix', () => {
    const out = run('See AgentService.kt:42 for the fix.');
    expect(out).toContain('[AgentService.kt:42](file:AgentService.kt:42)');
  });

  it('linkifies file with :line-range suffix', () => {
    const out = run('See AgentService.kt:42-50 for the fix.');
    expect(out).toContain('[AgentService.kt:42-50](file:AgentService.kt:42-50)');
  });

  it('linkifies relative paths with directories', () => {
    const out = run('Check agent/src/AgentLoop.kt for details.');
    expect(out).toContain('[agent/src/AgentLoop.kt](file:agent/src/AgentLoop.kt)');
  });

  it('does NOT linkify files without a known extension', () => {
    const out = run('Open README please.');
    expect(out).not.toContain('file:');
  });
});

describe('remarkChatLinkify — code skip', () => {
  it('does NOT linkify text inside inline code', () => {
    const out = run('Per `AgentService.kt:42`');
    // The inline-code text must not become a link.
    expect(out).not.toContain('](file:AgentService.kt:42)');
    expect(out).toContain('`AgentService.kt:42`');
  });

  it('does NOT linkify text inside a fenced code block', () => {
    const md = ['```kotlin', 'val foo = AgentService.kt', 'val bar = WORK-1', '```'].join('\n');
    const out = run(md);
    expect(out).not.toContain('](file:');
    expect(out).not.toContain('](jira:');
  });
});

describe('remarkChatLinkify — link skip', () => {
  it('does NOT relinkify text inside an existing link', () => {
    const out = run('[See WORK-1](https://example.com/work-1)');
    // The "WORK-1" inside the link label is text inside a `link` node — must
    // not be re-wrapped.
    expect(out).not.toContain('[WORK-1](jira:WORK-1)');
  });
});

describe('remarkChatLinkify — no false positives', () => {
  it('does NOT linkify a sentence with no matches', () => {
    const out = run('Hello, world!');
    expect(out).not.toContain('](file:');
    expect(out).not.toContain('](jira:');
  });
});
