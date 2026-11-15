package kr.gongchae.bell.ingestion;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Real public-path responses from fixtures/, with the {"data": ...} wrapper removed so the body has the official
 * envelope. Test cases are made by editing these real rows in code, so every case keeps the fixture's provenance.
 */
final class ListFixture {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ObjectNode root;

    private ListFixture(ObjectNode root) {
        this.root = root;
    }

    /** fixtures/alio-public-list-sample.json: 10 real rows, totalCount 497 as received. */
    static ListFixture sample() {
        return load("fixtures/alio-public-list-sample.json");
    }

    /** fixtures/alio-public-list-quality-sample.json: 2 real rows with suspected broken text. */
    static ListFixture quality() {
        return load("fixtures/alio-public-list-quality-sample.json");
    }

    private static ListFixture load(String path) {
        try {
            JsonNode raw = JSON.readTree(Files.readString(Path.of(path)));
            return new ListFixture(((ObjectNode) raw.get("data")).deepCopy());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** totalCount = rows in this body, as a complete single-page response would have. */
    ListFixture complete() {
        root.put("totalCount", rows().size());
        return this;
    }

    ArrayNode rows() {
        return (ArrayNode) root.get("result");
    }

    ObjectNode row(long sn) {
        for (JsonNode row : rows()) {
            if (row.path("recrutPblntSn").asLong() == sn) {
                return (ObjectNode) row;
            }
        }
        throw new IllegalArgumentException("no row " + sn);
    }

    ListFixture edit(long sn, Consumer<ObjectNode> change) {
        change.accept(row(sn));
        return this;
    }

    ListFixture without(long sn) {
        for (int i = 0; i < rows().size(); i++) {
            if (rows().get(i).path("recrutPblntSn").asLong() == sn) {
                rows().remove(i);
                return this;
            }
        }
        throw new IllegalArgumentException("no row " + sn);
    }

    ListFixture only(long... sns) {
        for (int i = rows().size() - 1; i >= 0; i--) {
            long sn = rows().get(i).path("recrutPblntSn").asLong();
            if (java.util.Arrays.stream(sns).noneMatch(s -> s == sn)) {
                rows().remove(i);
            }
        }
        return this;
    }

    ListFixture envelope(Consumer<ObjectNode> change) {
        change.accept(root);
        return this;
    }

    /** Each element wrapped as {"item": {...}}, the shape the official swagger documents. */
    ListFixture wrapItems() {
        ArrayNode wrapped = JSON.createArrayNode();
        rows().forEach(row -> wrapped.add(JSON.createObjectNode().set("item", row)));
        root.set("result", wrapped);
        return this;
    }

    String body() {
        return JSON.writeValueAsString(root);
    }
}
