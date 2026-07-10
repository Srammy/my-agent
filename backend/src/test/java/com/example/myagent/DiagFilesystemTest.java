package com.example.myagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;

class DiagFilesystemTest {

  @Test
  void inspectDeleteBehavior() {
    InMemoryStore store = new InMemoryStore();
    RemoteFilesystem fs = new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    RuntimeContext ctx = RuntimeContext.builder().userId("1").sessionId("s").build();

    fs.write(ctx, "skills/java-helper/SKILL.md", "test content");
    System.out.println("After write, store.size: " + store.size());

    // Test delete with various paths
    WriteResult d1 = fs.delete(ctx, "skills/java-helper");
    System.out.println("delete(skills/java-helper): success=" + d1.isSuccess() + " error=" + d1.error());
    System.out.println("After delete(skills/java-helper), store.size: " + store.size());

    // Re-write and try with leading slash
    fs.write(ctx, "skills/java-helper/SKILL.md", "test content");
    WriteResult d2 = fs.delete(ctx, "/skills/java-helper");
    System.out.println("delete(/skills/java-helper): success=" + d2.isSuccess() + " error=" + d2.error());
    System.out.println("After delete(/skills/java-helper), store.size: " + store.size());

    // Re-write and try with full file path
    fs.write(ctx, "skills/java-helper/SKILL.md", "test content");
    WriteResult d3 = fs.delete(ctx, "/skills/java-helper/SKILL.md");
    System.out.println("delete(/skills/java-helper/SKILL.md): success=" + d3.isSuccess() + " error=" + d3.error());
    System.out.println("After delete file, store.size: " + store.size());

    // Test ls after delete
    LsResult ls = fs.ls(ctx, "/skills");
    System.out.println("ls(/skills) after delete file: entries=" + ls.entries().size());
  }
}
