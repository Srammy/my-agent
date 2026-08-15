package com.example.myagent.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.withSettings;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.example.myagent.knowledge.document.KnowledgeDocumentEntity;
import com.example.myagent.knowledge.document.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KnowledgeDocumentMapperTest {

  @Test
  void documentLookupUsesTheOwnerAndDocumentIdScope() {
    KnowledgeDocumentMapper mapper =
        mock(KnowledgeDocumentMapper.class, withSettings().defaultAnswer(org.mockito.Answers.CALLS_REAL_METHODS));
    KnowledgeDocumentEntity expected = new KnowledgeDocumentEntity();
    expected.setId("doc-1");
    expected.setUserId(7L);
    doReturn(expected).when(mapper).selectOne(any());

    KnowledgeDocumentEntity actual = mapper.findOwnedById(7L, "doc-1");

    ArgumentCaptor<Wrapper<KnowledgeDocumentEntity>> captor = ArgumentCaptor.forClass(Wrapper.class);
    verify(mapper).selectOne(captor.capture());
    assertThat(actual).isSameAs(expected);
    assertThat(captor.getValue()).isNotNull();
  }
}
