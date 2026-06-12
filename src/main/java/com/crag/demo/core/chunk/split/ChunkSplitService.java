package com.crag.demo.core.chunk.split;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import com.knuddels.jtokkit.api.IntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 文档分块服务 —— 基于 Spring AI TokenTextSplitter 将文本拆分为 parent groups + child chunks.
 *
 * 分块策略：
 * - parent chunk：大窗口（~1024 token），保留完整上下文，不做向量化
 * - child chunk：小粒度（~256 token 净新增），用于 Embedding + 检索匹配
 * - child chunk 之间有 overlap（~64 token），实际单 chunk 上限 ~320 token
 *
 * Token 计数使用 JTokkit CL100K_BASE 编码，与 DeepSeek / GPT-4 系列 tokenizer 一致.
 * 分块边界由 TokenTextSplitter 按句末标点（.?!\n）自动选择，语义完整性优于固定窗口.
 *
 * @since 2026-06-10
 */
@Component
public class ChunkSplitService {

    private static final Logger log = LoggerFactory.getLogger(ChunkSplitService.class);

    /** Child chunk 净新增目标 token 数（不含 overlap，实际上限 ~320 token）. */
    private static final int CHILD_SIZE = 256;

    /** Parent chunk 目标 token 数. */
    private static final int PARENT_SIZE = 1024;

    /** Child chunk 间重叠 token 数. */
    private static final int OVERLAP = 64;

    /** TokenTextSplitter 最小 chunk 字符数（过短的 chunk 会被丢弃）. */
    private static final int MIN_CHUNK_SIZE_CHARS = 50;

    /**
     * TokenTextSplitter 最小可嵌入长度.
     * 设为 0 确保任何非空文本都不会被 TokenTextSplitter 静默丢弃.
     * 默认值 5 会导致 <= 5 字符的 chunk 丢失.
     * 注意：TokenTextSplitter 使用严格大于（>）比较，0 即 length > 0 通过.
     */
    private static final int MIN_CHUNK_LENGTH_TO_EMBED = 0;

    /** CL100K_BASE 编码器，与 DeepSeek / GPT-4 系列 tokenizer 等价. */
    private final Encoding encoding;

    /** Parent 级分块器：按 1024 token 切分原始文档. */
    private final TokenTextSplitter parentSplitter;

    /** Child 级分块器：按 256 token 切分 parent 内容. */
    private final TokenTextSplitter childSplitter;

    public ChunkSplitService() {
        EncodingRegistry registry = Encodings.newLazyEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);

        this.parentSplitter = TokenTextSplitter.builder()
            .withChunkSize(PARENT_SIZE)
            .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
            .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
            .withKeepSeparator(true)
            .build();

        this.childSplitter = TokenTextSplitter.builder()
            .withChunkSize(CHILD_SIZE)
            .withMinChunkSizeChars(MIN_CHUNK_SIZE_CHARS)
            .withMinChunkLengthToEmbed(MIN_CHUNK_LENGTH_TO_EMBED)
            .withKeepSeparator(true)
            .build();
    }

    /**
     * 将文本拆分为 parent groups + child chunks.
     *
     * Parent 级使用 TokenTextSplitter(chunkSize=1024) 切分全部 parent chunks；
     * 在每个 parent 内部使用 TokenTextSplitter(chunkSize=256) 切分 child，
     * child 之间通过 JTokkit 添加 ~64 token 重叠.
     *
     * @param content 原始纯文本
     * @return ChunkSplitResult 含 N 个 parent group，每个 group 下有若干 child
     */
    public ChunkSplitResult split(String content) {
        if (content == null || content.isEmpty()) {
            log.debug("ChunkSplitService.split called with empty content, returning empty result");
            return new ChunkSplitResult(
                new ChunkSplitData("", 0, null),
                Collections.emptyList()
            );
        }

        // Step 1: Parent 级分块 —— 先使用 TokenTextSplitter，再用 JTokkit 兜底强制 token 上限
        List<String> parentContents = splitWithTokenLimit(content, PARENT_SIZE);

        List<ChunkSplitGroup> groups = new ArrayList<>();
        log.debug("Document split into {} parent chunks", parentContents.size());

        for (int groupIdx = 0; groupIdx < parentContents.size(); groupIdx++) {
            String parentContent = parentContents.get(groupIdx);
            int parentTokenCount = encoding.countTokens(parentContent);

            // Step 2: Child 级分块 —— 在 parent 内部按 256 token 切分，并兜底强制 token 上限
            List<String> childContents = splitWithTokenLimit(parentContent, CHILD_SIZE);
            log.debug("Parent[{}] ({} tokens) → {} child chunks", groupIdx, parentTokenCount, childContents.size());

            // Step 3: 为同一 parent 内的 child 之间添加 overlap
            List<ChunkSplitData> children = new ArrayList<>();
            for (int i = 0; i < childContents.size(); i++) {
                String childContent = childContents.get(i);

                // 为第 2 个及之后的 child 前置前一个 child 的尾部 overlap
                if (i > 0) {
                    String prevContent = childContents.get(i - 1);
                    String overlapText = extractLastTokens(prevContent, OVERLAP);
                    if (!overlapText.isEmpty()) {
                        childContent = overlapText + "\n" + childContent;
                    }
                }

                int tokenCount = encoding.countTokens(childContent);
                children.add(new ChunkSplitData(childContent, tokenCount, i));
            }

            // Step 4: 构造 parent（chunkIndex = groupIdx，不做向量化；保留 index 用于 RAG 上下文窗口扩展）
            ChunkSplitData parent = new ChunkSplitData(parentContent, parentTokenCount, groupIdx);
            groups.add(new ChunkSplitGroup(parent, children));
        }

        return new ChunkSplitResult(groups);
    }

    /**
     * 先用 TokenTextSplitter 做语义边界切分，再对过长片段用 tokenizer 强制切分.
     */
    private List<String> splitWithTokenLimit(String text, int maxTokens) {
        TokenTextSplitter splitter = (maxTokens == PARENT_SIZE) ? parentSplitter : childSplitter;
        List<Document> docs = splitter.split(new Document(text));
        if (docs.isEmpty()) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        for (Document doc : docs) {
            chunks.addAll(splitByTokenLimit(doc.getText(), maxTokens));
        }
        return chunks;
    }

    /**
     * 按 tokenizer 精确 token 数切分，作为 TokenTextSplitter 未触发时的硬上限兜底.
     */
    private List<String> splitByTokenLimit(String text, int maxTokens) {
        if (text == null || text.isEmpty()) {
            return Collections.emptyList();
        }
        IntArrayList tokens = encoding.encode(text);
        if (tokens.size() <= maxTokens) {
            return List.of(text);
        }

        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < tokens.size(); start += maxTokens) {
            int end = Math.min(start + maxTokens, tokens.size());
            IntArrayList chunkTokens = new IntArrayList(end - start);
            for (int i = start; i < end; i++) {
                chunkTokens.add(tokens.get(i));
            }
            String chunk = encoding.decode(chunkTokens);
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
        }
        return chunks;
    }

    /**
     * 提取文本末尾的指定数量 token 对应的原始文本.
     *
     * 用于 child chunk 之间的 overlap：取前一个 child 的最后 N 个 token，
     * 拼接到下一个 child 开头，防止语义边界截断.
     *
     * @param text       输入文本
     * @param tokenCount 要提取的 token 数量
     * @return 末尾 token 对应的原始文本，输入为空时返回空字符串
     */
    private String extractLastTokens(String text, int tokenCount) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        IntArrayList tokens = encoding.encode(text);
        int size = tokens.size();
        if (size <= tokenCount) {
            return text;
        }

        // 取最后 tokenCount 个 token，解码回文本
        IntArrayList overlapTokens = new IntArrayList(tokenCount);
        for (int i = size - tokenCount; i < size; i++) {
            overlapTokens.add(tokens.get(i));
        }
        return encoding.decode(overlapTokens);
    }
}
