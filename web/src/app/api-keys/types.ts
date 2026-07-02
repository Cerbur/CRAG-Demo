/**
 * Local lightweight KB summary used by the API Key index aggregation. We do
 * NOT import the full KnowledgeBase type from features/knowledge (that would
 * be a cross-feature import, forbidden by the architecture test); we only need
 * id + name for backlinks.
 */
export interface KnowledgeBaseLite {
  readonly id: string;
  readonly name: string;
}
