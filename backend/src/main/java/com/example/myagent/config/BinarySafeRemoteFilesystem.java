package com.example.myagent.config;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Preserves binary uploads while retaining the AgentScope remote filesystem contract. */
final class BinarySafeRemoteFilesystem extends RemoteFilesystem {

  private final BaseStore store;
  private final NamespaceFactory namespaceFactory;

  BinarySafeRemoteFilesystem(BaseStore store, NamespaceFactory namespaceFactory) {
    super(store, namespaceFactory);
    this.store = store;
    this.namespaceFactory = namespaceFactory;
  }

  @Override
  public List<FileUploadResponse> uploadFiles(
      RuntimeContext context, List<Map.Entry<String, byte[]>> files) {
    List<String> namespace = namespaceFactory.getNamespace(context);
    if (namespace == null || namespace.isEmpty()) {
      throw new IllegalStateException("NamespaceFactory returned null or empty namespace");
    }

    List<FileUploadResponse> responses = new ArrayList<>();
    for (Map.Entry<String, byte[]> file : files) {
      FileData fileData = toFileData(file.getValue());
      store.put(namespace, normalizePath(file.getKey()), toStoreValue(fileData));
      responses.add(FileUploadResponse.success(file.getKey()));
    }
    return responses;
  }

  private static String normalizePath(String path) {
    if (path == null || path.isBlank()) {
      return "/";
    }
    String normalized = path.startsWith("/") ? path : "/" + path;
    while (normalized.length() > 1 && normalized.endsWith("/")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized;
  }

  private static FileData toFileData(byte[] bytes) {
    try {
      String content = StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
      return FileData.create(content, "utf-8");
    } catch (CharacterCodingException ignored) {
      return FileData.create(Base64.getEncoder().encodeToString(bytes), "base64");
    }
  }

  private static Map<String, Object> toStoreValue(FileData fileData) {
    Map<String, Object> value = new HashMap<>();
    value.put("content", fileData.content());
    value.put("encoding", fileData.encoding());
    if (fileData.createdAt() != null) {
      value.put("created_at", fileData.createdAt());
    }
    if (fileData.modifiedAt() != null) {
      value.put("modified_at", fileData.modifiedAt());
    }
    return value;
  }
}
