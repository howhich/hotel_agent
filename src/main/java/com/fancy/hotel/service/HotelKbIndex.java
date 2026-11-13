package com.fancy.hotel.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fancy.hotel.mapper.HotelKbMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 酒店知识库检索（P5.3）—— ES 承载，MySQL 是真相
 *
 * <h3>为什么直接用 JDK 的 HttpClient，不引入 ES 官方客户端</h3>
 * 我们只用到三个 API：建索引、bulk 写入、search。为一个 HTTP 调用引入
 * 一个 10MB+ 的客户端依赖（还要跟着 ES 版本升级）不划算，而且它会把
 * "索引长什么样"藏进注解里 —— 而本项目里 mapping 是要能一眼看懂、能手工重建的。
 *
 * <h3>为什么不装 IK 分词插件</h3>
 * 试过了：容器内拉 GitHub 超时。改用 ES 内置的 ngram analyzer（二元切分），
 * 实测对中文的三件事都正常：正文检索、引用高亮、错别字的 fuzziness 匹配。
 * 对"几十份酒店条款"这个规模，IK 带来的差别肉眼不可见。
 *
 * <h3>★ 索引是可重建的派生数据</h3>
 * 真相在 {@code hotel_kb_doc}。索引坏了、mapping 要改，调一次 {@link #rebuild()} 就行 ——
 * 和库存缓存、GEO 索引的处理方式完全一致（箭头只能从真相指向派生）。
 */
@Slf4j
@Service
public class HotelKbIndex {

    /** 名称匹配的最低分：低于这个分数就当"没认出来"，宁可反问也不要乱认 */
    private static final double NAME_MATCH_MIN_SCORE = 0.3;

    /**
     * 检索的最低相关度
     *
     * ★ 没有这个阈值，"拒答"就形同虚设：
     *   ngram 检索几乎总能召回点什么（只要有一个二字切片相同），
     *   于是"这家有室内恒温泳池吗"也会拿回一堆停车/宠物的段落。
     *   分数低于这个值的一律丢掉，让上层走到"查不到"的分支。
     *   实测：真正相关的段落 7~9 分，不相关的 1~2 分，所以 2.5 是个安全的界线。
     */
    private static final double MIN_RELEVANCE = 2.5;

    private final HotelKbMapper kbMapper;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String indexName;
    private final HttpClient http;

    public HotelKbIndex(HotelKbMapper kbMapper,
                        ObjectMapper objectMapper,
                        @Value("${hotel.es.base-url:http://127.0.0.1:9200}") String baseUrl,
                        @Value("${hotel.es.index:hotel_kb_v1}") String indexName,
                        @Value("${hotel.es.timeout-seconds:10}") int timeoutSeconds) {
        this.kbMapper = kbMapper;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.indexName = indexName;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.timeout = Duration.ofSeconds(Math.max(3, timeoutSeconds));
    }

    private final Duration timeout;

    // =========================================================================
    // 建索引
    // =========================================================================

    /**
     * 从 MySQL 全量重建索引
     *
     * @return 写入的文档数
     */
    public int rebuild() {
        List<Map<String, Object>> docs = kbMapper.selectAllDocs();
        List<Map<String, Object>> hotels = kbMapper.selectHotelNames();

        // 重建 = 删了重来。索引名带版本，真要保留旧版就换名字
        send("DELETE", "/" + indexName, null);
        String created = send("PUT", "/" + indexName, mapping());
        if (created == null) {
            throw new IllegalStateException("创建 ES 索引失败: " + indexName);
        }

        // bulk 要求最后一行也以换行结尾，否则报 "must be terminated by a newline"
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map<String, Object> d : docs) {
            sb.append("{\"index\":{\"_index\":\"").append(indexName).append("\"}}\n");
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("docType", "doc");
            src.put("hotelId", num(d.get("hotelId")));
            src.put("hotelName", hotelNameOf(hotels, num(d.get("hotelId"))));
            src.put("section", str(d.get("section")));
            src.put("content", str(d.get("content")));
            sb.append(toJson(src)).append('\n');
            count++;
        }
        for (Map<String, Object> h : hotels) {
            sb.append("{\"index\":{\"_index\":\"").append(indexName).append("\"}}\n");
            Map<String, Object> src = new LinkedHashMap<>();
            src.put("docType", "hotel_name");
            src.put("hotelId", num(h.get("hotelId")));
            src.put("hotelName", str(h.get("hotelName")));
            src.put("section", "");
            src.put("content", "");
            sb.append(toJson(src)).append('\n');
            count++;
        }

        String resp = sendRaw("POST", "/" + indexName + "/_bulk?refresh=true", sb.toString(),
                "application/x-ndjson");
        if (resp == null || resp.contains("\"errors\":true")) {
            log.warn("bulk 写入有错误: {}", brief(resp));
        }
        log.info("知识库索引已重建: index={}, 原文 {} 份, 酒店名 {} 个, 共 {} 条文档",
                indexName, docs.size(), hotels.size(), count);
        return count;
    }

    /** 索引里有多少条 */
    public long count() {
        String resp = send("GET", "/" + indexName + "/_count", null);
        if (resp == null) {
            return -1;
        }
        try {
            return objectMapper.readTree(resp).path("count").asLong(-1);
        } catch (Exception e) {
            return -1;
        }
    }

    // =========================================================================
    // 检索
    // =========================================================================

    /** 一条检索结果（带引用来源） */
    public record KbHit(Long hotelId, String hotelName, String section, String snippet, double score) {
    }

    /**
     * 检索能回答这个问题的原文片段
     *
     * @param hotelId 可选：限定某家酒店（用户说了酒店名就传，避免跨酒店串答案）
     */
    public List<KbHit> retrieve(String question, Long hotelId, int size) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Map<String, Object> multiMatch = new LinkedHashMap<>();
        multiMatch.put("query", question);
        multiMatch.put("fields", List.of("content^2", "section"));
        Map<String, Object> must = Map.of("multi_match", multiMatch);

        Map<String, Object> bool = new LinkedHashMap<>();
        bool.put("filter", hotelId == null
                ? List.of(Map.of("term", Map.of("docType", "doc")))
                : List.of(Map.of("term", Map.of("docType", "doc")),
                         Map.of("term", Map.of("hotelId", hotelId))));
        bool.put("must", List.of(must));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", Math.max(1, Math.min(size, 10)));
        body.put("query", Map.of("bool", bool));
        body.put("highlight", Map.of("fields", Map.of("content", Map.of())));

        String resp = send("POST", "/" + indexName + "/_search", body);   // ★ 传对象，不要传已序列化的字符串（否则会双重编码）
        List<KbHit> out = new ArrayList<>();
        if (resp == null) {
            return out;
        }
        try {
            JsonNode hits = objectMapper.readTree(resp).path("hits").path("hits");
            for (JsonNode h : hits) {
                JsonNode src = h.path("_source");
                JsonNode hl = h.path("highlight").path("content");
                String snippet = hl.isArray() && !hl.isEmpty()
                        ? hl.get(0).asText() : src.path("content").asText("");
                double score = h.path("_score").asDouble(0);
                if (score < MIN_RELEVANCE) {
                    continue;       // 不相关就丢掉，让上层去"拒答"，而不是拿它凑数
                }
                out.add(new KbHit(
                        src.path("hotelId").asLong(),
                        src.path("hotelName").asText(""),
                        src.path("section").asText(""),
                        snippet,
                        score));
            }
        } catch (Exception e) {
            log.warn("解析检索结果失败: {}", e.getMessage());
        }
        log.debug("检索[{}] 命中 {} 条（阈值 {}）", question, out.size(), MIN_RELEVANCE);
        return out;
    }

    /**
     * 按酒店名（可带错别字）找 hotelId
     *
     * <p>{@code fuzziness=AUTO} 在中文 ngram 上实测有效：
     * "西部国宾馆"（错一个字）也能匹配到"杭州西湖国宾馆"，只是分数减半 ——
     * 所以必须用分数阈值兜住，否则容易乱认。
     *
     * @return 认不出来返回 null（调用方应当反问用户，而不是瞎猜一个）
     */
    public Long matchHotelId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Map<String, Object> match = new LinkedHashMap<>();
        match.put("query", name);
        match.put("fuzziness", "AUTO");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("size", 1);
        body.put("query", Map.of("bool", Map.of(
                "filter", List.of(Map.of("term", Map.of("docType", "hotel_name"))),
                "must", List.of(Map.of("match", Map.of("hotelName", match))))));

        String resp = send("POST", "/" + indexName + "/_search", body);   // ★ 传对象，不要传已序列化的字符串（否则会双重编码）
        if (resp == null) {
            return null;
        }
        try {
            JsonNode hits = objectMapper.readTree(resp).path("hits").path("hits");
            if (!hits.isArray() || hits.isEmpty()) {
                return null;
            }
            double score = hits.get(0).path("_score").asDouble(0);
            if (score < NAME_MATCH_MIN_SCORE) {
                log.debug("名称匹配分数过低({})，当作没认出来: {}", score, name);
                return null;
            }
            return hits.get(0).path("_source").path("hotelId").asLong();
        } catch (Exception e) {
            log.warn("解析名称匹配结果失败: {}", e.getMessage());
            return null;
        }
    }

    // =========================================================================
    // HTTP 与工具
    // =========================================================================

    /** ES 的 mapping：内置 ngram（二元切分），不依赖任何插件 */
    private Map<String, Object> mapping() {
        Map<String, Object> ngramTok = new LinkedHashMap<>();
        ngramTok.put("type", "ngram");
        ngramTok.put("min_gram", 2);
        ngramTok.put("max_gram", 2);
        ngramTok.put("token_chars", List.of("letter", "digit"));

        Map<String, Object> analyzer = new LinkedHashMap<>();
        analyzer.put("type", "custom");
        analyzer.put("tokenizer", "cn_ngram_tok");
        analyzer.put("filter", List.of("lowercase"));

        Map<String, Object> settings = Map.of("analysis", Map.of(
                "tokenizer", Map.of("cn_ngram_tok", ngramTok),
                "analyzer", Map.of("cn_ngram", analyzer)));

        Map<String, Object> props = new LinkedHashMap<>();
        props.put("docType", Map.of("type", "keyword"));
        props.put("hotelId", Map.of("type", "long"));
        props.put("hotelName", Map.of("type", "text", "analyzer", "cn_ngram",
                "fields", Map.of("kw", Map.of("type", "keyword"))));
        props.put("section", Map.of("type", "keyword"));
        props.put("content", Map.of("type", "text", "analyzer", "cn_ngram"));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("settings", settings);
        body.put("mappings", Map.of("properties", props));
        return body;
    }

    private String send(String method, String path, Object body) {
        return sendRaw(method, path, body == null ? null : toJson(body), "application/json");
    }

    /** 发一个请求；失败只记日志并返回 null（检索失败应当降级，不应让业务 500） */
    private String sendRaw(String method, String path, String body, String contentType) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", contentType);
            HttpRequest req = body == null
                    ? b.method(method, HttpRequest.BodyPublishers.noBody()).build()
                    : b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() >= 300) {
                log.warn("ES 请求失败: {} {} -> {} {}", method, path, resp.statusCode(), brief(resp.body()));
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("ES 不可用（降级）: {} {} -> {}", method, path, e.getMessage());
            return null;
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    private static String hotelNameOf(List<Map<String, Object>> hotels, Long hotelId) {
        for (Map<String, Object> h : hotels) {
            if (hotelId != null && hotelId.equals(num(h.get("hotelId")))) {
                return str(h.get("hotelName"));
            }
        }
        return "";
    }

    private static Long num(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(o.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String brief(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= 300 ? s : s.substring(0, 300) + "...";
    }
}
