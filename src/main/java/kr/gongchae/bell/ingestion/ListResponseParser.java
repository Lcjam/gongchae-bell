package kr.gongchae.bell.ingestion;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads the official recruitment list envelope. Any problem rejects the whole response (no partial rows). */
final class ListResponseParser {
    static final JsonMapper JSON = JsonMapper.builder().build();
    /** Joins prefCondCn and prefCn in posting.pref; readers split on it. */
    static final String PREF_SEPARATOR = "\u001F";
    static final List<String> CODE_TYPES = List.of("NCS", "REGION", "HIRE", "EDU");
    private static final Map<String, String[]> CODE_FIELDS = Map.of(
            "NCS", new String[]{"ncsCdLst", "ncsCdNmLst"},
            "REGION", new String[]{"workRgnLst", "workRgnNmLst"},
            "HIRE", new String[]{"hireTypeLst", "hireTypeNmLst"},
            "EDU", new String[]{"acbgCondLst", "acbgCondNmLst"});
    private static final Pattern YMD = Pattern.compile("\\d{8}");
    private static final Pattern DIGITS = Pattern.compile("\\d{1,18}");
    private static final DateTimeFormatter YMD_FORMAT =
            DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT);

    record Page(int totalCount, List<PostingRow> rows) {
    }

    /** codes: type → sorted distinct codes (types without codes are absent); names: type → code → name. */
    record PostingRow(long sn, String instCode, String instName, String title, LocalDate begin, LocalDate end,
                      String recrutSe, String nopeRaw, Long nope, String aplyQlfc, String pref, String scrnprcdr,
                      String srcUrl, String ongoingYn, Map<String, List<String>> codes,
                      Map<String, Map<String, String>> names) {
    }

    Page parse(String body) {
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JacksonException e) {
            throw new IngestionException(Failure.NON_JSON, null);
        }
        if (root == null || !root.isObject()) {
            throw new IngestionException(Failure.NON_JSON, null);
        }
        JsonNode code = root.path("resultCode");
        if (!(code.isIntegralNumber() || code.isString()) || !"200".equals(code.asString())) {
            throw new IngestionException(Failure.RESULT_CODE_NOT_OK, code.isIntegralNumber() ? "resultCode=" + code.asString() : null);
        }
        JsonNode total = root.path("totalCount");
        JsonNode result = root.path("result");
        if (!total.isInt() || !result.isArray()) {
            throw new IngestionException(Failure.INVALID_ENVELOPE, null);
        }
        var rows = new ArrayList<PostingRow>();
        var seen = new HashSet<Long>();
        for (JsonNode element : result) {
            // The official swagger wraps each element in "item"; the observed public response does not.
            PostingRow row = row(element.has("item") ? element.get("item") : element);
            if (!seen.add(row.sn())) {
                throw new IngestionException(Failure.DUPLICATE_SN, "sn=" + row.sn());
            }
            rows.add(row);
        }
        return new Page(total.intValue(), rows);
    }

    private static PostingRow row(JsonNode n) {
        if (!n.isObject()) {
            throw invalid("element", null);
        }
        long sn = sn(n.path("recrutPblntSn"));
        String instCode = required(n, "pblntInstCd", 40, sn);
        String recrutSe = text(n, "recrutSe", sn);
        if (recrutSe != null && recrutSe.length() > 20) {
            throw invalid("recrutSe", sn);
        }
        String ongoing = text(n, "ongoingYn", sn);
        if (ongoing != null && !ongoing.equals("Y") && !ongoing.equals("N")) {
            throw invalid("ongoingYn", sn);
        }
        String nopeRaw = text(n, "recrutNope", sn);
        Long nope = n.path("recrutNope").isIntegralNumber() && n.path("recrutNope").canConvertToLong()
                ? Long.valueOf(n.path("recrutNope").longValue())
                : nopeRaw != null && DIGITS.matcher(nopeRaw).matches() ? Long.valueOf(nopeRaw) : null;
        var codes = new TreeMap<String, List<String>>();
        var names = new TreeMap<String, Map<String, String>>();
        for (String type : CODE_TYPES) {
            String[] fields = CODE_FIELDS.get(type);
            codes(n, type, fields[0], fields[1], sn, codes, names);
        }
        return new PostingRow(sn, instCode, required(n, "instNm", Integer.MAX_VALUE, sn),
                required(n, "recrutPbancTtl", Integer.MAX_VALUE, sn), date(n, "pbancBgngYmd", sn), date(n, "pbancEndYmd", sn),
                recrutSe, nopeRaw, nope, text(n, "aplyQlfcCn", sn), pref(n, sn), text(n, "scrnprcdrMthdExpln", sn),
                text(n, "srcUrl", sn), ongoing, codes, names);
    }

    /** prefCondCn U+001F prefCn, so a change in either original is observed; null when both are absent. */
    private static String pref(JsonNode n, long sn) {
        String summary = text(n, "prefCondCn", sn);
        String detail = text(n, "prefCn", sn);
        if (summary == null && detail == null) {
            return null;
        }
        if ((summary != null && summary.contains(PREF_SEPARATOR)) || (detail != null && detail.contains(PREF_SEPARATOR))) {
            throw invalid("pref", sn);
        }
        return (summary == null ? "" : summary) + PREF_SEPARATOR + (detail == null ? "" : detail);
    }

    private static void codes(JsonNode n, String type, String codeField, String nameField, long sn,
                              Map<String, List<String>> codes, Map<String, Map<String, String>> names) {
        String raw = text(n, codeField, sn);
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] tokens = raw.split(",", -1);
        var sorted = new TreeSet<String>();
        for (String token : tokens) {
            String code = token.strip();
            if (code.length() > 40) {
                throw invalid(codeField, sn);
            }
            if (!code.isEmpty()) {
                sorted.add(code);
            }
        }
        if (sorted.isEmpty()) {
            return;
        }
        codes.put(type, List.copyOf(sorted));
        String rawNames = text(n, nameField, sn);
        String[] nameTokens = rawNames == null ? new String[0] : rawNames.split(",", -1);
        if (nameTokens.length == tokens.length) { // names pair with codes by position only when the counts agree
            var byCode = new TreeMap<String, String>();
            for (int i = 0; i < tokens.length; i++) {
                if (!tokens[i].isBlank() && !nameTokens[i].isBlank()) {
                    byCode.put(tokens[i].strip(), nameTokens[i].strip());
                }
            }
            names.put(type, byCode);
        }
    }

    private static long sn(JsonNode v) {
        if (v.isIntegralNumber() && v.canConvertToLong()) {
            return v.longValue();
        }
        if (v.isString() && DIGITS.matcher(v.stringValue()).matches()) {
            return Long.parseLong(v.stringValue());
        }
        throw invalid("recrutPblntSn", null);
    }

    private static LocalDate date(JsonNode n, String field, long sn) {
        String s = text(n, field, sn);
        if (s == null || !YMD.matcher(s).matches()) {
            throw invalid(field, sn);
        }
        try {
            return LocalDate.parse(s, YMD_FORMAT);
        } catch (DateTimeParseException e) {
            throw invalid(field, sn);
        }
    }

    private static String required(JsonNode n, String field, int maxLength, long sn) {
        String s = text(n, field, sn);
        if (s == null || s.isBlank() || s.length() > maxLength) {
            throw invalid(field, sn);
        }
        return s;
    }

    /** The value exactly as sent (no trimming); null when absent or JSON null. */
    private static String text(JsonNode n, String field, long sn) {
        JsonNode v = n.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (!v.isValueNode()) {
            throw invalid(field, sn);
        }
        return v.asString();
    }

    private static IngestionException invalid(String field, Long sn) {
        return new IngestionException(Failure.INVALID_ROW, field + (sn == null ? "" : " sn=" + sn));
    }
}
