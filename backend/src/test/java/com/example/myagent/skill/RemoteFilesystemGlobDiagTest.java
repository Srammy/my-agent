package com.example.myagent.skill;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteFilesystemGlobDiagTest {

  @Test
  void globFindsSkillMdAfterWrite() {
    InMemoryStore store = new InMemoryStore();
    AbstractFilesystem fs = new RemoteFilesystem(store, IsolationScope.USER.toNamespaceFactory());
    RuntimeContext ctx = RuntimeContext.builder().userId("1").sessionId("s").build();

    WriteResult wr = fs.write(ctx, "skills/java-helper/SKILL.md",
        "---\nname: java-helper\ndescription: x\n---\n");
    System.out.println("write: success=" + wr.isSuccess() + " path=" + wr.path());

    GlobResult gr = fs.glob(ctx, "SKILL.md", "skills");
    System.out.println("glob(SKILL.md, skills): success=" + gr.isSuccess()
        + " count=" + (gr.matches() == null ? "null" : gr.matches().size())
        + " matches=" + gr.matches());

    GlobResult gr2 = fs.glob(ctx, "SKILL.md", "/skills");
    System.out.println("glob(SKILL.md, /skills): count="
        + (gr2.matches() == null ? "null" : gr2.matches().size()));

    assertThat(gr.isSuccess()).isTrue();
    assertThat(gr.matches()).isNotEmpty();
  }
}
