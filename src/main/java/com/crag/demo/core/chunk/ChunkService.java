package com.crag.demo.core.chunk;

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
 * 文档分块服务 —— 基于 Spring AI TokenTextSplitter 将文本拆分为 child chunk + parent chunk.
 *
 * 分块策略：
 * - parent chunk：大窗口（~1024 token），保留完整上下文，不做向量化
 * - child chunk：小粒度（~256 token），用于 Embedding + 检索匹配
 * - child chunk 之间有 overlap（~64 token），减少边界截断损失
 *
 * Token 计数使用 JTokkit CL100K_BASE 编码，与 DeepSeek / GPT-4 系列 tokenizer 一致.
 * 分块边界由 TokenTextSplitter 按句末标点（.?!\n）自动选择，语义完整性优于固定窗口.
 *
 * @since 2026-06-10
 */
@Component
public class ChunkService {

    private static final Logger log = LoggerFactory.getLogger(ChunkService.class);

    /** Child chunk 目标 token 数. */
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

    public ChunkService() {
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
     * 将文本拆分为 child + parent chunks.
     *
     * Parent 级使用 TokenTextSplitter(chunkSize=1024) 切分，
     * 取第一个 parent chunk；在其内部使用 TokenTextSplitter(chunkSize=256) 切分 child，
     * child 之间通过 JTokkit 添加 ~64 token 重叠.
     *
     * @param content 原始纯文本
     * @return ChunkResult 含 1 个 parent + N 个 child（N >= 0）
     */
    public ChunkResult split(String content) {
        if (content == null || content.isEmpty()) {
            log.debug("ChunkService.split called with empty content, returning empty result");
            return new ChunkResult(
                new ChunkData("", 0, null),
                Collections.emptyList()
            );
        }

        // Step 1: Parent 级分块 —— TokenTextSplitter 按 1024 token 切分，取第一个 parent
        List<Document> parentDocs = parentSplitter.split(new Document(content));
        Document firstParent = parentDocs.isEmpty()
            ? new Document(content)
            : parentDocs.get(0);
        String parentContent = firstParent.getText();
        int parentTokenCount = encoding.countTokens(parentContent);

        if (parentDocs.size() > 1) {
            log.debug("Document split into {} parent chunks, using first ({} tokens)",
                parentDocs.size(), parentTokenCount);
        }

        // Step 2: Child 级分块 —— 在 parent 内部按 256 token 切分
        List<Document> childDocs = childSplitter.split(new Document(parentContent));
        log.debug("Parent ({} tokens) → {} child chunks", parentTokenCount, childDocs.size());

        // Step 3: 为 child 之间添加 overlap
        List<ChunkData> children = new ArrayList<>();
        for (int i = 0; i < childDocs.size(); i++) {
            String childContent = childDocs.get(i).getText();

            // 为第 2 个及之后的 child 前置前一个 child 的尾部 overlap
            if (i > 0) {
                String prevContent = childDocs.get(i - 1).getText();
                String overlapText = extractLastTokens(prevContent, OVERLAP);
                if (!overlapText.isEmpty()) {
                    childContent = overlapText + "\n" + childContent;
                }
            }

            int tokenCount = encoding.countTokens(childContent);
            children.add(new ChunkData(childContent, tokenCount, i));
        }

        // Step 4: 构造 parent（chunkIndex = null，不做向量化）
        ChunkData parent = new ChunkData(parentContent, parentTokenCount, null);
        return new ChunkResult(parent, children);
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
