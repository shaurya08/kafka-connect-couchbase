/*
 * Copyright (c) 2017 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.couchbase.connect.kafka;

import com.couchbase.client.core.error.DocumentNotFoundException;
import com.couchbase.client.core.logging.LogRedaction;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.codec.RawJsonTranscoder;
import com.couchbase.client.java.kv.PersistTo;
import com.couchbase.client.java.kv.ReplicateTo;
import com.couchbase.client.java.kv.UpsertOptions;
import com.couchbase.connect.kafka.config.sink.CouchbaseSinkConfig;
import com.couchbase.connect.kafka.sink.DocumentMode;
import com.couchbase.connect.kafka.sink.N1qlMode;
import com.couchbase.connect.kafka.sink.N1qlWriter;
import com.couchbase.connect.kafka.sink.SubDocumentMode;
import com.couchbase.connect.kafka.sink.SubDocumentWriter;
import com.couchbase.connect.kafka.util.DocumentIdExtractor;
import com.couchbase.connect.kafka.util.DocumentPathExtractor;
import com.couchbase.connect.kafka.util.JsonBinaryDocument;
import com.couchbase.connect.kafka.util.Version;
import com.couchbase.connect.kafka.util.config.ConfigHelper;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.apache.kafka.connect.sink.SinkTask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.couchbase.client.core.util.CbCollections.mapOf;
import static com.couchbase.client.java.kv.RemoveOptions.removeOptions;
import static java.nio.charset.StandardCharsets.UTF_8;

public class CouchbaseSinkTask extends SinkTask {
  private static final Logger LOGGER = LoggerFactory.getLogger(CouchbaseSinkTask.class);

  private String bucketName;
  private Collection collection;
  private KafkaCouchbaseClient client;
  private JsonConverter converter;
  private DocumentIdExtractor documentIdExtractor;
  private DocumentMode documentMode;

  private SubDocumentWriter subDocumentWriter;
  private N1qlWriter n1qlWriter;

  private PersistTo persistTo;
  private ReplicateTo replicateTo;

  private Duration documentExpiry;

  @Override
  public String version() {
    return Version.getVersion();
  }

  @Override
  public void start(Map<String, String> properties) {
    CouchbaseSinkConfig config;
    try {
      config = ConfigHelper.parse(CouchbaseSinkConfig.class, properties);
    } catch (ConfigException e) {
      throw new ConnectException("Couldn't start CouchbaseSinkTask due to configuration error", e);
    }

    LogRedaction.setRedactionLevel(config.logRedaction());
    client = new KafkaCouchbaseClient(config);
    bucketName = config.bucket();
    collection = client.cluster()
        .bucket(bucketName)
        .defaultCollection();

    converter = new JsonConverter();
    converter.configure(mapOf("schemas.enable", false), false);

    String docIdPointer = config.documentId();
    if (docIdPointer != null && !docIdPointer.isEmpty()) {
      documentIdExtractor = new DocumentIdExtractor(docIdPointer, config.removeDocumentId());
    }

    documentMode = config.documentMode();
    persistTo = config.persistTo();
    replicateTo = config.replicateTo();

    documentExpiry = config.documentExpiration();

    switch (documentMode) {
      case SUBDOCUMENT: {
        SubDocumentMode subDocumentMode = config.subdocumentOperation();
        String path = config.subdocumentPath();
        boolean createPaths = config.subdocumentCreatePath();
        boolean createDocuments = config.createDocument();

        subDocumentWriter = new SubDocumentWriter(subDocumentMode, path, createPaths, createDocuments, documentExpiry);
        break;
      }
      case N1QL: {
        N1qlMode n1qlMode = config.n1qlOperation();
        boolean createDocuments = config.createDocument();
        List<String> n1qlWhereFields = config.n1qlWhereFields();

        n1qlWriter = new N1qlWriter(n1qlMode, n1qlWhereFields, createDocuments);
        break;
      }
    }
  }

  @Override
  public void put(java.util.Collection<SinkRecord> records) {
    if (records.isEmpty()) {
      return;
    }
    final SinkRecord first = records.iterator().next();
    final int recordsCount = records.size();
    LOGGER.trace("Received {} records. First record kafka coordinates:({}-{}-{}). Writing them to the Couchbase...",
        recordsCount, first.topic(), first.kafkaPartition(), first.kafkaOffset());

    Map<String, JsonBinaryDocument> idToDocumentOrNull = toJsonBinaryDocuments(records);

    Flux.fromIterable(idToDocumentOrNull.entrySet())
        .flatMap(entry -> {
          if (entry.getValue() == null) {
            return removeIfExists(entry.getKey());
          }

          JsonBinaryDocument doc = entry.getValue();

          switch (documentMode) {
            case N1QL: {
              return n1qlWriter.write(client.cluster(), bucketName, doc);
            }
            case SUBDOCUMENT: {
              return subDocumentWriter.write(collection.reactive(), doc, persistTo, replicateTo);
            }
            default: {
              return collection.reactive()
                  .upsert(doc.id(), doc.content(), UpsertOptions.upsertOptions()
                      .durability(persistTo, replicateTo)
                      .expiry(documentExpiry)
                      .transcoder(RawJsonTranscoder.INSTANCE))
                  .then();
            }
          }
        }).blockLast();
  }

  /**
   * Converts Kafka records to documents and indexes them by document ID.
   * <p>
   * If there are duplicate document IDs, ignores all but the last. This
   * prevents a stale version of the document from "winning" by being the
   * last one written to Couchbase.
   *
   * @return a map where the key is the ID of a document, and the value is the document.
   * A null value indicates the document should be deleted.
   */
  private Map<String, JsonBinaryDocument> toJsonBinaryDocuments(java.util.Collection<SinkRecord> records) {
    Map<String, JsonBinaryDocument> idToDocumentOrNull = new HashMap<>();
    for (SinkRecord record : records) {
      if (record.value() == null) {
        String documentId = documentIdFromKafkaMetadata(record);
        idToDocumentOrNull.put(documentId, null);
        continue;
      }

      JsonBinaryDocument doc = convert(record);
      idToDocumentOrNull.put(doc.id(), doc);
    }

    int deduplicatedRecords = records.size() - idToDocumentOrNull.size();
    if (deduplicatedRecords != 0) {
      LOGGER.debug("Batch contained {} redundant Kafka records.", deduplicatedRecords);
    }

    return idToDocumentOrNull;
  }

  private Mono<Void> removeIfExists(String documentId) {
    return collection.reactive()
        .remove(documentId, removeOptions().durability(persistTo, replicateTo))
        .onErrorResume(DocumentNotFoundException.class, throwable -> Mono.empty())
        .then();
  }

  private static String toString(ByteBuffer byteBuffer) {
    final ByteBuffer sliced = byteBuffer.slice();
    byte[] bytes = new byte[sliced.remaining()];
    sliced.get(bytes);
    return new String(bytes, UTF_8);
  }

  private static String documentIdFromKafkaMetadata(SinkRecord record) {
    Object key = record.key();

    if (key instanceof String
        || key instanceof Number
        || key instanceof Boolean) {
      return key.toString();
    }

    if (key instanceof byte[]) {
      return new String((byte[]) key, UTF_8);
    }

    if (key instanceof ByteBuffer) {
      return toString((ByteBuffer) key);
    }

    return record.topic() + "/" + record.kafkaPartition() + "/" + record.kafkaOffset();
  }


  private JsonBinaryDocument convert(SinkRecord record) {

    byte[] valueAsJsonBytes = converter.fromConnectData(record.topic(), record.valueSchema(), record.value());
    String defaultId = null;

    try {
      if (documentIdExtractor != null) {
        return documentIdExtractor.extractDocumentId(valueAsJsonBytes);
      }

    } catch (DocumentPathExtractor.DocumentPathNotFoundException e) {
      defaultId = documentIdFromKafkaMetadata(record);
      LOGGER.warn(e.getMessage() + "; using fallback ID '{}'", defaultId);

    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    if (defaultId == null) {
      defaultId = documentIdFromKafkaMetadata(record);
    }

    return new JsonBinaryDocument(defaultId, valueAsJsonBytes);
  }

  @Override
  public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
  }

  @Override
  public void stop() {
    client.close();
  }
}
