import { memo } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

export const MarkdownReply = memo(function MarkdownReply({ content }: { content: string }) {
  return <div className="markdown">
    <ReactMarkdown remarkPlugins={[remarkGfm]} components={{
      a({ href, children }) {
        if (!href || !/^https?:\/\//i.test(href)) return <span>{children}</span>
        return <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
      },
      img({ alt }) { return <span>{alt}</span> },
    }}>{content}</ReactMarkdown>
  </div>
})
