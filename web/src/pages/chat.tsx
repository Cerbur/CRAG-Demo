import type { JSX } from 'react';
import { ChatView } from '@features/chat/components/chat-view';
import { useChat } from '@app/chat/use-chat';

/**
 * Chat page (/app/chat).
 *
 * An independent knowledge-retrieval chat. The user enters a temporary API Key
 * inline (held only in page memory — never persisted), asks a question, and the
 * answer + sources render in the transcript. There is no chat history
 * persistence, no KB/model selector, and no streaming (plan_22 §22.7
 * non-goals). Refreshing the page clears both the key and the transcript.
 *
 * Mounted under the protected /app/* surface (Console login required), but the
 * chat feature itself uses the Open API + a user-entered API Key, which is
 * independent of the Console session (api-client.md §3).
 */
export function ChatPage(): JSX.Element {
  const viewModel = useChat();
  return <ChatView viewModel={viewModel} />;
}
