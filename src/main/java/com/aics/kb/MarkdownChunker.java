package com.aics.kb;

import com.aics.common.AicsProperties;
import com.aics.common.TokenEstimator;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 结构化切片（技术设计 §3.2 / D4）：按 Markdown 标题层级切，而不是定长窗口。
 *
 * <p>理由：引用溯源要定位到「第 2.3 节」，定长切分会在章节中间断开，既丢结构也丢语义。
 *
 * <p>后处理规则：
 * <ol>
 *   <li>body 为空 → 丢弃（纯标题节点）</li>
 *   <li>body &lt; minChars → 与下一个兄弟节点合并，合并后不超过 maxChars</li>
 *   <li>body &gt; maxChars → 按空行分段聚合；单段仍超长则按句末标点二次切分，窗口间保留 overlapChars</li>
 *   <li>表格与代码块是原子单元，不拆分；单个原子单元超 maxChars 时整块保留并标记 oversized</li>
 * </ol>
 */
@Component
public class MarkdownChunker {

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})[ \\t]+(.*)$");
    private static final Pattern FENCE = Pattern.compile("^(```|~~~).*$");
    private static final Pattern TABLE_LINE = Pattern.compile("^\\s*\\|.*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？；!?;])");

    private final int maxChars;
    private final int minChars;
    private final int overlapChars;
    private final int maxHeadingLevel;

    public MarkdownChunker(AicsProperties props) {
        this.maxChars = props.getChunking().getMaxChars();
        this.minChars = props.getChunking().getMinChars();
        this.overlapChars = props.getChunking().getOverlapChars();
        this.maxHeadingLevel = props.getChunking().getMaxHeadingLevel();
    }

    public MarkdownChunker(int maxChars, int minChars, int overlapChars, int maxHeadingLevel) {
        this.maxChars = maxChars;
        this.minChars = minChars;
        this.overlapChars = overlapChars;
        this.maxHeadingLevel = maxHeadingLevel;
    }

    public record Chunk(
            int index,
            String headingPath,
            String headingText,
            int headingLevel,
            String content,
            int charStart,
            int charEnd,
            boolean oversized) {
    }

    /** 内部中间结构：一个标题节点（含正文与原始位置）。 */
    private static final class Section {
        String headingText = "";
        String headingLine = "";
        int level;
        String path = "";
        String parentPath = "";
        int start;
        int end;
        final StringBuilder body = new StringBuilder();
        boolean fromTitle;

        String bodyText() {
            return body.toString().strip();
        }
    }

    public List<Chunk> chunk(String markdown) {
        String text = normalize(markdown == null ? "" : markdown);
        List<Section> sections = parse(text);
        List<Section> merged = mergeShortSections(sections);

        List<Chunk> chunks = new ArrayList<>();
        for (Section s : merged) {
            String body = s.bodyText();
            if (body.isEmpty()) {
                continue;
            }
            if (body.length() <= maxChars) {
                chunks.add(new Chunk(chunks.size(), s.path, s.headingText, s.level, body, s.start, s.end, false));
            } else {
                chunks.addAll(splitLongSection(s, body, chunks.size()));
            }
        }
        return fixOffsets(text, chunks);
    }

    /**
     * 回填 charStart/charEnd：用内容在原文中的真实位置，保证「引用回看」能定位到具体段落。
     * 合并与二次切分都会改变内容，因此统一在这里按出现顺序校准。
     */
    private List<Chunk> fixOffsets(String text, List<Chunk> chunks) {
        List<Chunk> out = new ArrayList<>(chunks.size());
        int cursor = 0;
        for (Chunk c : chunks) {
            int from = text.indexOf(c.content(), Math.max(0, cursor - overlapChars - 1));
            if (from < 0) {
                from = text.indexOf(c.content());
            }
            if (from < 0) {
                out.add(c);
                continue;
            }
            int to = Math.min(text.length(), from + c.content().length());
            cursor = to;
            out.add(new Chunk(c.index(), c.headingPath(), c.headingText(), c.headingLevel(), c.content(),
                    from, to, c.oversized()));
        }
        return out;
    }

    // ---------------------------------------------------------------- 归一化

    private String normalize(String md) {
        String text = md.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder sb = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            // 去除行尾空白；统一围栏为 ```
            String trimmed = stripTrailing(line);
            if (trimmed.startsWith("~~~")) {
                trimmed = "```" + trimmed.substring(3);
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    // ---------------------------------------------------------------- 解析标题树

    private List<Section> parse(String text) {
        List<Section> sections = new ArrayList<>();
        List<int[]> stackLevels = new ArrayList<>();
        List<String> stackTexts = new ArrayList<>();

        Section current = null;
        boolean inFence = false;
        boolean firstHeadingSeen = false;

        int offset = 0;
        String[] lines = text.split("\n", -1);
        for (String line : lines) {
            int lineStart = offset;
            offset += line.length() + 1;

            if (FENCE.matcher(line).matches()) {
                inFence = !inFence;
                if (current == null) {
                    current = newSection("", "", 0, "", lineStart);
                    sections.add(current);
                }
                current.body.append(line).append('\n');
                current.end = offset;
                continue;
            }

            Matcher m = inFence ? null : HEADING.matcher(line);
            if (m != null && m.matches()) {
                int level = m.group(1).length();
                String headingText = m.group(2).strip();

                if (level <= maxHeadingLevel) {
                    if (current != null) {
                        current.end = lineStart;
                    }
                    boolean isDocTitle = !firstHeadingSeen && level == 1;
                    firstHeadingSeen = true;

                    if (isDocTitle) {
                        // 文档标题（首个 H1）不进 headingPath，路径从次一级标题开始
                        stackLevels.clear();
                        stackTexts.clear();
                    } else {
                        while (!stackLevels.isEmpty() && stackLevels.getLast()[0] >= level) {
                            stackLevels.removeLast();
                            stackTexts.removeLast();
                        }
                    }

                    String parentPath = String.join(" > ", stackTexts);
                    String path = isDocTitle
                            ? ""
                            : (parentPath.isEmpty() ? headingText : parentPath + " > " + headingText);

                    Section s = newSection(headingText, line, level, path, lineStart);
                    s.parentPath = parentPath;
                    s.fromTitle = isDocTitle;
                    current = s;
                    sections.add(s);

                    if (!isDocTitle) {
                        stackLevels.add(new int[]{level});
                        stackTexts.add(headingText);
                    }
                    continue;
                }
                // H4+ 视为正文，继续留在当前 section
            }

            if (current == null) {
                current = newSection("", "", 0, "", lineStart);
                sections.add(current);
            }
            current.body.append(line).append('\n');
            current.end = offset;
        }

        for (Section s : sections) {
            if (s.end < s.start) {
                s.end = s.start;
            }
        }
        return sections;
    }

    private Section newSection(String headingText, String headingLine, int level, String path, int start) {
        Section s = new Section();
        s.headingText = headingText;
        s.headingLine = headingLine;
        s.level = level;
        s.path = path;
        s.start = start;
        s.end = start;
        return s;
    }

    // ---------------------------------------------------------------- 短段合并

    private List<Section> mergeShortSections(List<Section> sections) {
        List<Section> out = new ArrayList<>();
        for (Section s : sections) {
            String body = s.bodyText();
            if (body.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                Section prev = out.getLast();
                String prevBody = prev.bodyText();
                boolean sameParent = prev.parentPath.equals(s.parentPath);
                boolean prevShort = prevBody.length() < minChars;
                int combined = prevBody.length() + body.length() + (s.headingLine.isEmpty() ? 2 : s.headingLine.length() + 3);
                if (prevShort && sameParent && combined <= maxChars) {
                    // 合并进上一个兄弟节点：正文拼接，来源标题按原文保留
                    if (!s.headingLine.isEmpty()) {
                        prev.body.append("\n\n").append(s.headingLine).append('\n');
                    } else {
                        prev.body.append("\n\n");
                    }
                    prev.body.append(s.body);
                    prev.end = Math.max(prev.end, s.end);
                    continue;
                }
            }
            out.add(s);
        }
        return out;
    }

    // ---------------------------------------------------------------- 超长切分

    private List<Chunk> splitLongSection(Section s, String body, int startIndex) {
        List<Chunk> chunks = new ArrayList<>();
        for (Window w : packWindows(body)) {
            chunks.add(new Chunk(startIndex + chunks.size(), s.path, s.headingText, s.level,
                    w.text, s.start, s.end, w.oversized));
        }
        if (chunks.isEmpty()) {
            chunks.add(new Chunk(startIndex, s.path, s.headingText, s.level, body, s.start, s.end, true));
        }
        return chunks;
    }

    private record Window(String text, boolean oversized) {
    }

    private record Block(String text, boolean atomic) {
    }

    private List<Window> packWindows(String body) {
        List<Block> blocks = parseBlocks(body);
        List<Window> windows = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean currentOversized = false;

        for (Block block : blocks) {
            boolean tooLongAlone = block.text.length() > maxChars;
            if (block.atomic && tooLongAlone) {
                // 表格/代码块整块保留，绝不截断
                if (current.length() > 0) {
                    windows.add(new Window(current.toString().strip(), currentOversized));
                    current = new StringBuilder();
                    currentOversized = false;
                }
                windows.add(new Window(block.text.strip(), true));
                continue;
            }
            if (!block.atomic && tooLongAlone) {
                if (current.length() > 0) {
                    windows.add(new Window(current.toString().strip(), currentOversized));
                    current = new StringBuilder();
                    currentOversized = false;
                }
                for (String piece : splitParagraph(block.text)) {
                    windows.add(new Window(piece.strip(), false));
                }
                continue;
            }
            int extra = current.length() == 0 ? 0 : 2;
            if (current.length() + block.text.length() + extra > maxChars) {
                windows.add(new Window(current.toString().strip(), currentOversized));
                current = new StringBuilder();
                currentOversized = false;
            }
            if (current.length() > 0) {
                current.append("\n\n");
            }
            current.append(block.text);
            currentOversized |= block.atomic && tooLongAlone;
        }
        if (current.length() > 0) {
            windows.add(new Window(current.toString().strip(), currentOversized));
        }
        return windows;
    }

    /** 拆块：代码围栏与表格视为原子单元，其余按空行分段。 */
    private List<Block> parseBlocks(String body) {
        List<Block> blocks = new ArrayList<>();
        String[] lines = body.split("\n", -1);

        int i = 0;
        StringBuilder para = new StringBuilder();
        boolean inFence = false;
        StringBuilder fence = new StringBuilder();

        while (i < lines.length) {
            String line = lines[i];

            if (!inFence && FENCE.matcher(line).matches()) {
                flushParagraph(blocks, para);
                inFence = true;
                fence.append(line).append('\n');
                i++;
                continue;
            }
            if (inFence) {
                fence.append(line).append('\n');
                if (FENCE.matcher(line).matches()) {
                    inFence = false;
                    blocks.add(new Block(stripTrailing(fence.toString()), true));
                    fence = new StringBuilder();
                }
                i++;
                continue;
            }
            if (TABLE_LINE.matcher(line).matches()) {
                flushParagraph(blocks, para);
                StringBuilder table = new StringBuilder();
                while (i < lines.length && TABLE_LINE.matcher(lines[i]).matches()) {
                    table.append(lines[i]).append('\n');
                    i++;
                }
                blocks.add(new Block(stripTrailing(table.toString()), true));
                continue;
            }
            if (line.isBlank()) {
                flushParagraph(blocks, para);
                i++;
                continue;
            }
            para.append(line).append('\n');
            i++;
        }
        flushParagraph(blocks, para);
        if (fence.length() > 0) {
            // 未闭合的围栏：按原子块保留，避免内容被截断
            blocks.add(new Block(stripTrailing(fence.toString()), true));
        }
        return blocks;
    }

    private void flushParagraph(List<Block> blocks, StringBuilder para) {
        String text = para.toString().strip();
        if (!text.isEmpty()) {
            blocks.add(new Block(text, false));
        }
        para.setLength(0);
    }

    private List<String> splitParagraph(String para) {
        List<String> sentences = new ArrayList<>();
        for (String part : SENTENCE_END.split(para)) {
            if (!part.isBlank()) {
                sentences.add(part);
            }
        }
        if (sentences.isEmpty()) {
            sentences.add(para);
        }

        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String sentence : sentences) {
            if (sentence.length() > maxChars) {
                // 单句超长：硬切并保留重叠
                if (current.length() > 0) {
                    out.add(current.toString());
                    current = new StringBuilder();
                }
                int from = 0;
                while (from < sentence.length()) {
                    int to = Math.min(sentence.length(), from + maxChars);
                    out.add(sentence.substring(from, to));
                    if (to >= sentence.length()) {
                        break;
                    }
                    from = Math.max(from + 1, to - overlapChars);
                }
                continue;
            }
            if (current.length() + sentence.length() > maxChars && current.length() > 0) {
                out.add(current.toString());
                String tail = current.substring(Math.max(0, current.length() - overlapChars));
                current = new StringBuilder(tail);
            }
            current.append(sentence);
        }
        if (!current.isEmpty()) {
            out.add(current.toString());
        }
        return out;
    }

    /** 供入库侧估算 token 用。 */
    public static int tokenCount(String content) {
        return TokenEstimator.estimate(content);
    }
}
