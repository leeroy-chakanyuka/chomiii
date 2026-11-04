import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, it } from 'vitest'
import { MarkdownReply } from './MarkdownReply'

afterEach(cleanup)

it('formats model Markdown as text, lists, code, and tables without executing HTML', () => {
  const { container } = render(<MarkdownReply content={'**A helpful answer**\n\n- one\n- two\n\n'
    + '```java\nSystem.out.println("hi");\n```\n\n| Name | Value |\n| --- | --- |\n| Chomi | 4 |\n\n'
    + '<script>window.bad = true</script>\n\n[unsafe](javascript:alert(1))\n\n![tracker](https://example.org/pixel)'} />)

  expect(screen.getByText('A helpful answer').tagName).toBe('STRONG')
  expect(screen.getByRole('list').querySelectorAll('li')).toHaveLength(2)
  expect(container.querySelector('pre code')).toHaveTextContent('System.out.println("hi");')
  expect(screen.getByRole('table')).toHaveTextContent('Chomi')
  expect(container.querySelector('script')).toBeNull()
  expect(container.querySelector('a[href^="javascript:"]')).toBeNull()
  expect(container.querySelector('img')).toBeNull()
})
